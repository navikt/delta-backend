package no.nav.delta.email

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.microsoft.aad.msal4j.ClientCredentialFactory
import com.microsoft.aad.msal4j.ClientCredentialParameters
import com.microsoft.aad.msal4j.ConfidentialClientApplication
import com.microsoft.graph.models.*
import com.microsoft.graph.requests.GraphServiceClient
import java.lang.RuntimeException
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import no.nav.delta.Environment
import no.nav.delta.event.Event
import no.nav.delta.event.Participant

interface CloudClient {
    fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String> = emptyList(),
        ccRecipients: List<String> = emptyList(),
        bccRecipients: List<String> = emptyList()
    )

    fun createEvent(event: Event, participant: Participant): Either<Throwable, String>

    fun updateEvent(
        calendarEventId: String,
        event: Event,
        participant: Participant
    ): Either<Throwable, Unit>

    fun deleteEvent(calendarEventId: String): Either<Throwable, Unit>

    companion object {
        fun fromEnvironment(env: Environment): CloudClient {
            if (env.isDev) {
                return DummyCloudClient()
            }
            val email = env.deltaEmailAddress
            val azureAppClientId = env.azureAppClientId
            val azureAppTenantId = env.azureAppTenantId
            val azureAppClientSecret = env.azureAppClientSecret

            return AzureCloudClient(
                applicationEmailAddress = email,
                azureAppClientId = azureAppClientId,
                azureAppTenantId = azureAppTenantId,
                azureAppClientSecret = azureAppClientSecret)
        }
    }
}

