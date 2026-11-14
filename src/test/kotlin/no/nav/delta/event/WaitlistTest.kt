package no.nav.delta.event

import arrow.core.left
import com.microsoft.kiota.ApiException
import no.nav.delta.calendar.SharedCalendarRepository
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.calendar.SharedCalendarValidationException
import no.nav.delta.calendar.SharedCalendarAttendeeSnapshot
import no.nav.delta.calendar.SharedCalendarResponse
import no.nav.delta.email.CloudClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
