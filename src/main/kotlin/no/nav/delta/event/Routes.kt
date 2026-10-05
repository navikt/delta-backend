package no.nav.delta.event

import arrow.core.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.time.LocalDateTime
import java.util.UUID
import kotlin.reflect.jvm.jvmName
import no.nav.delta.Environment
import no.nav.delta.application.enforceM2mReadOnlyAccess
import no.nav.delta.email.CloudClient
import no.nav.delta.email.batchSendUpdateOrCreationNotification
import no.nav.delta.email.sendCancellationNotification
import no.nav.delta.email.sendUpdateOrCreationNotification
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.room.MasterEventResult
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.event.Routes")

fun Route.eventApi(database: DatabaseInterface, cloudClient: CloudClient, env: Environment) {
    authenticate("jwt") {
        enforceM2mReadOnlyAccess()
        route("/event") {
            get {
                val onlyFuture = call.parameters["onlyFuture"]?.toBoolean() ?: false
                val onlyPast = call.parameters["onlyPast"]?.toBoolean() ?: false

                val onlyMine = call.parameters["onlyMine"]?.toBoolean() ?: false
                val onlyJoined = call.parameters["onlyJoined"]?.toBoolean() ?: false
                val isApplicationToken =
                    call.principal<JWTPrincipal>()?.payload?.getClaim("idtyp")?.asString() == "app"
                if (isApplicationToken && (onlyMine || onlyJoined)) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        "onlyMine and onlyJoined are not available to M2M callers",
                    )
                }
                val participantEmailParameter = call.parameters["participantEmail"]
                val fromParameter = call.parameters["from"]
                val toParameter = call.parameters["to"]
                val attendanceLookupRequested =
                    participantEmailParameter != null || fromParameter != null || toParameter != null
                var attendanceCategoryId: Int? = null
                var attendanceEmail: String? = null
                var startsAtOrAfter: LocalDateTime? = null
                var startsBefore: LocalDateTime? = null
                if (attendanceLookupRequested) {
                    if (!isApplicationToken) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "participantEmail, from, and to are only available to M2M callers",
                        )
                    }
                    val categoryValues =
                        call.request.queryParameters.getAll("categories")?.flatMap { it.split(",") }
                    attendanceCategoryId =
                        categoryValues
                            ?.singleOrNull()
                            ?.toIntOrNull()
                            ?.takeIf { it > 0 }
                    if (attendanceCategoryId == null) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "Attendance lookup requires exactly one positive integer category",
                        )
                    }
                    attendanceEmail =
                        participantEmailParameter
                            ?.trim()
                            ?.lowercase()
                            ?.takeIf { it.isNotBlank() && it.matches(Regex("[^\\s@]+@[^\\s@]+")) }
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                "participantEmail must be a valid email address",
                            )
                    val parsedFrom =
                        fromParameter?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                "from must be an ISO local date-time",
                            )
                    val parsedTo =
                        toParameter?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                "to must be an ISO local date-time",
                            )
                    if (!parsedFrom.isBefore(parsedTo)) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "from must be before to",
                        )
                    }
                    startsAtOrAfter = parsedFrom
                    startsBefore = parsedTo
                }
                val email = if (isApplicationToken) "" else call.principalEmail()
                val hostedBy = if (onlyMine) email.some() else none()

                val joinedBy =
                    when {
                        attendanceEmail != null -> attendanceEmail.some()
                        onlyJoined -> email.some()
                        else -> none()
                    }

                val onlyPublic = !onlyMine && !onlyJoined

                val categories =
                    if (attendanceLookupRequested) {
                        listOf(
                            attendanceCategoryId
                                ?: return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    "Attendance lookup requires exactly one positive integer category",
                                )
                        )
                    } else {
                        call.parameters["categories"]?.split(",")?.map { it.toInt() } ?: emptyList()
                    }

                call.respond(
                    database
                        .getFullEvents(
                            categories,
                            onlyFuture,
                            onlyPast,
                            onlyPublic,
                            hostedBy,
                            joinedBy,
                            startsAtOrAfter,
                            startsBefore,
                        )
                        .map { it.hideTeamsDetailsUnlessParticipantOrHost(email) }
                )
            }
            route("/{id}") {
                get {
                    val id =
                        call.getUuidFromPath().getOrElse {
                            return@get it.left().unwrapAndRespond(call)
                        }

                    database.getFullEvent(id.toString())
                        .map { it.hideTeamsDetailsUnlessParticipantOrHost(call.principalEmail()) }
                        .unwrapAndRespond(call)
                }
            }
        }
        route("/admin/event") {
            put {
                val createEvent = call.receive(CreateEvent::class)
                val email = call.principalEmail()
                val principal = Participant(email, call.principalName())

                if (createEvent.startTime.isAfter(createEvent.endTime)) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest, "Start time must be before end time"
                    )
                }

                validateRoomAndTeamsToggles(createEvent, call.principalGroups(), env)?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, it)
                }
                validateRoomFields(createEvent)?.let {
                    return@put call.respond(HttpStatusCode.BadRequest, it)
                }

                if (createEvent.recurrence != null) {
                    if (createEvent.requestsRoomOrTeams()) {
                        return@put call.respond(HttpStatusCode.BadRequest, RECURRING_NOT_SUPPORTED)
                    }

                    val createdSeries =
                        database
                            .createRecurringEventSeries(createEvent, email, call.principalName())
                            .getOrElse {
                                return@put it.left().unwrapAndRespond(call)
                            }

                    if (createEvent.sendNotificationEmail == true) {
                        Thread(
                            createCreationNotificationFuture(
                                events = createdSeries.affectedEvents,
                                database = database,
                                cloudClient = cloudClient,
                                participant = principal,
                            )
                        ).start()
                    }

                    return@put database.getFullEvent(createdSeries.referenceEventId.toString()).unwrapAndRespond(call)
                }

                // The master event (room booking / Teams meeting) is created before the row exists
                // in the database, so a Graph failure leaves nothing behind to clean up.
                val masterResult: MasterEventResult? =
                    if (createEvent.requestsRoomOrTeams()) {
                        cloudClient.createMasterEvent(createEvent.toDraftEvent(UUID.randomUUID())).getOrElse {
                            logger.warn("Failed to create master event for room/Teams booking", it)
                            return@put call.respond(
                                HttpStatusCode.BadGateway,
                                "Failed to create room booking or Teams meeting",
                            )
                        }
                    } else {
                        null
                    }

                // Graph can create the event but silently skip the Teams meeting (e.g. no Teams
                // license on the Delta mailbox). Teams can't be turned off later, so never save
                // isOnlineMeeting=true without a join link.
                if (createEvent.isOnlineMeeting == true && masterResult?.teamsJoinUrl == null) {
                    masterResult?.let { deleteMasterBestEffort(cloudClient, it.calendarEventId, null) }
                    logger.warn("Teams meeting requested but Graph returned no join URL; nothing saved")
                    return@put call.respond(HttpStatusCode.BadGateway, TEAMS_MEETING_NOT_CREATED)
                }

                // If anything below fails after the master was created, undo both the master and
                // the event row, so no room stays booked for an event that was never fully created.
                fun rollbackCreate(eventId: UUID?) {
                    if (masterResult == null) return
                    deleteMasterBestEffort(cloudClient, masterResult.calendarEventId, eventId)
                    eventId?.let { database.deleteEvent(it.toString()) }
                }

                val created =
                    runCatching { database.addEvent(createEvent) }.getOrElse {
                        rollbackCreate(null)
                        throw it
                    }
                val event =
                    if (masterResult != null) {
                        val withMaster = created.withMasterResult(masterResult)
                        val persisted =
                            database.setMasterCalendarEventId(created.id.toString(), masterResult.calendarEventId)
                                .flatMap { database.updateEvent(withMaster) }
                        persisted.getOrElse {
                            rollbackCreate(created.id)
                            return@put it.left().unwrapAndRespond(call)
                        }
                        withMaster
                    } else {
                        created
                    }
                val createEventFuture = {
                    cloudClient.sendUpdateOrCreationNotification(
                        event,
                        database,
                        principal,
                        null,
                    )
                }

                database
                    .registerForEvent(
                        event.id.toString(),
                        email,
                        call.principalName(),
                        ParticipantType.HOST,
                    )
                    .getOrElse {
                        rollbackCreate(event.id)
                        return@put it.left().unwrapAndRespond(call)
                    }

                createEvent.categories?.let { categories ->
                    database.setCategories(event.id.toString(), categories).getOrElse {
                        rollbackCreate(event.id)
                        return@put it.left().unwrapAndRespond(call)
                    }
                }

                if (createEvent.sendNotificationEmail == true) {
                    Thread(createEventFuture).start()
                }

                database.getFullEvent(event.id.toString()).unwrapAndRespond(call)
            }
            route("/{id}") {
                delete {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }

                    val editScope = call.parameters["editScope"]?.let {
                        runCatching { EventEditScope.valueOf(it) }.getOrElse {
                            return@delete call.respond(HttpStatusCode.BadRequest, "Invalid editScope value")
                        }
                    }

                    if (editScope == EventEditScope.UPCOMING) {
                        // No master-event cleanup here: room/Teams is rejected for every event in a
                        // recurring series (create, UPCOMING and single-occurrence edits), so series
                        // occurrences never have a master calendar event.
                        val notificationData =
                            database
                                .deleteRecurringSeriesFromOccurrence(event.id.toString(), call.principalEmail())
                                .getOrElse { return@delete it.left().unwrapAndRespond(call) }

                        Thread {
                            notificationData.forEach { (deletedEvent, pairs) ->
                                pairs.forEach { (participant, calendarEventId) ->
                                    cloudClient.sendCancellationNotification(
                                        calendarEventId.getOrNull(), deletedEvent, participant
                                    )
                                }
                            }
                        }.start()

                        return@delete call.respond("Success")
                    }

                    // I don't care if this fails...
                    val sendCancellationFuture =
                        database
                            .getAllParticipantsAndCalendarEventIds(event.id.toString())
                            .map { pairs ->
                                {
                                    pairs.map { (participant, calendarEventId) ->
                                        cloudClient.sendCancellationNotification(
                                            calendarEventId.getOrNull(), event, participant
                                        )
                                    }
                                    Unit
                                }
                            }
                            .getOrElse { {} }

                    database.getMasterCalendarEventId(event.id.toString()).getOrElse { null }?.let { masterId ->
                        deleteMasterBestEffort(cloudClient, masterId, event.id)
                    }

                    database
                        .deleteEvent(event.id.toString())
                        .map {
                            Thread(sendCancellationFuture).start()
                            "Success"
                        }
                        .unwrapAndRespond(call)
                }
                post {
                    val originalEvent =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }

                    val changedEvent = call.receive<CreateEvent>()
                    if (changedEvent.startTime.isAfter(changedEvent.endTime)) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest, "Start time must be before end time"
                        )
                    }

                    validateRoomAndTeamsToggles(changedEvent, call.principalGroups(), env)?.let {
                        return@post call.respond(HttpStatusCode.BadRequest, it)
                    }
                    validateRoomFields(changedEvent)?.let {
                        return@post call.respond(HttpStatusCode.BadRequest, it)
                    }

                    if ((changedEvent.editScope ?: EventEditScope.SINGLE) == EventEditScope.UPCOMING) {
                        if (changedEvent.requestsRoomOrTeams()) {
                            return@post call.respond(HttpStatusCode.BadRequest, RECURRING_NOT_SUPPORTED)
                        }

                        val recurringUpdate =
                            database
                                .updateRecurringSeriesFromOccurrence(
                                    eventId = originalEvent.id.toString(),
                                    createEvent = changedEvent,
                                    updatedByEmail = call.principalEmail(),
                                ).getOrElse {
                                    return@post it.left().unwrapAndRespond(call)
                                }

                        if (changedEvent.sendNotificationEmail == true) {
                            Thread(
                                createUpdateNotificationFuture(
                                    events = recurringUpdate.affectedEvents,
                                    database = database,
                                    cloudClient = cloudClient,
                                )
                            ).start()
                        }

                        return@post database.getFullEvent(recurringUpdate.referenceEventId.toString()).unwrapAndRespond(call)
                    }

                    // Single-occurrence edits of a recurring series are rejected too, so series
                    // occurrences never get a master event (see the UPCOMING delete above).
                    if (changedEvent.requestsRoomOrTeams() && database.isRecurringOccurrence(originalEvent.id)) {
                        return@post call.respond(HttpStatusCode.BadRequest, RECURRING_NOT_SUPPORTED)
                    }

                    // Once enabled, Teams stays until the event is deleted: turning it off would
                    // need a PATCH Graph may not honour, and would kill the join link participants
                    // already have.
                    if (changedEvent.isOnlineMeeting == false && originalEvent.isOnlineMeeting) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "Teams meeting cannot be removed from an event; delete the event instead",
                        )
                    }

                    // Merge rules: null room/Teams fields mean "keep what's there", so clients
                    // without the feature (toggle off, older frontend) never remove a booking by
                    // omitting the fields. A room can be changed (new roomEmail) but not removed.
                    val roomChanged =
                        changedEvent.roomEmail != null &&
                            !changedEvent.roomEmail.equals(originalEvent.roomEmail, ignoreCase = true)
                    val mergedEvent =
                        Event(
                            id = originalEvent.id,
                            title = changedEvent.title,
                            description = changedEvent.description,
                            startTime = changedEvent.startTime,
                            endTime = changedEvent.endTime,
                            location = changedEvent.location,
                            public = changedEvent.public,
                            participantLimit = changedEvent.participantLimit,
                            signupDeadline = changedEvent.signupDeadline,
                            roomEmail = changedEvent.roomEmail ?: originalEvent.roomEmail,
                            roomName = if (changedEvent.roomEmail != null) changedEvent.roomName else originalEvent.roomName,
                            roomStatus = originalEvent.roomStatus,
                            isOnlineMeeting = changedEvent.isOnlineMeeting ?: originalEvent.isOnlineMeeting,
                            teamsJoinUrl = originalEvent.teamsJoinUrl,
                            teamsConferenceId = originalEvent.teamsConferenceId,
                            teamsDialIn = originalEvent.teamsDialIn,
                        )
                    val needsMaster = mergedEvent.roomEmail != null || mergedEvent.isOnlineMeeting

                    val existingMasterId =
                        database.getMasterCalendarEventId(originalEvent.id.toString()).getOrElse { null }
                    val createsNewMaster = needsMaster && existingMasterId == null

                    // Also runs for plain title/time edits, so the master (room booking + Teams
                    // meeting) always follows the event.
                    val masterResult: MasterEventResult? =
                        if (needsMaster) {
                            val result =
                                if (existingMasterId != null) {
                                    cloudClient.updateMasterEvent(existingMasterId, mergedEvent)
                                } else {
                                    cloudClient.createMasterEvent(mergedEvent)
                                }
                            result.getOrElse {
                                logger.warn("Failed to sync master event for event ${originalEvent.id}", it)
                                return@post call.respond(
                                    HttpStatusCode.BadGateway,
                                    "Failed to create room booking or Teams meeting",
                                )
                            }
                        } else {
                            null
                        }

                    val newEvent =
                        if (masterResult == null) {
                            mergedEvent
                        } else {
                            mergedEvent.copy(
                                roomStatus = mergeRoomStatus(
                                    mergedEvent.roomEmail,
                                    originalEvent.roomStatus,
                                    masterResult.roomStatus,
                                    freshBooking = roomChanged || createsNewMaster,
                                ),
                                teamsJoinUrl = masterResult.teamsJoinUrl ?: originalEvent.teamsJoinUrl,
                                teamsConferenceId = masterResult.teamsConferenceId ?: originalEvent.teamsConferenceId,
                                teamsDialIn = masterResult.teamsDialIn ?: originalEvent.teamsDialIn,
                            )
                        }

                    // Undo this request's Graph change if a later step fails: delete a master it
                    // created, or restore an existing master to the original event, so Outlook and
                    // the DB don't diverge.
                    fun rollbackNewMaster() {
                        if (masterResult == null) return
                        if (createsNewMaster) {
                            deleteMasterBestEffort(cloudClient, masterResult.calendarEventId, originalEvent.id)
                            database.setMasterCalendarEventId(originalEvent.id.toString(), null)
                        } else if (existingMasterId != null) {
                            cloudClient.updateMasterEvent(existingMasterId, originalEvent).onLeft {
                                logger.warn("Failed to restore master event $existingMasterId for event ${originalEvent.id}", it)
                            }
                        }
                    }

                    // Same guard as on create: never persist Teams without a join link.
                    if (newEvent.isOnlineMeeting && newEvent.teamsJoinUrl == null) {
                        rollbackNewMaster()
                        logger.warn("Teams meeting requested for event ${originalEvent.id} but Graph returned no join URL")
                        return@post call.respond(HttpStatusCode.BadGateway, TEAMS_MEETING_NOT_CREATED)
                    }

                    if (createsNewMaster && masterResult != null) {
                        database.setMasterCalendarEventId(originalEvent.id.toString(), masterResult.calendarEventId)
                            .getOrElse {
                                rollbackNewMaster()
                                return@post it.left().unwrapAndRespond(call)
                            }
                    }

                    val sendUpdateFuture =
                        database
                            .getAllParticipantsAndCalendarEventIds(newEvent.id.toString())
                            .map { pairs ->
                                {
                                    cloudClient.batchSendUpdateOrCreationNotification(
                                        newEvent,
                                        database,
                                        pairs.map { (participant, calendarEventId) ->
                                            Pair(participant, calendarEventId.getOrNull())
                                        },
                                    )
                                }
                            }
                            .getOrElse { {} }

                    database.updateEvent(newEvent).getOrElse {
                        rollbackNewMaster()
                        return@post it.left().unwrapAndRespond(call)
                    }

                    changedEvent.categories?.let { categories ->
                        database.setCategories(newEvent.id.toString(), categories).getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    }

                    if (changedEvent.sendNotificationEmail == true) {
                        Thread(sendUpdateFuture).start()
                    }

                    database.getFullEvent(newEvent.id.toString()).unwrapAndRespond(call)
                }
                delete("/participant") {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }
                    val participantEmail = call.receive<EmailToken>().email
                    val deleteCalendarEventFuture =
                        database
                            .getCalendarEventId(event.id.toString(), participantEmail)
                            .map {
                                {
                                    if (it != null) {
                                        cloudClient.deleteEvent(it)
                                    }
                                }
                            }
                            .getOrElse { {} }

                    database
                        .unregisterFromEvent(event.id.toString(), participantEmail)
                        .map {
                            Thread(deleteCalendarEventFuture).start()
                            "Success"
                        }
                        .unwrapAndRespond(call)
                }
                post("/participant") {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    val changeParticipant = call.receive<ChangeParticipant>()

                    database
                        .changeParticipant(event.id.toString(), changeParticipant)
                        .map { "Success" }
                        .unwrapAndRespond(call)
                }
                post("/category") {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    val categories = call.receive<List<Int>>()
                    database
                        .setCategories(event.id.toString(), categories)
                        .map { "Success" }
                        .unwrapAndRespond(call)
                }
            }
        }
        route("/user/event") {
            route("/{id}") {
                post {
                    val id =
                        call.getUuidFromPath().getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    val user = Participant(call.principalEmail(), call.principalName())

                    val updateCalendarEventFuture =
                        database
                            .getEvent(id.toString())
                            .map { event ->
                                {
                                    cloudClient.sendUpdateOrCreationNotification(
                                        event, database, user, null
                                    )
                                }
                            }
                            .getOrElse { {} }

                    database
                        .registerForEvent(id.toString(), user.email, user.name)
                        .map {
                            Thread(updateCalendarEventFuture).start()
                            "Success"
                        }
                        .unwrapAndRespond(call)
                }
                delete {
                    val id =
                        call.getUuidFromPath().getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }
                    val email = call.principalEmail()
                    val deleteCalendarEventFuture =
                        database
                            .getCalendarEventId(id.toString(), email)
                            .map {
                                {
                                    if (it != null) {
                                        cloudClient.deleteEvent(it)
                                    }
                                }
                            }
                            .getOrElse { {} }

                    database
                        .unregisterFromEvent(id.toString(), email)
                        .map {
                            Thread(deleteCalendarEventFuture).start()
                            "Success"
                        }
                        .unwrapAndRespond(call)
                }
            }
        }
        route("/category") {
            get { call.respond(database.getCategories()) }
            put {
                val category = call.receive<CreateCategory>()
                database.createCategory(category).unwrapAndRespond(call)
            }
        }
    }
}

