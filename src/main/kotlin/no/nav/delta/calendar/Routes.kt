package no.nav.delta.calendar

import arrow.core.getOrElse
import no.nav.delta.application.respondProblem
import arrow.core.left
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.delta.event.EmailToken
import no.nav.delta.event.InviteeRequest
import no.nav.delta.event.InviteMode
import no.nav.delta.event.getEventWithPrivilege
import no.nav.delta.event.getFullEvent
import no.nav.delta.event.principalEmail
import no.nav.delta.event.unwrapAndRespond
import no.nav.delta.plugins.DatabaseInterface

fun Route.sharedCalendarApi(database: DatabaseInterface) {
    val repository = SharedCalendarRepository(database)
    authenticate("jwt") {
        route("/admin/event/{id}") {
            post("/invitations") {
                val event = call.getEventWithPrivilege(database).getOrElse {
                    return@post it.left().unwrapAndRespond(call)
                }
                if (event.inviteMode != InviteMode.SHARED) {
                    return@post call.respondProblem(HttpStatusCode.BadRequest, "Invitasjoner krever en delt kalender.")
                }
                val invitees = call.receive<List<InviteeRequest>>()
                sharedMutation(call) {
                    repository.invite(event.id, invitees, call.principalEmail())
                    database.getFullEvent(event.id.toString()).unwrapAndRespond(call)
                }
            }
            delete("/invitations") {
                val event = call.getEventWithPrivilege(database).getOrElse {
                    return@delete it.left().unwrapAndRespond(call)
                }
                if (event.inviteMode != InviteMode.SHARED) {
                    return@delete call.respondProblem(HttpStatusCode.BadRequest, "Invitasjoner krever en delt kalender.")
                }
                val email = call.receive<EmailToken>().email
                sharedMutation(call) {
                    repository.remove(event.id, email)
                    database.getFullEvent(event.id.toString()).unwrapAndRespond(call)
                }
            }
            post("/calendar/retry") {
                val event = call.getEventWithPrivilege(database).getOrElse {
                    return@post it.left().unwrapAndRespond(call)
                }
                if (event.inviteMode != InviteMode.SHARED) {
                    return@post call.respondProblem(HttpStatusCode.BadRequest, "Nytt forsøk krever en delt kalender.")
                }
                sharedMutation(call) {
                    repository.retry(event.id)
                    database.getFullEvent(event.id.toString()).unwrapAndRespond(call)
                }
            }
        }
    }
}

internal suspend fun sharedMutation(call: ApplicationCall, action: suspend () -> Unit) {
    try {
        action()
    } catch (error: SharedCalendarValidationException) {
        call.respondProblem(HttpStatusCode.fromValue(error.statusCode), error.message ?: "Ugyldig endring i kalenderen.")
    }
}
