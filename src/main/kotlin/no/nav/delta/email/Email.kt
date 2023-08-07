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
    participants: List<Participant>,
    hosts: List<Participant>,
    database: DatabaseInterface,
    calendarEventId: String?,
) {
    if (calendarEventId != null) {
        updateEvent(
            event = event,
            participants = participants,
            hosts = hosts,
            calendarEventId = calendarEventId,
        )
    } else {
        createEvent(
                event = event,
                participants = participants,
                hosts = hosts,
            )
            .map { calendarEventId ->
                database.setCalendarEventId(event.id.toString(), calendarEventId)
            }
    }
}

fun CloudClient.sendCancellationNotification(
    calendarEventId: String,
    event: Event,
    participants: List<Participant>,
    hosts: List<Participant>
) {
    deleteEvent(calendarEventId = calendarEventId)

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
        bccRecipients =
            listOf(participants, hosts).flatMap { it.map { participant -> participant.email } })
}
