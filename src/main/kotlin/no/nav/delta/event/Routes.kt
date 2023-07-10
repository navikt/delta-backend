package no.nav.delta.event

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.delta.plugins.DatabaseInterface
import java.sql.Timestamp


fun Route.eventApi(database: DatabaseInterface) {
    route("/event") {
        accept(ContentType.Application.Json) {
            get {
                call.respond(database.getEvents())
            }
        }
        route("/{id}") {
            get {
                val id = call.parameters["id"]
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                val result = database.getEvent(id.toInt())
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
            put {
                val createEvent = call.receive(CreateEvent::class)

                val principal = call.principal<JWTPrincipal>()!!
                val ownerEmail = principal["preferred_username"]!!.lowercase()
                println(createEvent.startTime)
                println(createEvent.startTime.toInstant())

                database.addEvent(
                    ownerEmail,
                    createEvent.title,
                    createEvent.description,
                    Timestamp.from(createEvent.startTime.toInstant()),
                    Timestamp.from(createEvent.endTime.toInstant()),
                )
                call.respond("success")
            }
        }
    }
}