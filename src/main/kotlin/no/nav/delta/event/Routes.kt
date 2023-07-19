package no.nav.delta.event

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.right
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.sql.Timestamp
import java.util.UUID
import kotlin.reflect.jvm.jvmName
import no.nav.delta.plugins.DatabaseInterface

fun Route.eventApi(database: DatabaseInterface) {
    route("/event") {
        accept(ContentType.Application.Json) { get { call.respond(database.getFutureEvents()) } }
        route("/{id}") {
            get {
                val id =
                    getUuidFromPath(call).getOrElse {
                        return@get it(call)
                    }

                database
                    .getEvent(id.toString())
                    .flatMap { event ->
                        database.getParticipants(id.toString()).flatMap { participants ->
                            EventWithParticipants(event, participants).right()
                        }
                    }
                    .unwrapAndRespond(call)
            }
            authenticate("jwt") {
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
            route("/{id}") {
                delete {
                    val principal = call.principal<JWTPrincipal>()!!
                    val email = principal["preferred_username"]!!.lowercase()
                    val id =
                        getUuidFromPath(call).getOrElse {
                            return@delete it(call)
                        }
                    val event =
                        database.getEvent(id.toString()).getOrElse {
                            return@delete it.defaultResponse(call)
                        }

                    if (event.ownerEmail != email) {
                        return@delete call.respond(HttpStatusCode.Forbidden, "No access")
                    }

                    database
                        .deleteEvent(id.toString())
                        .flatMap { "Success".right() }
                        .unwrapAndRespond(call)
                }
                patch {
                    val principal = call.principal<JWTPrincipal>()!!
                    val email = principal["preferred_username"]!!.lowercase()
                    val id =
                        getUuidFromPath(call).getOrElse {
                            return@patch it(call)
                        }

                    val originalEvent =
                        database.getEvent(id.toString()).getOrElse {
                            return@patch it.defaultResponse(call)
                        }

                    if (originalEvent.ownerEmail != email) {
                        return@patch call.respond(HttpStatusCode.Forbidden, "No access")
                    }
                    val changedEvent = call.receive<ChangeEvent>()

                    val newEvent =
                        Event(
                            id = originalEvent.id,
                            ownerEmail = originalEvent.ownerEmail,
                            title = changedEvent.title ?: originalEvent.title,
                            description = changedEvent.description ?: originalEvent.description,
                            startTime = changedEvent.startTime ?: originalEvent.startTime,
                            endTime = changedEvent.endTime ?: originalEvent.endTime,
                            location = changedEvent.location ?: originalEvent.location,
                        )
                    database.updateEvent(newEvent).unwrapAndRespond(call)
                }
                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val email = principal["preferred_username"]!!.lowercase()
                    val id =
                        getUuidFromPath(call).getOrElse {
                            return@post it(call)
                        }

                    val originalEvent =
                        database.getEvent(id.toString()).getOrElse {
                            return@post it.defaultResponse(call)
                        }

                    if (originalEvent.ownerEmail != email) {
                        return@post call.respond(HttpStatusCode.Forbidden, "No access")
                    }
                    val changedEvent = call.receive<CreateEvent>()

                    val newEvent =
                        Event(
                            id = originalEvent.id,
                            ownerEmail = originalEvent.ownerEmail,
                            title = changedEvent.title,
                            description = changedEvent.description,
                            startTime = changedEvent.startTime,
                            endTime = changedEvent.endTime,
                            location = changedEvent.location,
                        )
                    database.updateEvent(newEvent).unwrapAndRespond(call)
                }
            }
        }

        route("/user/event") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val email = principal["preferred_username"]!!.lowercase()

                call.respond(database.getJoinedEvents(email))
            }
            route("/{id}") {
                post {
                    val id =
                        getUuidFromPath(call).getOrElse {
                            return@post it(call)
                        }
                    val principal = call.principal<JWTPrincipal>()!!
                    val email = principal["preferred_username"]!!.lowercase()

                    database
                        .registerForEvent(id.toString(), email)
                        .flatMap { "Success".right() }
                        .unwrapAndRespond(call)
                }
                delete {
                    val id =
                        getUuidFromPath(call).getOrElse {
                            return@delete it(call)
                        }
                    val principal = call.principal<JWTPrincipal>()!!
                    val email = principal["preferred_username"]!!.lowercase()

                    database
                        .unregisterFromEvent(id.toString(), email)
                        .flatMap { "Success".right() }
                        .unwrapAndRespond(call)
                }
            }
        }
    }
}

suspend fun Either<Any, Any>.unwrapAndRespond(call: ApplicationCall) {
    this.fold(
        {
            when (it) {
                is ExceptionWithDefaultResponse -> it.defaultResponse(call)
                // Unknown exceptions, this *should* never happen
                is Exception -> throw it
                else -> throw RuntimeException("Unhandled exception: ${it::class.jvmName}")
            }
        },
        { call.respond(it) },
    )
}

suspend fun getUuidFromPath(
    call: ApplicationCall
): Either<suspend (ApplicationCall) -> Unit, UUID> {
    val id =
        call.parameters["id"]
            ?: return Either.Left { c -> c.respond(HttpStatusCode.BadRequest, "Missing id") }

    return runCatching { UUID.fromString(id) }
        .fold(
            { it.right() },
            { Either.Left { c -> c.respond(HttpStatusCode.BadRequest, "Invalid id") } },
        )
}
