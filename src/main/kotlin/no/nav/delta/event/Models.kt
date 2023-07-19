package no.nav.delta.event

import java.util.Date
import java.util.UUID

data class Event(
    val id: UUID,
    val ownerEmail: String,
    val title: String,
    val description: String,
    val startTime: Date,
    val endTime: Date,
    val location: String?,
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
    val startTime: Date,
    val endTime: Date,
    val location: String,
)

data class ChangeEvent(
    val title: String?,
    val description: String?,
    val startTime: Date?,
    val endTime: Date?,
    val location: String?,
)
