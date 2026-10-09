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
import com.microsoft.graph.users.item.events.item.cancel.CancelPostRequestBody
import com.microsoft.graph.models.odataerrors.ODataError
import com.microsoft.kiota.ApiException
import java.lang.RuntimeException
import java.net.URI
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

/**
 * The calendar event no longer exists in Graph. Expected when a webhook notification races with
 * our own deletion (e.g. deleting an event or unregistering a participant), so callers should
 * treat it as a no-op rather than an error.
 */
class CalendarEventNotFoundException(calendarEventId: String, cause: Throwable? = null) :
    RuntimeException("Calendar event $calendarEventId no longer exists", cause)

private fun graphFailure(what: String, e: Exception): Throwable =
    RuntimeException("$what (${describeGraphError(e)})", e)

private const val PLACES_PAGE_SIZE = 999
private const val PLACES_MAX_PAGES = 50

/**
 * Graph's `/places` endpoints page with `$top`/`$skip` and don't reliably return
 * `@odata.nextLink`, so a nextLink-only loop silently stops after the first page. Keeps
 * fetching until a page is empty or yields no new items (in case `$skip` is ignored).
 */
internal fun <T> fetchAllPlaces(
    key: (T) -> String?,
    fetchPage: (top: Int, skip: Int) -> List<T>?,
): List<T> {
    val items = LinkedHashMap<String, T>()
    var skip = 0
    repeat(PLACES_MAX_PAGES) {
        val page = fetchPage(PLACES_PAGE_SIZE, skip).orEmpty()
        if (page.isEmpty()) return items.values.toList()
        val before = items.size
        page.forEachIndexed { i, item -> items.putIfAbsent(key(item) ?: "#${skip + i}", item) }
        if (items.size == before) return items.values.toList()
        skip += page.size
    }
    return items.values.toList()
}

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

    /**
     * A returned Graph ID proves creation, not completed synchronization. When Teams was requested
     * callers must persist that ID and retry until native teamsJoinUrl is present before marking synced.
     */
    fun createSharedEvent(
        event: Event,
        attendees: List<Participant>,
        transactionId: String,
    ): Either<Throwable, MasterEventResult> = UnsupportedOperationException("Shared events unsupported").left()

    fun getSharedEvent(calendarEventId: String): Either<Throwable, SharedCalendarSnapshot> =
        UnsupportedOperationException("Shared events unsupported").left()

    fun getSharedEventForSync(
        calendarEventId: String,
        knownAttendeeEmails: Set<String>,
    ): Either<Throwable, SharedCalendarSnapshot> = getSharedEvent(calendarEventId)

    fun getSharedEventForCancellation(calendarEventId: String): Either<Throwable, SharedCalendarSnapshot> =
        getSharedEvent(calendarEventId)

    /**
     * Attendees-only PATCH preserving supplied Graph responses. An opaque [changeKey] is not
     * a precondition; only a quoted ETag (from snapshot.etag) is eligible for If-Match.
     * Graph event If-Match behavior must be validated before relying on it for concurrency.
     */
    fun updateSharedAttendees(
        calendarEventId: String,
        attendees: List<SharedCalendarAttendee>,
        changeKey: String? = null,
    ): Either<Throwable, Unit> = UnsupportedOperationException("Shared events unsupported").left()

    fun updateSharedAttendees(
        calendarEventId: String,
        attendees: List<SharedCalendarAttendee>,
        changeKey: String?,
        existingAttendeeEmails: Set<String>,
    ): Either<Throwable, Unit> = updateSharedAttendees(calendarEventId, attendees, changeKey)

    fun updateSharedDetails(calendarEventId: String, event: Event): Either<Throwable, MasterEventResult> =
        UnsupportedOperationException("Shared events unsupported").left()

    fun cancelSharedEvent(calendarEventId: String): Either<Throwable, Unit> =
        UnsupportedOperationException("Shared events unsupported").left()

    /** Requires application permission User.Read.All; groups are deliberately excluded. */
    fun searchPeople(query: String): Either<Throwable, List<DirectoryPerson>> =
        UnsupportedOperationException("Directory search unsupported").left()

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

