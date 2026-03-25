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
            db.close()
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

    @Test
    fun getFullEventsReturnsParticipantsAndCategories() {
        val category = db.createCategory(CreateCategory("testcategory")).getOrNull()!!
        val event = db.addEvent(futureEventTest("fullEventTest"))
        db.registerForEvent(event.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        db.registerForEvent(event.id.toString(), "participant@example.com", "Participant User")
        db.setCategories(event.id.toString(), listOf(category.id))

        val results = db.getFullEvents(onlyFuture = true)
        val fullEvent = results.find { it.event.id == event.id }

        Assertions.assertNotNull(fullEvent)
        Assertions.assertEquals(1, fullEvent!!.hosts.size)
        Assertions.assertEquals("host@example.com", fullEvent.hosts[0].email)
        Assertions.assertEquals(1, fullEvent.participants.size)
        Assertions.assertEquals("participant@example.com", fullEvent.participants[0].email)
        Assertions.assertEquals(1, fullEvent.categories.size)
        Assertions.assertEquals("testcategory", fullEvent.categories[0].name)
    }

    @Test
    fun getFullEventsWithNoParticipantsOrCategories() {
        val event = db.addEvent(futureEventTest("emptyFullEvent"))
        val results = db.getFullEvents(onlyFuture = true)
        val fullEvent = results.find { it.event.id == event.id }

        Assertions.assertNotNull(fullEvent)
        Assertions.assertTrue(fullEvent!!.hosts.isEmpty())
        Assertions.assertTrue(fullEvent.participants.isEmpty())
        Assertions.assertTrue(fullEvent.categories.isEmpty())
    }

    @Test
    fun createRecurringSeriesCreatesLinkedOccurrences() {
        val category = db.createCategory(CreateCategory("recurring-category")).getOrNull()!!
        val start = LocalDateTime.now().plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val createEvent =
            futureEventTest("recurring-create", start).copy(
                categories = listOf(category.id),
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(2)),
            )

        val createdSeries =
            db.createRecurringEventSeries(createEvent, "host@example.com", "Host User").getOrNull()!!

        Assertions.assertEquals(3, createdSeries.affectedEvents.size)

        val firstEvent = db.getFullEvent(createdSeries.referenceEventId.toString()).getOrNull()!!
        val recurringSeries = requireNotNull(firstEvent.recurringSeries)
        Assertions.assertEquals(RecurrenceFrequency.WEEKLY, recurringSeries.frequency)
        Assertions.assertEquals(1, firstEvent.categories.size)
        val seriesId = recurringSeries.seriesId

        createdSeries.affectedEvents.forEach { event ->
            val fullEvent = db.getFullEvent(event.id.toString()).getOrNull()!!
            Assertions.assertNotNull(fullEvent.recurringSeries)
            Assertions.assertEquals(seriesId, fullEvent.recurringSeries?.seriesId)
        }
    }

    @Test
    fun updatingUpcomingRecurringOccurrencesSplitsSeriesAndPreservesPast() {
        val originalCategory = db.createCategory(CreateCategory("recurring-original")).getOrNull()!!
        val updatedCategory = db.createCategory(CreateCategory("recurring-updated")).getOrNull()!!
        val start = LocalDateTime.now().plusDays(14).withHour(9).withMinute(0).withSecond(0).withNano(0)
        val createEvent =
            futureEventTest("recurring-update", start).copy(
                categories = listOf(originalCategory.id),
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(2)),
            )

        val createdSeries =
            db.createRecurringEventSeries(createEvent, "host@example.com", "Host User").getOrNull()!!

        val firstOccurrence = createdSeries.affectedEvents[0]
        val secondOccurrence = createdSeries.affectedEvents[1]
        val thirdOccurrence = createdSeries.affectedEvents[2]

        val updateRequest =
            futureEventTest("recurring-update-upcoming", secondOccurrence.startTime.plusHours(1)).copy(
                endTime = secondOccurrence.startTime.plusHours(3),
                categories = listOf(updatedCategory.id),
                recurrence = createEvent.recurrence,
                editScope = EventEditScope.UPCOMING,
            )

        db.updateRecurringSeriesFromOccurrence(
            eventId = secondOccurrence.id.toString(),
            createEvent = updateRequest,
            updatedByEmail = "host@example.com",
        ).getOrNull()!!

        val firstFull = db.getFullEvent(firstOccurrence.id.toString()).getOrNull()!!
        val secondFull = db.getFullEvent(secondOccurrence.id.toString()).getOrNull()!!
        val thirdFull = db.getFullEvent(thirdOccurrence.id.toString()).getOrNull()!!

        Assertions.assertEquals("recurring-update", firstFull.event.title)
        Assertions.assertEquals("recurring-update-upcoming", secondFull.event.title)
        Assertions.assertEquals("recurring-update-upcoming", thirdFull.event.title)
        Assertions.assertNotEquals(firstFull.recurringSeries!!.seriesId, secondFull.recurringSeries?.seriesId)
        Assertions.assertEquals(secondFull.recurringSeries!!.seriesId, thirdFull.recurringSeries?.seriesId)
        Assertions.assertEquals("recurring-original", firstFull.categories.single().name)
        Assertions.assertEquals("recurring-updated", secondFull.categories.single().name)
        Assertions.assertEquals("recurring-updated", thirdFull.categories.single().name)
    }

    @Test
    fun createMonthlyRecurringSeriesCreatesLinkedOccurrences() {
        val start = LocalDateTime.now().plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val createEvent =
            futureEventTest("recurring-monthly", start).copy(
                recurrence = RecurrenceRequest(RecurrenceFrequency.MONTHLY, start.toLocalDate().plusMonths(2).plusDays(6)),
            )

        val createdSeries =
            db.createRecurringEventSeries(createEvent, "host@example.com", "Host User").getOrNull()!!

        Assertions.assertEquals(3, createdSeries.affectedEvents.size)

        val firstEvent = db.getFullEvent(createdSeries.referenceEventId.toString()).getOrNull()!!
        val recurringSeries = requireNotNull(firstEvent.recurringSeries)
        Assertions.assertEquals(RecurrenceFrequency.MONTHLY, recurringSeries.frequency)

        val occurrenceDates = createdSeries.affectedEvents.map { it.startTime.toLocalDate() }
        val startDate = start.toLocalDate()
        Assertions.assertEquals(startDate, occurrenceDates[0])
        // All occurrences must fall on the same day of week as the start date
        Assertions.assertTrue(occurrenceDates.all { it.dayOfWeek == startDate.dayOfWeek })
        // Each occurrence must be in the expected calendar month
        Assertions.assertEquals(startDate.plusMonths(1).month, occurrenceDates[1].month)
        Assertions.assertEquals(startDate.plusMonths(2).month, occurrenceDates[2].month)
    }

    @Test
    fun extendingUntilDateCreatesNewOccurrences() {
        val start = LocalDateTime.now().plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val createEvent =
            futureEventTest("recurring-extend", start).copy(
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(2)),
            )

        val createdSeries =
            db.createRecurringEventSeries(createEvent, "host@example.com", "Host User").getOrNull()!!
        Assertions.assertEquals(3, createdSeries.affectedEvents.size) // weeks 0, 1, 2

        val firstOccurrence = createdSeries.affectedEvents[0]
        val originalSeriesId = db.getFullEvent(firstOccurrence.id.toString()).getOrNull()!!.recurringSeries!!.seriesId

        // Extend untilDate by 2 more weeks — should produce 2 new events
        val updateRequest =
            futureEventTest("recurring-extend", firstOccurrence.startTime).copy(
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(4)),
                editScope = EventEditScope.UPCOMING,
            )

        val result =
            db.updateRecurringSeriesFromOccurrence(
                eventId = firstOccurrence.id.toString(),
                createEvent = updateRequest,
                updatedByEmail = "host@example.com",
            ).getOrNull()!!

        Assertions.assertEquals(5, result.affectedEvents.size) // weeks 0–4

        // All 5 should belong to the same series (no split — editing from index 0)
        result.affectedEvents.forEach { event ->
            val full = db.getFullEvent(event.id.toString()).getOrNull()!!
            Assertions.assertEquals(originalSeriesId, full.recurringSeries!!.seriesId)
        }

        // Dates should be exactly one week apart
        val dates = result.affectedEvents.map { it.startTime.toLocalDate() }
        for (i in 1 until dates.size) {
            Assertions.assertEquals(dates[i - 1].plusWeeks(1), dates[i])
        }
    }

    @Test
    fun shorteningUntilDateDeletesExcessOccurrences() {
        val start = LocalDateTime.now().plusDays(7).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val createEvent =
            futureEventTest("recurring-shorten", start).copy(
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(4)),
            )

        val createdSeries =
            db.createRecurringEventSeries(createEvent, "host@example.com", "Host User").getOrNull()!!
        Assertions.assertEquals(5, createdSeries.affectedEvents.size) // weeks 0–4

        val firstOccurrence = createdSeries.affectedEvents[0]
        val removedEvent = createdSeries.affectedEvents[4] // week 4, should be deleted

        // Shorten to 2 weeks — events at weeks 3 and 4 should be deleted
        val updateRequest =
            futureEventTest("recurring-shorten", firstOccurrence.startTime).copy(
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(2)),
                editScope = EventEditScope.UPCOMING,
            )

        val result =
            db.updateRecurringSeriesFromOccurrence(
                eventId = firstOccurrence.id.toString(),
                createEvent = updateRequest,
                updatedByEmail = "host@example.com",
            ).getOrNull()!!

        Assertions.assertEquals(3, result.affectedEvents.size) // weeks 0–2

        // The event that was at week 4 should no longer exist
        Assertions.assertTrue(db.getEvent(removedEvent.id.toString()).isLeft())
    }

    private fun futureEventTest(
        title: String,
        startTime: LocalDateTime = LocalDateTime.now().plusHours(1),
    ) = CreateEvent(
        title = title,
        description = "desc",
        startTime = startTime,
        endTime = startTime.plusHours(1),
        location = "location",
        public = true,
        participantLimit = 2,
        sendNotificationEmail = false,
        signupDeadline = startTime.minusMinutes(30),
    )
}
