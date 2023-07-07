package no.nav.delta.event

import java.util.*

data class Event (
    val id: Int,
    val ownerEmail: String,
    val title: String,
    val description: String,
    val startTime: Date,
    val endTime: Date,
)

data class CreateEvent (
    val title: String,
    val description: String,
    val startTime: Date,
    val endTime: Date,
)