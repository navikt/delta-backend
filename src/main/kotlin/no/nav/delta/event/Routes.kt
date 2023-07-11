package no.nav.delta.event

import arrow.core.Either
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
import java.sql.Timestamp
import java.util.UUID
import kotlin.reflect.jvm.jvmName
import no.nav.delta.plugins.DatabaseInterface

fun Route.eventApi(database: DatabaseInterface) {
    route("/event") {
        accept(ContentType.Application.Json) { get { call.respond(database.getFutureEvents()) } }
        route("/{id}") {
            get {
                val id = getUuidFromPath(call)?.toString() ?: return@get
                database.getEvent(id).unwrapAndRespond(call)
            }
            post {
                val id = getUuidFromPath(call)?.toString() ?: return@post
                val email = call.receive(RegistrationEmail::class).email

                database.registerForEvent(id, email).unwrapAndRespond(call)
            }
            delete {
                val id = getUuidFromPath(call) ?: return@delete
                val otp = call.receive(ParticipationOtp::class).otp

                database.unregisterFromEvent(id.toString(), otp.toString()).unwrapAndRespond(call)
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
                    return@put call.respond(
                        HttpStatusCode.BadRequest, "Start time must be before end time")
                }

                call.respond(
                    database.addEvent(
                        ownerEmail,
                        createEvent.title,
                        createEvent.description,
                        Timestamp.from(createEvent.startTime.toInstant()),
                        Timestamp.from(createEvent.endTime.toInstant()),
                        createEvent.location,
                    ),
                )
            }
        }
    }
}

suspend fun Either<Any, Any>.unwrapAndRespond(call: ApplicationCall) {
    this.fold(
        {
            when (it) {
                is ExceptionWithDefaultResponse -> it.defaultResponse(call).invoke()
                is Exception -> throw it
                else -> throw RuntimeException("Unhandled exception: ${it::class.jvmName}")
            }
        },
        { call.respond(it) },
    )
}

suspend fun getUuidFromPath(call: ApplicationCall): UUID? {
    val id =
        call.parameters["id"]
            ?: run {
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