suspend fun Either<Any, Any>.unwrapAndRespond(call: ApplicationCall) {
    this.fold(
        {
            when (it) {
                is ExceptionWithDefaultResponse -> it.defaultResponse(call)
                // Unknown exceptions, this *should* never happen
                is Exception -> throw it
                else -> throw RuntimeException("Unhandled exception: ${it::class.jvmName}")
            }
        },
        { call.respond(it) },
    )
}

fun ApplicationCall.getUuidFromPath(): Either<IdException, UUID> {
    val id = parameters["id"] ?: return MissingIdException.left()

    return runCatching { UUID.fromString(id) }
        .fold(
            { it.right() },
            { InvalidIdException.left() },
        )
}

fun ApplicationCall.getEventWithPrivilege(
    database: DatabaseInterface
): Either<EventAccessException, Event> =
    getUuidFromPath()
        .map { id -> Pair(id, principalEmail()) }
        .flatMap {
            val (id, email) = it
            database.getEvent(id.toString()).flatMap { event ->
                database.getHosts(id.toString()).flatMap { hosts ->
                    if (hosts.find { p -> p.email == email } == null) {
                        ForbiddenException.left()
                    } else {
                        event.right()
                    }
                }
            }
        }

fun ApplicationCall.principalEmail(): String {
    return if(System.getenv("NAIS_CLUSTER_NAME").isNullOrEmpty()) {
        "test@localhost"
    } else {
        principal<JWTPrincipal>()!!["preferred_username"]!!.lowercase()
    }
}

