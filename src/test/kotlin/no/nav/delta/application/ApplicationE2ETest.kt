package no.nav.delta.application

import arrow.core.right
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.event.FullEvent
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installFullTestApplication
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import no.nav.delta.support.waitUntilSuspending
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationE2ETest {
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
    fun `full application serves health endpoints and event lifecycle through public api`() = testApplication {
        val env = localTestEnvironment()
        application {
            installFullTestApplication(env, database, cloudClient)
        }

        assertEquals("I'm alive! :)", client.get("/internal/is_alive").bodyAsText())
        assertEquals("I'm ready! :)", client.get("/internal/is_ready").bodyAsText())
        assertEquals("Webhook subscription healthy", client.get("/internal/webhook_subscription_ready").bodyAsText())

        val categoryResponse =
            client.put("/category") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"e2e-${UUID.randomUUID().toString().take(8)}"}""")
            }
        assertEquals(HttpStatusCode.OK, categoryResponse.status)
        val categoryId = readJson<Map<String, Any>>(categoryResponse.bodyAsText())["id"].toString().toInt()

        val createResponse =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson("e2e-${UUID.randomUUID()}"))
            }
        assertEquals(HttpStatusCode.OK, createResponse.status)
        val created = readJson<FullEvent>(createResponse.bodyAsText())

        val setCategoryResponse =
            client.post("/admin/event/${created.event.id}/category") {
                contentType(ContentType.Application.Json)
                setBody("[$categoryId]")
            }
        assertEquals(HttpStatusCode.OK, setCategoryResponse.status)

        val listResponse = client.get("/event?onlyMine=true&categories=$categoryId")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listed = readJson<List<FullEvent>>(listResponse.bodyAsText())
        assertTrue(listed.any { it.event.id == created.event.id })

        val updateResponse =
            client.post("/admin/event/${created.event.id}") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson("updated-${UUID.randomUUID()}", description = "updated via e2e"))
            }
        assertEquals(HttpStatusCode.OK, updateResponse.status)

        val fetched = readJson<FullEvent>(client.get("/event/${created.event.id}").bodyAsText())
        assertEquals("updated via e2e", fetched.event.description)

        val deleteResponse = client.delete("/admin/event/${created.event.id}")
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val missingResponse = client.get("/event/${created.event.id}")
        assertEquals(HttpStatusCode.NotFound, missingResponse.status)
    }

    @Test
    fun `webhook decline is observable through public api`() = testApplication {
        val env = localTestEnvironment()
        application {
            installFullTestApplication(env, database, cloudClient)
        }

        val createResponse =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson("webhook-e2e-${UUID.randomUUID()}"))
            }
        val created = readJson<FullEvent>(createResponse.bodyAsText())

        val joinResponse = client.post("/user/event/${created.event.id}")
        assertEquals(HttpStatusCode.Conflict, joinResponse.status)

        database.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO participant (event_id, email, name, type, calendar_event_id)
                VALUES (?::uuid, ?, ?, 'PARTICIPANT', ?)
                ON CONFLICT (event_id, email) DO UPDATE SET calendar_event_id = EXCLUDED.calendar_event_id
                """
            ).use {
                it.setString(1, created.event.id.toString())
                it.setString(2, "participant@example.com")
                it.setString(3, "Participant User")
                it.setString(4, "calendar-e2e")
                it.executeUpdate()
            }
            connection.commit()
        }

        cloudClient.attendeeStatuses["calendar-e2e"] = com.microsoft.graph.models.ResponseType.Declined.right()

        val response =
            client.post("/webhook/calendar") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "value": [
                        {
                          "clientState": "${env.webhookClientState}",
                          "resource": "users/${env.deltaEmailAddress}/events/calendar-e2e",
                          "changeType": "updated",
                          "subscriptionId": "subscription-e2e"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            }
        assertEquals(HttpStatusCode.Accepted, response.status)

        waitUntilSuspending {
            val event = readJson<FullEvent>(client.get("/event/${created.event.id}").bodyAsText())
            event.participants.none { it.email == "participant@example.com" }
        }

        val updated = readJson<FullEvent>(client.get("/event/${created.event.id}").bodyAsText())
        assertFalse(updated.participants.any { it.email == "participant@example.com" })
        assertTrue(cloudClient.deletedCalendarEventIds.contains("calendar-e2e"))
    }

    private fun createEventJson(
        title: String,
        description: String = "created via e2e",
    ): String {
        val startTime = LocalDateTime.now().plusDays(2)
        return """
            {
              "title": "$title",
              "description": "$description",
              "startTime": "$startTime",
              "endTime": "${startTime.plusHours(1)}",
              "location": "room-e2e",
              "public": true,
              "participantLimit": 10,
              "signupDeadline": "${startTime.minusHours(2)}",
              "sendNotificationEmail": false
            }
        """.trimIndent()
    }
}
