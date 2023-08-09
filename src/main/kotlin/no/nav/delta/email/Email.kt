package no.nav.delta.email

import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.event.setCalendarEventId
import no.nav.delta.plugins.DatabaseInterface

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
        )
    } else {
        createEvent(
                event = event,
                participant = participant,
            )
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
    if (calendarEventId != null) deleteEvent(calendarEventId = calendarEventId)

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
