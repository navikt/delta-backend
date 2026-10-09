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

/** Deliberately excludes Graph's error message, IDs, addresses and payloads. */
class SharedGraphException(
    val httpStatus: Int?,
    val code: String?,
    val retryAfterSeconds: Long?,
    cause: Throwable? = null,
) : RuntimeException("Shared calendar request failed (status=${httpStatus ?: "unknown"})", cause)