/**
 * Rejects requests that set room booking or Teams meeting fields when the calling user does not
 * have the corresponding feature enabled (see docs/teams-meeting-room-booking-plan.md). Returns
 * an error message to respond with, or null when the request is allowed.
 */
private fun validateRoomAndTeamsToggles(
    createEvent: CreateEvent,
    groups: List<String>,
    env: Environment,
): String? {
    val requestsRoom = createEvent.roomEmail != null || createEvent.roomName != null
    if (requestsRoom && !env.isRoomBookingEnabledFor(groups)) {
        return "Room booking is not enabled"
    }

    if (createEvent.isOnlineMeeting == true && !env.isTeamsMeetingEnabledFor(groups)) {
        return "Teams meeting is not enabled"
    }

    return null
}

private const val TEAMS_MEETING_NOT_CREATED =
    "Teams meeting could not be created; nothing was saved"

private const val RECURRING_NOT_SUPPORTED =
    "Room booking and Teams meetings are not supported for recurring events"

/** roomEmail and roomName must be sent together: a name without an address can't be booked. */
private fun validateRoomFields(createEvent: CreateEvent): String? =
    if ((createEvent.roomEmail == null) != (createEvent.roomName == null)) {
        "roomEmail and roomName must be set together"
    } else {
        null
    }

