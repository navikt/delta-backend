package no.nav.delta.room

import java.time.LocalDateTime
import no.nav.delta.event.RoomBookingStatus

data class RoomList(
    val displayName: String?,
    val emailAddress: String?,
)

data class RoomInfo(
    val displayName: String?,
    val emailAddress: String?,
    val capacity: Int?,
    val building: String?,
    val floorLabel: String?,
    val isWheelChairAccessible: Boolean?,
)

/**
 * Free/busy status for a single room over a time range.
 *
 * [availabilityView] is the raw Graph "availabilityView" string: one character per
 * [availabilityViewInterval]-minute slot, where '0' = free, '1' = tentative, '2' = busy,
 * '3' = out of office, '4' = working elsewhere. Empty when [error] is set.
 */
data class RoomAvailability(
    val emailAddress: String,
    val availabilityView: String?,
    val error: String?,
)

data class RoomAvailabilityRequest(
    val roomEmails: List<String>,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val availabilityViewInterval: Int = 30,
)

data class RoomAvailabilityError(
    val title: String,
    val status: Int,
    val detail: String,
    val code: String?,
    val upstreamStatus: Int?,
    val requestId: String?,
    val type: String = "about:blank",
)

/**
 * Result of creating/updating the "master" calendar event that carries the room booking and/or
 * Teams meeting for a Delta event (see docs/teams-meeting-room-booking-plan.md). [roomStatus] is
 * null when the master event has no room attendee.
 */
data class MasterEventResult(
    val calendarEventId: String,
    val roomStatus: RoomBookingStatus?,
    val teamsJoinUrl: String?,
    val teamsConferenceId: String?,
    val teamsDialIn: String?,
)