class AzureCloudClient internal constructor(
    private val applicationEmailAddress: String,
    private val graphClient: GraphServiceClient,
) : CloudClient {
    constructor(
        applicationEmailAddress: String,
        azureAppClientId: String,
        azureAppTenantId: String,
        azureAppClientSecret: String,
    ) : this(
        applicationEmailAddress,
        GraphServiceClient(
            AzureIdentityAuthenticationProvider(
                ClientSecretCredentialBuilder()
                    .clientId(azureAppClientId)
                    .clientSecret(azureAppClientSecret)
                    .tenantId(azureAppTenantId)
                    .build(),
                arrayOf<String>(),
                "https://graph.microsoft.com/.default",
            ),
            GraphClientFactory.create()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        ),
    )

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
        } catch (e: ApiException) {
            if (e.responseStatusCode == 404) {
                CalendarEventNotFoundException(calendarEventId, e).left()
            } else {
                graphFailure("Failed to get attendee status for event $calendarEventId", e).left()
            }
        } catch (e: Exception) {
            RuntimeException("Failed to get attendee status for event $calendarEventId", e).left()
        }
    }

    override fun getRoomLists(): Either<Throwable, List<RoomList>> {
        return try {
            fetchAllPlaces({ it.emailAddress }) { top, skip ->
                graphClient.places().graphRoomList().get {
                    it.queryParameters?.top = top
                    it.queryParameters?.skip = skip
                }?.value?.map { RoomList(displayName = it.displayName, emailAddress = it.emailAddress) }
            }.also { graphLogger.info("Graph room lists: ${it.size} returned") }.right()
        } catch (e: Exception) {
            graphFailure("Failed to get room lists", e).left()
        }
    }

    override fun getAllRooms(): Either<Throwable, List<RoomInfo>> {
        return try {
            fetchAllPlaces({ it.emailAddress }) { top, skip ->
                graphClient.places().graphRoom().get {
                    it.queryParameters?.top = top
                    it.queryParameters?.skip = skip
                }?.value?.map { it.toRoomInfo() }
            }.also { graphLogger.info("Graph all rooms: ${it.size} returned") }.right()
        } catch (e: Exception) {
            graphFailure("Failed to get all rooms", e).left()
        }
    }

    override fun getRooms(roomListEmail: String): Either<Throwable, List<RoomInfo>> {
        return try {
            val builder = graphClient.places().byPlaceId(roomListEmail).graphRoomList().rooms()
            fetchAllPlaces({ it.emailAddress }) { top, skip ->
                builder.get {
                    it.queryParameters?.top = top
                    it.queryParameters?.skip = skip
                }?.value?.map { it.toRoomInfo() }
            }.also { graphLogger.info("Graph rooms for list $roomListEmail: ${it.size} returned") }.right()
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

    private fun <T> sharedRequest(block: () -> T): Either<Throwable, T> {
        if (applicationEmailAddress.isBlank()) {
            return SharedGraphException(null, "MissingMailbox", null).left()
        }
        return try {
            block().right()
        } catch (e: SharedGraphException) {
            e.left()
        } catch (e: Exception) {
            val api = e as? ApiException
            val retryAfter = api?.responseHeaders?.entries
                ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 }
            SharedGraphException(
                api?.responseStatusCode,
                (e as? ODataError)?.error?.code,
                retryAfter,
                e,
            ).left()
        }
    }

    private fun toSharedEventResult(
        calendarEventId: String,
        graphEvent: com.microsoft.graph.models.Event,
    ): MasterEventResult {
        val resource = graphEvent.attendees?.firstOrNull { it.type == AttendeeType.Resource }
        return toMasterEventResult(calendarEventId, graphEvent).copy(
            roomStatus = resource?.status?.response.toRoomBookingStatus(resource != null),
        )
    }

    override fun createSharedEvent(
        event: Event,
        attendees: List<Participant>,
        transactionId: String,
    ): Either<Throwable, MasterEventResult> = sharedRequest {
        if (transactionId.isBlank()) throw SharedGraphException(null, "MissingTransactionId", null)
        requireIndividualAttendees(attendees.map { it.email })
        val payload = prepareMasterCalendarEvent(event).apply {
            this.attendees = sharedAttendees(event, attendees).map { it.toGraphAttendee() }
            responseRequested = true
            this.transactionId = transactionId
            location = Location().apply { displayName = event.roomName ?: event.location }
            body = ItemBody().apply {
                contentType = BodyType.Html
                content = sharedDescriptionHtml(event)
            }
        }
        val created = graphClient.users().byUserId(applicationEmailAddress).calendar().events().post(payload)
            ?: throw SharedGraphException(null, "InvalidGraphResponse", null)
        val id = created.id ?: throw SharedGraphException(null, "InvalidGraphResponse", null)
        val refreshed = if (event.isOnlineMeeting && created.onlineMeeting?.joinUrl == null) {
            graphClient.users().byUserId(applicationEmailAddress).events().byEventId(id).get()
                ?: throw SharedGraphException(null, "InvalidGraphResponse", null)
        } else created
        toSharedEventResult(id, refreshed)
    }

    override fun updateSharedAttendees(
        calendarEventId: String,
        attendees: List<SharedCalendarAttendee>,
        changeKey: String?,
    ): Either<Throwable, Unit> = updateSharedAttendees(
        calendarEventId, attendees, changeKey, emptySet(),
    )

    override fun updateSharedAttendees(
        calendarEventId: String,
        attendees: List<SharedCalendarAttendee>,
        changeKey: String?,
        existingAttendeeEmails: Set<String>,
    ): Either<Throwable, Unit> = sharedRequest {
        requireIndividualAttendees(
            attendees.filterNot { it.isResource }.map { it.email },
            existingAttendeeEmails,
        )
        val payload = com.microsoft.graph.models.Event().apply {
            // Remove the SDK's default discriminator without marking it as a null PATCH field.
            backingStore.clear()
            this.attendees = attendees.map { it.toGraphAttendee() }
        }
        graphClient.users().byUserId(applicationEmailAddress).events().byEventId(calendarEventId)
            .patch(payload) { config ->
                // A changeKey is not an ETag. Only pass a genuine quoted ETag supplied by the caller.
                // Graph event If-Match support still requires tenant validation; this is not a lock.
                changeKey?.takeIf(::isQuotedEtag)?.let { config.headers.add("If-Match", it) }
            }
        Unit
    }

    private fun readSharedGraphEvent(calendarEventId: String): com.microsoft.graph.models.Event =
        graphClient.users().byUserId(applicationEmailAddress).events().byEventId(calendarEventId).get {
            it.queryParameters?.select = arrayOf(
                "id", "attendees", "body", "changeKey", "onlineMeeting", "isOnlineMeeting", "isCancelled",
            )
        } ?: throw SharedGraphException(null, "InvalidGraphResponse", null)

    override fun getSharedEvent(calendarEventId: String): Either<Throwable, SharedCalendarSnapshot> =
        sharedRequest { readSharedSnapshot(calendarEventId) { true } }

    override fun getSharedEventForSync(
        calendarEventId: String,
        knownAttendeeEmails: Set<String>,
    ): Either<Throwable, SharedCalendarSnapshot> {
        val known = knownAttendeeEmails.mapTo(mutableSetOf()) { it.lowercase() }
        return sharedRequest { readSharedSnapshot(calendarEventId) { email -> email.lowercase() !in known } }
    }

    override fun getSharedEventForCancellation(
        calendarEventId: String,
    ): Either<Throwable, SharedCalendarSnapshot> =
        sharedRequest { readSharedSnapshot(calendarEventId) { false } }

    private fun readSharedSnapshot(
        calendarEventId: String,
        shouldClassify: (String) -> Boolean,
    ): SharedCalendarSnapshot {
        val graphEvent = readSharedGraphEvent(calendarEventId)
        val result = toSharedEventResult(calendarEventId, graphEvent)
        val classifications = mutableMapOf<String, Boolean>()
        val attendees = graphEvent.attendees.orEmpty()
        if (attendees.size > SHARED_MAX_ATTENDEES) {
            throw SharedGraphException(null, "TooManyGraphAttendees", null)
        }
        return SharedCalendarSnapshot(
            attendees = attendees.map { attendee ->
                val email = attendee.emailAddress?.address
                    ?: throw SharedGraphException(null, "InvalidGraphResponse", null)
                val isResource = attendee.type == AttendeeType.Resource
                SharedCalendarAttendee(
                    email = email,
                    name = attendee.emailAddress?.name ?: attendee.emailAddress?.address.orEmpty(),
                    response = attendee.status?.response,
                    responseTime = attendee.status?.time,
                    isResource = isResource,
                    isIndividual = isResource || !shouldClassify(email) ||
                        classifications.getOrPut(email.lowercase()) { isIndividuallyAddressed(email) },
                )
            },
            body = graphEvent.body?.content,
            changeKey = graphEvent.changeKey,
            roomStatus = result.roomStatus,
            teamsJoinUrl = result.teamsJoinUrl,
            teamsConferenceId = result.teamsConferenceId,
            teamsDialIn = result.teamsDialIn,
            isCancelled = graphEvent.isCancelled == true,
            etag = (graphEvent.additionalData["@odata.etag"] as? String)?.takeIf(::isQuotedEtag),
        )
    }

    /** GroupMember.Read.All authorizes the directory group lookup used to detect list aliases. */
    private fun isIndividuallyAddressed(email: String): Boolean {
        val groups = graphClient.groups().get {
            it.queryParameters?.select = arrayOf("id")
            it.queryParameters?.filter = "mail eq '${email.replace("'", "''")}' or " +
                "proxyAddresses/any(p:p eq 'smtp:${email.lowercase().replace("'", "''")}' or " +
                "p eq 'SMTP:${email.lowercase().replace("'", "''")}')"
            it.queryParameters?.top = 1
            it.queryParameters?.count = true
            it.headers.add("ConsistencyLevel", "eventual")
        } ?: throw SharedGraphException(null, "InvalidDirectoryResponse", null)
        val values = groups.value ?: throw SharedGraphException(null, "InvalidDirectoryResponse", null)
        if (values.isEmpty() && groups.odataNextLink != null) {
            throw SharedGraphException(null, "InvalidDirectoryResponse", null)
        }
        return values.isEmpty()
    }

    private fun requireIndividualAttendees(emails: List<String>, knownEmails: Set<String> = emptySet()) {
        if (emails.size > SHARED_MAX_ATTENDEES) throw SharedGraphException(400, "TooManyAttendees", null)
        val known = knownEmails.mapTo(mutableSetOf()) { it.lowercase() }
        if (emails.distinctBy { it.lowercase() }.any {
                it.lowercase() !in known && !isIndividuallyAddressed(it)
            }
        ) {
            throw SharedGraphException(400, "GroupInvitationsNotSupported", null)
        }
    }

    override fun updateSharedDetails(
        calendarEventId: String,
        event: Event,
    ): Either<Throwable, MasterEventResult> = sharedRequest {
        val existing = readSharedGraphEvent(calendarEventId)
        if (existing.isOnlineMeeting == true &&
            (existing.body?.content == null || existing.body?.contentType != BodyType.Html)) {
            throw SharedGraphException(null, "MissingMeetingBody", null)
        }
        val payload = com.microsoft.graph.models.Event().apply {
            subject = event.title
            start = event.startTime.toDateTimeTimeZone()
            end = event.endTime.toDateTimeTimeZone()
            location = Location().apply { displayName = event.roomName ?: event.location }
            body = ItemBody().apply {
                contentType = BodyType.Html
                content = updateSharedBody(
                    existing.body?.content?.takeIf { existing.body?.contentType == BodyType.Html },
                    event,
                )
            }
            if (event.isOnlineMeeting) {
                isOnlineMeeting = true
                onlineMeetingProvider = OnlineMeetingProviderType.TeamsForBusiness
            }
        }
        graphClient.users().byUserId(applicationEmailAddress).events().byEventId(calendarEventId).patch(payload)
        toSharedEventResult(calendarEventId, readSharedGraphEvent(calendarEventId))
    }

    override fun cancelSharedEvent(calendarEventId: String): Either<Throwable, Unit> = sharedRequest {
        graphClient.users().byUserId(applicationEmailAddress).events().byEventId(calendarEventId)
            .cancel().post(CancelPostRequestBody())
        Unit
    }

    override fun searchPeople(query: String): Either<Throwable, List<DirectoryPerson>> = sharedRequest {
        // /users is intentional: /people needs broader permissions and can include external contacts.
        // The caller validates query length before this adapter is invoked.
        val escaped = query.replace("'", "''")
        val users = graphClient.users()
        var page = users.get {
            it.queryParameters?.select = arrayOf("id", "displayName", "mail")
            it.queryParameters?.filter = "startswith(displayName,'$escaped') or startswith(mail,'$escaped')"
            it.queryParameters?.top = DIRECTORY_MAX_RESULTS
        } ?: throw SharedGraphException(null, "InvalidGraphResponse", null)
        val people = LinkedHashMap<String, DirectoryPerson>()
        val visited = mutableSetOf<String>()
        for (pageNumber in 1..DIRECTORY_MAX_PAGES) {
            for (user in page.value.orEmpty()) {
                if (user.odataType != null && user.odataType != "#microsoft.graph.user") continue
                val id = user.id ?: continue
                val name = user.displayName ?: continue
                val mail = user.mail?.takeIf(::isNavMail) ?: continue
                people.putIfAbsent(mail.lowercase(), DirectoryPerson(id, name, mail))
                if (people.size == DIRECTORY_MAX_RESULTS) return@sharedRequest people.values.toList()
            }
            val next = page.odataNextLink ?: break
            if (pageNumber == DIRECTORY_MAX_PAGES || !visited.add(next)) break
            val uri = URI(next)
            if (uri.scheme != "https" || uri.host != "graph.microsoft.com" ||
                uri.path != "/v1.0/users" || uri.userInfo != null || uri.port !in listOf(-1, 443)) {
                throw SharedGraphException(null, "InvalidDirectoryNextLink", null)
            }
            page = users.withUrl(next).get() ?: throw SharedGraphException(null, "InvalidGraphResponse", null)
        }
        people.values.toList()
    }
}