/**
 * Whether the request sets any room/Teams field. On create this means a master event (see
 * docs/teams-meeting-room-booking-plan.md) is needed; on update, null fields mean "keep".
 * `isOnlineMeeting = false` alone is not a request for anything.
 */
private fun CreateEvent.requestsRoomOrTeams(): Boolean =
    roomEmail != null || roomName != null || isOnlineMeeting == true

/**
 * Room status after a master update. A fresh booking (new room or new master) takes whatever
 * Graph reports. For an unchanged room, a PATCH re-fetch usually says PENDING until the room
 * re-answers, so a known ACCEPTED/DECLINED is kept unless Graph reports a definite answer.
 */
private fun mergeRoomStatus(
    roomEmail: String?,
    previous: RoomBookingStatus?,
    fromGraph: RoomBookingStatus?,
    freshBooking: Boolean,
): RoomBookingStatus? =
    when {
        roomEmail == null -> null
        freshBooking -> fromGraph ?: RoomBookingStatus.PENDING
        fromGraph != null && fromGraph != RoomBookingStatus.PENDING -> fromGraph
        else -> previous ?: fromGraph ?: RoomBookingStatus.PENDING
    }

/** A lingering master event is low-harm, so failures are logged rather than surfaced. */
private fun deleteMasterBestEffort(cloudClient: CloudClient, masterId: String, eventId: UUID?) {
    cloudClient.deleteMasterEvent(masterId).onLeft {
        logger.warn("Failed to delete master event $masterId for event $eventId", it)
    }
}

