package no.nav.delta.email

import com.microsoft.graph.models.AttendeeType
import com.microsoft.graph.models.OnlineMeetingProviderType
import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MasterCalendarEventTest {
    private val base =
        Event(
            id = UUID.randomUUID(),
            title = "Fagdag",
            description = "must never end up on the master",
            startTime = LocalDateTime.of(2026, 10, 1, 9, 0),
            endTime = LocalDateTime.of(2026, 10, 1, 10, 0),
            location = "free text",
            public = true,
            participantLimit = 0,
            signupDeadline = null,
        )

    @Test
    fun `master never sets body, so the Teams join block Exchange injects is never overwritten`() {
        listOf(
            base,
            base.copy(roomEmail = "room1@nav.no", roomName = "Room 1"),
            base.copy(isOnlineMeeting = true),
            base.copy(roomEmail = "room1@nav.no", roomName = "Room 1", isOnlineMeeting = true),
        ).forEach { assertNull(prepareMasterCalendarEvent(it).body) }
    }

    @Test
    fun `room is the only attendee, as a resource, and named in the location`() {
        val master = prepareMasterCalendarEvent(base.copy(roomEmail = "room1@nav.no", roomName = "Room 1"))

        val attendee = master.attendees!!.single()
        assertEquals("room1@nav.no", attendee.emailAddress!!.address)
        assertEquals(AttendeeType.Resource, attendee.type)
        assertEquals("Room 1", master.location!!.displayName)
        assertEquals("Fagdag", master.subject)
        assertNull(master.isOnlineMeeting)
    }

    @Test
    fun `teams-only master has no attendees and requests a Teams meeting`() {
        val master = prepareMasterCalendarEvent(base.copy(isOnlineMeeting = true))

        assertNull(master.attendees)
        assertNull(master.location)
        assertEquals(true, master.isOnlineMeeting)
        assertEquals(OnlineMeetingProviderType.TeamsForBusiness, master.onlineMeetingProvider)
    }
}
