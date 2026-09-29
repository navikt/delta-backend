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
import com.microsoft.graph.users.item.calendar.getschedule.GetSchedulePostRequestBody
import com.microsoft.graph.models.odataerrors.ODataError
import com.microsoft.kiota.ApiException
import java.lang.RuntimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import no.nav.delta.Environment
import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.event.RoomBookingStatus
import no.nav.delta.room.RoomAvailability
import no.nav.delta.room.RoomInfo
import no.nav.delta.room.RoomList
import no.nav.delta.room.MasterEventResult
import org.slf4j.LoggerFactory

private val graphLogger = LoggerFactory.getLogger("no.nav.delta.email.Graph")

/**
 * Graph SDK exceptions keep the useful details (HTTP status, error code, message, request-id)
 * in structured fields rather than in `message`, so wrap them in a readable one.
 */
internal fun describeGraphError(e: Throwable): String =
    when (e) {
        is ODataError -> {
            val err = e.error
            val requestId = err?.innerError?.requestId
            "status=${e.responseStatusCode} code=${err?.code} message=${err?.message} requestId=$requestId"
        }
        is ApiException -> "status=${e.responseStatusCode} message=${e.message}"
        else -> "${e::class.simpleName}: ${e.message}"
    }

private fun Room.toRoomInfo() =
    RoomInfo(
        displayName = displayName,
        emailAddress = emailAddress,
        capacity = capacity,
        building = building,
        floorLabel = floorLabel,
        isWheelChairAccessible = isWheelChairAccessible,
    )

