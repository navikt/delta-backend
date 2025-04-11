package no.nav.delta.event

import no.nav.delta.Environment
import no.nav.delta.plugins.DatabaseConfig
import no.nav.delta.plugins.DatabaseInterface
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabasesTest {

    private lateinit var env: Environment
    private lateinit var db: DatabaseInterface
    private val postgresContainer = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))


    @BeforeAll
    fun setup() {
        postgresContainer.start()
        env = Environment(
            dbJdbcUrl = postgresContainer.jdbcUrl,
            dbUsername = postgresContainer.username,
            dbPassword = postgresContainer.password
        )
        db = DatabaseConfig(env)
    }

    @AfterAll
    fun tearDown() {
        postgresContainer.stop()
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
        val lolEvent1 = futureEventTest("lolEvent1")
        val lolEvent2 = futureEventTest("lolEvent2")
        val event1 = db.addEvent(lolEvent1)
        db.addEvent(lolEvent2)
        val events = db.getEvents(onlyFuture = true)
        Assertions.assertEquals(2, events.size)

        db.deleteEvent(event1.id.toString())
        Assertions.assertEquals(1, db.getEvents().size)
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