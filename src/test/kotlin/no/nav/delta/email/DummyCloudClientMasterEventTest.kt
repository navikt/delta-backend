package no.nav.delta.email

import arrow.core.*
import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.event.Event
import no.nav.delta.event.RoomBookingStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DummyCloudClientMasterEventTest {
    private val cloudClient = DummyCloudClient()

    private fun event(
        roomEmail: String? = null,
        roomName: String? = null,
        isOnlineMeeting: Boolean = false,
    ) = Event(
        id = UUID.randomUUID(),
        title = "Test event",
        description = "desc",
        startTime = LocalDateTime.now().plusDays(1),
        endTime = LocalDateTime.now().plusDays(1).plusHours(1),
        location = "somewhere",
        public = true,
        participantLimit = 10,
        signupDeadline = null,
        roomEmail = roomEmail,
        roomName = roomName,
        isOnlineMeeting = isOnlineMeeting,
    )

    @Test
    fun `create master event with only a room reports accepted status and no teams details`() {
        val result = cloudClient.createMasterEvent(event(roomEmail = "room1@nav.no", roomName = "Room 1"))
            .getOrNull()

        assertEquals(RoomBookingStatus.ACCEPTED, result?.roomStatus)
        assertNull(result?.teamsJoinUrl)
    }

    @Test
    fun `create master event with only teams reports a join link and no room status`() {
        val result = cloudClient.createMasterEvent(event(isOnlineMeeting = true)).getOrNull()

        assertNull(result?.roomStatus)
        assertEquals(true, result?.teamsJoinUrl?.startsWith("https://teams.microsoft.com"))
    }

    @Test
    fun `create master event with neither room nor teams reports nothing`() {
        val result = cloudClient.createMasterEvent(event()).getOrNull()

        assertNull(result?.roomStatus)
        assertNull(result?.teamsJoinUrl)
    }

    @Test
    fun `update master event keeps the given calendar event id`() {
        val result =
            cloudClient
                .updateMasterEvent("existing-master-id", event(roomEmail = "room1@nav.no"))
                .getOrNull()

        assertEquals("existing-master-id", result?.calendarEventId)
    }

    @Test
    fun `delete master event succeeds`() {
        val result = cloudClient.deleteMasterEvent("some-master-id")

        assertEquals(true, result.isRight())
    }
}
