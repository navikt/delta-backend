package no.nav.delta.email

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.azure.identity.ClientSecretCredentialBuilder
import com.microsoft.graph.core.authentication.AzureIdentityAuthenticationProvider
import com.microsoft.graph.core.content.BatchRequestContentCollection
import com.microsoft.graph.core.requests.GraphClientFactory
import com.microsoft.graph.serviceclient.GraphServiceClient
import com.microsoft.graph.models.*
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody
import java.lang.RuntimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
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

    fun batchUpdateOrCreateEvents(
        event: Event,
        participantsWithCalendarIds: List<Pair<Participant, String?>>,
    ): Map<Participant, Either<Throwable, String?>>

    fun getUserDisplayName(email: String): String?

    fun createSubscription(
        notificationUrl: String,
        resource: String,
        clientState: String,
        expirationDateTime: OffsetDateTime,
    ): Either<Throwable, Subscription>

    fun renewSubscription(
        subscriptionId: String,
        newExpiration: OffsetDateTime,
    ): Either<Throwable, Unit>

    fun deleteSubscription(subscriptionId: String): Either<Throwable, Unit>

    fun getEventAttendeeStatus(calendarEventId: String): Either<Throwable, ResponseType?>

    companion object {
        fun fromEnvironment(env: Environment): CloudClient {
            if (env.isDev || env.isLocal) {
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
    private val graphClient: GraphServiceClient

    init {
        val authProvider = AzureIdentityAuthenticationProvider(
            ClientSecretCredentialBuilder()
                .clientId(azureAppClientId)
                .clientSecret(azureAppClientSecret)
                .tenantId(azureAppTenantId)
                .build(),
            arrayOf<String>(),
            "https://graph.microsoft.com/.default"
        )

        this.graphClient = GraphServiceClient(
            authProvider,
            GraphClientFactory.create().build()
        )
    }

    private fun emailAsRecipient(email: String) =
        Recipient().apply { emailAddress = EmailAddress().apply { address = email } }

    private fun emailAsAttendee(email: String) =
        Attendee().apply { emailAddress = EmailAddress().apply { address = email } }

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

        val message = Message()
        message.toRecipients = toRecipients.map(this::emailAsRecipient)
        message.ccRecipients = ccRecipients.map(this::emailAsRecipient)
        message.bccRecipients = bccRecipients.map(this::emailAsRecipient)

        message.subject = subject
        message.body =
            ItemBody().apply {
                contentType = BodyType.Text
                content = body
            }

        graphClient
            .users()
            .byUserId(applicationEmailAddress)
            .sendMail()
            .post(SendMailPostRequestBody().apply {
                this.message = message
                saveToSentItems = false
            })
    }

    private fun prepareCalendarEvent(
        event: Event,
        participant: Participant,
    ): Either<Throwable, com.microsoft.graph.models.Event> {
        if (applicationEmailAddress.isBlank()) {
            return RuntimeException("Missing application email address").left()
        }

        val calendarEvent =
            Event().apply {
                subject = event.title
                body =
                    event.description.let {
                        ItemBody().apply {
                            contentType = BodyType.Html
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
                    .users()
                    .byUserId(applicationEmailAddress)
                    .calendar()
                    .events()
                    .post(calendarEvent)
                    ?.id
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
            .flatMap { calendarEvent ->
                try {
                    graphClient
                        .users()
                        .byUserId(applicationEmailAddress)
                        .events()
                        .byEventId(calendarEventId)
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
        return try {
            graphClient
                .users()
                .byUserId(applicationEmailAddress)
                .events()
                .byEventId(calendarEventId)
                .delete()
            Unit.right()
        } catch (e: Exception) {
            RuntimeException("Failed to delete event", e).left()
        }
    }

    override fun batchUpdateOrCreateEvents(
        event: Event,
        participantsWithCalendarIds: List<Pair<Participant, String?>>,
    ): Map<Participant, Either<Throwable, String?>> {
        if (applicationEmailAddress.isBlank()) {
            return participantsWithCalendarIds.associate { (p, _) ->
                p to RuntimeException("Missing application email address").left()
            }
        }
        if (participantsWithCalendarIds.isEmpty()) return emptyMap()

        val results = mutableMapOf<Participant, Either<Throwable, String?>>()
        // Maps batch request step ID -> (participant, isCreate)
        val requestIdToInfo = mutableMapOf<String, Pair<Participant, Boolean>>()
        val batchContent = BatchRequestContentCollection(graphClient)

        for ((participant, calendarEventId) in participantsWithCalendarIds) {
            prepareCalendarEvent(event, participant).fold(
                { e -> results[participant] = e.left() },
                { calendarEvent ->
                    val requestInfo = if (calendarEventId != null) {
                        graphClient.users().byUserId(applicationEmailAddress)
                            .events().byEventId(calendarEventId)
                            .toPatchRequestInformation(calendarEvent)
                    } else {
                        graphClient.users().byUserId(applicationEmailAddress)
                            .calendar().events()
                            .toPostRequestInformation(calendarEvent)
                    }
                    val requestId = batchContent.addBatchRequestStep(requestInfo)
                    requestIdToInfo[requestId] = Pair(participant, calendarEventId == null)
                }
            )
        }

        if (requestIdToInfo.isEmpty()) return results

        try {
            val batchResponse = graphClient.batchRequestBuilder.post(batchContent, null)
            val statusCodes = batchResponse.getResponsesStatusCodes()

            for ((requestId, info) in requestIdToInfo) {
                val (participant, isCreate) = info
                val status = statusCodes[requestId]
                when {
                    status == null ->
                        results[participant] = RuntimeException("No batch response for request").left()
                    status in 200..299 -> {
                        if (isCreate) {
                            try {
                                val createdEvent = batchResponse.getResponseById(requestId, com.microsoft.graph.models.Event::createFromDiscriminatorValue)
                                results[participant] = (createdEvent?.id
                                    ?: throw RuntimeException("No event ID in batch create response")).right<String>()
                            } catch (e: Exception) {
                                results[participant] = RuntimeException("Failed to parse batch create response", e).left()
                            }
                        } else {
                            results[participant] = (null as String?).right()
                        }
                    }
                    else ->
                        results[participant] = RuntimeException("Batch request failed with status $status").left()
                }
            }
        } catch (e: Exception) {
            requestIdToInfo.values.forEach { (participant, _) ->
                if (!results.containsKey(participant)) {
                    results[participant] = RuntimeException("Batch request failed", e).left()
                }
            }
        }

        return results
    }

    override fun getUserDisplayName(email: String): String? {
        return try {
            graphClient.users().byUserId(email).get()?.displayName
        } catch (e: Exception) {
            null
        }
    }

    override fun createSubscription(
        notificationUrl: String,
        resource: String,
        clientState: String,
        expirationDateTime: OffsetDateTime,
    ): Either<Throwable, Subscription> {
        return try {
            val subscription = Subscription().apply {
                this.notificationUrl = notificationUrl
                this.resource = resource
                this.clientState = clientState
                this.expirationDateTime = expirationDateTime
                this.changeType = "updated"
            }
            graphClient.subscriptions().post(subscription).right()
        } catch (e: Exception) {
            RuntimeException("Failed to create subscription", e).left()
        }
    }

    override fun renewSubscription(
        subscriptionId: String,
        newExpiration: OffsetDateTime,
    ): Either<Throwable, Unit> {
        return try {
            val patch = Subscription().apply { expirationDateTime = newExpiration }
            graphClient.subscriptions().bySubscriptionId(subscriptionId).patch(patch)
            Unit.right()
        } catch (e: Exception) {
            RuntimeException("Failed to renew subscription $subscriptionId", e).left()
        }
    }

    override fun deleteSubscription(subscriptionId: String): Either<Throwable, Unit> {
        return try {
            graphClient.subscriptions().bySubscriptionId(subscriptionId).delete()
            Unit.right()
        } catch (e: Exception) {
            RuntimeException("Failed to delete subscription $subscriptionId", e).left()
        }
    }

    override fun getEventAttendeeStatus(calendarEventId: String): Either<Throwable, ResponseType?> {
        return try {
            val event = graphClient
                .users()
                .byUserId(applicationEmailAddress)
                .events()
                .byEventId(calendarEventId)
                .get { it.queryParameters?.select = arrayOf("attendees") }
            val status = event?.attendees?.firstOrNull()?.status?.response
            status.right()
        } catch (e: Exception) {
            RuntimeException("Failed to get attendee status for event $calendarEventId", e).left()
        }
    }
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

    override fun batchUpdateOrCreateEvents(
        event: Event,
        participantsWithCalendarIds: List<Pair<Participant, String?>>,
    ): Map<Participant, Either<Throwable, String?>> {
        return participantsWithCalendarIds.associate { (participant, calendarEventId) ->
            if (calendarEventId != null) {
                println("DummyEmailClient: Updating event (batch): id='$calendarEventId' subject='${event.title}' to=$participant")
                participant to (null as String?).right()
            } else {
                println("DummyEmailClient: Creating event (batch): subject='${event.title}' to=$participant")
                participant to "dummy-id".right()
            }
        }
    }

    override fun getUserDisplayName(email: String): String? {
        println("DummyEmailClient: Looking up display name for $email")
        return email
    }

    override fun createSubscription(
        notificationUrl: String,
        resource: String,
        clientState: String,
        expirationDateTime: OffsetDateTime,
    ): Either<Throwable, Subscription> {
        println("DummyEmailClient: Creating subscription for resource='$resource' notificationUrl='$notificationUrl'")
        return Subscription().apply {
            id = "dummy-subscription-id"
            this.resource = resource
            this.expirationDateTime = expirationDateTime
            this.clientState = clientState
        }.right()
    }

    override fun renewSubscription(
        subscriptionId: String,
        newExpiration: OffsetDateTime,
    ): Either<Throwable, Unit> {
        println("DummyEmailClient: Renewing subscription id='$subscriptionId' until=$newExpiration")
        return Unit.right()
    }

    override fun deleteSubscription(subscriptionId: String): Either<Throwable, Unit> {
        println("DummyEmailClient: Deleting subscription id='$subscriptionId'")
        return Unit.right()
    }

    override fun getEventAttendeeStatus(calendarEventId: String): Either<Throwable, ResponseType?> {
        println("DummyEmailClient: Getting attendee status for calendarEventId='$calendarEventId'")
        return null.right()
    }
}

fun LocalDateTime.toDateTimeTimeZone(): DateTimeTimeZone =
    DateTimeTimeZone().apply {
        timeZone = "Europe/Oslo"
        dateTime = this@toDateTimeTimeZone.toString()
    }