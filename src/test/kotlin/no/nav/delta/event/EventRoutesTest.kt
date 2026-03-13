package no.nav.delta.event

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
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installTestApi
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import no.nav.delta.support.waitUntilSuspending
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventRoutesTest {
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
    fun `admin put creates event and registers host`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val title = "event-${UUID.randomUUID()}"
        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = title, sendNotificationEmail = false))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals(title, fullEvent.event.title)
        assertEquals("test@localhost", fullEvent.hosts.single().email)
    }

    @Test
    fun `admin put rejects invalid date range`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(
                        title = "invalid-${UUID.randomUUID()}",
                        startTime = LocalDateTime.now().plusHours(3),
                        endTime = LocalDateTime.now().plusHours(2),
                        sendNotificationEmail = false,
                    )
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Start time must be before end time", response.bodyAsText())
    }

    @Test
    fun `get event returns bad request for invalid uuid`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val response = client.get("/event/not-a-uuid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid id", response.bodyAsText())
    }

    @Test
    fun `get event supports joined and category filters`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val matchingCategory = database.createCategory(CreateCategory(shortName("route"))).getOrNull()!!
        val otherCategory = database.createCategory(CreateCategory(shortName("other"))).getOrNull()!!

        val includedEvent = database.addEvent(futureEvent("included-${UUID.randomUUID()}"))
        val excludedEvent = database.addEvent(futureEvent("excluded-${UUID.randomUUID()}"))

        database.registerForEvent(includedEvent.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        database.registerForEvent(includedEvent.id.toString(), "test@localhost", "Test User")
        database.setCategories(includedEvent.id.toString(), listOf(matchingCategory.id))

        database.registerForEvent(excludedEvent.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        database.setCategories(excludedEvent.id.toString(), listOf(otherCategory.id))

        val response = client.get("/event?onlyJoined=true&categories=${matchingCategory.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        val events = readJson<List<FullEvent>>(response.bodyAsText())
        assertEquals(listOf(includedEvent.id), events.map { it.event.id })
    }

    @Test
    fun `admin post updates an event`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val createdResponse =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = "before-${UUID.randomUUID()}", sendNotificationEmail = false))
            }
        val created = readJson<FullEvent>(createdResponse.bodyAsText())

        val response =
            client.post("/admin/event/${created.event.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(
                        title = "after-${UUID.randomUUID()}",
                        description = "updated description",
                        location = "New room",
                        sendNotificationEmail = false,
                    )
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = readJson<FullEvent>(response.bodyAsText())
        assertEquals("updated description", updated.event.description)
        assertEquals("New room", updated.event.location)
    }

    @Test
    fun `admin category route updates event categories`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val createdResponse =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = "category-${UUID.randomUUID()}", sendNotificationEmail = false))
            }
        val created = readJson<FullEvent>(createdResponse.bodyAsText())
        val category = database.createCategory(CreateCategory(shortName("cat"))).getOrNull()!!

        val response =
            client.post("/admin/event/${created.event.id}/category") {
                contentType(ContentType.Application.Json)
                setBody("[${category.id}]")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Success", response.bodyAsText())
        assertEquals(listOf(category.id), database.getCategories(created.event.id.toString()).getOrNull()!!.map { it.id })
    }

    @Test
    fun `user join and leave updates calendar state`() = testApplication {
        application {
            installTestApi(localTestEnvironment(), database) {
                eventApi(database, cloudClient)
            }
        }

        val event = database.addEvent(futureEvent("joinable-${UUID.randomUUID()}"))
        database.registerForEvent(event.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)

        val joinResponse = client.post("/user/event/${event.id}")
        assertEquals(HttpStatusCode.OK, joinResponse.status)

        waitUntilSuspending {
            database.getCalendarEventId(event.id.toString(), "test@localhost").getOrNull() != null
        }

        val calendarEventId = database.getCalendarEventId(event.id.toString(), "test@localhost").getOrNull()
        assertNotNull(calendarEventId)

        val leaveResponse = client.delete("/user/event/${event.id}")
        assertEquals(HttpStatusCode.OK, leaveResponse.status)

        waitUntilSuspending {
            database.getParticipants(event.id.toString()).getOrNull()!!.none { it.email == "test@localhost" } &&
                cloudClient.deletedCalendarEventIds.contains(calendarEventId)
        }

        assertTrue(cloudClient.deletedCalendarEventIds.contains(calendarEventId))
    }

    private fun createEventJson(
        title: String,
        description: String = "desc",
        location: String = "room-1",
        startTime: LocalDateTime = LocalDateTime.now().plusDays(2),
        endTime: LocalDateTime = startTime.plusHours(1),
        sendNotificationEmail: Boolean = false,
    ): String =
        """
        {
          "title": "$title",
          "description": "$description",
          "startTime": "${startTime}",
          "endTime": "${endTime}",
          "location": "$location",
          "public": true,
          "participantLimit": 10,
          "signupDeadline": "${startTime.minusHours(2)}",
          "sendNotificationEmail": $sendNotificationEmail
        }
        """.trimIndent()

    private fun futureEvent(title: String) =
        CreateEvent(
            title = title,
            description = "desc",
            startTime = LocalDateTime.now().plusDays(2),
            endTime = LocalDateTime.now().plusDays(2).plusHours(1),
            location = "room-2",
            public = true,
            participantLimit = 10,
            signupDeadline = LocalDateTime.now().plusDays(1),
            sendNotificationEmail = false,
        )

    private fun shortName(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"
}
