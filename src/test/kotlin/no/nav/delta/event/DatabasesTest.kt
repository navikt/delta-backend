package no.nav.delta.event

import no.nav.delta.Environment
import no.nav.delta.plugins.DatabaseConfig
import no.nav.delta.plugins.DatabaseInterface
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID

@Testcontainers
class DatabasesTest {

    companion object {
        @Container
        private val postgresContainer = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))

        private lateinit var db: DatabaseInterface

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = DatabaseConfig(Environment(
                dbJdbcUrl = postgresContainer.jdbcUrl,
                dbUsername = postgresContainer.username,
                dbPassword = postgresContainer.password,
            ))
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            postgresContainer.stop()
        }
    }

    @Test
    @Order(1)
    fun testDatabaseConnection() {
        val result = db.getEvent(UUID.randomUUID().toString())
        Assertions.assertEquals(EventNotFoundException, result.leftOrNull())
    }

    @Test
    fun saveAndGetNewEvent() {
        val title = "lolTitle"
        val newEvent = futureEventTest(title)
        val result = db.addEvent(newEvent)
        Assertions.assertEquals(title, result.title)
        val fetchedEvent = db.getEvent(result.id.toString())
        Assertions.assertNotNull(fetchedEvent.getOrNull())
        Assertions.assertEquals(title, fetchedEvent.getOrNull()?.title)
    }

    @Test
    fun saveDeleteAndGetEvents() {
        val before = db.getEvents(onlyFuture = true).size
        val lolEvent1 = futureEventTest("lolEvent1")
        val lolEvent2 = futureEventTest("lolEvent2")
        val event1 = db.addEvent(lolEvent1)
        db.addEvent(lolEvent2)
        val events = db.getEvents(onlyFuture = true)
        Assertions.assertEquals(before + 2, events.size)

        db.deleteEvent(event1.id.toString())
        Assertions.assertEquals(before + 1, db.getEvents(onlyFuture = true).size)
    }

    @Test
    fun fetchNonExistentEventReturnsNull() {
        val nonExistentEventId = UUID.randomUUID().toString()
        val result = db.getEvent(nonExistentEventId)
        Assertions.assertNull(result.getOrNull())
        Assertions.assertEquals(EventNotFoundException, result.leftOrNull())
    }

    private fun futureEventTest(title: String) = CreateEvent(
        title = title,
        description = "desc",
        startTime = LocalDateTime.now().plusHours(1),
        endTime = LocalDateTime.now().plusHours(2),
        location = "location",
        public = true,
        participantLimit = 2,
        sendNotificationEmail = false,
        signupDeadline = LocalDateTime.now().plusMinutes(10),
    )
}