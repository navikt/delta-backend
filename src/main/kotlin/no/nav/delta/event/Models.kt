package no.nav.delta.event

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
    val sendNotificationEmail: Boolean? = true
)

data class Category(
    val id: Int,
    val name: String,
)

data class CreateCategory(
    val name: String,
)
