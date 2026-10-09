package no.nav.delta.room

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration
import no.nav.delta.Environment
import no.nav.delta.application.enforceM2mReadOnlyAccess
import no.nav.delta.email.CloudClient
import no.nav.delta.email.RoomAvailabilityException
import no.nav.delta.event.principalGroups
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.room.Routes")

// getSchedule limits. The interval range is documented by Microsoft; the room count and 62-day
// range are conservative caps from Exchange free/busy limits.
private const val MAX_AVAILABILITY_ROOMS = 20
private val MAX_AVAILABILITY_RANGE: Duration = Duration.ofDays(62)

/**
 * All routes here are guarded behind [Environment.isRoomBookingEnabledFor]; when the feature is
 * disabled for the caller, requests are rejected with 400 rather than silently ignored.
 */
fun Route.roomApi(
    cloudClient: CloudClient,
    env: Environment,
    roomCatalog: RoomCatalog = RoomCatalog(cloudClient),
) {

    authenticate("jwt") {
        enforceM2mReadOnlyAccess()
        route("/rooms") {
            get {
                if (!env.isRoomBookingEnabledFor(call.principalGroups())) {
                    return@get call.respond(HttpStatusCode.BadRequest, "Room booking is not enabled")
                }

                roomCatalog.roomLists().fold(
                    { error ->
                        logger.warn("Failed to get room lists", error)
                        call.respond(HttpStatusCode.BadGateway, "Failed to get room lists")
                    },
                    { call.respond(it) },
                )
            }
            route("/search") {
                get {
                    if (!env.isRoomBookingEnabledFor(call.principalGroups())) {
                        return@get call.respond(HttpStatusCode.BadRequest, "Room booking is not enabled")
                    }
                    val query = call.parameters["q"]?.trim().orEmpty()
                    if (query.length < 2) {
                        return@get call.respond(HttpStatusCode.BadRequest, "q must be at least 2 characters")
                    }
                    val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25

                    roomCatalog.allRooms().fold(
                        { error ->
                            logger.warn("Failed to get all rooms", error)
                            call.respond(HttpStatusCode.BadGateway, "Failed to get rooms")
                        },
                        { call.respond(searchRooms(it, query, limit)) },
                    )
                }
            }
            route("/{roomListEmail}") {
                get {
                        if (!env.isRoomBookingEnabledFor(call.principalGroups())) {
                        return@get call.respond(HttpStatusCode.BadRequest, "Room booking is not enabled")
                    }

                    val roomListEmail =
                        call.parameters["roomListEmail"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing roomListEmail")

                    roomCatalog.rooms(roomListEmail).fold(
                        { error ->
                            logger.warn("Failed to get rooms for $roomListEmail", error)
                            call.respond(HttpStatusCode.BadGateway, "Failed to get rooms")
                        },
                        { call.respond(it) },
                    )
                }
            }
            route("/availability") {
                post {
                        if (!env.isRoomBookingEnabledFor(call.principalGroups())) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Room booking is not enabled")
                    }

                    val request = call.receive(RoomAvailabilityRequest::class)
                    if (request.roomEmails.isEmpty()) {
                        return@post call.respond(HttpStatusCode.BadRequest, "roomEmails must not be empty")
                    }
                    if (request.roomEmails.size > MAX_AVAILABILITY_ROOMS) {
                        return@post call.respond(HttpStatusCode.BadRequest, "At most $MAX_AVAILABILITY_ROOMS roomEmails per request")
                    }
                    if (!request.startTime.isBefore(request.endTime)) {
                        return@post call.respond(HttpStatusCode.BadRequest, "startTime must be before endTime")
                    }
                    if (Duration.between(request.startTime, request.endTime) > MAX_AVAILABILITY_RANGE) {
                        return@post call.respond(HttpStatusCode.BadRequest, "Time range must be at most 62 days")
                    }
                    if (request.availabilityViewInterval !in 5..1440) {
                        return@post call.respond(HttpStatusCode.BadRequest, "availabilityViewInterval must be between 5 and 1440")
                    }

                    cloudClient
                        .getRoomAvailability(
                            request.roomEmails,
                            request.startTime,
                            request.endTime,
                            request.availabilityViewInterval,
                        )
                        .fold(
                            { error ->
                                val failure = error as? RoomAvailabilityException
                                    ?: RoomAvailabilityException(request, error)
                                logger.warn("{}", failure.message)
                                call.respond(HttpStatusCode.BadGateway, failure.clientMessage)
                            },
                            { call.respond(it) },
                        )
                }
            }
        }
    }
}

/**
 * Case-insensitive search where every whitespace-separated term must appear in the room's
 * display name or email, in any order. Punctuation like "(RV)" and "-" is ignored, so
 * "fya1 a347" and "kaptein" both find "(RV) FYA1 - A347 Kaptein - Videokonf".
 * Rooms whose name starts with the first term are ranked first, then alphabetically.
 */
internal fun searchRooms(rooms: List<RoomInfo>, query: String, limit: Int): List<RoomInfo> {
    fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}@.]+"), " ").trim()
    val terms = normalize(query).split(" ").filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()

    return rooms
        .map { it to normalize("${it.displayName.orEmpty()} ${it.emailAddress.orEmpty()}") }
        .filter { (_, haystack) -> terms.all { haystack.contains(it) } }
        .sortedWith(
            compareBy<Pair<RoomInfo, String>> { (room, _) ->
                val words = normalize(room.displayName.orEmpty()).split(" ")
                if (words.any { it.startsWith(terms.first()) }) 0 else 1
            }.thenBy { (room, _) -> room.displayName.orEmpty().lowercase() }
        )
        .take(limit)
        .map { it.first }
}