private fun graphFailure(what: String, e: Exception): Throwable =
    RuntimeException("$what (${describeGraphError(e)})", e)

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

    fun getRoomLists(): Either<Throwable, List<RoomList>>

    fun getRooms(roomListEmail: String): Either<Throwable, List<RoomInfo>>

    /** Every room in the tenant (all pages), for name search. */
    fun getAllRooms(): Either<Throwable, List<RoomInfo>>

    fun getRoomAvailability(
        roomEmails: List<String>,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        availabilityViewInterval: Int = 30,
    ): Either<Throwable, List<RoomAvailability>>

    /**
     * Creates the "master" calendar event that holds the room booking and/or Teams meeting for
     * [event] (see docs/teams-meeting-room-booking-plan.md). The master has no human attendees;
     * only the room, if any, as a resource attendee.
     */
    fun createMasterEvent(event: Event): Either<Throwable, MasterEventResult>

    /**
     * Updates the master event. Implementations must never set or clear the calendar event's
     * text body: Exchange writes the Teams join block into it, and touching that field (even to
     * patch unrelated fields) would remove the join info from the calendar entry.
     */
    fun updateMasterEvent(calendarEventId: String, event: Event): Either<Throwable, MasterEventResult>

    fun deleteMasterEvent(calendarEventId: String): Either<Throwable, Unit>

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
                    ItemBody().apply {
                        contentType = BodyType.Html
                        content = buildInviteBodyHtml(event)
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
            (graphClient.subscriptions().post(subscription)
                ?: throw RuntimeException("Failed to create subscription: null response")).right()
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
            // Master events carry the room as a Resource attendee; participant invites have no
            // resource, so they fall back to the (single) human attendee.
            val status = event?.attendees
                ?.let { attendees -> attendees.firstOrNull { it.type == AttendeeType.Resource } ?: attendees.firstOrNull() }
                ?.status?.response
            status.right()
        } catch (e: Exception) {
            RuntimeException("Failed to get attendee status for event $calendarEventId", e).left()
        }
    }

    override fun getRoomLists(): Either<Throwable, List<RoomList>> {
        return try {
            val lists = mutableListOf<RoomList>()
            var page = graphClient.places().graphRoomList().get()
            while (page != null) {
                page.value?.forEach { lists += RoomList(displayName = it.displayName, emailAddress = it.emailAddress) }
                val next = page.odataNextLink ?: break
                page = graphClient.places().graphRoomList().withUrl(next).get()
            }
            lists.toList().also { graphLogger.info("Graph room lists: ${it.size} returned") }.right()
        } catch (e: Exception) {
            graphFailure("Failed to get room lists", e).left()
        }
    }

    override fun getAllRooms(): Either<Throwable, List<RoomInfo>> {
        return try {
            val rooms = mutableListOf<RoomInfo>()
            var page = graphClient.places().graphRoom().get { it.queryParameters?.top = 999 }
            while (page != null) {
                page.value?.forEach { rooms += it.toRoomInfo() }
                val next = page.odataNextLink ?: break
                page = graphClient.places().graphRoom().withUrl(next).get()
            }
            graphLogger.info("Graph all rooms: ${rooms.size} returned")
            rooms.right()
        } catch (e: Exception) {
            graphFailure("Failed to get all rooms", e).left()
        }
    }

    override fun getRooms(roomListEmail: String): Either<Throwable, List<RoomInfo>> {
        return try {
            val rooms = mutableListOf<RoomInfo>()
            val builder = graphClient.places().byPlaceId(roomListEmail).graphRoomList().rooms()
            var page = builder.get()
            while (page != null) {
                page.value?.forEach { rooms += it.toRoomInfo() }
                val next = page.odataNextLink ?: break
                page = builder.withUrl(next).get()
            }
            rooms.toList().also { graphLogger.info("Graph rooms for list $roomListEmail: ${it.size} returned") }.right()
        } catch (e: Exception) {
            graphFailure("Failed to get rooms for room list $roomListEmail", e).left()
        }
    }

    override fun getRoomAvailability(
        roomEmails: List<String>,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        availabilityViewInterval: Int,
    ): Either<Throwable, List<RoomAvailability>> {
        if (applicationEmailAddress.isBlank()) {
            return RuntimeException("Missing application email address").left()
        }
        if (roomEmails.isEmpty()) return emptyList<RoomAvailability>().right()

        return try {
            val requestBody = GetSchedulePostRequestBody().apply {
                schedules = roomEmails
                this.startTime = startTime.toDateTimeTimeZone()
                this.endTime = endTime.toDateTimeTimeZone()
                this.availabilityViewInterval = availabilityViewInterval
            }
            val response = graphClient
                .users()
                .byUserId(applicationEmailAddress)
                .calendar()
                .getSchedule()
                .post(requestBody)

            (response?.value ?: emptyList()).mapIndexed { index, scheduleInfo ->
                RoomAvailability(
                    emailAddress = roomEmails.getOrElse(index) { scheduleInfo.scheduleId ?: "" },
                    availabilityView = scheduleInfo.availabilityView,
                    error = scheduleInfo.error?.message,
                )
            }.also { result ->
                graphLogger.info(
                    "Graph getSchedule: requested ${roomEmails.size}, returned ${result.size}, " +
                        "per room: " + result.joinToString { "${it.emailAddress}=${it.availabilityView ?: "err:" + it.error}" }
                )
            }.right()
        } catch (e: Exception) {
            graphFailure("Failed to get room availability", e).left()
        }
    }

    /**
     * Logs what Graph actually did with a master event, e.g. whether a Teams meeting was
     * created (needs a Teams license on the Delta mailbox) and the room's response. Never logs
     * the join URL itself: it grants access to the meeting.
     */
    private fun logMasterEventResponse(
        operation: String,
        requested: Event,
        graphEvent: com.microsoft.graph.models.Event?,
    ) {
        val room = graphEvent?.attendees?.firstOrNull { it.type == AttendeeType.Resource }
        val meeting = graphEvent?.onlineMeeting
        val message =
            "Graph master event $operation: id=${graphEvent?.id} " +
                "requested(room=${requested.roomEmail}, teams=${requested.isOnlineMeeting}) " +
                "returned(isOnlineMeeting=${graphEvent?.isOnlineMeeting}, provider=${graphEvent?.onlineMeetingProvider}, " +
                "onlineMeeting=${if (meeting == null) "null" else "present"}, hasJoinUrl=${meeting?.joinUrl != null}, " +
                "hasConferenceId=${meeting?.conferenceId != null}, hasDialIn=${meeting?.tollNumber != null || !meeting?.phones.isNullOrEmpty()}, " +
                "roomAttendee=${room?.emailAddress?.address}, roomResponse=${room?.status?.response})"
        if (requested.isOnlineMeeting && meeting?.joinUrl == null) {
            graphLogger.warn("$message: Teams meeting requested but none was returned (missing Teams license on the Delta mailbox?)")
        } else {
            graphLogger.info(message)
        }
    }

    private fun toMasterEventResult(
        calendarEventId: String,
        graphEvent: com.microsoft.graph.models.Event?,
    ): MasterEventResult {
        val roomStatus = graphEvent?.attendees?.firstOrNull { it.type == AttendeeType.Resource }?.status?.response?.let {
            when (it) {
                ResponseType.Accepted -> RoomBookingStatus.ACCEPTED
                ResponseType.Declined -> RoomBookingStatus.DECLINED
                else -> RoomBookingStatus.PENDING
            }
        }
        val onlineMeeting = graphEvent?.onlineMeeting
        val dialIn = onlineMeeting?.let {
            val number = it.tollNumber ?: it.phones?.firstOrNull()?.number
            when {
                number == null -> null
                it.quickDial != null -> it.quickDial
                else -> number
            }
        }

        return MasterEventResult(
            calendarEventId = calendarEventId,
            roomStatus = roomStatus,
            teamsJoinUrl = onlineMeeting?.joinUrl,
            teamsConferenceId = onlineMeeting?.conferenceId,
            teamsDialIn = dialIn,
        )
    }

    override fun createMasterEvent(event: Event): Either<Throwable, MasterEventResult> {
        if (applicationEmailAddress.isBlank()) {
            return RuntimeException("Missing application email address").left()
        }
        return try {
            val calendarEvent = prepareMasterCalendarEvent(event)
            val created = graphClient
                .users()
                .byUserId(applicationEmailAddress)
                .calendar()
                .events()
                .post(calendarEvent)
            val id = created?.id
                ?: return RuntimeException("Failed to create master event: Graph returned no id").left()
            // The Teams details can lag the create response; re-fetch once before giving up.
            val withMeeting =
                if (event.isOnlineMeeting && created.onlineMeeting?.joinUrl == null) {
                    try {
                        graphClient.users().byUserId(applicationEmailAddress).events().byEventId(id)
                            .get {
                                it.queryParameters?.select =
                                    arrayOf("attendees", "onlineMeeting", "isOnlineMeeting", "onlineMeetingProvider")
                            } ?: created
                    } catch (e: Exception) {
                        graphLogger.warn("Failed to refresh master event $id after create; using create response", e)
                        created
                    }
                } else {
                    created
                }
            logMasterEventResponse("create", event, withMeeting)
            toMasterEventResult(id, withMeeting).right()
        } catch (e: Exception) {
            graphFailure("Failed to create master event", e).left()
        }
    }

    override fun updateMasterEvent(
        calendarEventId: String,
        event: Event,
    ): Either<Throwable, MasterEventResult> {
        if (applicationEmailAddress.isBlank()) {
            return RuntimeException("Missing application email address").left()
        }
        return try {
            val calendarEvent = prepareMasterCalendarEvent(event)
            val updated = graphClient
                .users()
                .byUserId(applicationEmailAddress)
                .events()
                .byEventId(calendarEventId)
                .patch(calendarEvent)

            // Re-fetch to get the room's attendee status and the Teams meeting details, which
            // the PATCH response does not reliably include.
            val refreshed =
                try {
                    graphClient
                        .users()
                        .byUserId(applicationEmailAddress)
                        .events()
                        .byEventId(calendarEventId)
                        .get {
                            it.queryParameters?.select =
                                arrayOf("attendees", "onlineMeeting", "isOnlineMeeting", "onlineMeetingProvider")
                        } ?: updated
                } catch (e: Exception) {
                    graphLogger.warn("Failed to refresh master event $calendarEventId after update; using update response", e)
                    updated
                }

            logMasterEventResponse("update", event, refreshed)
            toMasterEventResult(calendarEventId, refreshed).right()
        } catch (e: Exception) {
            graphFailure("Failed to update master event $calendarEventId", e).left()
        }
    }

    override fun deleteMasterEvent(calendarEventId: String): Either<Throwable, Unit> =
        deleteEvent(calendarEventId)
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
        return null
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

    override fun getRoomLists(): Either<Throwable, List<RoomList>> {
        println("DummyEmailClient: Getting room lists")
        return listOf(
            RoomList(displayName = "Bygg A", emailAddress = "bygg-a@nav.no"),
            RoomList(displayName = "Bygg B", emailAddress = "bygg-b@nav.no"),
        ).right()
    }

    override fun getAllRooms(): Either<Throwable, List<RoomInfo>> =
        listOf("(RV) FYA1 - A347 Kaptein - Videokonf", "(RV) FYA1 - AU01 Auditorium", "(RV) FYA2 - B210 Styrmann")
            .mapIndexed { i, name ->
                RoomInfo(name, "room${i + 1}@nav.no", 6 + i * 4, "FYA", "${i + 2}. etasje", true)
            }.right()

    override fun getRooms(roomListEmail: String): Either<Throwable, List<RoomInfo>> {
        println("DummyEmailClient: Getting rooms for room list '$roomListEmail'")
        return listOf(
            RoomInfo(
                displayName = "Møterom 1",
                emailAddress = "room1@nav.no",
                capacity = 6,
                building = roomListEmail,
                floorLabel = "3. etasje",
                isWheelChairAccessible = true,
            ),
            RoomInfo(
                displayName = "Møterom 2",
                emailAddress = "room2@nav.no",
                capacity = 12,
                building = roomListEmail,
                floorLabel = "3. etasje",
                isWheelChairAccessible = false,
            ),
        ).right()
    }

    override fun getRoomAvailability(
        roomEmails: List<String>,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        availabilityViewInterval: Int,
    ): Either<Throwable, List<RoomAvailability>> {
        println("DummyEmailClient: Getting room availability for $roomEmails from $startTime to $endTime")
        return roomEmails.map { email ->
            RoomAvailability(emailAddress = email, availabilityView = "0", error = null)
        }.right()
    }

    private fun dummyMasterEventResult(calendarEventId: String, event: Event): MasterEventResult =
        MasterEventResult(
            calendarEventId = calendarEventId,
            roomStatus = if (event.roomEmail != null) RoomBookingStatus.ACCEPTED else null,
            teamsJoinUrl = if (event.isOnlineMeeting) "https://teams.microsoft.com/l/meetup-join/dummy" else null,
            teamsConferenceId = if (event.isOnlineMeeting) "123456789" else null,
            teamsDialIn = if (event.isOnlineMeeting) "+47 21 00 00 00,,123456789#" else null,
        )

    override fun createMasterEvent(event: Event): Either<Throwable, MasterEventResult> {
        println("DummyEmailClient: Creating master event for '${event.title}' room=${event.roomEmail} teams=${event.isOnlineMeeting}")
        return dummyMasterEventResult("dummy-master-id", event).right()
    }

    override fun updateMasterEvent(calendarEventId: String, event: Event): Either<Throwable, MasterEventResult> {
        println("DummyEmailClient: Updating master event id='$calendarEventId' for '${event.title}'")
        return dummyMasterEventResult(calendarEventId, event).right()
    }

    override fun deleteMasterEvent(calendarEventId: String): Either<Throwable, Unit> {
        println("DummyEmailClient: Deleting master event id='$calendarEventId'")
        return Unit.right()
    }
}

