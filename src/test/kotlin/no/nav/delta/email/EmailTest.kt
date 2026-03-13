package no.nav.delta.email

import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.event.CreateEvent
import no.nav.delta.event.Participant
import no.nav.delta.event.ParticipantType
import no.nav.delta.event.addEvent
import no.nav.delta.event.getCalendarEventId
import no.nav.delta.event.registerForEvent
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailTest {
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
    fun `sendUpdateOrCreationNotification stores new calendar id`() {
        val event = database.addEvent(futureEvent("email-${UUID.randomUUID()}"))
        val participant = Participant(email = "participant@example.com", name = "Participant User")
        database.registerForEvent(event.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        database.registerForEvent(event.id.toString(), participant.email, participant.name)

        cloudClient.sendUpdateOrCreationNotification(event, database, participant, null)

        assertEquals(listOf("calendar-1"), cloudClient.createdCalendarEventIds)
        assertEquals("calendar-1", database.getCalendarEventId(event.id.toString(), participant.email).getOrNull())
    }

    @Test
    fun `batchSendUpdateOrCreationNotification stores only created calendar ids`() {
        val event = database.addEvent(futureEvent("batch-${UUID.randomUUID()}"))
        val newParticipant = Participant(email = "new@example.com", name = "New User")
        val existingParticipant = Participant(email = "existing@example.com", name = "Existing User")
        database.registerForEvent(event.id.toString(), "host@example.com", "Host User", ParticipantType.HOST)
        database.registerForEvent(event.id.toString(), newParticipant.email, newParticipant.name)
        database.registerForEvent(event.id.toString(), existingParticipant.email, existingParticipant.name)

        cloudClient.batchSendUpdateOrCreationNotification(
            event = event,
            database = database,
            participantsWithCalendarIds =
                listOf(
                    newParticipant to null,
                    existingParticipant to "calendar-existing",
                ),
        )

        assertEquals("calendar-1", database.getCalendarEventId(event.id.toString(), newParticipant.email).getOrNull())
        assertEquals(null, database.getCalendarEventId(event.id.toString(), existingParticipant.email).getOrNull())
        assertTrue(cloudClient.updatedCalendarEventIds.contains("calendar-existing"))
    }

    @Test
    fun `sendCancellationNotification deletes calendar event and sends email`() {
        val event = futureEvent("cancel-${UUID.randomUUID()}").let(database::addEvent)
        val participant = Participant(email = "cancel@example.com", name = "Cancel User")

        cloudClient.sendCancellationNotification("calendar-99", event, participant)

        assertTrue(cloudClient.deletedCalendarEventIds.contains("calendar-99"))
        assertEquals(listOf("cancel@example.com"), cloudClient.sentEmails.single().toRecipients)
        assertTrue(cloudClient.sentEmails.single().subject.contains(event.title))
    }

    private fun futureEvent(title: String) =
        CreateEvent(
            title = title,
            description = "desc",
            startTime = LocalDateTime.now().plusDays(2),
            endTime = LocalDateTime.now().plusDays(2).plusHours(1),
            location = "room-4",
            public = true,
            participantLimit = 10,
            signupDeadline = LocalDateTime.now().plusDays(1),
            sendNotificationEmail = false,
        )
}
