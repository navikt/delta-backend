package no.nav.delta.event

import arrow.core.*
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
import no.nav.delta.email.sendCancellationNotification
import no.nav.delta.email.sendJoinConfirmation
import no.nav.delta.email.sendUpdateNotification
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.EmailClient

fun Route.eventApi(database: DatabaseInterface, emailClient: EmailClient) {
    authenticate("jwt") {
        route("/event") {
            get {
                val email = call.principalEmail()

                val onlyFuture = call.parameters["onlyFuture"]?.toBoolean() ?: false
                val onlyPast = call.parameters["onlyPast"]?.toBoolean() ?: false

                val onlyMine = call.parameters["onlyMine"]?.toBoolean() ?: false
                val hostedBy = if (onlyMine) email.some() else none()

                val onlyJoined = call.parameters["onlyJoined"]?.toBoolean() ?: false
                val joinedBy = if (onlyJoined) email.some() else none()

                val onlyPublic = !onlyMine && !onlyJoined

                call.respond(
                    database.getEvents(onlyFuture, onlyPast, onlyPublic, hostedBy, joinedBy))
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
                                database.getHosts(id.toString()).map {
                                    EventWithParticipants(
                                        event = event, hosts = it, participants = participants)
                                }
                            }
                        }
                        .unwrapAndRespond(call)
                }
            }
        }
        route("/admin/event") {
            put {
                val createEvent = call.receive(CreateEvent::class)
                val email = call.principalEmail()

                if (createEvent.startTime.isAfter(createEvent.endTime)) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest, "Start time must be before end time")
                }

                val event =
                    database.addEvent(
                        createEvent,
                    )

                database
                    .registerForEvent(
                        event.id.toString(),
                        email,
                        call.principalName(),
                        ParticipantType.HOST,
                    )
                    .map { event }
                    .unwrapAndRespond(call)
            }
            route("/{id}") {
                delete {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }

                    database
                        .deleteEvent(event.id.toString())
                        .map {
                            database.getParticipants(event.id.toString()).map { participants ->
                                emailClient.sendCancellationNotification(event, participants)
                            }
                            "Success"
                        }
                        .unwrapAndRespond(call)
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
                            title = changedEvent.title,
                            description = changedEvent.description,
                            startTime = changedEvent.startTime,
                            endTime = changedEvent.endTime,
                            location = changedEvent.location,
                            public = changedEvent.public,
                            participantLimit = changedEvent.participantLimit,
                            signupDeadline = changedEvent.signupDeadline,
                        )
                    database
                        .updateEvent(newEvent)
                        .map { event ->
                            database.getParticipants(event.id.toString()).flatMap { participants ->
                                database.getHosts(event.id.toString()).map { hosts ->
                                    emailClient.sendUpdateNotification(event, participants, hosts)
                                }
                            }
                            event
                        }
                        .unwrapAndRespond(call)
                }
                delete("/participant") {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@delete it.left().unwrapAndRespond(call)
                        }
                    val participantEmail = call.receive<EmailToken>().email

                    database
                        .unregisterFromEvent(event.id.toString(), participantEmail)
                        .map { "Success" }
                        .unwrapAndRespond(call)
                }
                post("/participant") {
                    val event =
                        call.getEventWithPrivilege(database).getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    val changeParticipant = call.receive<ChangeParticipant>()

                    database
                        .changeParticipant(event.id.toString(), changeParticipant)
                        .map { "Success" }
                        .unwrapAndRespond(call)
                }
            }
        }
        route("/user/event") {
            route("/{id}") {
                post {
                    val id =
                        call.getUuidFromPath().getOrElse {
                            return@post it.left().unwrapAndRespond(call)
                        }
                    val email = call.principalEmail()
                    val name = call.principalName()

                    database
                        .registerForEvent(id.toString(), email, name)
                        .map {
                            database.getEvent(id.toString()).flatMap { event ->
                                database.getHosts(id.toString()).map { hosts ->
                                    emailClient.sendJoinConfirmation(
                                        event, Participant(email, name), hosts)
                                }
                            }
                            "Success"
                        }
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
                        .map { "Success" }
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
): Either<EventAccessException, Event> =
    getUuidFromPath()
        .map { id -> Pair(id, principalEmail()) }
        .flatMap {
            val (id, email) = it
            database.getEvent(id.toString()).flatMap { event ->
                database.getHosts(id.toString()).flatMap { hosts ->
                    if (hosts.find { p -> p.email == email } == null) {
                        ForbiddenException.left()
                    } else {
                        event.right()
                    }
                }
            }
        }

fun ApplicationCall.principalEmail() =
    principal<JWTPrincipal>()!!["preferred_username"]!!.lowercase()

fun ApplicationCall.principalName() = principal<JWTPrincipal>()!!["name"]!!