/** The subset of fields the master event's Graph representation is built from. */
private fun CreateEvent.toDraftEvent(id: UUID): Event =
    Event(
        id = id,
        title = title,
        description = description,
        startTime = startTime,
        endTime = endTime,
        location = location,
        public = public,
        participantLimit = participantLimit,
        signupDeadline = signupDeadline,
        roomEmail = roomEmail,
        roomName = roomName,
        isOnlineMeeting = isOnlineMeeting ?: false,
    )

private fun Event.withMasterResult(masterResult: MasterEventResult): Event =
    copy(
        roomStatus = if (roomEmail != null) (masterResult.roomStatus ?: RoomBookingStatus.PENDING) else null,
        teamsJoinUrl = masterResult.teamsJoinUrl,
        teamsConferenceId = masterResult.teamsConferenceId,
        teamsDialIn = masterResult.teamsDialIn,
    )

/**
 * Teams meeting details (join link, conference ID, dial-in) are only shown to registered
 * participants and hosts — the join link lets anyone into the meeting, unlike the room, which is
 * already visible to anyone who sees the calendar invite (see
 * docs/teams-meeting-room-booking-plan.md).
 */
private fun FullEvent.hideTeamsDetailsUnlessParticipantOrHost(email: String): FullEvent {
    val isParticipantOrHost =
        hosts.any { it.email == email } || participants.any { it.email == email }
    if (isParticipantOrHost) return this

    return copy(
        event = event.copy(
            teamsJoinUrl = null,
            teamsConferenceId = null,
            teamsDialIn = null,
        )
    )
}

