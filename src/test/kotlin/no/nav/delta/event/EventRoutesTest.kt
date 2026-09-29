package no.nav.delta.event

import arrow.core.left
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
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
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
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
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
    fun `admin put rejects room fields when room booking is disabled`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "room-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no"}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Room booking is not enabled", response.bodyAsText())
    }

    @Test
    fun `admin put rejects isOnlineMeeting when teams meeting is disabled`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "teams-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","isOnlineMeeting":true}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Teams meeting is not enabled", response.bodyAsText())
    }

    @Test
    fun `admin put accepts room fields when room booking is enabled for the caller`() = testApplication {
        application {
            val env =
                localTestEnvironment()
                    .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS)
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "room-ok-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Møterom 1"}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals("room1@nav.no", fullEvent.event.roomEmail)
        assertEquals("Møterom 1", fullEvent.event.roomName)
    }

    @Test
    fun `admin put creates a master event and stores the returned room status and teams link`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        cloudClient.masterEventResult =
            no.nav.delta.room.MasterEventResult(
                calendarEventId = "master-1",
                roomStatus = RoomBookingStatus.ACCEPTED,
                teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc",
                teamsConferenceId = "123456789",
                teamsDialIn = "+47 21 00 00 00,,123456789#",
            ).right()

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "master-create-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1","isOnlineMeeting":true}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, cloudClient.createdMasterEvents.size)

        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals(RoomBookingStatus.ACCEPTED, fullEvent.event.roomStatus)
        assertEquals("https://teams.microsoft.com/l/meetup-join/abc", fullEvent.event.teamsJoinUrl)

        val storedMasterId = database.getMasterCalendarEventId(fullEvent.event.id.toString()).getOrNull()
        assertEquals("master-1", storedMasterId)
    }

    @Test
    fun `admin put returns 502 and creates no event when master event creation fails`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        cloudClient.masterEventResult = RuntimeException("graph failure").left()
        val eventsBefore = database.getEvents(onlyFuture = true).size

        val title = "master-fail-${UUID.randomUUID()}"
        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = title, sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1"}"""
                )
            }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(eventsBefore, database.getEvents(onlyFuture = true).size)
        assertTrue(database.getEvents(onlyFuture = true).none { it.title == title })
    }

    @Test
    fun `admin post adds a room to an event that had none, creating a master event`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = database.addEvent(futureEvent("no-room-yet-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User", ParticipantType.HOST)

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = created.title, sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1"}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, cloudClient.createdMasterEvents.size)
        assertEquals(0, cloudClient.updatedMasterEvents.size)
        assertEquals("master-1", database.getMasterCalendarEventId(created.id.toString()).getOrNull())
    }

    @Test
    fun `admin post updates the master event when the room changes on an already-booked event`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = database.addEvent(futureEvent("has-room-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User", ParticipantType.HOST)
        database.setMasterCalendarEventId(created.id.toString(), "existing-master-id")

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = created.title, sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room2@nav.no","roomName":"Room 2"}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, cloudClient.createdMasterEvents.size)
        assertEquals(1, cloudClient.updatedMasterEvents.size)
        assertEquals("existing-master-id", cloudClient.updatedMasterEvents.single().first)
        assertEquals(
            "existing-master-id",
            database.getMasterCalendarEventId(created.id.toString()).getOrNull(),
        )
    }

    @Test
    fun `admin post without room or teams fields keeps the existing booking and syncs the master`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("keep-room-${UUID.randomUUID()}")
        val newTitle = "renamed-${UUID.randomUUID()}"
        val newStart = LocalDateTime.now().plusDays(5).withNano(0)

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = newTitle, startTime = newStart, sendNotificationEmail = false))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(cloudClient.deletedMasterEventIds.isEmpty())
        assertEquals("existing-master-id", database.getMasterCalendarEventId(created.id.toString()).getOrNull())

        val (masterId, draft) = cloudClient.updatedMasterEvents.single()
        assertEquals("existing-master-id", masterId)
        assertEquals(newTitle, draft.title)
        assertEquals(newStart, draft.startTime)
        assertEquals("room1@nav.no", draft.roomEmail)
        assertEquals(true, draft.isOnlineMeeting)

        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals("room1@nav.no", fullEvent.event.roomEmail)
        assertEquals("Room 1", fullEvent.event.roomName)
        assertEquals(true, fullEvent.event.isOnlineMeeting)
        assertEquals("https://teams.microsoft.com/l/meetup-join/abc", fullEvent.event.teamsJoinUrl)
    }

    @Test
    fun `admin post keeps an accepted room status when graph reports pending after an unrelated edit`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("keep-status-${UUID.randomUUID()}")
        cloudClient.updateMasterEventResult = masterResult("existing-master-id", RoomBookingStatus.PENDING).right()

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = "edited-${UUID.randomUUID()}", sendNotificationEmail = false))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(RoomBookingStatus.ACCEPTED, readJson<FullEvent>(response.bodyAsText()).event.roomStatus)
    }

    @Test
    fun `admin post changing the room patches the master with the new room and takes a fresh status`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("change-room-${UUID.randomUUID()}")
        cloudClient.updateMasterEventResult = masterResult("existing-master-id", RoomBookingStatus.PENDING).right()

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = created.title, sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room2@nav.no","roomName":"Room 2"}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("room2@nav.no", cloudClient.updatedMasterEvents.single().second.roomEmail)
        val event = readJson<FullEvent>(response.bodyAsText()).event
        assertEquals("room2@nav.no", event.roomEmail)
        assertEquals("Room 2", event.roomName)
        assertEquals(RoomBookingStatus.PENDING, event.roomStatus)
    }

    @Test
    fun `admin post rejects turning teams off on an event that has a teams meeting`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("teams-off-${UUID.randomUUID()}")

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = created.title, sendNotificationEmail = false)
                        .dropLast(2) + ""","isOnlineMeeting":false}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(cloudClient.updatedMasterEvents.isEmpty())
        assertEquals(true, database.getEvent(created.id.toString()).getOrNull()!!.isOnlineMeeting)
    }

    @Test
    fun `admin post returns 502 and leaves the event unchanged when the master update fails`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("update-fail-${UUID.randomUUID()}")
        cloudClient.updateMasterEventResult = RuntimeException("graph failure").left()

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = "should-not-persist", sendNotificationEmail = false))
            }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val stored = database.getEvent(created.id.toString()).getOrNull()!!
        assertEquals(created.title, stored.title)
        assertEquals("room1@nav.no", stored.roomEmail)
    }

    @Test
    fun `admin post rejects room fields when room booking is disabled`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = database.addEvent(futureEvent("post-disabled-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User", ParticipantType.HOST)

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = created.title, sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1"}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Room booking is not enabled", response.bodyAsText())
        assertTrue(cloudClient.createdMasterEvents.isEmpty())
    }

    @Test
    fun `admin put rejects teams when only room booking is enabled`() = testApplication {
        application {
            val env =
                localTestEnvironment()
                    .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS)
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "mixed-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1","isOnlineMeeting":true}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Teams meeting is not enabled", response.bodyAsText())
        assertTrue(cloudClient.createdMasterEvents.isEmpty())
    }

    @Test
    fun `admin put rejects roomName without roomEmail`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "name-only-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","roomName":"Room 1"}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("roomEmail and roomName must be set together", response.bodyAsText())
    }

    @Test
    fun `admin put ignores server-managed room and teams fields sent by the client`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "server-managed-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) +
                        ""","roomStatus":"ACCEPTED","teamsJoinUrl":"https://evil.example","teamsConferenceId":"1","teamsDialIn":"2"}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val event = readJson<FullEvent>(response.bodyAsText()).event
        assertEquals(null, event.roomStatus)
        assertEquals(null, event.teamsJoinUrl)
        assertTrue(cloudClient.createdMasterEvents.isEmpty())
    }

    @Test
    fun `admin put rolls back the master and the event when a later step fails`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val title = "rollback-${UUID.randomUUID()}"
        // Signup deadline (start - 2h) is already passed, so registering the host fails after
        // the event row and the master event were created.
        val start = LocalDateTime.now().plusHours(1)

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = title, startTime = start, sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1"}"""
                )
            }

        assertTrue(response.status != HttpStatusCode.OK, "expected failure, got ${response.status}")
        assertEquals(listOf("master-1"), cloudClient.deletedMasterEventIds)
        assertTrue(database.getEvents(onlyFuture = true).none { it.title == title })
    }

    @Test
    fun `admin post rejects room fields with editScope UPCOMING`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val occurrenceId = createRecurringSeries()

        val response =
            client.post("/admin/event/$occurrenceId") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "upcoming-room", sendNotificationEmail = false)
                        .dropLast(2) + ""","editScope":"UPCOMING","roomEmail":"room1@nav.no","roomName":"Room 1"}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(cloudClient.createdMasterEvents.isEmpty())
    }

    @Test
    fun `admin post rejects room or teams on a single occurrence of a recurring series`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val occurrenceId = createRecurringSeries()

        val response =
            client.post("/admin/event/$occurrenceId") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "single-occurrence-teams", sendNotificationEmail = false)
                        .dropLast(2) + ""","editScope":"SINGLE","isOnlineMeeting":true}"""
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(cloudClient.createdMasterEvents.isEmpty())
    }

    @Test
    fun `admin delete succeeds even when deleting the master event fails`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("delete-fail-${UUID.randomUUID()}")
        cloudClient.failDeleteMasterEvent = true

        val response = client.delete("/admin/event/${created.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("existing-master-id"), cloudClient.deletedMasterEventIds)
        assertTrue(database.getEvent(created.id.toString()).isLeft())
    }

    @Test
    fun `admin delete removes the master event before deleting the event`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = database.addEvent(futureEvent("delete-with-room-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User", ParticipantType.HOST)
        database.setMasterCalendarEventId(created.id.toString(), "existing-master-id")

        val response = client.delete("/admin/event/${created.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("existing-master-id"), cloudClient.deletedMasterEventIds)
    }

    @Test
    fun `admin put rejects room fields on a recurring event`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "title": "recurring-room-${UUID.randomUUID()}",
                      "description": "desc",
                      "startTime": "${LocalDateTime.now().plusDays(1)}",
                      "endTime": "${LocalDateTime.now().plusDays(1).plusHours(1)}",
                      "location": "room-1",
                      "public": true,
                      "participantLimit": 10,
                      "signupDeadline": null,
                      "sendNotificationEmail": false,
                      "roomEmail": "room1@nav.no",
                      "recurrence": {
                        "frequency": "WEEKLY",
                        "untilDate": "${LocalDateTime.now().plusWeeks(3).toLocalDate()}"
                      }
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get event hides teams details for a caller who is not a participant or host`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val created = database.addEvent(futureEvent("teams-hidden-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "someone-else@nav.no", "Someone Else", ParticipantType.HOST)
        database.updateEvent(
            created.copy(
                isOnlineMeeting = true,
                teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc",
                teamsConferenceId = "123456789",
                teamsDialIn = "+47 21 00 00 00,,123456789#",
            )
        )

        val response = client.get("/event/${created.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals(true, fullEvent.event.isOnlineMeeting)
        assertEquals(null, fullEvent.event.teamsJoinUrl)
        assertEquals(null, fullEvent.event.teamsConferenceId)
        assertEquals(null, fullEvent.event.teamsDialIn)
    }

    @Test
    fun `get event shows teams details to a registered participant`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val created = database.addEvent(futureEvent("teams-visible-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User")
        database.updateEvent(
            created.copy(
                isOnlineMeeting = true,
                teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc",
            )
        )

        val response = client.get("/event/${created.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals("https://teams.microsoft.com/l/meetup-join/abc", fullEvent.event.teamsJoinUrl)
    }

    @Test
    fun `get event list hides teams details for a caller who is not a participant or host`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val created = database.addEvent(futureEvent("teams-list-hidden-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "someone-else@nav.no", "Someone Else", ParticipantType.HOST)
        database.updateEvent(
            created.copy(isOnlineMeeting = true, teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc")
        )

        val response = client.get("/event")

        assertEquals(HttpStatusCode.OK, response.status)
        val events = readJson<List<FullEvent>>(response.bodyAsText())
        val listed = events.single { it.event.id == created.id }
        assertEquals(null, listed.event.teamsJoinUrl)
    }

    @Test
    fun `get event shows teams details to a host`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = bookedEvent("teams-host-${UUID.randomUUID()}")

        val response = client.get("/event/${created.id}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "https://teams.microsoft.com/l/meetup-join/abc",
            readJson<FullEvent>(response.bodyAsText()).event.teamsJoinUrl,
        )
    }

    @Test
    fun `get event list shows teams details to a registered participant`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        val created = database.addEvent(futureEvent("teams-list-visible-${UUID.randomUUID()}"))
        database.registerForEvent(created.id.toString(), "someone-else@nav.no", "Someone Else", ParticipantType.HOST)
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User")
        database.updateEvent(
            created.copy(isOnlineMeeting = true, teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc")
        )

        val response = client.get("/event")

        assertEquals(HttpStatusCode.OK, response.status)
        val listed = readJson<List<FullEvent>>(response.bodyAsText()).single { it.event.id == created.id }
        assertEquals("https://teams.microsoft.com/l/meetup-join/abc", listed.event.teamsJoinUrl)
    }

    @Test
    fun `admin put returns 502 and saves nothing when graph creates no teams join url`() = testApplication {
        val env = enabledEnv()
        application { installTestApi(env, database) { eventApi(database, cloudClient, env) } }
        val title = "no-teams-link-${UUID.randomUUID()}"

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = title, sendNotificationEmail = false).dropLast(2) + ""","isOnlineMeeting":true}""")
            }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(listOf("master-1"), cloudClient.deletedMasterEventIds)
        assertTrue(database.getEvents(onlyFuture = true).none { it.title == title })
    }

    @Test
    fun `admin post enabling teams without a join url restores the master and keeps the event unchanged`() = testApplication {
        val env = enabledEnv()
        application { installTestApi(env, database) { eventApi(database, cloudClient, env) } }
        val created = bookedEvent("teams-on-fail-${UUID.randomUUID()}")
        database.updateEvent(created.copy(isOnlineMeeting = false, teamsJoinUrl = null))

        val response =
            client.post("/admin/event/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(createEventJson(title = "renamed", sendNotificationEmail = false).dropLast(2) + ""","isOnlineMeeting":true}""")
            }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        // First call applies the change, second restores the original event on the master.
        assertEquals(2, cloudClient.updatedMasterEvents.size)
        assertEquals(created.title, cloudClient.updatedMasterEvents.last().second.title)
        val stored = database.getEvent(created.id.toString()).getOrNull()!!
        assertEquals(created.title, stored.title)
        assertEquals(false, stored.isOnlineMeeting)
    }

    /** An event hosted by the test user with a room (ACCEPTED), Teams and a master event. */
    private fun bookedEvent(title: String): Event {
        val created = database.addEvent(futureEvent(title))
        database.registerForEvent(created.id.toString(), "test@localhost", "Test User", ParticipantType.HOST)
        database.setMasterCalendarEventId(created.id.toString(), "existing-master-id")
        return database.updateEvent(
            created.copy(
                roomEmail = "room1@nav.no",
                roomName = "Room 1",
                roomStatus = RoomBookingStatus.ACCEPTED,
                isOnlineMeeting = true,
                teamsJoinUrl = "https://teams.microsoft.com/l/meetup-join/abc",
            )
        ).getOrNull()!!
    }

    private fun masterResult(id: String, roomStatus: RoomBookingStatus?) =
        no.nav.delta.room.MasterEventResult(
            calendarEventId = id,
            roomStatus = roomStatus,
            teamsJoinUrl = null,
            teamsConferenceId = null,
            teamsDialIn = null,
        )

    /** Creates a weekly series hosted by the test user and returns the first occurrence's id. */
    private fun createRecurringSeries(): UUID {
        val start = LocalDateTime.now().plusDays(1)
        val draft =
            futureEvent("series-${UUID.randomUUID()}").copy(
                startTime = start,
                endTime = start.plusHours(1),
                signupDeadline = null,
                recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.plusWeeks(3).toLocalDate()),
            )
        val series = database.createRecurringEventSeries(draft, "test@localhost", "Test User").getOrNull()!!
        return series.referenceEventId
    }

    private fun enabledEnv() =
        localTestEnvironment().copy(
            featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS,
            featureTeamsMeeting = no.nav.delta.FeatureAccess.MAINTAINERS,
        )

    @Test
    fun `get event returns bad request for invalid uuid`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }

        val response = client.get("/event/not-a-uuid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid id", response.bodyAsText())
    }

    @Test
    fun `get event supports joined and category filters`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
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
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
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
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
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
            val env = localTestEnvironment()
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
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
