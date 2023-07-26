package no.nav.delta.event

import java.time.LocalDateTime
import java.util.UUID

data class Event(
    val id: UUID,
    val ownerEmail: String,
    val title: String,
    val description: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val location: String,
    val public: Boolean,
    val participantLimit: Int,
    val signupDeadline: LocalDateTime?,
)

data class EventWithParticipants(
    val event: Event,
    val participants: List<Participant>,
)

data class Participant(
    val email: String,
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
)
