package no.nav.delta.application

import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer
import com.fasterxml.jackson.module.kotlin.addDeserializer
import com.fasterxml.jackson.module.kotlin.addSerializer
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.serialization.jackson.jackson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.routing.routing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import no.nav.delta.Environment
import no.nav.delta.event.eventApi
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.EmailClient

fun createApplicationEngine(
    env: Environment,
    // applicationState: ApplicationState,
    database: DatabaseInterface,
    emailClient: EmailClient,
    jwkProvider: JwkProvider,
): ApplicationEngine =
    embeddedServer(
        Netty, env.applicationPort, module = { mySetup(env, database, emailClient, jwkProvider) })

fun Application.mySetup(
    env: Environment,
    database: DatabaseInterface,
    emailClient: EmailClient,
    jwkProvider: JwkProvider
) {
    setupAuth(env, jwkProvider, env.jwtIssuer)
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
            registerKotlinModule()

            val javaTimeModule = JavaTimeModule()
            javaTimeModule.addSerializer(
                LocalDateTime::class, LocalDateTimeSerializer(DateTimeFormatter.ISO_DATE_TIME))
            javaTimeModule.addDeserializer(
                LocalDateTime::class,
                LocalDateTimeDeserializer(DateTimeFormatter.ISO_DATE_TIME),
            )
            registerModule(javaTimeModule)
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        }
        json()
    }

    routing {
        swaggerUI(path = "openapi")
        eventApi(database, emailClient)
    }
}
