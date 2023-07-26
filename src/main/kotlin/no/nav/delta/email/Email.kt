package no.nav.delta.email

import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.plugins.EmailClient

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

Hilsen,
Team ΔDelta
"""

    sendEmail(subject = subject, body = email, toRecipients = listOf(participant.email))
}
