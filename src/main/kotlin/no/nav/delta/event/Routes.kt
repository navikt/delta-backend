package no.nav.delta.event

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.accept
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
                val id = getUuidFromPath(call)?.toString() ?: return@get
                val result = database.getEvent(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(result)
            }
            post {
                val id = getUuidFromPath(call)?.toString() ?: return@post
                val email = call.receive(RegistrationEmail::class).email

                // Don't leak information about whether the user is already registered
                val successResponse = "Successfully registered"
                if (database.alreadyRegisteredForEvent(id, email)) return@post call.respond(successResponse)

                database.registerForEvent(id, email) ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(successResponse)
            }
            delete {
                val id = getUuidFromPath(call) ?: return@delete

                val otp = call.receive(ParticipationOtp::class).otp
                if (!database.unregisterFromEvent(id.toString(), otp.toString())) {
                    return@delete call.respond(HttpStatusCode.NotFound)
                }

                call.respond(HttpStatusCode.OK)
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
                    createEvent.location,
                )
                call.respond(result)
            }
        }
    }
}

suspend fun getUuidFromPath(call: ApplicationCall): UUID? {
    val id = call.parameters["id"] ?: run {
        call.respond(HttpStatusCode.BadRequest, "Missing id")
        return null
    }

    return try {
        UUID.fromString(id)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "Invalid UUID")
        null
    }
}
