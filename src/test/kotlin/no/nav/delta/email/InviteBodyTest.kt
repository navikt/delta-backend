package no.nav.delta.email

import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.event.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InviteBodyTest {
    private fun baseEvent(
        roomName: String? = null,
        teamsJoinUrl: String? = null,
        teamsConferenceId: String? = null,
        teamsDialIn: String? = null,
    ) = Event(
        id = UUID.randomUUID(),
        title = "Test",
        description = "line one\nline two",
        startTime = LocalDateTime.now().plusDays(1),
        endTime = LocalDateTime.now().plusDays(1).plusHours(1),
        location = "somewhere",
        public = true,
        participantLimit = 10,
        signupDeadline = null,
        roomName = roomName,
        teamsJoinUrl = teamsJoinUrl,
        teamsConferenceId = teamsConferenceId,
        teamsDialIn = teamsDialIn,
    )

    @Test
    fun `body contains only the description when there is no room or teams meeting`() {
        val body = buildInviteBodyHtml(baseEvent())

        assertTrue(body.contains("line one<br>line two"))
        assertFalse(body.contains("Rom"))
        assertFalse(body.contains("Teams"))
    }

    @Test
    fun `body includes the room name when a room is booked`() {
        val body = buildInviteBodyHtml(baseEvent(roomName = "Møterom 1"))

        assertTrue(body.contains("Møterom 1"))
    }

    @Test
    fun `body includes a teams join link when there is a teams meeting`() {
        val body =
            buildInviteBodyHtml(
                baseEvent(teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc")
            )

        assertTrue(body.contains("""href="https://teams.microsoft.com/l/meetup-join/abc""""))
        assertTrue(body.contains("Bli med i Teams-møtet"))
    }

    @Test
    fun `body includes dial-in and conference id when present`() {
        val body =
            buildInviteBodyHtml(
                baseEvent(
                    teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc",
                    teamsDialIn = "+47 21 00 00 00,,123456789#",
                    teamsConferenceId = "123456789",
                )
            )

        assertTrue(body.contains("+47 21 00 00 00"))
        assertTrue(body.contains("123456789"))
    }

    @Test
    fun `body escapes html in the room name`() {
        val body = buildInviteBodyHtml(baseEvent(roomName = "<script>alert(1)</script>"))

        assertFalse(body.contains("<script>"))
        assertTrue(body.contains("&lt;script&gt;"))
    }
}
