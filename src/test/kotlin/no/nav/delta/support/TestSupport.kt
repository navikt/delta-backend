package no.nav.delta.support

import arrow.core.Either
import arrow.core.right
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.microsoft.graph.models.ResponseType
import com.microsoft.graph.models.Subscription
import io.ktor.server.application.Application
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import no.nav.delta.Environment
import no.nav.delta.application.mySetup
import no.nav.delta.application.installDeltaApiPlugins
import no.nav.delta.email.CloudClient
import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.plugins.DatabaseConfig
import no.nav.delta.plugins.DatabaseInterface
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

val testObjectMapper =
    jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

inline fun <reified T> readJson(json: String): T = testObjectMapper.readValue(json)

class TestDatabase private constructor(
    val database: DatabaseInterface,
    private val container: PostgreSQLContainer<*>,
) : AutoCloseable {
    override fun close() {
        container.stop()
    }

    companion object {
        fun create(): TestDatabase {
            val container = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
            container.start()
            val database =
                DatabaseConfig(
                    Environment(
                        dbJdbcUrl = container.jdbcUrl,
                        dbUsername = container.username,
                        dbPassword = container.password,
                    )
                )
            return TestDatabase(database, container)
        }
    }
}

fun localTestEnvironment() =
    Environment(
        faggruppeAdminGroupId = "test-admin-group",
        deltaEmailAddress = "delta@example.com",
        webhookClientState = "test-client-state",
    )

fun Application.installTestApi(
    env: Environment,
    database: DatabaseInterface,
    routes: Routing.() -> Unit,
) {
    installDeltaApiPlugins(env, NoopJwkProvider)
    routing(routes)
}

fun Application.installFullTestApplication(
    env: Environment,
    database: DatabaseInterface,
    cloudClient: CloudClient,
    startBackgroundTasks: Boolean = false,
) {
    mySetup(
        env = env,
        database = database,
        cloudClient = cloudClient,
        jwkProvider = NoopJwkProvider,
        startBackgroundTasks = startBackgroundTasks,
    )
}

object NoopJwkProvider : JwkProvider {
    override fun get(keyId: String): Jwk {
        error("JWK provider should not be called in local route tests")
    }
}

data class SentEmail(
    val subject: String,
    val body: String,
    val toRecipients: List<String>,
    val ccRecipients: List<String>,
    val bccRecipients: List<String>,
)

class RecordingCloudClient : CloudClient {
    val sentEmails = mutableListOf<SentEmail>()
    val createdCalendarEventIds = mutableListOf<String>()
    val updatedCalendarEventIds = mutableListOf<String>()
    val deletedCalendarEventIds = mutableListOf<String>()
    val attendeeStatuses = mutableMapOf<String, Either<Throwable, ResponseType?>>()
    val userDisplayNames = mutableMapOf<String, String?>()

    override fun sendEmail(
        subject: String,
        body: String,
        toRecipients: List<String>,
        ccRecipients: List<String>,
        bccRecipients: List<String>,
    ) {
        sentEmails +=
            SentEmail(
                subject = subject,
                body = body,
                toRecipients = toRecipients,
                ccRecipients = ccRecipients,
                bccRecipients = bccRecipients,
            )
    }

    override fun createEvent(event: Event, participant: Participant): Either<Throwable, String> {
        val calendarEventId = "calendar-${createdCalendarEventIds.size + 1}"
        createdCalendarEventIds += calendarEventId
        return calendarEventId.right()
    }

    override fun updateEvent(
        calendarEventId: String,
        event: Event,
        participant: Participant,
    ): Either<Throwable, Unit> {
        updatedCalendarEventIds += calendarEventId
        return Unit.right()
    }

    override fun deleteEvent(calendarEventId: String): Either<Throwable, Unit> {
        deletedCalendarEventIds += calendarEventId
        return Unit.right()
    }

    override fun batchUpdateOrCreateEvents(
        event: Event,
        participantsWithCalendarIds: List<Pair<Participant, String?>>,
    ): Map<Participant, Either<Throwable, String?>> =
        participantsWithCalendarIds.associate { (participant, calendarEventId) ->
            if (calendarEventId == null) {
                val createdId = "calendar-${createdCalendarEventIds.size + 1}"
                createdCalendarEventIds += createdId
                participant to createdId.right()
            } else {
                updatedCalendarEventIds += calendarEventId
                participant to null.right()
            }
        }

    override fun getUserDisplayName(email: String): String? = userDisplayNames[email]

    override fun createSubscription(
        notificationUrl: String,
        resource: String,
        clientState: String,
        expirationDateTime: OffsetDateTime,
    ): Either<Throwable, Subscription> =
        Subscription().apply {
            id = UUID.randomUUID().toString()
            this.expirationDateTime = expirationDateTime
        }.right()

    override fun renewSubscription(
        subscriptionId: String,
        newExpiration: OffsetDateTime,
    ): Either<Throwable, Unit> = Unit.right()

    override fun deleteSubscription(subscriptionId: String): Either<Throwable, Unit> = Unit.right()

    override fun getEventAttendeeStatus(calendarEventId: String): Either<Throwable, ResponseType?> =
        attendeeStatuses[calendarEventId] ?: ResponseType.Accepted.right()
}

fun waitUntil(
    timeout: Duration = 3.seconds,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
        if (condition()) {
            return
        }
        Thread.sleep(25)
    }
    check(condition()) { "Condition was not met within $timeout" }
}

suspend fun waitUntilSuspending(
    timeout: Duration = 3.seconds,
    interval: Duration = 25.milliseconds,
    condition: suspend () -> Boolean,
) {
    withTimeout(timeout) {
        while (!condition()) {
            delay(interval)
        }
    }
}