fun LocalDateTime.toDateTimeTimeZone(): DateTimeTimeZone =
    DateTimeTimeZone().apply {
        timeZone = "Europe/Oslo"
        dateTime = this@toDateTimeTimeZone.toString()
    }

/**
 * Builds the master calendar event for [event]. Deliberately never touches `body`: Exchange
 * writes the Teams join block into it, and setting it here would overwrite that block on update.
 *
 * `attendees`/`location` are only set when there is a room, and `isOnlineMeeting` only when true.
 * That is safe because the routes never remove a room or Teams from an existing event (only a
 * room *change*, which replaces the attendee list) — see docs/teams-meeting-room-booking-plan.md.
 */
internal fun prepareMasterCalendarEvent(event: Event): com.microsoft.graph.models.Event {
    return com.microsoft.graph.models.Event().apply {
        subject = event.title
        start = event.startTime.toDateTimeTimeZone()
        end = event.endTime.toDateTimeTimeZone()

        if (event.roomEmail != null) {
            location = Location().apply { displayName = event.roomName ?: event.roomEmail }
            attendees = listOf(
                Attendee().apply {
                    emailAddress = EmailAddress().apply { address = event.roomEmail }
                    type = AttendeeType.Resource
                }
            )
        }

        if (event.isOnlineMeeting) {
            isOnlineMeeting = true
            onlineMeetingProvider = OnlineMeetingProviderType.TeamsForBusiness
        }
    }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/**
 * Builds the participant invite body: the event description, plus a Delta-rendered room/Teams
 * block when they are set on [event]. Deliberately does not copy the Teams HTML Exchange writes
 * into the *master* event's body (see docs/teams-meeting-room-booking-plan.md) — this is a
 * different calendar event (the participant's own invite), so there is no such HTML to preserve
 * here in the first place.
 */
fun buildInviteBodyHtml(event: Event): String {
    val description = """<p>${event.description.replace("\n", "<br>")}</p>"""

    val room = event.roomName?.let { "<p><strong>Rom:</strong> ${it.escapeHtml()}</p>" } ?: ""

    val teams = event.teamsJoinUrl?.let { joinUrl ->
        buildString {
            append("<p><strong>Bli med i Teams-møtet</strong><br>")
            append("""<a href="$joinUrl">Bli med i møtet</a>""")
            val dialIn = event.teamsDialIn
            if (dialIn != null) {
                append("<br>Ring inn: ${dialIn.escapeHtml()}")
                event.teamsConferenceId?.let { append(" (konferanse-ID: ${it.escapeHtml()})") }
            }
            append("</p>")
        }
    } ?: ""

    return description + room + teams
}
