package no.nav.delta.event

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.core.some
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
import java.util.UUID
import kotlin.reflect.jvm.jvmName
import no.nav.delta.plugins.DatabaseInterface

fun Route.eventApi(database: DatabaseInterface) {
    route("/event") {
        accept(ContentType.Application.Json) {
            get { call.respond(database.getEvents(onlyFuture = true, onlyPublic = true)) }
        }
        route("/{id}") {
            get {
                val id =
                    call.getUuidFromPath().getOrElse {
                        return@get it.left().unwrapAndRespond(call)
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
        }
    }

    authenticate("jwt") {
        route("/admin/event") {
            get {
                val ownerEmail = call.principalEmail()

                call.respond(database.getEvents(byOwner = ownerEmail.some()))
            }

            put {
                val createEvent = call.receive(CreateEvent::class)
                val ownerEmail = call.principalEmail()

                if (createEvent.startTime.after(createEvent.endTime)) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest, "Start time must be before end time")
                }

                call.respond(
                    database.addEvent(
                        ownerEmail,
                        createEvent,
                    ),
                )
            }
            route("/{id}") {
                delete {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }

                    database
                        .deleteEvent(event.id.toString())
                        .flatMap { "Success".right() }
                        .unwrapAndRespond(call)
                }
                patch {
                    val originalEvent =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@patch it.left().unwrapAndRespond(call)
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
                            public = changedEvent.public ?: originalEvent.public,
                        )
                    database.updateEvent(newEvent).unwrapAndRespond(call)
                }
                post {
                    val originalEvent =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@post it.left().unwrapAndRespond(call)
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
                            public = changedEvent.public,
                        )
                    database.updateEvent(newEvent).unwrapAndRespond(call)
                }
                delete("/participant") {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }
                    val participantEmail = call.receive<Participant>().email

                    database
                        .unregisterFromEvent(event.id.toString(), participantEmail)
                        .flatMap { "Success".right() }
                        .unwrapAndRespond(call)
                }
            }
        }

        route("/user/event") {
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val email = principal["preferred_username"]!!.lowercase()

                call.respond(database.getEvents(joinedBy = email.some()))
            }
            route("/{id}") {
                post {
                    val id =
                        call.getUuidFromPath().getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    val email = call.principalEmail()

                    database
                        .registerForEvent(id.toString(), email)
                        .flatMap { "Success".right() }
                        .unwrapAndRespond(call)
                }
                delete {
                    val id =
                        call.getUuidFromPath().getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }
                    val email = call.principalEmail()

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

fun ApplicationCall.getUuidFromPath(): Either<IdException, UUID> {
    val id = parameters["id"] ?: return MissingIdException.left()

    return runCatching { UUID.fromString(id) }
        .fold(
            { it.right() },
            { InvalidIdException.left() },
        )
}

fun ApplicationCall.getEventWithPrivilege(
    database: DatabaseInterface
): Either<EventAccessException, Event> {
    val id =
        getUuidFromPath().getOrElse {
            return it.left()
        }
    val email = principalEmail()

    val event =
        database.getEvent(id.toString()).getOrElse {
            return it.left()
        }

    if (event.ownerEmail != email) {
        return ForbiddenException.left()
    }

    return event.right()
}

fun ApplicationCall.principalEmail() =
    principal<JWTPrincipal>()!!["preferred_username"]!!.lowercase()
