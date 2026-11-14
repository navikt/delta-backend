package no.nav.delta.event

import arrow.core.left
import arrow.core.right
import com.microsoft.kiota.ApiException
import no.nav.delta.calendar.SharedCalendarRepository
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.calendar.SharedCalendarValidationException
import no.nav.delta.calendar.SharedCalendarAttendeeSnapshot
import no.nav.delta.calendar.SharedCalendarResponse
import no.nav.delta.email.CloudClient
import no.nav.delta.email.batchSendUpdateOrCreationNotification
import no.nav.delta.email.sendUpdateOrCreationNotification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class WaitlistTest {
    private lateinit var fixture: TestDatabase
    private lateinit var calendar: SharedCalendarRepository
    private lateinit var waitlist: WaitlistRepository

    @BeforeEach
    fun setup() {
        fixture = TestDatabase.create()
        calendar = SharedCalendarRepository(fixture.database)
        waitlist = WaitlistRepository(fixture.database)
    }

    @AfterEach
    fun close() { if (::fixture.isInitialized) fixture.close() }

    private fun draft(limit: Int = 2) = CreateEvent(
        title = "Queue test", description = "Synthetic event", location = "Office", public = true,
        startTime = LocalDateTime.now().plusDays(2), endTime = LocalDateTime.now().plusDays(2).plusHours(1),
        signupDeadline = LocalDateTime.now().plusDays(1), participantLimit = limit, waitlistEnabled = true,
    )

    private fun promotedLegacy(email: String): Event {
        val db = fixture.database
        val event = db.addEvent(draft())
        db.registerForEvent(event.id.toString(), "host@nav.no", "Host", ParticipantType.HOST)
        db.registerForEvent(event.id.toString(), "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant(email, "Waiting"))
        db.unregisterFromEvent(event.id.toString(), "attendee@nav.no")
        return event
    }

    @Test
    fun `free seats without an existing queue remain available for direct registration`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!

        assertThrows(SharedCalendarValidationException::class.java) {
            waitlist.join(event.id, Participant("newcomer@nav.no", "Newcomer"))
        }
        assertEquals(0, waitlist.state(event.id, "newcomer@nav.no").count)
        assertTrue(calendar.signup(event.id, "newcomer@nav.no", "Newcomer").isRight())
    }

    @Test
    fun `joining a full event queues the caller without creating attendance or calendar work`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!
        calendar.signup(event.id, "attendee@nav.no", "Attendee")
        calendar.complete(calendar.claimNext()!!)

        val state = waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))

        assertEquals(1, state.position)
        assertEquals(1, state.count)
        assertFalse(state.registered)
        assertEquals(1, fixture.database.getFullEvent(event.id.toString()).getOrNull()!!.participants.size)
        assertNull(calendar.claimNext())
        assertEquals(state, waitlist.join(event.id, Participant("waiting@nav.no", "Waiting")))
    }

    @Test
    fun `cancellation promotes first in queue and leaving then rejoining loses priority`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!
        calendar.signup(event.id, "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("first@nav.no", "First"))
        waitlist.join(event.id, Participant("second@nav.no", "Second"))
        waitlist.leave(event.id, "first@nav.no")
        assertEquals(2, waitlist.join(event.id, Participant("first@nav.no", "First")).position)
        calendar.remove(event.id, "attendee@nav.no")
        assertTrue(waitlist.state(event.id, "second@nav.no").registered)
        assertNull(waitlist.state(event.id, "second@nav.no").position)
        assertEquals(1, waitlist.state(event.id, "first@nav.no").position)
        assertEquals(listOf("first@nav.no"), waitlist.entries(event.id, "host@nav.no").map { it.email })
        assertThrows(ForbiddenException::class.java) { waitlist.entries(event.id, "stranger@nav.no") }
    }

    @Test
    fun `legacy cancellation promotes and durably delivers calendar and promotion notice`() {
        val db = fixture.database
        val event = db.addEvent(draft())
        db.registerForEvent(event.id.toString(), "host@nav.no", "Host", ParticipantType.HOST)
        db.registerForEvent(event.id.toString(), "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        db.unregisterFromEvent(event.id.toString(), "attendee@nav.no")
        assertTrue(waitlist.state(event.id, "waiting@nav.no").registered)
        val cloud = RecordingCloudClient()
        val worker = WaitlistNotificationWorker(db, cloud)
        assertTrue(worker.runOnce())
        assertEquals(1, cloud.createdCalendarEventIds.size)
        assertEquals(listOf("waiting@nav.no"), cloud.sentEmails.single().toRecipients)
        assertFalse(worker.runOnce())
        db.unregisterFromEvent(event.id.toString(), "waiting@nav.no")
        assertTrue(worker.runOnce())
        assertEquals(cloud.createdCalendarEventIds, cloud.deletedCalendarEventIds)
        assertFalse(worker.runOnce())
    }

    @Test
    fun `capacity increase promotes in order and a nonempty queue blocks disabling or unlimited seats`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!
        calendar.signup(event.id, "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("first@nav.no", "First"))
        waitlist.join(event.id, Participant("second@nav.no", "Second"))
        assertThrows(SharedCalendarValidationException::class.java) {
            calendar.update(event.id, draft().copy(waitlistEnabled = false), "host@nav.no")
        }
        assertThrows(SharedCalendarValidationException::class.java) {
            calendar.update(event.id, draft().copy(waitlistEnabled = false, participantLimit = 0), "host@nav.no")
        }
        calendar.update(event.id, draft(3), "host@nav.no")
        assertTrue(waitlist.state(event.id, "first@nav.no").registered)
        assertEquals(1, waitlist.state(event.id, "second@nav.no").position)
        assertEquals(1, waitlist.mine("second@nav.no").single().position)
    }

    @Test
    fun `Outlook decline releases a reserved seat to the queue`() {
        val event = calendar.create(draft().copy(invitees = listOf(InviteeRequest("invitee@nav.no"))),
            "host@nav.no", "Host").getOrNull()!!
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        calendar.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("invitee@nav.no", "Invitee", SharedCalendarResponse.DECLINED),
        ))
        assertTrue(waitlist.state(event.id, "waiting@nav.no").registered)
    }

    @Test
    fun `existing calendar attendees cannot bypass a queue blocked by the attendee ceiling`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!
        calendar.signup(event.id, "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        fixture.database.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO participant(event_id,email,name,type,status)
                SELECT ?, 'declined-'||i||'@nav.no','Declined','PARTICIPANT','DECLINED'
                FROM generate_series(1,487) i
            """.trimIndent()).use { it.setObject(1, event.id); it.executeUpdate() }
            c.prepareStatement("""
                INSERT INTO participant(event_id,email,name,type,status)
                VALUES (?,'forwarded@nav.no','Forwarded','PARTICIPANT','FORWARDED')
            """.trimIndent()).use { it.setObject(1, event.id); it.executeUpdate() }
            c.prepareStatement("UPDATE participant SET status='DECLINED' WHERE event_id=? AND email='attendee@nav.no'")
                .use { it.setObject(1, event.id); it.executeUpdate() }
            c.commit()
        }

        assertEquals(EventFullException, calendar.signup(event.id, "declined-1@nav.no", "Declined").leftOrNull())
        assertTrue(calendar.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("forwarded@nav.no", "Forwarded", SharedCalendarResponse.ACCEPTED),
        )).isRight())
        assertFalse(waitlist.state(event.id, "forwarded@nav.no").registered)
        assertEquals(1, waitlist.state(event.id, "waiting@nav.no").position)
    }

    @Test
    fun `declines reclaim calendar slots durably before promoting a new queued attendee`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!
        calendar.signup(event.id, "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        fixture.database.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO participant(event_id,email,name,type,status)
                SELECT ?, 'forwarded-'||i||'@nav.no','Forwarded','PARTICIPANT','FORWARDED'
                FROM generate_series(1,488) i
            """.trimIndent()).use { it.setObject(1, event.id); it.executeUpdate() }
            c.commit()
        }
        calendar.complete(calendar.claimNext()!!)

        calendar.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("attendee@nav.no", "Attendee", SharedCalendarResponse.DECLINED),
        ))

        assertFalse(waitlist.state(event.id, "waiting@nav.no").registered)
        val removal = calendar.claimNext()
        assertNotNull(removal, "the declined calendar attendee needs durable removal work")
        assertTrue(calendar.loadDesired(removal!!).removals.contains("attendee@nav.no"))
        assertFalse(calendar.loadDesired(removal).attendees.any { it.email == "waiting@nav.no" })
        calendar.complete(removal)
        assertTrue(waitlist.state(event.id, "waiting@nav.no").registered)
        val replacement = calendar.claimNext()!!
        val desired = calendar.loadDesired(replacement)
        assertTrue(desired.attendees.any { it.email == "waiting@nav.no" })
        assertTrue(desired.attendees.size <= 490)
    }

    @Test
    fun `reserved invitees can accept and newcomers can queue while promotion waits for a calendar slot`() {
        val event = calendar.create(draft().copy(invitees = listOf(InviteeRequest("invitee@nav.no"))),
            "host@nav.no", "Host").getOrNull()!!
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        fixture.database.connection.use { c ->
            c.prepareStatement("""
                INSERT INTO participant(event_id,email,name,type,status)
                SELECT ?, 'forwarded-'||i||'@nav.no','Forwarded','PARTICIPANT','FORWARDED'
                FROM generate_series(1,488) i
            """.trimIndent()).use { it.setObject(1, event.id); it.executeUpdate() }
            c.commit()
        }
        calendar.update(event.id, draft(3), "host@nav.no")

        assertThrows(SharedCalendarValidationException::class.java) {
            waitlist.join(event.id, Participant("invitee@nav.no", "Invitee"))
        }
        assertTrue(calendar.signup(event.id, "invitee@nav.no", "Invitee").isRight())
        assertTrue(waitlist.state(event.id, "invitee@nav.no").registered)
        assertEquals(1, waitlist.state(event.id, "waiting@nav.no").position)
        val joined = waitlist.join(event.id, Participant("newcomer@nav.no", "Newcomer"))
        assertEquals(2, joined.position)
        assertFalse(joined.registered)
        assertEquals(EventFullException, calendar.signup(event.id, "newcomer@nav.no", "Newcomer").leftOrNull())

        calendar.complete(calendar.claimNext()!!)
        assertTrue(waitlist.state(event.id, "waiting@nav.no").registered)
        assertEquals(1, waitlist.state(event.id, "newcomer@nav.no").position)
    }

    @Test
    fun `closed queues allow leaving but never promotion or new admission`() {
        val event = calendar.create(draft(), "host@nav.no", "Host").getOrNull()!!
        calendar.signup(event.id, "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        fixture.database.connection.use { c ->
            c.prepareStatement("UPDATE event SET signup_deadline=? WHERE id=?").use { s ->
                s.setObject(1, allocationNow().minusMinutes(1)); s.setObject(2, event.id); s.executeUpdate()
            }
            c.commit()
        }
        calendar.remove(event.id, "attendee@nav.no")
        assertFalse(waitlist.state(event.id, "waiting@nav.no").registered)
        assertFalse(waitlist.state(event.id, "waiting@nav.no").open)
        assertThrows(DeadlinePassedException::class.java) {
            waitlist.join(event.id, Participant("late@nav.no", "Late"))
        }
        assertEquals(0, waitlist.leave(event.id, "waiting@nav.no").count)
    }

    @Test
    fun `concurrent legacy registrations cannot consume the same last seat`() {
        val db = fixture.database
        val event = db.addEvent(draft())
        db.registerForEvent(event.id.toString(), "host@nav.no", "Host", ParticipantType.HOST)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val results = (1..4).map { index ->
                pool.submit<Boolean> {
                    start.await()
                    db.registerForEvent(event.id.toString(), "user$index@nav.no", "User $index").isRight()
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get() })
            assertEquals(1, db.getFullEvent(event.id.toString()).getOrNull()!!.participants.size)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `legacy capacity edits preserve queue policy and promote before accepting newcomers`() {
        val db = fixture.database
        val event = db.addEvent(draft())
        db.registerForEvent(event.id.toString(), "host@nav.no", "Host", ParticipantType.HOST)
        db.registerForEvent(event.id.toString(), "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("first@nav.no", "First"))
        waitlist.join(event.id, Participant("second@nav.no", "Second"))
        assertThrows(SharedCalendarValidationException::class.java) {
            db.updateEvent(event.copy(waitlistEnabled = false))
        }
        db.updateEvent(event.copy(participantLimit = 3))
        assertTrue(waitlist.state(event.id, "first@nav.no").registered)
        assertEquals(EventFullException, db.registerForEvent(event.id.toString(), "new@nav.no", "New").leftOrNull())
        assertEquals(1, waitlist.state(event.id, "second@nav.no").position)
    }

    @Test
    fun `recurring occurrences keep independent queues and upcoming capacity edits promote each queue`() {
        val db = fixture.database
        val request = draft().copy(recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY,
            LocalDateTime.now().plusDays(10).toLocalDate()))
        val series = db.createRecurringEventSeries(request, "host@nav.no", "Host", InviteMode.SHARED).getOrNull()!!
        assertEquals(2, series.affectedEvents.size)
        series.affectedEvents.forEachIndexed { index, event ->
            assertTrue(event.waitlistEnabled)
            calendar.signup(event.id, "attendee@nav.no", "Attendee")
            waitlist.join(event.id, Participant("wait$index@nav.no", "Waiting $index"))
        }
        assertEquals(1, waitlist.mine("wait0@nav.no").size)
        db.updateRecurringSeriesFromOccurrence(series.referenceEventId.toString(),
            request.copy(participantLimit = 3, editScope = EventEditScope.UPCOMING), "host@nav.no")
        series.affectedEvents.forEachIndexed { index, event ->
            assertTrue(waitlist.state(event.id, "wait$index@nav.no").registered)
        }
    }

    @Test
    fun `mail failure keeps the registered seat and retries without recreating the legacy calendar`() {
        val db = fixture.database
        val event = db.addEvent(draft())
        db.registerForEvent(event.id.toString(), "host@nav.no", "Host", ParticipantType.HOST)
        db.registerForEvent(event.id.toString(), "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        db.unregisterFromEvent(event.id.toString(), "attendee@nav.no")
        val cloud = RecordingCloudClient()
        var failMail = true
        val flaky = object : CloudClient by cloud {
            override fun sendEmail(subject: String, body: String, toRecipients: List<String>,
                ccRecipients: List<String>, bccRecipients: List<String>) {
                if (failMail) throw java.io.IOException("Synthetic mail outage")
                cloud.sendEmail(subject, body, toRecipients, ccRecipients, bccRecipients)
            }
        }
        val worker = WaitlistNotificationWorker(db, flaky)
        assertTrue(worker.runOnce())
        assertTrue(waitlist.state(event.id, "waiting@nav.no").registered)
        assertEquals(1, cloud.createdCalendarEventIds.size)
        assertTrue(cloud.sentEmails.isEmpty())
        db.connection.use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE waitlist_notification SET next_attempt=NOW()") }
            c.commit()
        }
        failMail = false
        assertTrue(worker.runOnce())
        assertEquals(1, cloud.createdCalendarEventIds.size)
        assertEquals(1, cloud.sentEmails.size)
        assertFalse(worker.runOnce())
    }

    @Test
    fun `calendar updates remain independent of pending or exhausted promotion mail`() {
        val event = promotedLegacy("waiting@nav.no")
        val db = fixture.database
        val cloud = RecordingCloudClient()
        val updatedTitles = mutableListOf<String>()
        var failMail = true
        val adapter = object : CloudClient by cloud {
            override fun sendEmail(subject: String, body: String, toRecipients: List<String>,
                ccRecipients: List<String>, bccRecipients: List<String>) {
                if (failMail) throw java.io.IOException("Synthetic mail outage")
                cloud.sendEmail(subject, body, toRecipients, ccRecipients, bccRecipients)
            }
            override fun updateEvent(calendarEventId: String, event: Event, participant: Participant): arrow.core.Either<Throwable, Unit> {
                updatedTitles += event.title
                return cloud.updateEvent(calendarEventId, event, participant)
            }
        }
        val worker = WaitlistNotificationWorker(db, adapter)
        assertTrue(worker.runOnce())
        val calendarId = cloud.createdCalendarEventIds.single()
        val person = Participant("waiting@nav.no", "Waiting")

        for (deliveryState in listOf("PENDING", "FAILED")) {
            if (deliveryState == "FAILED") {
                db.connection.use { c ->
                    c.createStatement().use { it.executeUpdate("UPDATE waitlist_notification SET attempts=9,next_attempt=NOW()") }
                    c.commit()
                }
                assertTrue(worker.runOnce())
                assertTrue(worker.metrics().contains("delta_waitlist_notifications_failed 1"))
                assertFalse(worker.runOnce())
            }
            val edited = db.updateEvent(event.copy(title = "Edited while mail is $deliveryState")).getOrNull()!!
            val before = cloud.updatedCalendarEventIds.size

            cloud.batchSendUpdateOrCreationNotification(edited, db, listOf(person to null))
            adapter.sendUpdateOrCreationNotification(edited, db, person, null)

            assertEquals(before + 2, cloud.updatedCalendarEventIds.size)
            assertEquals(listOf(calendarId, calendarId), cloud.updatedCalendarEventIds.takeLast(2))
            assertEquals(edited.title, updatedTitles.last())
            assertEquals(listOf(calendarId), cloud.createdCalendarEventIds)
            assertTrue(cloud.sentEmails.isEmpty())
        }

        failMail = false
        db.connection.use { c ->
            c.createStatement().use {
                it.executeUpdate("UPDATE waitlist_notification SET state='PENDING',attempts=0,next_attempt=NOW()")
            }
            c.commit()
        }
        assertTrue(worker.runOnce())
        assertEquals("Edited while mail is FAILED", updatedTitles.last())
        assertTrue(cloud.sentEmails.single().subject.contains("Edited while mail is FAILED"))
        assertEquals(listOf(calendarId), cloud.createdCalendarEventIds)
        assertFalse(worker.runOnce())
    }

    @Test
    fun `workers skip in-flight promotion delivery and process later jobs without spinning`() {
        promotedLegacy("first@nav.no")
        promotedLegacy("second@nav.no")
        val cloud = RecordingCloudClient()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adapter = object : CloudClient by cloud {
            override fun sendEmail(subject: String, body: String, toRecipients: List<String>,
                ccRecipients: List<String>, bccRecipients: List<String>) {
                if (toRecipients == listOf("first@nav.no")) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                cloud.sendEmail(subject, body, toRecipients, ccRecipients, bccRecipients)
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        val first = pool.submit<Boolean> { WaitlistNotificationWorker(fixture.database, adapter).runOnce() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = WaitlistNotificationWorker(fixture.database, adapter)
            assertTrue(other.runOnce())
            assertEquals(listOf("second@nav.no"), cloud.sentEmails.single().toRecipients)
            assertFalse(other.runOnce(), "in-flight work must not report progress or cause a tight polling loop")
        } finally {
            release.countDown()
            try { assertTrue(first.get(5, TimeUnit.SECONDS)) } finally { pool.shutdownNow() }
        }
        assertEquals(2, cloud.sentEmails.size)
    }

    @Test
    fun `legacy update batches leave promotion creation to its worker even with stale recipient ids`() {
        val event = promotedLegacy("waiting@nav.no")
        val cloud = RecordingCloudClient()
        val staleRecipients = listOf(Participant("waiting@nav.no", "Waiting") to null)

        cloud.batchSendUpdateOrCreationNotification(event, fixture.database, staleRecipients)
        assertTrue(cloud.createdCalendarEventIds.isEmpty())
        val worker = WaitlistNotificationWorker(fixture.database, cloud)
        assertTrue(worker.runOnce())
        cloud.batchSendUpdateOrCreationNotification(event, fixture.database, staleRecipients)

        assertEquals(1, cloud.createdCalendarEventIds.size)
        assertEquals(cloud.createdCalendarEventIds, cloud.updatedCalendarEventIds)
        fixture.database.unregisterFromEvent(event.id.toString(), "waiting@nav.no")
        assertTrue(worker.runOnce())
        assertEquals(cloud.createdCalendarEventIds, cloud.deletedCalendarEventIds)
    }

    @Test
    fun `uncertain legacy creation is recovered and cancelled after signoff or event deletion`() {
        for (deleteEvent in listOf(false, true)) {
            val event = promotedLegacy("waiting@nav.no")
            val cloud = RecordingCloudClient()
            val requests = mutableListOf<Pair<Event, String>>()
            val created = mutableMapOf<String, String>()
            var loseResponse = true
            val adapter = object : CloudClient by cloud {
                override fun createWaitlistEvent(event: Event, participant: Participant, transactionId: String): arrow.core.Either<Throwable, String> {
                    requests += event to transactionId
                    val id = created.getOrPut(transactionId) { cloud.createEvent(event, participant).getOrNull()!! }
                    return if (loseResponse) {
                        loseResponse = false
                        java.io.IOException("Synthetic lost Graph response").left()
                    } else id.right()
                }
            }

            val worker = WaitlistNotificationWorker(fixture.database, adapter)
            assertTrue(worker.runOnce())
            assertEquals(1, cloud.createdCalendarEventIds.size)
            assertNull(fixture.database.getCalendarEventId(event.id.toString(), "waiting@nav.no").getOrNull())
            fixture.database.updateEvent(event.copy(title = "Changed after uncertain creation"))
            if (deleteEvent) fixture.database.deleteEvent(event.id.toString())
            else fixture.database.unregisterFromEvent(event.id.toString(), "waiting@nav.no")

            repeat(3) { worker.runOnce() }

            assertEquals(cloud.createdCalendarEventIds, cloud.deletedCalendarEventIds)
            assertEquals(2, requests.size)
            assertEquals(requests.first(), requests.last(), "recovery must reuse the exact creation snapshot and transaction ID")
            assertTrue(cloud.sentEmails.isEmpty())
            assertFalse(worker.runOnce())
        }
    }

    @Test
    fun `retried promotion creation preserves its original request then applies current event details`() {
        val event = promotedLegacy("waiting@nav.no")
        val cloud = RecordingCloudClient()
        val requests = mutableListOf<Event>()
        var savedId: String? = null
        val adapter = object : CloudClient by cloud {
            override fun createWaitlistEvent(event: Event, participant: Participant, transactionId: String): arrow.core.Either<Throwable, String> {
                requests += event
                if (savedId == null) {
                    savedId = cloud.createEvent(event, participant).getOrNull()!!
                    return java.io.IOException("Synthetic lost Graph response").left()
                }
                return savedId!!.right()
            }
        }
        val worker = WaitlistNotificationWorker(fixture.database, adapter)
        worker.runOnce()
        fixture.database.updateEvent(event.copy(title = "Updated after creation"))
        fixture.database.connection.use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE waitlist_notification SET next_attempt=NOW()") }
            c.commit()
        }

        assertTrue(worker.runOnce())
        assertEquals(2, requests.size)
        assertEquals(requests.first(), requests.last())
        assertEquals(1, cloud.createdCalendarEventIds.size)
        assertEquals(cloud.createdCalendarEventIds, cloud.updatedCalendarEventIds)
        assertTrue(cloud.sentEmails.single().subject.contains("Updated after creation"))
    }

    @Test
    fun `cancellation before delivery never creates an invitation or sends a notice`() {
        val event = promotedLegacy("waiting@nav.no")
        fixture.database.unregisterFromEvent(event.id.toString(), "waiting@nav.no")
        val cloud = RecordingCloudClient()
        val worker = WaitlistNotificationWorker(fixture.database, cloud)

        assertTrue(worker.runOnce())
        assertFalse(worker.runOnce())
        assertTrue(cloud.createdCalendarEventIds.isEmpty())
        assertTrue(cloud.sentEmails.isEmpty())
    }

    @Test
    fun `recovering a cancelled promotion cannot overwrite a later promotion for the same person`() {
        val event = promotedLegacy("waiting@nav.no")
        val cloud = RecordingCloudClient()
        val created = mutableMapOf<String, String>()
        var loseResponse = true
        val adapter = object : CloudClient by cloud {
            override fun createWaitlistEvent(event: Event, participant: Participant, transactionId: String): arrow.core.Either<Throwable, String> {
                val id = created.getOrPut(transactionId) { cloud.createEvent(event, participant).getOrNull()!! }
                return if (loseResponse) {
                    loseResponse = false
                    java.io.IOException("Synthetic lost Graph response").left()
                } else id.right()
            }
        }
        val worker = WaitlistNotificationWorker(fixture.database, adapter)
        assertTrue(worker.runOnce())
        val db = fixture.database
        db.unregisterFromEvent(event.id.toString(), "waiting@nav.no")
        db.registerForEvent(event.id.toString(), "replacement@nav.no", "Replacement")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting again"))
        db.unregisterFromEvent(event.id.toString(), "replacement@nav.no")

        repeat(5) { worker.runOnce() }

        assertEquals(2, cloud.createdCalendarEventIds.size)
        assertEquals(listOf(cloud.createdCalendarEventIds.first()), cloud.deletedCalendarEventIds)
        assertEquals(cloud.createdCalendarEventIds.last(),
            db.getCalendarEventId(event.id.toString(), "waiting@nav.no").getOrNull())
        assertTrue(waitlist.state(event.id, "waiting@nav.no").registered)
        assertEquals(1, cloud.sentEmails.size)
        assertFalse(worker.runOnce())
    }

    @Test
    fun `cancelled recovery work cannot suppress a later direct registration invitation`() {
        val event = promotedLegacy("waiting@nav.no")
        val cloud = RecordingCloudClient()
        var savedId: String? = null
        val adapter = object : CloudClient by cloud {
            override fun createWaitlistEvent(event: Event, participant: Participant, transactionId: String): arrow.core.Either<Throwable, String> {
                if (savedId == null) {
                    savedId = cloud.createEvent(event, participant).getOrNull()!!
                    return java.io.IOException("Synthetic lost Graph response").left()
                }
                return savedId!!.right()
            }
        }
        val worker = WaitlistNotificationWorker(fixture.database, adapter)
        assertTrue(worker.runOnce())
        val db = fixture.database
        db.unregisterFromEvent(event.id.toString(), "waiting@nav.no")
        assertTrue(db.registerForEvent(event.id.toString(), "waiting@nav.no", "Direct registration").isRight())

        cloud.sendUpdateOrCreationNotification(event, db, Participant("waiting@nav.no", "Direct registration"), null)

        assertEquals(2, cloud.createdCalendarEventIds.size)
        repeat(3) { worker.runOnce() }
        assertEquals(listOf(cloud.createdCalendarEventIds.first()), cloud.deletedCalendarEventIds)
        assertEquals(cloud.createdCalendarEventIds.last(),
            db.getCalendarEventId(event.id.toString(), "waiting@nav.no").getOrNull())
        assertTrue(cloud.sentEmails.isEmpty())
    }

    @Test
    fun `legacy cancellation retries failures but acknowledges wrapped Graph not found`() {
        val db = fixture.database
        val event = db.addEvent(draft())
        db.registerForEvent(event.id.toString(), "host@nav.no", "Host", ParticipantType.HOST)
        db.registerForEvent(event.id.toString(), "attendee@nav.no", "Attendee")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))
        db.unregisterFromEvent(event.id.toString(), "attendee@nav.no")
        val cloud = RecordingCloudClient()
        var status = 503
        val adapter = object : CloudClient by cloud {
            override fun deleteEvent(calendarEventId: String) =
                RuntimeException("Failed to delete event", object : ApiException() {
                    init { responseStatusCode = status }
                }).left()
        }
        val worker = WaitlistNotificationWorker(db, adapter)
        assertTrue(worker.runOnce())
        db.unregisterFromEvent(event.id.toString(), "waiting@nav.no")
        assertTrue(worker.runOnce())
        assertTrue(worker.metrics().contains("delta_waitlist_notifications_pending 1"))
        assertFalse(worker.runOnce())
        db.connection.use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE waitlist_calendar_cancellation SET next_attempt=NOW()") }
            c.commit()
        }
        status = 404
        assertTrue(worker.runOnce())
        assertFalse(worker.runOnce())
        assertTrue(worker.metrics().contains("delta_waitlist_notifications_pending 0"))
        assertTrue(worker.metrics().contains("delta_waitlist_notifications_failed 0"))
    }
}
