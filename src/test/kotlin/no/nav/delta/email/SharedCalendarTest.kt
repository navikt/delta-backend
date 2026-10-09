package no.nav.delta.email

import com.microsoft.graph.models.ResponseType
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal fun sharedTestEvent() = Event(
    id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    title = "Shared event",
    description = "Description",
    startTime = LocalDateTime.of(2026, 10, 2, 9, 0),
    endTime = LocalDateTime.of(2026, 10, 2, 10, 0),
    location = "Office",
    public = true,
    participantLimit = 10,
    signupDeadline = null,
    roomEmail = "room@nav.no",
    roomName = "Room",
    isOnlineMeeting = true,
)

class SharedCalendarTest {
    @Test
    fun `dummy create retries return same shared event and individually addressed attendees`() {
        val client = DummyCloudClient()
        val event = sharedTestEvent()
        val people = listOf(Participant("host@nav.no", "Host"), Participant("person@nav.no", "Person"))
        val first = client.createSharedEvent(event, people, "persisted-transaction").getOrNull()!!
        assertEquals(first, client.createSharedEvent(event, people, "persisted-transaction").getOrNull())
        val snapshot = client.getSharedEvent(first.calendarEventId).getOrNull()!!
        assertEquals(listOf("host@nav.no", "person@nav.no", "room@nav.no"), snapshot.attendees.map { it.email })
        assertTrue(snapshot.attendees.last().isResource)
        assertNotNull(snapshot.teamsJoinUrl)
        assertFalse(snapshot.isCancelled)
    }

    @Test
    fun `dummy attendee and details mutations retain responses and move versions without changing native meeting`() {
        val client = DummyCloudClient()
        val event = sharedTestEvent()
        val id = client.createSharedEvent(event, listOf(Participant("person@nav.no", "Person")), "tx").getOrNull()!!.calendarEventId
        val before = client.getSharedEvent(id).getOrNull()!!
        val time = OffsetDateTime.parse("2026-10-01T08:00:00Z")
        val answered = before.attendees.map { it.copy(response = ResponseType.Accepted, responseTime = time) }
        assertTrue(client.updateSharedAttendees(id, answered, before.changeKey).isRight())
        val afterAttendees = client.getSharedEvent(id).getOrNull()!!
        assertEquals(answered, afterAttendees.attendees)
        assertNotEquals(before.changeKey, afterAttendees.changeKey)
        assertTrue(client.updateSharedDetails(id, event.copy(title = "Edited", description = "New description")).isRight())
        val edited = client.getSharedEvent(id).getOrNull()!!
        assertEquals(answered, edited.attendees)
        assertEquals(before.teamsJoinUrl, edited.teamsJoinUrl)
        assertTrue(edited.body!!.contains("New description"))
        assertFalse(edited.body.contains(">Description<"))
        assertNotEquals(afterAttendees.changeKey, edited.changeKey)
        assertEquals(404, (client.updateSharedAttendees("missing", answered).leftOrNull() as SharedGraphException).httpStatus)
    }

    @Test
    fun `dummy cancellation is idempotent for existing event and never invents missing events`() {
        val client = DummyCloudClient()
        val id = client.createSharedEvent(sharedTestEvent(), emptyList(), "tx").getOrNull()!!.calendarEventId
        assertTrue(client.cancelSharedEvent(id).isRight())
        val cancelled = client.getSharedEvent(id).getOrNull()!!
        assertTrue(cancelled.isCancelled)
        assertTrue(client.cancelSharedEvent(id).isRight())
        assertEquals(cancelled, client.getSharedEvent(id).getOrNull())
        assertEquals(404, (client.cancelSharedEvent("missing").leftOrNull() as SharedGraphException).httpStatus)
        assertTrue(client.updateSharedDetails(id, sharedTestEvent()).isLeft())
    }

    @Test
    fun `dummy directory search uses seeded real people fields and Nav mail only`() {
        val client = DummyCloudClient(directoryPeople = listOf(
            DirectoryPerson("1", "Alex One", "alex.one@nav.no"),
            DirectoryPerson("2", "Alex External", "alex@external.no"),
            DirectoryPerson("3", "Other", "other@nav.no"),
        ))
        assertEquals(listOf(DirectoryPerson("1", "Alex One", "alex.one@nav.no")), client.searchPeople("alex").getOrNull())
        assertEquals(emptyList<DirectoryPerson>(), client.searchPeople("Missing").getOrNull())
    }

    @Test
    fun `shared create never accepts an absent persisted transaction identifier`() {
        val error = DummyCloudClient().createSharedEvent(sharedTestEvent(), emptyList(), " ")
            .leftOrNull() as SharedGraphException
        assertEquals("MissingTransactionId", error.code)
    }
}
