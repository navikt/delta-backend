package no.nav.delta.event

import no.nav.delta.calendar.SharedCalendarAttendeeSnapshot
import no.nav.delta.calendar.SharedCalendarRepository
import no.nav.delta.calendar.SharedCalendarResponse
import no.nav.delta.calendar.SharedCalendarValidationException
import no.nav.delta.support.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class SignupOptionsTest {
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

    private fun draft(
        options: List<SignupOptionRequest>? = listOf(
            SignupOptionRequest(label = "Fysisk", participantLimit = 1, waitlistEnabled = true),
            SignupOptionRequest(label = "Digitalt"),
        ),
    ) = CreateEvent(
        title = "Options", description = "Synthetic event", location = "Office", public = true,
        startTime = LocalDateTime.now().plusDays(2), endTime = LocalDateTime.now().plusDays(2).plusHours(1),
        signupDeadline = LocalDateTime.now().plusDays(1), participantLimit = 0, signupOptions = options,
    )

    private fun create(draft: CreateEvent = draft()): Event = calendar.create(draft, "host@nav.no", "Host").getOrNull()!!
    private fun full(event: Event) = fixture.database.getFullEvent(event.id.toString()).getOrNull()!!
    private fun Event.option(label: String) = signupOptions.single { it.label == label }

    @Test
    fun `options allocate independently and hosts do not consume option seats`() {
        val event = create()
        val physical = event.option("Fysisk").id
        val digital = event.option("Digitalt").id

        assertTrue(calendar.signup(event.id, "first@nav.no", "First", physical).isRight())
        assertEquals(EventFullException, calendar.signup(event.id, "second@nav.no", "Second", physical).leftOrNull())
        assertTrue(calendar.signup(event.id, "second@nav.no", "Second", digital).isRight())

        val saved = full(event)
        assertEquals(1, saved.event.option("Fysisk").allocatedSeats)
        assertEquals(1, saved.event.option("Digitalt").allocatedSeats)
        assertEquals(0, saved.event.participantLimit)
        assertEquals(mapOf("first@nav.no" to physical, "second@nav.no" to digital),
            saved.participants.associate { it.email to it.signupOptionId })
        assertNull(saved.hosts.single().signupOptionId)
    }

    @Test
    fun `several options require a valid choice from the same event`() {
        val event = create()
        val other = create()

        assertThrows(SharedCalendarValidationException::class.java) {
            calendar.signup(event.id, "person@nav.no", "Person")
        }
        assertThrows(SharedCalendarValidationException::class.java) {
            calendar.signup(event.id, "person@nav.no", "Person", other.option("Digitalt").id)
        }
        assertTrue(full(event).participants.isEmpty())
    }

    @Test
    fun `switching into a full option keeps the current place`() {
        val event = create()
        val physical = event.option("Fysisk").id
        val digital = event.option("Digitalt").id
        calendar.signup(event.id, "first@nav.no", "First", physical)
        calendar.signup(event.id, "second@nav.no", "Second", digital)

        assertEquals(EventFullException, calendar.switchSignupOption(event.id, "second@nav.no", physical).leftOrNull())
        assertEquals(digital, full(event).participants.single { it.email == "second@nav.no" }.signupOptionId)

        assertTrue(calendar.switchSignupOption(event.id, "first@nav.no", digital).isRight())
        assertTrue(calendar.switchSignupOption(event.id, "second@nav.no", physical).isRight())
        assertEquals(mapOf("first@nav.no" to digital, "second@nav.no" to physical),
            full(event).participants.associate { it.email to it.signupOptionId })
    }

    @Test
    fun `waiting lists queue and promote per option`() {
        val event = create()
        val physical = event.option("Fysisk").id
        val digital = event.option("Digitalt").id
        calendar.signup(event.id, "first@nav.no", "First", physical)

        val queued = waitlist.join(event.id, Participant("waiting@nav.no", "Waiting", physical))
        assertEquals(1, queued.position)
        assertEquals(physical, queued.signupOptionId)
        assertThrows(SharedCalendarValidationException::class.java) {
            waitlist.join(event.id, Participant("digital@nav.no", "Digital", digital))
        }
        assertTrue(calendar.signup(event.id, "digital@nav.no", "Digital", digital).isRight())

        assertTrue(calendar.signoff(event.id, "first@nav.no").isRight())
        val promoted = full(event).participants.single { it.email == "waiting@nav.no" }
        assertEquals(physical, promoted.signupOptionId)
        assertEquals(0, full(event).event.option("Fysisk").waitlistCount)
    }

    @Test
    fun `an Outlook acceptance without a chosen option asks the person to choose in Delta`() {
        val event = create()
        calendar.complete(calendar.claimNext()!!)

        calendar.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("forward@nav.no", "Forward", SharedCalendarResponse.ACCEPTED),
        ))

        assertEquals("OPTION_REQUIRED", calendar.refusals(event.id).single().reason)
        assertEquals(ParticipantStatus.DECLINED, full(event).invited.single().status)
    }

    @Test
    fun `invitations reserve a seat in their option`() {
        val event = create(draft().copy(invitees = null))
        val physical = event.option("Fysisk").id
        val digital = event.option("Digitalt").id
        assertTrue(calendar.addInvitations(event.id, listOf(InviteeRequest("invited@nav.no", physical))).isRight())

        assertEquals(EventFullException, calendar.signup(event.id, "other@nav.no", "Other", physical).leftOrNull())
        calendar.complete(calendar.claimNext()!!)
        calendar.reconcile(event.id, listOf(
            SharedCalendarAttendeeSnapshot("invited@nav.no", "Invited", SharedCalendarResponse.ACCEPTED),
        ))
        assertEquals(physical, full(event).participants.single().signupOptionId)
        assertTrue(calendar.refusals(event.id).isEmpty())
        assertTrue(calendar.signup(event.id, "other@nav.no", "Other", digital).isRight())
    }

    @Test
    fun `option edits cannot strand registrations or queues`() {
        val event = create()
        val physical = event.option("Fysisk")
        val digital = event.option("Digitalt")
        calendar.signup(event.id, "first@nav.no", "First", physical.id)
        calendar.signup(event.id, "digital1@nav.no", "Digital", digital.id)
        calendar.signup(event.id, "digital2@nav.no", "Digital", digital.id)
        waitlist.join(event.id, Participant("queue@nav.no", "Queue", physical.id))
        fun rejected(vararg options: SignupOptionRequest) = assertThrows(SharedCalendarValidationException::class.java) {
            calendar.update(event.id, draft(options.toList()), "host@nav.no")
        }.statusCode
        val keepPhysical = SignupOptionRequest(physical.id, "Fysisk", 1, true)

        assertEquals(409, rejected(keepPhysical, SignupOptionRequest(digital.id, "Digitalt", 1)))
        assertEquals(409, rejected(keepPhysical))
        assertEquals(409, rejected(SignupOptionRequest(physical.id, "Fysisk", 1), SignupOptionRequest(digital.id, "Digitalt")))
        assertEquals(400, rejected())

        val renamed = calendar.update(event.id, draft(listOf(
            SignupOptionRequest(physical.id, "I salen", 1, true),
            SignupOptionRequest(digital.id, "Teams"),
            SignupOptionRequest(label = "Opptak"),
        )), "host@nav.no")
        assertEquals(listOf("I salen", "Teams", "Opptak"), renamed.signupOptions.map { it.label })
        assertEquals(physical.id, renamed.option("I salen").id)
        assertEquals(1, renamed.option("I salen").waitlistCount)
    }

    @Test
    fun `events without options keep event-level capacity where hosts count`() {
        val event = create(draft(options = null).copy(participantLimit = 2))
        assertTrue(event.signupOptions.isEmpty())

        assertThrows(SharedCalendarValidationException::class.java) {
            calendar.signup(event.id, "person@nav.no", "Person", UUID.randomUUID())
        }
        assertTrue(calendar.signup(event.id, "person@nav.no", "Person").isRight())
        assertEquals(EventFullException, calendar.signup(event.id, "late@nav.no", "Late").leftOrNull())
        assertThrows(SharedCalendarValidationException::class.java) {
            calendar.update(event.id, draft(listOf(SignupOptionRequest(label = "Alene"))), "host@nav.no")
        }
    }

    @Test
    fun `converting an event moves its participants and queue into the first option`() {
        val event = create(draft(options = null).copy(participantLimit = 2, waitlistEnabled = true))
        calendar.signup(event.id, "person@nav.no", "Person")
        waitlist.join(event.id, Participant("waiting@nav.no", "Waiting"))

        val converted = calendar.update(event.id, draft(listOf(
            SignupOptionRequest(label = "Fysisk", participantLimit = 1, waitlistEnabled = true),
            SignupOptionRequest(label = "Digitalt"),
        )), "host@nav.no")

        val physical = converted.option("Fysisk")
        assertEquals(1, physical.allocatedSeats)
        assertEquals(1, physical.waitlistCount)
        assertEquals(physical.id, full(event).participants.single().signupOptionId)
        assertEquals(physical.id, waitlist.state(event.id, "waiting@nav.no").signupOptionId)
    }

    @Test
    fun `recurring series share options across upcoming occurrences`() {
        val series = fixture.database.createRecurringEventSeries(
            draft().copy(recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY,
                LocalDateTime.now().plusDays(2).toLocalDate().plusWeeks(2))),
            "host@nav.no", "Host", InviteMode.SHARED,
        ).getOrNull()!!.affectedEvents
        assertEquals(3, series.size)
        assertTrue(series.all { it.signupOptions.map { option -> option.label } == listOf("Fysisk", "Digitalt") })
        assertEquals(3, series.map { it.option("Fysisk").id }.toSet().size)

        val first = series.first()
        val updated = fixture.database.updateRecurringSeriesFromOccurrence(first.id.toString(), draft(listOf(
            SignupOptionRequest(first.option("Fysisk").id, "I salen", 5, true),
            SignupOptionRequest(first.option("Digitalt").id, "Digitalt"),
        )).copy(startTime = first.startTime, endTime = first.endTime, editScope = EventEditScope.UPCOMING), "host@nav.no")
            .getOrNull()!!.affectedEvents

        assertTrue(updated.all { it.option("I salen").participantLimit == 5 })
        assertEquals(series.map { it.option("Fysisk").id }, updated.map { it.option("I salen").id })
    }
}
