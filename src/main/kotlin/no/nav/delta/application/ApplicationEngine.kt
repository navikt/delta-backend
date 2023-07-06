package no.nav.delta.application

import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import no.nav.delta.endpoints.eventApi
import no.nav.delta.plugins.*
import setupAuth

fun createApplicationEngine(
    env: Environment,
    //applicationState: ApplicationState,
    database: DatabaseInterface,
    jwkProvider: JwkProvider,
): ApplicationEngine = embeddedServer(Netty, env.applicationPort) {
    setupAuth(env, jwkProvider, env.jwtIssuer)
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        }
        json()
    }

    routing {
        swaggerUI(path = "openapi")
        get("/") {
            call.respond(Response(text = "Hello world!"))
        }
        eventApi(database)
    }
}

data class Response(val text: String)