class AzureCloudClient(
    private val applicationEmailAddress: String,
    azureAppClientId: String,
    azureAppTenantId: String,
    azureAppClientSecret: String
) : CloudClient {
    private val tokenClient: ConfidentialClientApplication
    private val scopes = setOf("https://graph.microsoft.com/.default")

    private var azureToken: AzureToken? = null
    private val graphClient: GraphServiceClient<okhttp3.Request>

    init {
        val authorityUrl = "https://login.microsoftonline.com/${azureAppTenantId}"
        val clientSecret = ClientCredentialFactory.createFromSecret(azureAppClientSecret)

        this.tokenClient =
            ConfidentialClientApplication.builder(azureAppClientId, clientSecret)
                .authority(authorityUrl)
                .build()
        this.graphClient =
            GraphServiceClient.builder()
                .authenticationProvider {
                    CompletableFuture.completedFuture(this.azureToken?.accessToken)
                }
                .buildClient()
    }

    private fun emailAsRecipient(email: String) =
        Recipient().apply { emailAddress = EmailAddress().apply { address = email } }

    private fun emailAsAttendee(email: String) =
        Attendee().apply { emailAddress = EmailAddress().apply { address = email } }

    private fun refreshTokenIfNeeded() {
        val currentToken = azureToken
        if (currentToken != null && currentToken.isActive(Date())) {
            return
        }
        refreshToken()
    }

    private fun refreshToken() {
        val authResult =
            tokenClient.acquireToken(ClientCredentialParameters.builder(scopes).build()).get()
        this.azureToken = AzureToken(authResult.accessToken(), authResult.expiresOnDate())
    }

    override fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String>,
        ccRecipients: List<String>,
        bccRecipients: List<String>
    ) {
        if (applicationEmailAddress.isBlank() ||
            (toRecipients.isEmpty() && ccRecipients.isEmpty() && bccRecipients.isEmpty())) {
            return
        }
        refreshTokenIfNeeded()

        val message = Message()
        message.toRecipients = toRecipients.map(this::emailAsRecipient)
        message.ccRecipients = ccRecipients.map(this::emailAsRecipient)
        message.bccRecipients = bccRecipients.map(this::emailAsRecipient)

        message.subject = subject
        message.body =
            ItemBody().apply {
                contentType = BodyType.TEXT
                content = body
            }

        graphClient
            .users(applicationEmailAddress)
            .sendMail(UserSendMailParameterSet.newBuilder().withMessage(message).build())
            .buildRequest()
            .post()
    }

    private fun prepareCalendarEvent(
        event: Event,
        participant: Participant,
    ): Either<Throwable, com.microsoft.graph.models.Event> {
        if (applicationEmailAddress.isBlank()) {
            return RuntimeException("Missing application email address").left()
        }
        refreshTokenIfNeeded()

        val calendarEvent =
            Event().apply {
                subject = event.title
                body =
                    event.description.let {
                        ItemBody().apply {
                            contentType = BodyType.HTML
                            content =
                                """<p>${event.description.replace("\n", "<br>")}</p>

<p><strong>Merk:</strong> Hvis du ikke kan delta, må du melde deg av via <a href="https://delta.nav.no/event/${event.id}/">arrangementsiden i Delta</a>. Det er ikke nok å avvise invitasjonen i Outlook.</p>
"""
                        }
                    }
                start = event.startTime.toDateTimeTimeZone()
                end = event.endTime.toDateTimeTimeZone()
                location = Location().apply { displayName = event.location }
                attendees = listOf(emailAsAttendee(participant.email))
            }

        return calendarEvent.right()
    }

    override fun createEvent(
        event: Event,
        participant: Participant,
    ): Either<Throwable, String> {
        return prepareCalendarEvent(event, participant).flatMap { calendarEvent ->
            try {
                graphClient
                    .users(applicationEmailAddress)
                    .calendar()
                    .events()
                    .buildRequest()
                    .post(calendarEvent)
                    .id
                    ?.right()
                    ?: RuntimeException("Failed to create event").left()
            } catch (e: Exception) {
                RuntimeException("Failed to create event", e).left()
            }
        }
    }

    override fun updateEvent(
        calendarEventId: String,
        event: Event,
        participant: Participant
    ): Either<Throwable, Unit> {
        return prepareCalendarEvent(event, participant)
            .map { calendarEvent ->
                calendarEvent.id = calendarEventId
                calendarEvent
            }
            .flatMap { calendarEvent ->
                try {
                    graphClient
                        .users(applicationEmailAddress)
                        .calendar()
                        .events(calendarEventId)
                        .buildRequest()
                        .patch(calendarEvent)
                    Unit.right()
                } catch (e: Exception) {
                    RuntimeException("Failed to update event", e).left()
                }
            }
    }

    override fun deleteEvent(calendarEventId: String): Either<Throwable, Unit> {
        if (applicationEmailAddress.isBlank()) {
            return RuntimeException("Missing application email address").left()
        }
        refreshTokenIfNeeded()

        graphClient
            .users(applicationEmailAddress)
            .calendar()
            .events(calendarEventId)
            .buildRequest()
            .delete()

        return Unit.right()
    }
}

private data class AzureToken(val accessToken: String, val expiresOnDate: Date?) {
    fun isActive(currentDate: Date) = expiresOnDate == null || currentDate.before(expiresOnDate)
}

class DummyCloudClient : CloudClient {
    override fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String>,
        ccRecipients: List<String>,
        bccRecipients: List<String>
    ) {
        println(
            "DummyEmailClient: Sending e-mail: subject='$subject' to=$toRecipients, cc=$ccRecipients, bcc=$bccRecipients")
    }

    override fun createEvent(event: Event, participant: Participant): Either<Throwable, String> {
        println("DummyEmailClient: Creating event: subject='${event.title}' to=$participant")
        return "dummy-id".right()
    }

    override fun updateEvent(
        calendarEventId: String,
        event: Event,
        participant: Participant
    ): Either<Throwable, Unit> {
        println(
            "DummyEmailClient: Updating event: id='$calendarEventId' subject='${event.title}' to=$participant")
        return Unit.right()
    }

    override fun deleteEvent(calendarEventId: String): Either<Throwable, Unit> {
        println("DummyEmailClient: Deleting event: id='$calendarEventId'")
        return Unit.right()
    }
}

fun LocalDateTime.toDateTimeTimeZone(): DateTimeTimeZone =
    DateTimeTimeZone().apply {
        timeZone = "Europe/Oslo"
        dateTime = this@toDateTimeTimeZone.toString()
    }
