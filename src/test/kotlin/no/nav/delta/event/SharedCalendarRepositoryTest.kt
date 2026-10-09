package no.nav.delta.event

import no.nav.delta.Environment
import no.nav.delta.calendar.SharedCalendarRepository
import no.nav.delta.calendar.SharedCalendarAttendeeSnapshot
import no.nav.delta.calendar.SharedCalendarResponse
import no.nav.delta.calendar.SharedCalendarValidationException
import no.nav.delta.plugins.DatabaseConfig
import no.nav.delta.plugins.DatabaseInterface
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import arrow.core.some

@Testcontainers
class SharedCalendarRepositoryTest {
    companion object {
        @Container
        private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
        private lateinit var db: DatabaseInterface
        private lateinit var repository: SharedCalendarRepository

        @JvmStatic @BeforeAll
        fun setup() {
            db = DatabaseConfig(Environment(
                dbJdbcUrl = postgres.jdbcUrl, dbUsername = postgres.username, dbPassword = postgres.password,
            ))
            repository = SharedCalendarRepository(db)
        }

        @JvmStatic @AfterAll
        fun close() { db.close() }
    }

    private fun draft(limit: Int = 5) = CreateEvent(
        title = "Shared", description = "Description",
        startTime = LocalDateTime.now().plusDays(5), endTime = LocalDateTime.now().plusDays(5).plusHours(1),
        location = "Office", public = true, participantLimit = limit,
        signupDeadline = LocalDateTime.now().plusDays(4),
        invitees = listOf(InviteeRequest("person@nav.no")),
    )

    @BeforeEach
    fun reset() {
        db.connection.use { c ->
            c.createStatement().use { it.execute("TRUNCATE event,shared_calendar_outbox,shared_calendar_removal,shared_calendar_recipient_daily CASCADE") }
            c.commit()
        }
    }

