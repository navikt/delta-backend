package no.nav.delta.event

import arrow.core.getOrElse
import arrow.core.left
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import no.nav.delta.application.enforceM2mReadOnlyAccess
import no.nav.delta.application.respondProblem
import no.nav.delta.plugins.DatabaseInterface

fun Route.waitlistApi(database: DatabaseInterface) {
    val repository = WaitlistRepository(database)
    authenticate("jwt") {
        enforceM2mReadOnlyAccess()
        intercept(ApplicationCallPipeline.Call) {
            context.response.headers.append("Cache-Control", "no-store")
            if (context.principal<JWTPrincipal>()?.payload?.getClaim("idtyp")?.asString() == "app") {
                context.respondProblem(HttpStatusCode.Forbidden, "Ventelister krever et brukertoken.")
                finish()
            }
        }
        get("/user/waitlist") {
            call.respond(repository.mine(call.principalEmail()))
        }
        route("/user/event/{id}/waitlist") {
            get {
                val id = call.getUuidFromPath().getOrElse { return@get it.left().unwrapAndRespond(call) }
                call.respond(repository.state(id, call.principalEmail()))
            }
            post {
                val id = call.getUuidFromPath().getOrElse { return@post it.left().unwrapAndRespond(call) }
                call.respond(repository.join(id, Participant(call.principalEmail(), call.principalName())))
            }
            delete {
                val id = call.getUuidFromPath().getOrElse { return@delete it.left().unwrapAndRespond(call) }
                call.respond(repository.leave(id, call.principalEmail()))
            }
        }
        route("/admin/event/{id}/waitlist") {
            get {
                val id = call.getUuidFromPath().getOrElse { return@get it.left().unwrapAndRespond(call) }
                call.respond(repository.entries(id, call.principalEmail()))
            }
            delete {
                val id = call.getUuidFromPath().getOrElse { return@delete it.left().unwrapAndRespond(call) }
                repository.leave(id, call.receive<EmailToken>().email, actorEmail = call.principalEmail())
                call.respond(repository.entries(id, call.principalEmail()))
            }
        }
    }
}
