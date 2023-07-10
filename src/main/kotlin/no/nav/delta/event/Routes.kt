package no.nav.delta.event

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.accept
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import no.nav.delta.plugins.DatabaseInterface
import java.sql.Timestamp
import java.util.UUID

fun Route.eventApi(database: DatabaseInterface) {
    route("/event") {
        accept(ContentType.Application.Json) {
            get {
                call.respond(database.getFutureEvents())
            }
        }
        route("/{id}") {
            get {
                // Get id from path, return 400 if missing
                val id = call.parameters["id"]
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing id")
                    return@get
                }

                // Check if id is a valid UUID
                try {
                    UUID.fromString(id)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid UUID")
                    return@get
                }

                // Get event from database, return 404 if not found
                val result = database.getEvent(id)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                call.respond(result)
            }
        }
    }

    authenticate("jwt") {
        route("/admin/event") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val ownerEmail = principal["preferred_username"]!!.lowercase()

                call.respond(database.getEventsByOwner(ownerEmail))
            }

            put {
                val createEvent = call.receive(CreateEvent::class)

                val principal = call.principal<JWTPrincipal>()!!
                val ownerEmail = principal["preferred_username"]!!.lowercase()

                if (createEvent.startTime.after(createEvent.endTime)) {
                    call.respond(HttpStatusCode.BadRequest, "Start time must be before end time")
                    return@put
                }

                val result = database.addEvent(
                    ownerEmail,
                    createEvent.title,
                    createEvent.description,
                    Timestamp.from(createEvent.startTime.toInstant()),
                    Timestamp.from(createEvent.endTime.toInstant()),
                )
                call.respond(result)
            }
        }
    }
}