private fun createCreationNotificationFuture(
    events: List<Event>,
    database: DatabaseInterface,
    cloudClient: CloudClient,
    participant: Participant,
): () -> Unit = {
    events.forEach { event ->
        cloudClient.sendUpdateOrCreationNotification(
            event = event,
            database = database,
            participant = participant,
            calendarEventId = null,
        )
    }
}

private fun createUpdateNotificationFuture(
    events: List<Event>,
    database: DatabaseInterface,
    cloudClient: CloudClient,
): () -> Unit = {
    events.forEach { event ->
        database
            .getAllParticipantsAndCalendarEventIds(event.id.toString())
            .map { pairs ->
                cloudClient.batchSendUpdateOrCreationNotification(
                    event = event,
                    database = database,
                    participantsWithCalendarIds =
                        pairs.map { (participant, calendarEventId) ->
                            Pair(participant, calendarEventId.getOrNull())
                        },
                )
            }
    }
}

/** Group id the local (non-NAIS) test principal is a member of; see [principalGroups]. */
const val LOCAL_PRINCIPAL_GROUP = "local-principal-group"

/** Entra ID group ids from the token's `groups` claim (only groups listed in nais.yaml are emitted). */
fun ApplicationCall.principalGroups(): List<String> {
    return if (System.getenv("NAIS_CLUSTER_NAME").isNullOrEmpty()) {
        listOf(LOCAL_PRINCIPAL_GROUP)
    } else {
        principal<JWTPrincipal>()!!.payload.getClaim("groups")?.asList(String::class.java) ?: emptyList()
    }
}

fun ApplicationCall.principalName(): String {
    return if(System.getenv("NAIS_CLUSTER_NAME").isNullOrEmpty()) {
        return "test@localhost"
    } else {
        principal<JWTPrincipal>()!!["name"]!!
    }
}
