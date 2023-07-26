package no.nav.delta.email

import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.plugins.EmailClient

const val footer = """Vennlig hilsen,
Team ΔDelta
"""

fun EmailClient.sendJoinConfirmation(
    event: Event,
    participant: Participant,
    hosts: List<Participant>
) {
    val subject = "Bekreftelse på påmelding til ${event.title}"
    val email =
        """Hei!

Du er nå påmeldt ${event.title}.

Arrangør${if (hosts.size == 1) "" else "er" }: ${hosts.joinToString(", ") { it.email }}
Tidspunkt: ${event.startTime} - ${event.endTime}
Sted: ${event.location}

Detaljert oppdatert informasjon finnes på på https://delta.nav.no/event/${event.id}. Kontakt arrangøren${if (hosts.size == 1) "" else "e" } om du har spørsmål.

$footer
"""

    sendEmail(subject = subject, body = email, toRecipients = listOf(participant.email))
}

fun EmailClient.sendUpdateNotification(
    event: Event,
    participants: List<Participant>,
    hosts: List<Participant>
) {
    val subject = "Oppdatering på ${event.title}"
    val email =
        """Hei!
Arrangementet ${event.title} har blitt oppdatert.

Her er den oppdaterte informasjonen:
Arrangør${if (hosts.size == 1) "" else "er" }: ${hosts.joinToString(", ") { it.email }}
Tidspunkt: ${event.startTime} - ${event.endTime}
Sted: ${event.location}

Detaljert oppdatert informasjon finnes på på https://delta.nav.no/event/${event.id}. Kontakt arrangøren${if (hosts.size == 1) "" else "e" } om du har spørsmål.

$footer
"""

    sendEmail(subject = subject, body = email, bccRecipients = participants.map { it.email })
}
