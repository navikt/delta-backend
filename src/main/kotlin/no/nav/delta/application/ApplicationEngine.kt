package no.nav.delta.application

import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer
import com.fasterxml.jackson.module.kotlin.addDeserializer
import com.fasterxml.jackson.module.kotlin.addSerializer
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.email.sendUpdateOrCreationNotification
import no.nav.delta.event.eventApi
import no.nav.delta.event.getAllParticipantsAndCalendarEventIds
import no.nav.delta.event.toEvent
import no.nav.delta.plugins.DatabaseInterface

fun createApplicationEngine(
    env: Environment,
    // applicationState: ApplicationState,
    database: DatabaseInterface,
    cloudClient: CloudClient,
    jwkProvider: JwkProvider,
): ApplicationEngine =
    embeddedServer(
        Netty, env.applicationPort, module = { mySetup(env, database, cloudClient, jwkProvider) })

fun Application.mySetup(
    env: Environment,
    database: DatabaseInterface,
    cloudClient: CloudClient,
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

    // Migrate to individual calendar events
    database.connection.apply {
        val statement =
            prepareStatement(
                """
SELECT * FROM event WHERE calendar_event_id IS NOT NULL FOR UPDATE;
""")
        statement.executeQuery().use { rs ->
            while (rs.next()) {
                rs.toEvent().let { event ->
                    val calendarEventId = rs.getString("calendar_event_id")!!
                    cloudClient.deleteEvent(calendarEventId)
                    database.getAllParticipantsAndCalendarEventIds(event.id.toString()).map { pairs
                        ->
                        pairs.forEach { (participant, _) ->
                            cloudClient.sendUpdateOrCreationNotification(
                                event = event,
                                database = database,
                                participant = participant,
                                calendarEventId = null,
                            )
                        }
                    }
                }
            }
        }
        prepareStatement("UPDATE event SET calendar_event_id = NULL").executeUpdate()
        commit()
    }

    routing {
        swaggerUI(path = "openapi")
        eventApi(database, cloudClient)
        get("/internal/is_alive") {
            if (alivenessCheck()) {
                call.respondText("I'm alive! :)")
            } else {
                call.respondText("I'm dead x_x", status = HttpStatusCode.InternalServerError)
            }
        }
        get("/internal/is_ready") {
            if (readinessCheck()) {
                call.respondText("I'm ready! :)")
            } else {
                call.respondText(
                    "Please wait! I'm not ready :(", status = HttpStatusCode.InternalServerError)
            }
        }
    }
}

fun alivenessCheck(): Boolean {
    return true
}

fun readinessCheck(): Boolean {
    return true
}
