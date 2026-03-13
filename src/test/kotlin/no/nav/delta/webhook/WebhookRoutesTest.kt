package no.nav.delta.webhook

import arrow.core.right
import com.microsoft.graph.models.ResponseType
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.event.CreateEvent
import no.nav.delta.event.ParticipantType
import no.nav.delta.event.addEvent
import no.nav.delta.event.getParticipants
import no.nav.delta.event.registerForEvent
import no.nav.delta.event.setCalendarEventId
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installTestApi
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.waitUntil
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookRoutesTest {
    private lateinit var testDatabase: TestDatabase
    private lateinit var database: DatabaseInterface
    private lateinit var cloudClient: RecordingCloudClient

    @BeforeAll
    fun setup() {
        testDatabase = TestDatabase.create()
        database = testDatabase.database
    }

    @BeforeEach
    fun resetCloudClient() {
        cloudClient = RecordingCloudClient()
    }

    @AfterAll
    fun tearDown() {
        testDatabase.close()
    }

    @Test
    fun `get validation token echoes token`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                webhookApi(database, cloudClient, env)
            }
        }

        val response = client.get("/webhook/calendar?validationToken=hello")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
    }

    @Test
    fun `post validation token echoes token`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                webhookApi(database, cloudClient, env)
            }
        }

        val response = client.post("/webhook/calendar?validationToken=world")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("world", response.bodyAsText())
    }

    @Test
    fun `invalid payload is accepted and ignored`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                webhookApi(database, cloudClient, env)
            }
        }

        val response =
            client.post("/webhook/calendar") {
                contentType(ContentType.Application.Json)
                setBody("""{"value": "not-a-list"}""")
            }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `declined attendee notification unregisters participant and deletes calendar event`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                webhookApi(database, cloudClient, env)
            }
        }

        val event = database.addEvent(futureEvent("webhook-${UUID.randomUUID()}"))
        database.registerForEvent(event.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        database.registerForEvent(event.id.toString(), "person@example.com", "Participant User")
        database.setCalendarEventId(event.id.toString(), "person@example.com", "calendar-42")
        cloudClient.attendeeStatuses["calendar-42"] = ResponseType.Declined.right()

        val response =
            client.post("/webhook/calendar") {
                contentType(ContentType.Application.Json)
                setBody(notificationPayload(clientState = env.webhookClientState, resource = "users/delta@example.com/events/calendar-42"))
            }

        assertEquals(HttpStatusCode.Accepted, response.status)

        waitUntil {
            database.getParticipants(event.id.toString()).getOrNull()!!.none { it.email == "person@example.com" } &&
                cloudClient.deletedCalendarEventIds.contains("calendar-42")
        }
    }

    @Test
    fun `notification with wrong client state is ignored`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                webhookApi(database, cloudClient, env)
            }
        }

        val event = database.addEvent(futureEvent("ignored-${UUID.randomUUID()}"))
        database.registerForEvent(event.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        database.registerForEvent(event.id.toString(), "person2@example.com", "Participant User")
        database.setCalendarEventId(event.id.toString(), "person2@example.com", "calendar-84")
        cloudClient.attendeeStatuses["calendar-84"] = ResponseType.Declined.right()

        val response =
            client.post("/webhook/calendar") {
                contentType(ContentType.Application.Json)
                setBody(notificationPayload(clientState = "wrong-state", resource = "users/delta@example.com/events/calendar-84"))
            }

        assertEquals(HttpStatusCode.Accepted, response.status)

        waitUntil {
            database.getParticipants(event.id.toString()).getOrNull()!!.any { it.email == "person2@example.com" }
        }
        assertFalse(cloudClient.deletedCalendarEventIds.contains("calendar-84"))
        assertTrue(database.getParticipants(event.id.toString()).getOrNull()!!.any { it.email == "person2@example.com" })
    }

    private fun notificationPayload(clientState: String, resource: String) =
        """
        {
          "value": [
            {
              "clientState": "$clientState",
              "resource": "$resource",
              "changeType": "updated",
              "subscriptionId": "subscription-1"
            }
          ]
        }
        """.trimIndent()

    private fun futureEvent(title: String) =
        CreateEvent(
            title = title,
            description = "desc",
            startTime = LocalDateTime.now().plusDays(2),
            endTime = LocalDateTime.now().plusDays(2).plusHours(1),
            location = "room-3",
            public = true,
            participantLimit = 10,
            signupDeadline = LocalDateTime.now().plusDays(1),
            sendNotificationEmail = false,
        )
}
