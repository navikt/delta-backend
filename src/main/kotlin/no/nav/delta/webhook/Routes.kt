package no.nav.delta.webhook

import com.microsoft.graph.models.ResponseType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.launch
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.event.RoomBookingStatus
import no.nav.delta.event.getEvent
import no.nav.delta.event.getEventIdByMasterCalendarEventId
import no.nav.delta.event.setRoomStatus
import no.nav.delta.event.unregisterFromEvent
import no.nav.delta.plugins.DatabaseInterface
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.webhook.Routes")

fun Route.webhookApi(
    database: DatabaseInterface,
    cloudClient: CloudClient,
    env: Environment,
) {
    route("/webhook/calendar") {
        // Support GET as well, though Graph validates notificationUrl with POST.
        get {
            if (!call.respondToValidationToken(call.parameters["validationToken"])) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        // MS Graph validates notificationUrl with POST ?validationToken=... and sends
        // actual change notifications as JSON POST requests.
        post {
            if (call.respondToValidationToken(call.parameters["validationToken"])) {
                return@post
            }

            val payload = try {
                call.receive<GraphNotificationPayload>()
            } catch (e: Exception) {
                logger.warn("Failed to parse notification payload: ${e.message}")
                call.respond(HttpStatusCode.Accepted)
                return@post
            }

            // Acknowledge immediately — MS Graph requires a response within 10 seconds.
            // Processing is offloaded to a background coroutine so the 202 is committed first.
            call.respond(HttpStatusCode.Accepted)

            call.application.launch {
                for (notification in payload.value) {
                    if (notification.clientState != env.webhookClientState) {
                        logger.warn("Received notification with invalid clientState, ignoring")
                        continue
                    }
                    if (notification.changeType != "updated") {
                        continue
                    }
                    try {
                        processNotification(notification, database, cloudClient)
                    } catch (e: Exception) {
                        logger.error("Unhandled exception processing notification for resource ${notification.resource}: ${e.message}", e)
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondToValidationToken(
    validationToken: String?,
): Boolean {
    if (validationToken == null) return false
    respondText(validationToken, ContentType.Text.Plain, HttpStatusCode.OK)
    return true
}

private fun processNotification(
    notification: GraphNotification,
    database: DatabaseInterface,
    cloudClient: CloudClient,
) {
    val calendarEventId = extractCalendarEventId(notification.resource) ?: run {
        logger.warn("Could not extract calendar event ID from resource: ${notification.resource}")
        return
    }

    val masterEventId = database.getEventIdByMasterCalendarEventId(calendarEventId).getOrNull()
    if (masterEventId != null) {
        processMasterEventNotification(masterEventId, calendarEventId, database, cloudClient)
        return
    }

    val participantRef = database.getParticipantByCalendarEventId(calendarEventId) ?: run {
        // Not a Delta-managed event — ignore
        return
    }

    val attendeeStatus = cloudClient.getEventAttendeeStatus(calendarEventId).fold(
        ifLeft = { err ->
            logger.error("Failed to get attendee status for $calendarEventId: ${err.message}", err)
            return
        },
        ifRight = { it }
    )

    if (attendeeStatus == ResponseType.Declined) {
        logger.info(
            "Participant ${participantRef.email} declined event ${participantRef.eventId} via Outlook, unregistering"
        )
        database.unregisterFromEvent(participantRef.eventId, participantRef.email).fold(
            ifLeft = { err ->
                logger.warn("Failed to unregister ${participantRef.email} from ${participantRef.eventId}: $err")
            },
            ifRight = {
                cloudClient.deleteEvent(calendarEventId).fold(
                    ifLeft = { err ->
                        logger.warn("Unregistered participant but failed to delete calendar event $calendarEventId: ${err.message}")
                    },
                    ifRight = {
                        logger.info("Successfully unregistered ${participantRef.email} and deleted calendar event $calendarEventId")
                    }
                )
            }
        )
    }
}

/**
 * Handles a notification for a "master" calendar event (the room booking / Teams meeting, see
 * docs/teams-meeting-room-booking-plan.md) rather than a participant invite. Only updates
 * [no.nav.delta.event.Event.roomStatus] — there is no email or attendee status to react to for
 * the Teams meeting itself, since the master has no human attendees.
 */
private fun processMasterEventNotification(
    eventId: String,
    calendarEventId: String,
    database: DatabaseInterface,
    cloudClient: CloudClient,
) {
    val attendeeStatus = cloudClient.getEventAttendeeStatus(calendarEventId).fold(
        ifLeft = { err ->
            logger.error("Failed to get room attendee status for master event $calendarEventId: ${err.message}", err)
            return
        },
        ifRight = { it }
    )

    val newStatus = when (attendeeStatus) {
        ResponseType.Accepted -> RoomBookingStatus.ACCEPTED
        ResponseType.Declined -> RoomBookingStatus.DECLINED
        else -> RoomBookingStatus.PENDING
    }

    database.getEvent(eventId).fold(
        ifLeft = {
            logger.warn("Master event $calendarEventId points at event $eventId which no longer exists")
        },
        ifRight = { event ->
            if (event.roomEmail == null) {
                // Teams-only master event, no room to track.
                return
            }
            if (event.roomStatus == newStatus) {
                return
            }
            // Targeted UPDATE of room_status only, so a concurrent admin edit is never overwritten.
            database.setRoomStatus(eventId, newStatus).fold(
                ifLeft = { err ->
                    logger.warn("Failed to update room status for event $eventId: $err")
                },
                ifRight = {
                    logger.info("Room status for event $eventId updated to $newStatus")
                }
            )
        }
    )
}

private val eventsResourceRegex =
    Regex("(?i)/events(?:\\('([^']+)'\\)|/([^/()]+))/?(?:\\?.*)?$")

/** Extracts the calendar event ID from a resource path like `users/{id}/events/{eventId}` */
private fun extractCalendarEventId(resource: String): String? {
    val normalized = if (resource.startsWith("/")) resource else "/$resource"
    val match = eventsResourceRegex.find(normalized) ?: return null
    return match.groups[1]?.value ?: match.groups[2]?.value
}
