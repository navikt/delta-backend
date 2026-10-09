package no.nav.delta.email

import com.microsoft.graph.models.ResponseType
import java.time.OffsetDateTime
import no.nav.delta.event.RoomBookingStatus

data class DirectoryPerson(val id: String, val name: String, val email: String)

data class SharedCalendarAttendee(
    val email: String,
    val name: String,
    val response: ResponseType? = null,
    val responseTime: OffsetDateTime? = null,
    val isResource: Boolean = false,
    val isIndividual: Boolean = true,
)

data class SharedCalendarSnapshot(
    val attendees: List<SharedCalendarAttendee>,
    val body: String? = null,
    val changeKey: String? = null,
    val roomStatus: RoomBookingStatus? = null,
    val teamsJoinUrl: String? = null,
    val teamsConferenceId: String? = null,
    val teamsDialIn: String? = null,
    val isCancelled: Boolean = false,
    val etag: String? = null,
)

enum class SharedCalendarOperation {
    CLASSIFY_ATTENDEE,
    CREATE_EVENT,
    READ_EVENT,
    UPDATE_ATTENDEES,
    UPDATE_DETAILS,
    CANCEL_EVENT,
    SEARCH_PEOPLE,
}

/** Deliberately excludes Graph's error message, IDs, addresses and payloads. */
class SharedGraphException(
    val httpStatus: Int?,
    code: String?,
    val retryAfterSeconds: Long?,
    cause: Throwable? = null,
    val operation: SharedCalendarOperation? = null,
) : RuntimeException(
    "Shared calendar request failed (status=${httpStatus ?: "unknown"}, operation=${operation ?: "unknown"})",
    cause,
) {
    val code: String? = code?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,79}")) }
}
