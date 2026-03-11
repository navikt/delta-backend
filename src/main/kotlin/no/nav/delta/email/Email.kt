package no.nav.delta.email

import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.event.setCalendarEventId
import no.nav.delta.plugins.DatabaseInterface
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("no.nav.delta.email.Email")

const val footer = """Vennlig hilsen,
Team ΔDelta
"""

fun CloudClient.sendUpdateOrCreationNotification(
    event: Event,
    database: DatabaseInterface,
    participant: Participant,
    calendarEventId: String?,
) {
    if (calendarEventId != null) {
        updateEvent(
            event = event,
            participant = participant,
            calendarEventId = calendarEventId,
        ).onLeft { e ->
            log.error("Failed to update calendar event $calendarEventId for ${participant.email}: ${e.message}", e)
        }
    } else {
        createEvent(
                event = event,
                participant = participant,
            )
            .onLeft { e ->
                log.error("Failed to create calendar event for ${participant.email}: ${e.message}", e)
            }
            .map { calendarEventId ->
                database.setCalendarEventId(event.id.toString(), participant.email, calendarEventId)
            }
    }
}

fun CloudClient.sendCancellationNotification(
    calendarEventId: String?,
    event: Event,
    participant: Participant,
) {
    if (calendarEventId != null) {
        deleteEvent(calendarEventId = calendarEventId).onLeft { e ->
            log.error("Failed to delete calendar event $calendarEventId for ${participant.email}: ${e.message}", e)
        }
    }

    val subject = "Avlysning av ${event.title}"
    val email =
        """Hei!

Arrangementet ${event.title} har blitt avlyst.

Vær på utkikk etter nye arrangementer på https://delta.nav.no.

$footer
"""

    sendEmail(
        subject = subject,
        body = email,
        toRecipients = listOf(participant.email),
    )
}
