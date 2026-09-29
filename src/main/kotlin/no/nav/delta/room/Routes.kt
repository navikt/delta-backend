package no.nav.delta.room

import arrow.core.Either
import arrow.core.right
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
import java.time.Instant
import java.util.LinkedHashMap
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.event.principalGroups
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.room.Routes")

// getSchedule limits. The interval range is documented by Microsoft; the room count and 62-day
// range are conservative caps from Exchange free/busy limits.
private const val MAX_AVAILABILITY_ROOMS = 20
private val MAX_AVAILABILITY_RANGE: Duration = Duration.ofDays(62)
private const val MAX_ROOM_LIST_CACHE_ENTRIES = 100

/** Caches successful loads only; a failed load is returned as-is and retried on the next call. */
private class Cache<T>(private val ttl: Duration) {
    private var value: T? = null
    private var fetchedAt: Instant? = null

    @Synchronized
    fun getOrLoad(load: () -> Either<Throwable, T>): Either<Throwable, T> {
        val cached = value
        val age = fetchedAt?.let { Duration.between(it, Instant.now()) }
        if (cached != null && age != null && age < ttl) {
            return cached.right()
        }
        return load().onRight {
            value = it
            fetchedAt = Instant.now()
        }
    }
}

/** An access-ordered cache map that evicts the least recently used entry at capacity. */
private class BoundedCacheMap<K, V>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

    @Synchronized
    fun getOrCreate(key: K, create: () -> V): V =
        entries[key] ?: create().also {
            entries[key] = it
            if (entries.size > maxEntries) {
                entries.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
}

/**
 * All routes here are guarded behind [Environment.isRoomBookingEnabledFor]; when the feature is
 * disabled for the caller, requests are rejected with 400 rather than silently ignored.
 */
fun Route.roomApi(cloudClient: CloudClient, env: Environment) {
    val roomListsCache = Cache<List<RoomList>>(Duration.ofHours(1))
    val roomsCache = BoundedCacheMap<String, Cache<List<RoomInfo>>>(MAX_ROOM_LIST_CACHE_ENTRIES)
    val allRoomsCache = Cache<List<RoomInfo>>(Duration.ofHours(1))

    authenticate("jwt") {
        route("/rooms") {
            get {
                if (!env.isRoomBookingEnabledFor(call.principalGroups())) {
                    return@get call.respond(HttpStatusCode.BadRequest, "Room booking is not enabled")
                }

                roomListsCache.getOrLoad { cloudClient.getRoomLists() }.fold(
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

                    allRoomsCache.getOrLoad { cloudClient.getAllRooms() }.fold(
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

                    val cache = roomsCache.getOrCreate(roomListEmail) { Cache(Duration.ofHours(1)) }
                    cache.getOrLoad { cloudClient.getRooms(roomListEmail) }.fold(
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
                                logger.warn("Failed to get room availability", error)
                                call.respond(HttpStatusCode.BadGateway, "Failed to get room availability")
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