    @Test
    fun `create persists host invitations and pending calendar work immediately`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val full = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals(InviteMode.SHARED, full.event.inviteMode)
        assertEquals(CalendarSyncStatus.PENDING, full.event.calendarSyncStatus)
        assertEquals(listOf(Participant("host@nav.no", "Host")), full.hosts)
        assertTrue(full.participants.isEmpty())
        assertEquals("person@nav.no", full.invited.single().email)
        assertEquals(ParticipantStatus.INVITED, full.invited.single().status)
        val work = repository.claimNext()!!
        assertEquals(event.id, work.eventId)
        assertEquals(setOf("host@nav.no", "person@nav.no"), repository.loadDesired(work).attendees.map { it.email }.toSet())
    }

    @Test
    fun `changing selected room resets previously accepted booking status`() {
        val event = repository.create(draft().copy(
            roomEmail = "room-one@nav.no", roomName = "Room One",
        ), "host@nav.no", "Host").getOrNull()!!
        db.connection.use { c ->
            c.prepareStatement("UPDATE event SET room_status='ACCEPTED' WHERE id=?").use {
                it.setObject(1, event.id)
                it.executeUpdate()
            }
            c.commit()
        }
        val updated = repository.update(event.id, draft().copy(
            roomEmail = "room-two@nav.no", roomName = "Room Two",
        )).getOrNull()!!
        assertEquals(RoomBookingStatus.PENDING, updated.roomStatus)
    }

    @Test
    fun `create acknowledgement after deletion preserves id and cancellation`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val create = repository.claimNext()!!
        assertTrue(repository.delete(event.id).isRight())
        assertTrue(repository.recordGraphId(create, "graph-created"))
        assertTrue(repository.complete(create))
        val cancel = repository.claimNext()!!
        assertTrue(cancel.cancel)
        assertEquals("graph-created", cancel.graphEventId)
        assertNull(repository.loadDesired(cancel).event)
        assertTrue(repository.complete(cancel))
        assertNull(repository.claimNext())
    }

    @Test
    fun `invites reserve capacity signup consumes same seat and decline releases it`() {
        val event = repository.create(draft(2), "host@nav.no", "Host").getOrNull()!!
        assertEquals(EventFullException, repository.signup(event.id, "other@nav.no", "Other").leftOrNull())
        assertTrue(repository.signup(event.id, "person@nav.no", "Person").isRight())
        assertEquals(1, db.getFullEvent(event.id.toString()).getOrNull()!!.participants.size)
        assertTrue(repository.signoff(event.id, "person@nav.no").isRight())
        assertTrue(repository.signup(event.id, "other@nav.no", "Other").isRight())
        assertEquals(EventWillHaveNoHostsException, repository.removeParticipant(event.id, "host@nav.no").leftOrNull())
        assertEquals(EventWillHaveNoHostsException,
            repository.changeParticipant(event.id, ChangeParticipant("host@nav.no", ParticipantType.PARTICIPANT)).leftOrNull())
    }

    @Test
    fun `RSVP and forwarding update Delta without producing calendar writes`() {
        val event = repository.create(draft(2), "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        repository.signup(event.id, "person@nav.no", "Person")
        assertNull(repository.claimNext())
        val snapshots = listOf(
            SharedCalendarAttendeeSnapshot("host@nav.no", "Host", SharedCalendarResponse.DECLINED),
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.TENTATIVE),
            SharedCalendarAttendeeSnapshot("forward@nav.no", "Forward", SharedCalendarResponse.NONE),
        )
        assertTrue(repository.reconcile(event.id, snapshots).isRight())
        val full = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals(1, full.hosts.size)
        assertEquals(1, full.participants.size)
        assertEquals(ParticipantStatus.FORWARDED, full.invited.single().status)
        assertNull(repository.claimNext())
        repository.reconcile(event.id, snapshots.map {
            if (it.email == "person@nav.no") it.copy(response = SharedCalendarResponse.DECLINED) else it
        })
        assertTrue(repository.signup(event.id, "new@nav.no", "New").isRight())
        val desired = repository.loadDesired(repository.claimNext()!!)
        assertTrue(desired.attendees.any { it.email == "person@nav.no" && it.status == ParticipantStatus.DECLINED })
    }

    @Test
    fun `stale worker failure cannot wipe newer writes and expired lease is fenced`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val now = Instant.now().plusSeconds(1)
        val work = repository.claimNext(now, Duration.ofSeconds(1))!!
        repository.addInvitations(event.id, listOf(InviteeRequest("extra@nav.no")))
        assertTrue(repository.fail(work))
        assertEquals(CalendarSyncStatus.PENDING, db.getEvent(event.id.toString()).getOrNull()!!.calendarSyncStatus)
        val newer = repository.claimNext(now.plusSeconds(2), Duration.ofSeconds(1))!!
        val replacement = repository.claimNext(now.plusSeconds(4))!!
        assertFalse(repository.complete(newer))
        assertFalse(repository.recordGraphId(newer, "stale-id"))
        assertTrue(repository.retry(replacement, now.plusSeconds(10)))
        assertNull(repository.claimNext(now.plusSeconds(5)))
        val retried = repository.claimNext(now.plusSeconds(11))!!
        assertTrue(repository.fail(retried))
        val failed = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals(CalendarSyncStatus.FAILED, failed.event.calendarSyncStatus)
        assertEquals("Calendar synchronization failed. Please retry.", failed.calendarSyncError)
        assertTrue(repository.retryFailed(event.id).isRight())
        assertNotNull(repository.claimNext(now.plusSeconds(12)))
    }

    @Test
    fun `remove and readd coalescing still sends durable removal before reinvitation`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        repository.removeParticipant(event.id, "person@nav.no")
        repository.addInvitations(event.id, listOf(InviteeRequest("person@nav.no")))
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Stale Graph", SharedCalendarResponse.ACCEPTED),
        ))
        val removal = repository.claimNext()!!
        assertFalse(repository.loadDesired(removal).attendees.any { it.email == "person@nav.no" })
        assertEquals(listOf("person@nav.no"), repository.loadDesired(removal).removals)
        repository.complete(removal)
        val readd = SharedCalendarRepository(db).claimNext()!!
        assertEquals(ParticipantStatus.INVITED,
            repository.loadDesired(readd).attendees.single { it.email == "person@nav.no" }.status)
        assertTrue(repository.loadDesired(readd).removals.isEmpty())
        repository.complete(readd)
        repository.removeParticipant(event.id, "person@nav.no")
        repository.complete(repository.claimNext()!!)
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Revoked Graph", SharedCalendarResponse.ACCEPTED),
        ))
        assertTrue(db.getFullEvent(event.id.toString()).getOrNull()!!.invited.isEmpty())
        assertNull(repository.claimNext())
    }

    @Test
    fun `concurrent signups cannot consume the same last seat`() {
        val event = repository.create(draft(2).copy(invitees = emptyList()), "host@nav.no", "Host").getOrNull()!!
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val results = (1..8).map { index ->
                pool.submit<arrow.core.Either<ExceptionWithDefaultResponse, Unit>> {
                    start.await()
                    repository.signup(event.id, "user$index@nav.no", "User $index")
                }
            }
            start.countDown()
            val completed = results.map { it.get() }
            assertEquals(1, completed.count { it.isRight() })
            assertEquals(7, completed.count { it.leftOrNull() == EventFullException })
            assertEquals(1, db.getFullEvent(event.id.toString()).getOrNull()!!.participants.size)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `expired invitations hold no seats and late accepts decline without outgoing work`() {
        val event = repository.create(draft(1).copy(signupDeadline = LocalDateTime.now().minusMinutes(1)),
            "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.ACCEPTED),
        ))
        val full = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals(ParticipantStatus.DECLINED, full.invited.single().status)
        assertNull(repository.claimNext())
        assertTrue(db.getFullEvents(joinedBy = "person@nav.no".some()).isEmpty())
        assertTrue(db.getFullEvents(joinedBy = "host@nav.no".some()).isEmpty())
    }

    @Test
    fun `event edit categories and invitation batch roll back together when capacity is exceeded`() {
        val category = db.createCategory(CreateCategory("shared-atomic")).getOrNull()!!
        val event = repository.create(draft(3), "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        val result = repository.update(event.id, draft(3).copy(
            title = "Not persisted", categories = listOf(category.id),
            invitees = listOf(InviteeRequest("one@nav.no"), InviteeRequest("two@nav.no")),
        ))
        assertEquals(EventFullException, result.leftOrNull())
        val full = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals("Shared", full.event.title)
        assertTrue(full.categories.isEmpty())
        assertEquals(listOf("person@nav.no"), full.invited.map { it.email })
        assertNull(repository.claimNext())
    }

    @Test
    fun `reconciliation has durable independent intent and unsupported forward removal`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        repository.enqueueReconciliation(event.id)
        val reconcile = repository.claimNext()!!
        assertTrue(reconcile.reconcile)
        assertFalse(reconcile.write)
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("outside@example.com", "Outside", SharedCalendarResponse.NONE),
        ))
        repository.complete(reconcile)
        val removal = repository.claimNext()!!
        assertTrue(removal.write)
        assertEquals(listOf("outside@example.com"), repository.loadDesired(removal).removals)
        repository.complete(removal)
        assertNull(repository.claimNext())
    }

    @Test
    fun `recurring occurrences independently queue shared writes and cancellation on shortening`() {
        val request = draft().copy(invitees = null, recurrence = RecurrenceRequest(
            RecurrenceFrequency.WEEKLY, LocalDateTime.now().plusDays(19).toLocalDate(),
        ))
        val created = db.createRecurringEventSeries(request, "host@nav.no", "Host", InviteMode.SHARED).getOrNull()!!
        assertEquals(3, created.affectedEvents.size)
        created.affectedEvents.forEach { event ->
            assertEquals(InviteMode.SHARED, event.inviteMode)
            assertEquals(CalendarSyncStatus.PENDING, event.calendarSyncStatus)
            val work = repository.claimNext()!!
            repository.recordGraphId(work, "graph-${work.eventId}")
            repository.complete(work)
        }
        val shortened = db.updateRecurringSeriesFromOccurrence(created.referenceEventId.toString(),
            request.copy(recurrence = request.recurrence!!.copy(untilDate = request.startTime.toLocalDate())),
            "host@nav.no").getOrNull()!!
        assertEquals(1, shortened.affectedEvents.size)
        assertEquals(InviteMode.SHARED, shortened.affectedEvents.single().inviteMode)
        assertEquals(CalendarSyncStatus.SYNCED, shortened.affectedEvents.single().calendarSyncStatus)
        val jobs = generateSequence { repository.claimNext()?.also { repository.complete(it) } }.toList()
        assertEquals(3, jobs.size)
        assertEquals(2, jobs.count { it.cancel })
        assertTrue(jobs.filter { it.cancel }.all { it.graphEventId != null })
    }

    @Test
    fun `metadata-only UPCOMING edit does not queue recurring calendar detail patches`() {
        val request = draft().copy(
            invitees = null,
            recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, LocalDateTime.now().plusDays(19).toLocalDate()),
        )
        val created = db.createRecurringEventSeries(request, "host@nav.no", "Host", InviteMode.SHARED).getOrNull()!!
        created.affectedEvents.forEach {
            val work = repository.claimNext()!!
            repository.recordGraphId(work, "graph-${work.eventId}")
            repository.complete(work)
        }
        val changed = db.updateRecurringSeriesFromOccurrence(
            created.referenceEventId.toString(),
            request.copy(public = false, participantLimit = request.participantLimit + 1),
            "host@nav.no",
        ).getOrNull()!!
        assertTrue(changed.affectedEvents.all { it.calendarSyncStatus == CalendarSyncStatus.SYNCED })
        val work = generateSequence { repository.claimNext()?.also { repository.complete(it) } }.toList()
        assertEquals(changed.affectedEvents.size, work.size)
        assertTrue(work.all { !it.details })
    }

    @Test
    fun `attendee ceiling includes rooms and failed batches leave no event`() {
        val invitees = (1..489).map { InviteeRequest("invite$it@nav.no") }
        val request = draft(0).copy(roomEmail = "room@nav.no", roomName = "Room", invitees = invitees)
        val rejected = assertThrows(SharedCalendarValidationException::class.java) {
            repository.create(request, "host@nav.no", "Host")
        }
        assertEquals(409, rejected.statusCode)
        assertTrue(db.getEvents().isEmpty())
        assertNull(repository.claimNext())
        val event = repository.create(request.copy(invitees = invitees.dropLast(1)), "host@nav.no", "Host").getOrNull()!!
        assertEquals(488, db.getFullEvent(event.id.toString()).getOrNull()!!.invited.size)
        assertThrows(SharedCalendarValidationException::class.java) {
            repository.addInvitations(event.id, listOf(InviteeRequest("extra@nav.no")))
        }
    }

    @Test
    fun `reserved Outlook accept registers and forwarded accept cannot steal a seat`() {
        val event = repository.create(draft(2), "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.TENTATIVE),
            SharedCalendarAttendeeSnapshot("forward@nav.no", "Forward", SharedCalendarResponse.ACCEPTED),
        ))
        var full = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals(ParticipantStatus.INVITED, full.invited.single { it.email == "person@nav.no" }.status)
        assertEquals(ParticipantStatus.DECLINED, full.invited.single { it.email == "forward@nav.no" }.status)
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.ACCEPTED),
        ))
        full = db.getFullEvent(event.id.toString()).getOrNull()!!
        assertEquals(listOf("person@nav.no"), full.participants.map { it.email })
        assertNull(repository.claimNext())
        assertEquals(event.id, db.getFullEvents(joinedBy = "person@nav.no".some()).single().event.id)
    }

    @Test
    fun `metrics persist recipient attempts and measure pending and failure ages`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val now = Instant.now()
        val metrics = repository.metrics(now.plusSeconds(60))
        assertEquals(1L, metrics.pendingRows)
        assertEquals(0L, metrics.failedRows)
        assertTrue(metrics.oldestPendingAgeSeconds >= 59)
        val work = repository.claimNext(now.plusSeconds(1))!!
        assertTrue(repository.recordEstimatedRecipients(work, 2, now))
        assertTrue(repository.recordEstimatedRecipients(work, 2, now))
        assertTrue(repository.fail(work))
        assertFalse(repository.recordEstimatedRecipients(work, 100, now))
        val failed = SharedCalendarRepository(db).metrics(now.plusSeconds(120))
        assertEquals(0L, failed.pendingRows)
        assertEquals(1L, failed.failedRows)
        assertTrue(failed.oldestFailureAgeSeconds >= 119)
        assertEquals(4L, failed.estimatedRecipientsToday)
        assertEquals(0L, repository.metrics(now.plus(Duration.ofDays(1))).estimatedRecipientsToday)
        repository.retryFailed(event.id)
        val retry = repository.metrics()
        assertEquals(1L, retry.pendingRows)
        assertEquals(0L, retry.failedRows)
        assertEquals(0L, retry.oldestFailureAgeSeconds)
        repository.recordRecipients(3)
        val statistics = SharedCalendarRepository(db).statistics()
        assertEquals(1L, statistics.pending)
        assertEquals(0L, statistics.failed)
        assertEquals(7L, statistics.recipientsToday)
        assertThrows(SharedCalendarValidationException::class.java) { repository.recordRecipients(-1) }
    }

    @Test
    fun `stale Outlook declines cannot overwrite newer acceptance or Delta signup`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        repository.complete(repository.claimNext()!!)
        val now = Instant.now()
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.ACCEPTED, respondedAt = now),
        ))
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.DECLINED, respondedAt = now.minusSeconds(5)),
        ))
        assertEquals(1, db.getFullEvent(event.id.toString()).getOrNull()!!.participants.size)
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.DECLINED, respondedAt = now.plusSeconds(1)),
        ))
        assertEquals(ParticipantStatus.DECLINED, db.getFullEvent(event.id.toString()).getOrNull()!!.invited.single().status)
        repository.signup(event.id, "person@nav.no", "Person")
        repository.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("person@nav.no", "Person", SharedCalendarResponse.DECLINED, respondedAt = now.plusSeconds(1)),
        ))
        assertEquals(1, db.getFullEvent(event.id.toString()).getOrNull()!!.participants.size)
    }

    @Test
    fun `webhook queues reconciliation by Graph id and leaves legacy and unknown ids alone`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val work = repository.claimNext()!!
        repository.recordGraphId(work, "opaque-graph-id")
        repository.complete(work)
        assertFalse(repository.enqueueReconciliation("unknown-id"))
        val legacy = db.addEvent(draft().copy(invitees = null))
        db.setMasterCalendarEventId(legacy.id.toString(), "legacy-graph-id")
        assertFalse(repository.enqueueReconciliation("legacy-graph-id"))
        assertTrue(repository.enqueueReconciliation("opaque-graph-id"))
        val reconciliation = repository.claimNext()!!
        assertEquals(event.id, reconciliation.eventId)
        assertTrue(reconciliation.reconcile)
        assertFalse(reconciliation.write)
    }

    @Test
    fun `authenticated local host is accepted without relaxing invitee Nav restriction`() {
        val event = repository.create(draft(), "test@localhost", "Local Host").getOrNull()!!
        assertEquals("test@localhost", db.getHosts(event.id.toString()).getOrNull()!!.single().email)
        assertTrue(repository.update(event.id, draft(), "test@localhost").id == event.id)
        repository.invite(event.id, listOf(InviteeRequest("another@nav.no")), "test@localhost")
        assertThrows(SharedCalendarValidationException::class.java) {
            repository.addInvitations(event.id, listOf(InviteeRequest("another@localhost")))
        }
        assertEquals(EventWillHaveNoHostsException, repository.removeParticipant(event.id, "test@localhost").leftOrNull())
    }

    @Test
    fun `live worker session prevents expired lease takeover during Graph operations`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val now = Instant.now().plusSeconds(1)
        val work = repository.claimNext(now, Duration.ofSeconds(1))!!
        val result = repository.withEventLock(work) {
            assertNull(SharedCalendarRepository(db).claimNext(now.plusSeconds(10)))
            assertNull(SharedCalendarRepository(db).withEventLock(work) { "overlap" })
            assertTrue(repository.recordGraphId(work, "graph-id"))
            "done"
        }
        assertEquals("done", result)
        val reclaimed = repository.claimNext(now.plusSeconds(11))!!
        assertEquals(event.id, reclaimed.eventId)
        assertNull(repository.withEventLock(work) { fail("Stale claim must not call Graph") })
        assertEquals("current", repository.withEventLock(reclaimed) { "current" })
        assertThrows(IllegalStateException::class.java) {
            repository.withEventLock(reclaimed) { error("Simulated network failure") }
        }
        assertNotNull(repository.claimNext(now.plusSeconds(400)))
    }

    @Test
    fun `detail edits have independent write intent and metadata does not overwrite producer edits`() {
        val event = repository.create(draft(), "host@nav.no", "Host").getOrNull()!!
        val create = repository.claimNext()!!
        assertTrue(create.details)
        repository.complete(create)
        repository.addInvitations(event.id, listOf(InviteeRequest("extra@nav.no")))
        val attendees = repository.claimNext()!!
        assertFalse(attendees.details)
        repository.update(event.id, draft().copy(title = "New title"))
        repository.recordMeetingMetadata(attendees, no.nav.delta.room.MasterEventResult(
            calendarEventId = "graph-id", teamsJoinUrl = "https://teams.microsoft.com/example",
            roomStatus = null, teamsConferenceId = null, teamsDialIn = null,
        ))
        repository.complete(attendees)
        val edit = repository.claimNext()!!
        assertTrue(edit.details)
        val updated = db.getEvent(event.id.toString()).getOrNull()!!
        assertEquals("New title", updated.title)
        assertNull(updated.teamsJoinUrl)
        repository.complete(edit)
        assertNull(repository.claimNext())
    }
}
