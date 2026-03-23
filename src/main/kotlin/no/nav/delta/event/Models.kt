package no.nav.delta.event

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
)

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
)

data class RecurringSeriesSummary(
    val seriesId: UUID,
    val frequency: RecurrenceFrequency,
    val untilDate: LocalDate,
    val editableScopes: List<EventEditScope> = listOf(EventEditScope.SINGLE, EventEditScope.UPCOMING),
)
