package no.nav.delta.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class Event(
    val id: UUID,
    val title: String,
    val description: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val location: String,
    val public: Boolean,
    val participantLimit: Int,
    val signupDeadline: LocalDateTime?,
    // Room booking (feature toggled, see docs/teams-meeting-room-booking-plan.md)
    val roomEmail: String? = null,
    val roomName: String? = null,
    val roomStatus: RoomBookingStatus? = null,
    // Teams meeting (feature toggled)
    val isOnlineMeeting: Boolean = false,
    val teamsJoinUrl: String? = null,
    val teamsConferenceId: String? = null,
    val teamsDialIn: String? = null,
)

enum class RoomBookingStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
}


data class FullEvent(
    val event: Event,
    val participants: List<Participant>,
    val hosts: List<Participant>,
    val categories: List<Category>,
    val recurringSeries: RecurringSeriesSummary? = null,
)

data class Participant(
    val email: String,
    val name: String,
)

data class EmailToken(
    val email: String,
)

enum class ParticipantType {
    HOST,
    PARTICIPANT,
}

data class ChangeParticipant(
    val email: String,
    val type: ParticipantType,
)

// Server-managed room/Teams fields are silently ignored if a client echoes them back (e.g. by
// round-tripping an Event); every other unknown field still fails deserialization.
@JsonIgnoreProperties(value = ["roomStatus", "teamsJoinUrl", "teamsConferenceId", "teamsDialIn"])
data class CreateEvent(
    val title: String,
    val description: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val location: String,
    val public: Boolean,
    val participantLimit: Int,
    val signupDeadline: LocalDateTime?,
    val sendNotificationEmail: Boolean? = true,
    val categories: List<Int>? = null,
    val recurrence: RecurrenceRequest? = null,
    val editScope: EventEditScope? = null,
    // Room booking / Teams meeting (feature toggled). Rejected with 400 when the calling user
    // does not have the corresponding feature enabled, see docs/teams-meeting-room-booking-plan.md.
    // On update, null means "keep the current value". roomEmail/roomName must be sent together.
    // A room can be changed but not removed, and Teams cannot be turned off once enabled —
    // delete the event instead.
    val roomEmail: String? = null,
    val roomName: String? = null,
    val isOnlineMeeting: Boolean? = null,
)

data class Category(
    val id: Int,
    val name: String,
)

data class CreateCategory(
    val name: String,
)

enum class RecurrenceFrequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
}

enum class EventEditScope {
    SINGLE,
    UPCOMING,
}

data class RecurrenceRequest(
    val frequency: RecurrenceFrequency,
    val untilDate: LocalDate,
    val signupDeadlineOffsetDays: Int? = null,
)

data class RecurringSeriesSummary(
    val seriesId: UUID,
    val frequency: RecurrenceFrequency,
    val untilDate: LocalDate,
    val editableScopes: List<EventEditScope> = listOf(EventEditScope.SINGLE, EventEditScope.UPCOMING),
)
