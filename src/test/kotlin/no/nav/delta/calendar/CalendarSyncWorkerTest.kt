package no.nav.delta.calendar

import arrow.core.left
import arrow.core.right
import com.microsoft.graph.models.ResponseType
import java.time.LocalDateTime
import java.time.OffsetDateTime
import no.nav.delta.email.CloudClient
import no.nav.delta.email.DummyCloudClient
import no.nav.delta.email.SharedCalendarAttendee
import no.nav.delta.email.SharedCalendarSnapshot
import no.nav.delta.event.CreateEvent
import no.nav.delta.event.CalendarSyncStatus
import no.nav.delta.event.InviteeRequest
import no.nav.delta.event.Participant
import no.nav.delta.event.getFullEvent
import no.nav.delta.event.getMasterCalendarEventId
import no.nav.delta.support.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarSyncWorkerTest {
    @Test
    fun `periodic reconciliation heals missed RSVP without sending meeting updates`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var accepted = false
            var writes = 0
            val cloud = object : CloudClient by dummy {
                override fun getSharedEvent(calendarEventId: String) = dummy.getSharedEvent(calendarEventId).map { snapshot ->
                    snapshot.copy(attendees = snapshot.attendees.map {
                        if (accepted && it.email == "colleague@nav.no") {
                            it.copy(response = ResponseType.Accepted, responseTime = OffsetDateTime.now())
                        } else it
                    })
                }
                override fun updateSharedAttendees(calendarEventId: String, attendees: List<SharedCalendarAttendee>, changeKey: String?) =
                    dummy.updateSharedAttendees(calendarEventId, attendees, changeKey).also { writes++ }
            }
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            accepted = true
            assertEquals(1, repository.enqueuePeriodicReconciliation())
            assertTrue(worker.runOnce())
            assertEquals(listOf("colleague@nav.no"),
                db.database.getFullEvent(event.id.toString()).getOrNull()!!.participants.map { it.email })
            assertEquals(0, writes)
        }
    }

    @Test
    fun `Teams provisioning has bounded retries without repeating delivered detail updates`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var writes = 0
            val cloud = object : CloudClient by dummy {
                override fun getSharedEvent(calendarEventId: String) =
                    dummy.getSharedEvent(calendarEventId).map { it.copy(teamsJoinUrl = null) }
                override fun updateSharedDetails(calendarEventId: String, event: no.nav.delta.event.Event) =
                    dummy.updateSharedDetails(calendarEventId, event).map {
                        writes++
                        it.copy(teamsJoinUrl = null)
                    }
            }
            val draft = futureEvent()
            val event = repository.create(draft, Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            repository.update(event.id, draft.copy(isOnlineMeeting = true, invitees = null), "host@nav.no")
            repeat(10) {
                db.database.connection.use { connection ->
                    connection.prepareStatement("UPDATE shared_calendar_outbox SET next_attempt=NOW() WHERE event_id=?").use {
                        it.setObject(1, event.id)
                        it.executeUpdate()
                    }
                    connection.commit()
                }
                worker.runOnce()
            }
            assertEquals(1, writes)
            assertEquals(CalendarSyncStatus.FAILED, db.database.getFullEvent(event.id.toString()).getOrNull()!!.event.calendarSyncStatus)
        }
    }

    @Test
    fun `ambiguous creation followed by deletion recovers and cancels the same event`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var createdId: String? = null
            var first = true
            val cloud = object : CloudClient by dummy {
                override fun createSharedEvent(
                    event: no.nav.delta.event.Event, attendees: List<Participant>, transactionId: String,
                ): arrow.core.Either<Throwable, no.nav.delta.room.MasterEventResult> {
                    val result = dummy.createSharedEvent(event, attendees, transactionId)
                    createdId = result.getOrNull()?.calendarEventId
                    if (first) {
                        first = false
                        return java.io.IOException("Lost response").left()
                    }
                    return result
                }
            }
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            assertTrue(repository.delete(event.id).isRight())
            assertTrue(worker.runOnce())
            val snapshot = dummy.getSharedEvent(createdId!!).getOrNull()
            assertTrue(snapshot == null || snapshot.isCancelled)
            assertEquals(0, repository.statistics().pending)
        }
    }

    @Test
    fun `full event refuses forwarded acceptance and emails reason without updating other attendees`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var snapshot: SharedCalendarSnapshot? = null
            val emails = mutableListOf<String>()
            var writes = 0
            var mailThrottled = true
            val cloud = object : CloudClient by dummy {
                override fun getSharedEvent(calendarEventId: String) = snapshot?.right() ?: dummy.getSharedEvent(calendarEventId)
                override fun sendEmail(subject: String, body: String, toRecipients: List<String>,
                    ccRecipients: List<String>, bccRecipients: List<String>) {
                    if (mailThrottled) {
                        mailThrottled = false
                        throw object : com.microsoft.kiota.ApiException() {
                            init { responseStatusCode = 429 }
                        }
                    }
                    emails.addAll(toRecipients)
                }
                override fun updateSharedAttendees(calendarEventId: String, attendees: List<SharedCalendarAttendee>, changeKey: String?) =
                    dummy.updateSharedAttendees(calendarEventId, attendees, changeKey).also { writes++ }
            }
            val event = repository.create(futureEvent().copy(participantLimit = 2), Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            val id = db.database.getMasterCalendarEventId(event.id.toString()).getOrNull()!!
            snapshot = dummy.getSharedEvent(id).getOrNull()!!.copy(attendees =
                dummy.getSharedEvent(id).getOrNull()!!.attendees +
                    SharedCalendarAttendee("forwarded@nav.no", "Forwarded", ResponseType.Accepted, OffsetDateTime.now()))
            repository.enqueueReconciliation(id)
            worker.runOnce()
            assertEquals(1, repository.refusals(event.id).size)
            repository.retry(event.id)
            worker.runOnce()
            repository.enqueueReconciliation(id)
            worker.runOnce()
            assertEquals(listOf("forwarded@nav.no"), emails)
            assertEquals(0, writes)
            val full = db.database.getFullEvent(event.id.toString()).getOrNull()!!
            assertEquals("DECLINED", full.invited.single { it.email == "forwarded@nav.no" }.status.name)
        }
    }

    @Test
    fun `Delta only metadata edits do not trigger meeting updates`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var writes = 0
            val cloud = object : CloudClient by dummy {
                override fun updateSharedDetails(calendarEventId: String, event: no.nav.delta.event.Event) =
                    dummy.updateSharedDetails(calendarEventId, event).also { writes++ }
            }
            val draft = futureEvent()
            val event = repository.create(draft, Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            repository.update(event.id, draft.copy(participantLimit = 10, public = false, invitees = null), "host@nav.no")
            assertEquals(false, worker.runOnce())
            assertEquals(0, writes)
        }
    }

    @Test
    fun `lost creation response retains original payload then applies newer edits`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            val transactionIds = mutableListOf<String>()
            val createdTitles = mutableListOf<String>()
            val updatedTitles = mutableListOf<String>()
            var first = true
            val cloud = object : CloudClient by dummy {
                override fun createSharedEvent(
                    event: no.nav.delta.event.Event,
                    attendees: List<Participant>,
                    transactionId: String,
                ): arrow.core.Either<Throwable, no.nav.delta.room.MasterEventResult> {
                    transactionIds.add(transactionId)
                    createdTitles.add(event.title)
                    val created = dummy.createSharedEvent(event, attendees, transactionId)
                    if (first) {
                        first = false
                        return java.io.IOException("Lost response").left()
                    }
                    return created
                }
                override fun updateSharedDetails(calendarEventId: String, event: no.nav.delta.event.Event) =
                    dummy.updateSharedDetails(calendarEventId, event).also { updatedTitles.add(event.title) }
            }
            val draft = futureEvent()
            val event = repository.create(draft, Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            repository.update(event.id, draft.copy(title = "New title", invitees = null), "host@nav.no")
            worker.runOnce()
            assertEquals(2, transactionIds.size)
            assertEquals(transactionIds[0], transactionIds[1])
            assertEquals(listOf(draft.title, draft.title), createdTitles)
            assertEquals(listOf("New title"), updatedTitles)
            assertEquals("SYNCED", db.database.getFullEvent(event.id.toString()).getOrNull()!!.event.calendarSyncStatus?.name)
        }
    }

    @Test
    fun `revoked attendee is not re-adopted while cancellation is pending`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val cloud = DummyCloudClient()
            val worker = CalendarSyncWorker(repository, cloud)
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            worker.runOnce()
            repository.remove(event.id, "colleague@nav.no")
            assertTrue(worker.runOnce())
            val full = db.database.getFullEvent(event.id.toString()).getOrNull()!!
            assertTrue(full.invited.isEmpty())
            val id = db.database.getMasterCalendarEventId(event.id.toString()).getOrNull()!!
            assertTrue(cloud.getSharedEvent(id).getOrNull()!!.attendees.none { it.email == "colleague@nav.no" })
        }
    }

    @Test
    fun `adding a person updates attendees without a details notification`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var attendeeWrites = 0
            var detailWrites = 0
            val cloud = object : CloudClient by dummy {
                override fun updateSharedDetails(calendarEventId: String, event: no.nav.delta.event.Event) =
                    dummy.updateSharedDetails(calendarEventId, event).also { detailWrites++ }

                override fun updateSharedAttendees(
                    calendarEventId: String,
                    attendees: List<SharedCalendarAttendee>,
                    changeKey: String?,
                ) = dummy.updateSharedAttendees(calendarEventId, attendees, changeKey).also { attendeeWrites++ }
            }
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            val worker = CalendarSyncWorker(repository, cloud)
            worker.runOnce()
            repository.invite(event.id, listOf(InviteeRequest("new@nav.no")), "host@nav.no")
            worker.runOnce()
            assertEquals(1, attendeeWrites)
            assertEquals(0, detailWrites)
        }
    }

    @Test
    fun `editing details invokes details adapter but RSVP does not`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var detailWrites = 0
            val cloud = object : CloudClient by dummy {
                override fun updateSharedDetails(calendarEventId: String, event: no.nav.delta.event.Event) =
                    dummy.updateSharedDetails(calendarEventId, event).also { detailWrites++ }
            }
            val worker = CalendarSyncWorker(repository, cloud)
            val draft = futureEvent()
            val event = repository.create(draft, Participant("host@nav.no", "Host"))
            worker.runOnce()
            repository.update(event.id, draft.copy(title = "Updated", invitees = null), "host@nav.no")
            worker.runOnce()
            assertEquals(1, detailWrites)
            val id = db.database.getMasterCalendarEventId(event.id.toString()).getOrNull()!!
            repository.enqueueReconciliation(id)
            worker.runOnce()
            assertEquals(1, detailWrites)
        }
    }

    @Test
    fun `durable creation synchronizes one shared event and later cancels after deletion`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val cloud = DummyCloudClient()
            val worker = CalendarSyncWorker(repository, cloud)
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            assertEquals("PENDING", event.calendarSyncStatus?.name)
            assertTrue(worker.runOnce())
            val id = db.database.getMasterCalendarEventId(event.id.toString()).getOrNull()
            assertNotNull(id)
            assertEquals("SYNCED", db.database.getFullEvent(event.id.toString()).getOrNull()!!.event.calendarSyncStatus?.name)
            assertTrue(repository.delete(event.id).isRight())
            assertTrue(worker.runOnce())
            val cancelled = cloud.getSharedEvent(id!!).getOrNull()
            assertTrue(cancelled == null || cancelled.isCancelled)
        }
    }

    @Test
    fun `reconciling RSVP and a forwarded attendee never patches the event`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            var writes = 0
            var overrideSnapshot: SharedCalendarSnapshot? = null
            val cloud = object : CloudClient by dummy {
                override fun getSharedEvent(calendarEventId: String) =
                    overrideSnapshot?.right() ?: dummy.getSharedEvent(calendarEventId)

                override fun updateSharedAttendees(
                    calendarEventId: String,
                    attendees: List<SharedCalendarAttendee>,
                    changeKey: String?,
                ): arrow.core.Either<Throwable, Unit> {
                    writes++
                    return dummy.updateSharedAttendees(calendarEventId, attendees, changeKey)
                }
            }
            val worker = CalendarSyncWorker(repository, cloud)
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            worker.runOnce()
            val id = db.database.getMasterCalendarEventId(event.id.toString()).getOrNull()!!
            val responseTime = OffsetDateTime.now().plusSeconds(1)
            overrideSnapshot = SharedCalendarSnapshot(
                listOf(
                    SharedCalendarAttendee("host@nav.no", "Host", ResponseType.Declined, responseTime),
                    SharedCalendarAttendee("colleague@nav.no", "Colleague", ResponseType.Accepted, responseTime),
                    SharedCalendarAttendee("forwarded@nav.no", "Forwarded"),
                )
            )
            repository.enqueueReconciliation(id)
            assertTrue(worker.runOnce())
            val full = db.database.getFullEvent(event.id.toString()).getOrNull()!!
            assertEquals(1, full.hosts.size)
            assertEquals(listOf("colleague@nav.no"), full.participants.map { it.email })
            assertEquals(listOf("forwarded@nav.no"), full.invited.map { it.email })
            assertEquals(0, writes)
        }
    }

    @Test
    fun `permanent Graph failure stays visible until host retries`() {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val dummy = DummyCloudClient()
            val cloud = object : CloudClient by dummy {
                override fun createSharedEvent(
                    event: no.nav.delta.event.Event,
                    attendees: List<Participant>,
                    transactionId: String,
                ) = UnsupportedOperationException("Calendar unavailable").left()
            }
            val event = repository.create(futureEvent(), Participant("host@nav.no", "Host"))
            assertTrue(CalendarSyncWorker(repository, cloud).runOnce())
            assertEquals("FAILED", db.database.getFullEvent(event.id.toString()).getOrNull()!!.event.calendarSyncStatus?.name)
            repository.retry(event.id)
            assertTrue(CalendarSyncWorker(repository, dummy).runOnce())
            assertEquals("SYNCED", db.database.getFullEvent(event.id.toString()).getOrNull()!!.event.calendarSyncStatus?.name)
        }
    }

    private fun futureEvent() = CreateEvent(
        title = "Shared worker",
        description = "Details",
        startTime = LocalDateTime.now().plusDays(10),
        endTime = LocalDateTime.now().plusDays(10).plusHours(1),
        location = "Office",
        public = true,
        participantLimit = 5,
        signupDeadline = null,
        invitees = listOf(InviteeRequest("colleague@nav.no")),
    )
}
