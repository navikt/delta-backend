package no.nav.delta.plugins

import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.application.*

fun Application.configureRouting() {
    routing {
        data class Response(val text: String)
        get("/") {
            call.respond(Response(text = "Hello world!"))
        }
    }
}