class DummyCloudClient(
    private val directoryPeople: List<DirectoryPerson> = listOf(
        DirectoryPerson("dummy-person-1", "Alex Example", "alex.example@nav.no"),
        DirectoryPerson("dummy-person-2", "Robin Example", "robin.example@nav.no"),
    ),
) : CloudClient {
    private data class StoredSharedEvent(
        val event: Event,
        val snapshot: SharedCalendarSnapshot,
        val result: MasterEventResult,
    )

    private val sharedEvents = mutableMapOf<String, StoredSharedEvent>()
    private val sharedTransactions = mutableMapOf<String, String>()

    @Synchronized
    override fun createSharedEvent(
        event: Event,
        attendees: List<Participant>,
        transactionId: String,
    ): Either<Throwable, MasterEventResult> {
        if (transactionId.isBlank()) return SharedGraphException(null, "MissingTransactionId", null).left()
        sharedTransactions[transactionId]?.let { return sharedEvents.getValue(it).result.right() }
        val id = "dummy-shared-" + java.util.UUID.nameUUIDFromBytes(transactionId.toByteArray(Charsets.UTF_8))
        val result = dummyMasterEventResult(id, event).copy(
            roomStatus = if (event.roomEmail != null) RoomBookingStatus.PENDING else null,
        )
        val people = sharedAttendees(event, attendees)
        sharedEvents[id] = StoredSharedEvent(
            event,
            SharedCalendarSnapshot(
                attendees = people,
                body = sharedDescriptionHtml(event),
                changeKey = "1",
                roomStatus = result.roomStatus,
                teamsJoinUrl = result.teamsJoinUrl,
                teamsConferenceId = result.teamsConferenceId,
                teamsDialIn = result.teamsDialIn,
            ),
            result,
        )
        sharedTransactions[transactionId] = id
        return result.right()
    }

    @Synchronized
    override fun getSharedEvent(calendarEventId: String): Either<Throwable, SharedCalendarSnapshot> =
        sharedEvents[calendarEventId]?.snapshot?.right()
            ?: SharedGraphException(404, "ErrorItemNotFound", null).left()

    @Synchronized
    override fun updateSharedAttendees(
        calendarEventId: String,
        attendees: List<SharedCalendarAttendee>,
        changeKey: String?,
    ): Either<Throwable, Unit> {
        val stored = sharedEvents[calendarEventId]
            ?: return SharedGraphException(404, "ErrorItemNotFound", null).left()
        if (stored.snapshot.isCancelled) return SharedGraphException(410, "EventCancelled", null).left()
        // Like the Azure adapter, an opaque changeKey is not treated as an If-Match lock.
        val snapshot = stored.snapshot.copy(
            attendees = attendees.toList(),
            changeKey = nextDummyVersion(stored.snapshot),
            roomStatus = attendees.firstOrNull { it.isResource }?.response.toRoomBookingStatus(attendees.any { it.isResource }),
        )
        sharedEvents[calendarEventId] = stored.copy(
            snapshot = snapshot,
            result = stored.result.copy(roomStatus = snapshot.roomStatus),
        )
        return Unit.right()
    }

    @Synchronized
    override fun updateSharedDetails(calendarEventId: String, event: Event): Either<Throwable, MasterEventResult> {
        val stored = sharedEvents[calendarEventId]
            ?: return SharedGraphException(404, "ErrorItemNotFound", null).left()
        if (stored.snapshot.isCancelled) return SharedGraphException(410, "EventCancelled", null).left()
        val meeting = if (event.isOnlineMeeting && stored.result.teamsJoinUrl == null) {
            dummyMasterEventResult(calendarEventId, event)
        } else stored.result
        val snapshot = stored.snapshot.copy(
            body = updateSharedBody(stored.snapshot.body, event),
            changeKey = nextDummyVersion(stored.snapshot),
            teamsJoinUrl = meeting.teamsJoinUrl,
            teamsConferenceId = meeting.teamsConferenceId,
            teamsDialIn = meeting.teamsDialIn,
        )
        val result = stored.result.copy(
            teamsJoinUrl = snapshot.teamsJoinUrl,
            teamsConferenceId = snapshot.teamsConferenceId,
            teamsDialIn = snapshot.teamsDialIn,
        )
        sharedEvents[calendarEventId] = StoredSharedEvent(event, snapshot, result)
        return result.right()
    }

    private fun nextDummyVersion(snapshot: SharedCalendarSnapshot): String =
        ((snapshot.changeKey?.toLongOrNull() ?: 0) + 1).toString()

    @Synchronized
    override fun cancelSharedEvent(calendarEventId: String): Either<Throwable, Unit> {
        val stored = sharedEvents[calendarEventId]
            ?: return SharedGraphException(404, "ErrorItemNotFound", null).left()
        if (!stored.snapshot.isCancelled) {
            sharedEvents[calendarEventId] = stored.copy(snapshot = stored.snapshot.copy(
                isCancelled = true,
                changeKey = nextDummyVersion(stored.snapshot),
            ))
        }
        return Unit.right()
    }

    override fun searchPeople(query: String): Either<Throwable, List<DirectoryPerson>> =
        directoryPeople.filter {
            isNavMail(it.email) &&
                (it.name.startsWith(query, ignoreCase = true) || it.email.startsWith(query, ignoreCase = true))
        }.distinctBy { it.email.lowercase() }.take(DIRECTORY_MAX_RESULTS).right()

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

private fun sharedDescriptionHtml(event: Event): String =
    """<div id="delta-shared-description">${event.description.replace("\n", "<br>")}</div>"""

private fun sharedAttendees(event: Event, attendees: List<Participant>): List<SharedCalendarAttendee> =
    attendees.filterNot { it.email.equals(event.roomEmail, ignoreCase = true) }
        .distinctBy { it.email.lowercase() }
        .map { SharedCalendarAttendee(it.email, it.name) } +
        listOfNotNull(event.roomEmail?.let {
            SharedCalendarAttendee(it, event.roomName ?: it, isResource = true)
        })

private fun SharedCalendarAttendee.toGraphAttendee(): Attendee =
    Attendee().apply {
        emailAddress = EmailAddress().apply {
            address = email
            name = this@toGraphAttendee.name
        }

        type = if (isResource) AttendeeType.Resource else AttendeeType.Required
        if (response != null || responseTime != null) {
            status = ResponseStatus().apply {
                response = this@toGraphAttendee.response
                time = responseTime
            }
        }
    }

private fun isQuotedEtag(value: String): Boolean =
    Regex("(?:W/)?\"[\\x21\\x23-\\x7E]+\"").matches(value)

private const val DIRECTORY_MAX_RESULTS = 50
private const val DIRECTORY_MAX_PAGES = 5
private const val SHARED_MAX_ATTENDEES = 500

private fun isNavMail(email: String): Boolean =
    Regex("""[^@\s]+@nav\.no""", RegexOption.IGNORE_CASE).matches(email)

private fun ResponseType?.toRoomBookingStatus(hasResource: Boolean): RoomBookingStatus? =
    if (!hasResource) null else when (this) {
        ResponseType.Accepted -> RoomBookingStatus.ACCEPTED
        ResponseType.Declined -> RoomBookingStatus.DECLINED
        else -> RoomBookingStatus.PENDING
    }

private fun updateSharedBody(existingBody: String?, event: Event): String {
    val description = sharedDescriptionHtml(event)
    if (existingBody == null) return description
    val opening = Regex("""<div\b[^>]*\bid\s*=\s*["']delta-shared-description["'][^>]*>""", RegexOption.IGNORE_CASE)
        .find(existingBody)
    if (opening != null) {
        var depth = 1
        for (tag in Regex("""</?div\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(existingBody, opening.range.last + 1)) {
            depth += if (tag.value.startsWith("</")) -1 else 1
            if (depth == 0) return existingBody.replaceRange(opening.range.first, tag.range.last + 1, description)
        }
        throw SharedGraphException(null, "InvalidMeetingBody", null)
    }
    // Exchange may normalize away our marker. Preserve the entire unknown body rather than
    // attempting to identify or reconstruct its native Teams join blob.
    val bodyTag = Regex("""<body\b[^>]*>""", RegexOption.IGNORE_CASE).find(existingBody)
    return if (bodyTag != null) existingBody.replaceRange(bodyTag.range.last + 1, bodyTag.range.last + 1, description)
    else description + existingBody
}

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
