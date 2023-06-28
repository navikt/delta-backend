package no.nav.delta.application

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.serialization.jackson.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.delta.Environment
import no.nav.delta.plugins.*

fun createApplicationEngine(
    env: Environment,
    //applicationState: ApplicationState,
    database: DatabaseInterface,
): ApplicationEngine = embeddedServer(Netty, env.applicationPort) {
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
        json()
    }

    routing {
        swaggerUI(path = "openapi")
        get("/") {
            call.respond(Response(text = "Hello world!"))
        }
    }
}

data class Response(val text: String)
