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
)

data class CreateEvent(
    val title: String,
    val description: String,
    val startTime: Date,
    val endTime: Date,
)

data class RegistrationEmail(
    val email: String,
)

data class ParticipationOtp(
    val otp: String,
)
