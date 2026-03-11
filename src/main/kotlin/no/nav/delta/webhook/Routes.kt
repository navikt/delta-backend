package no.nav.delta.webhook

import com.microsoft.graph.models.ResponseType
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
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
        // MS Graph sends a GET with validationToken when creating/renewing a subscription.
        // We must echo it back as plain text within 10 seconds.
        get {
            val validationToken = call.parameters["validationToken"]
            if (validationToken != null) {
                call.respondText(validationToken, ContentType.Text.Plain, HttpStatusCode.OK)
            } else {
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        // MS Graph sends change notifications here as POST requests.
        post {
            // Acknowledge quickly — MS Graph requires a response within 10 seconds.
            call.respond(HttpStatusCode.Accepted)

            val payload = try {
                call.receive<GraphNotificationPayload>()
            } catch (e: Exception) {
                logger.warn("Failed to parse notification payload: ${e.message}")
                return@post
            }

            for (notification in payload.value) {
                if (notification.clientState != env.webhookClientState) {
                    logger.warn("Received notification with invalid clientState, ignoring")
                    continue
                }
                if (notification.changeType != "updated") {
                    continue
                }
                processNotification(notification, database, cloudClient)
            }
        }
    }
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

    if (attendeeStatus == ResponseType.DECLINED) {
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

/** Extracts the calendar event ID from a resource path like `users/{id}/events/{eventId}` */
private fun extractCalendarEventId(resource: String): String? {
    val segments = resource.trimStart('/').split("/")
    val eventsIndex = segments.indexOfLast { it == "events" }
    return if (eventsIndex >= 0 && eventsIndex + 1 < segments.size) {
        segments[eventsIndex + 1]
    } else {
        null
    }
}
