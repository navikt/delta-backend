package no.nav.delta

import arrow.core.right
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.microsoft.graph.models.ResponseType
import com.microsoft.graph.models.Subscription
import java.sql.Connection
import java.time.OffsetDateTime
import no.nav.delta.email.CloudClient
import no.nav.delta.event.Event
import no.nav.delta.event.Participant
import no.nav.delta.plugins.DatabaseInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun `createApplicationDependencies uses injected factories`() {
        val env = Environment(dbJdbcUrl = "jdbc:test", dbUsername = "user", dbPassword = "pass")
        val database = StubDatabase()
        val cloudClient = StubCloudClient()
        val jwkProvider = StubJwkProvider()

        val dependencies =
            createApplicationDependencies(
                environment = env,
                databaseFactory = {
                    assertEquals(env, it)
                    database
                },
                cloudClientFactory = {
                    assertEquals(env, it)
                    cloudClient
                },
                jwkProviderFactory = {
                    assertEquals(env, it)
                    jwkProvider
                },
            )

        assertEquals(database, dependencies.database)
        assertEquals(cloudClient, dependencies.cloudClient)
        assertEquals(jwkProvider, dependencies.jwkProvider)
    }

    private class StubDatabase : DatabaseInterface {
        override val connection: Connection
            get() = error("Not used in this test")
    }

    private class StubJwkProvider : JwkProvider {
        override fun get(keyId: String): Jwk = error("Not used in this test")
    }

    private class StubCloudClient : CloudClient {
        override fun sendEmail(
            subject: String,
            body: String,
            toRecipients: List<String>,
            ccRecipients: List<String>,
            bccRecipients: List<String>,
        ) = Unit

        override fun createEvent(event: Event, participant: Participant) = "calendar".right()

        override fun updateEvent(calendarEventId: String, event: Event, participant: Participant) = Unit.right()

        override fun deleteEvent(calendarEventId: String) = Unit.right()

        override fun batchUpdateOrCreateEvents(
            event: Event,
            participantsWithCalendarIds: List<Pair<Participant, String?>>,
        ) = emptyMap<Participant, arrow.core.Either<Throwable, String?>>()

        override fun getUserDisplayName(email: String): String? = null

        override fun createSubscription(
            notificationUrl: String,
            resource: String,
            clientState: String,
            expirationDateTime: OffsetDateTime,
        ) = Subscription().right()

        override fun renewSubscription(subscriptionId: String, newExpiration: OffsetDateTime) = Unit.right()

        override fun deleteSubscription(subscriptionId: String) = Unit.right()

        override fun getEventAttendeeStatus(calendarEventId: String) = ResponseType.Accepted.right()
    }
}
