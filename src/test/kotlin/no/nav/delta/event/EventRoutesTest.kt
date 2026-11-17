package no.nav.delta.event

import no.nav.delta.support.assertProblem
import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.client.request.bearerAuth
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
import no.nav.delta.support.markEventsAsLegacy
import no.nav.delta.support.testObjectMapper
import no.nav.delta.support.waitUntilSuspending
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventRoutesTest {
    private val testJwtSecret = "event-routes-test-secret-that-is-long-enough"
    private val testJwtVerifier: JWTVerifier =
        JWT.require(Algorithm.HMAC256(testJwtSecret)).build()
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
    fun `admin put always creates a shared event and registers host`() = testApplication {
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
        assertEquals(InviteMode.SHARED, fullEvent.event.inviteMode)
        assertEquals(CalendarSyncStatus.PENDING, fullEvent.event.calendarSyncStatus)
        assertTrue(cloudClient.createdCalendarEventIds.isEmpty())
        assertTrue(cloudClient.createdMasterEvents.isEmpty())
    }

    @Test
    fun `M2M token with delta read role can read approved endpoints`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val token = m2mToken(listOf("delta.read"))
        val eventsResponse = client.get("/event") { bearerAuth(token) }
        val categoriesResponse = client.get("/category") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, eventsResponse.status)
        assertEquals(HttpStatusCode.OK, categoriesResponse.status)
    }

    @Test
    fun `M2M attendance lookup filters by category participant and event start period`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val category = database.createCategory(CreateCategory(shortName("attendance"))).getOrNull()!!
        val otherCategory = database.createCategory(CreateCategory(shortName("other"))).getOrNull()!!
        val from = LocalDateTime.now().plusDays(10).withNano(0)
        val to = from.plusDays(2)

        fun createRegisteredEvent(
            title: String,
            startTime: LocalDateTime,
            public: Boolean,
            categoryId: Int,
            email: String = "person@example.com",
        ): Event {
            val event =
                database.addEvent(
                    futureEvent(title).copy(
                        startTime = startTime,
                        endTime = startTime.plusHours(1),
                        public = public,
                    )
                )
            database.registerForEvent(event.id.toString(), email, "Test Person")
            database.setCategories(event.id.toString(), listOf(categoryId))
            return event
        }

        val included = createRegisteredEvent("included-${UUID.randomUUID()}", from, true, category.id)
        createRegisteredEvent("private-${UUID.randomUUID()}", from.plusHours(1), false, category.id)
        createRegisteredEvent("outside-${UUID.randomUUID()}", to, true, category.id)
        createRegisteredEvent("other-category-${UUID.randomUUID()}", from, true, otherCategory.id)
        createRegisteredEvent("other-user-${UUID.randomUUID()}", from, true, category.id, "other@example.com")

        val response =
            client.get(
                "/event?categories=${category.id}&participantEmail=person%40example.com&from=$from&to=$to"
            ) {
                bearerAuth(m2mToken(listOf("delta.read")))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val events = readJson<List<FullEvent>>(response.bodyAsText())
        assertEquals(listOf(included.id), events.map { it.event.id })
    }

    @Test
    fun `M2M attendance lookup requires category email and valid period`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.get("/event?participantEmail=person%40example.com") {
                bearerAuth(m2mToken(listOf("delta.read")))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)

        val from = LocalDateTime.now().plusDays(10).withNano(0)
        val query =
            "/event?categories=1&participantEmail=person%40example.com&from=$from&to=${from.plusDays(1)}"
        val userTokenResponse =
            client.get(query) {
                bearerAuth(userToken())
            }
        assertEquals(HttpStatusCode.BadRequest, userTokenResponse.status)

        val reversedPeriodResponse =
            client.get(
                "/event?categories=1&participantEmail=person%40example.com&from=$from&to=$from"
            ) {
                bearerAuth(m2mToken(listOf("delta.read")))
            }

        assertEquals(HttpStatusCode.BadRequest, reversedPeriodResponse.status)
    }

    @Test
    fun `M2M token without delta read role cannot read events`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val response = client.get("/event") { bearerAuth(m2mToken(emptyList())) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `M2M token with delta read role can read public events by id`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val event = database.addEvent(futureEvent("m2m-public-${UUID.randomUUID()}"))
        database.registerForEvent(event.id.toString(), "person@example.com", "Participant")
        database.registerForEvent(event.id.toString(), "host@example.com", "Host", ParticipantType.HOST)
        database.updateEvent(
            event.copy(
                isOnlineMeeting = true,
                teamsJoinUrl = "https://teams.microsoft.com/test",
                teamsConferenceId = "conference",
                teamsDialIn = "dial-in",
            )
        )

        val response =
            client.get("/event/${event.id}") {
                bearerAuth(m2mToken(listOf("delta.read")))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals(event.id, fullEvent.event.id)
        assertEquals(listOf(Participant("person@example.com", "Participant")), fullEvent.participants)
        assertEquals(listOf(Participant("host@example.com", "Host")), fullEvent.hosts)
        assertEquals(null, fullEvent.event.teamsJoinUrl)
        assertEquals(null, fullEvent.event.teamsConferenceId)
        assertEquals(null, fullEvent.event.teamsDialIn)
    }

    @Test
    fun `M2M private and unknown event ids return the same not found response`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }
        val privateEvent = database.addEvent(
            futureEvent("m2m-private-${UUID.randomUUID()}").copy(public = false)
        )
        val token = m2mToken(listOf("delta.read"))
        val privateResponse = client.get("/event/${privateEvent.id}") { bearerAuth(token) }
        val unknownResponse = client.get("/event/${UUID.randomUUID()}") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NotFound, privateResponse.status)
        assertEquals(HttpStatusCode.NotFound, unknownResponse.status)
        val privateProblem = assertProblem(privateResponse, "Fant ikke arrangementet.") - "requestId"
        val unknownProblem = assertProblem(unknownResponse, "Fant ikke arrangementet.") - "requestId"
        assertEquals(privateProblem, unknownProblem)
    }

    @Test
    fun `M2M event by id requires read role and valid uuid`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }
        val event = database.addEvent(futureEvent("m2m-role-${UUID.randomUUID()}"))
        val forbidden = client.get("/event/${event.id}") { bearerAuth(m2mToken(emptyList())) }
        val invalid = client.get("/event/not-a-uuid") {
            bearerAuth(m2mToken(listOf("delta.read")))
        }

        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }

    @Test
    fun `user token can still read private events by id`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }
        val event = database.addEvent(
            futureEvent("user-private-${UUID.randomUUID()}").copy(public = false)
        )
        val response = client.get("/event/${event.id}") { bearerAuth(userToken()) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(event.id, readJson<FullEvent>(response.bodyAsText()).event.id)
    }

    @Test
    fun `M2M token cannot use caller-specific event filters`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.get("/event?onlyJoined=true") {
                bearerAuth(m2mToken(listOf("delta.read")))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `M2M token with delta read role cannot write events`() = testApplication {
        application {
            val env = localTestEnvironment()
            installTestApi(env, database, testJwtVerifier) {
                eventApi(database, cloudClient, env)
            }
        }

        val response =
            client.put("/admin/event") {
                bearerAuth(m2mToken(listOf("delta.read")))
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
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
        assertProblem(response, "Starttidspunktet må være før sluttidspunktet.")
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
        assertProblem(response, "Romreservasjon er ikke slått på.")
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
        assertProblem(response, "Teams-møter er ikke slått på.")
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
    fun `admin put saves room and teams pending without synchronous Graph calls`() = testApplication {
        val env = enabledEnv()
        application {
            installTestApi(env, database) {
                eventApi(database, cloudClient, env)
            }
        }
        cloudClient.masterEventResult = RuntimeException("graph failure").left()

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    createEventJson(title = "master-create-${UUID.randomUUID()}", sendNotificationEmail = false)
                        .dropLast(2) + ""","roomEmail":"room1@nav.no","roomName":"Room 1","isOnlineMeeting":true}"""
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(cloudClient.createdMasterEvents.isEmpty())

        val fullEvent = readJson<FullEvent>(response.bodyAsText())
        assertEquals(InviteMode.SHARED, fullEvent.event.inviteMode)
        assertEquals(CalendarSyncStatus.PENDING, fullEvent.event.calendarSyncStatus)
        assertEquals(null, fullEvent.event.roomStatus)
        assertEquals(null, fullEvent.event.teamsJoinUrl)

        val storedMasterId = database.getMasterCalendarEventId(fullEvent.event.id.toString()).getOrNull()
        assertEquals(null, storedMasterId)
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
        assertProblem(response, "Romreservasjon er ikke slått på.")
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
        assertProblem(response, "Teams-møter er ikke slått på.")
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
        assertProblem(response, "roomEmail og roomName må settes sammen.")
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

    @Test
    fun `rejected waitlist edit restores the Graph master and leaves the event unchanged`() = testApplication {
        val env = enabledEnv()
        application { installTestApi(env, database) { eventApi(database, cloudClient, env) } }
        val booked = bookedEvent("waitlist-rollback-${UUID.randomUUID()}")
        val original = database.updateEvent(booked.copy(waitlistEnabled = true, participantLimit = 1)).getOrNull()!!
        WaitlistRepository(database).join(original.id, Participant("waiting@nav.no", "Waiting"))

        val response = client.post("/admin/event/${original.id}") {
            contentType(ContentType.Application.Json)
            setBody(createEventJson(title = "Rejected title", sendNotificationEmail = false))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(original.title, database.getEvent(original.id.toString()).getOrNull()!!.title)
        assertEquals(original.title, cloudClient.updatedMasterEvents.last().second.title)
        assertTrue(database.getEvent(original.id.toString()).getOrNull()!!.waitlistEnabled)
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

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `legacy recurring series cannot add occurrences and rejection preserves existing events`(index: Int) = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { eventApi(database, cloudClient, env) } }
        val start = LocalDateTime.now().plusDays(7).withNano(0)
        val draft = futureEvent("legacy-series-${UUID.randomUUID()}").copy(
            startTime = start,
            endTime = start.plusHours(1),
            recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(2)),
        )
        val series = database.createRecurringEventSeries(draft, "test@localhost", "Host").getOrNull()!!
        database.markEventsAsLegacy(series.affectedEvents)
        val before = series.affectedEvents.map { database.getFullEvent(it.id.toString()).getOrNull()!! }
        val selected = before[index].event
        val eventsBefore = database.getEvents().size
        val response = client.post("/admin/event/${selected.id}") {
            contentType(ContentType.Application.Json)
            setBody(testObjectMapper.writeValueAsString(draft.copy(
                title = "Must not be saved",
                startTime = selected.startTime,
                endTime = selected.endTime,
                recurrence = draft.recurrence!!.copy(untilDate = start.toLocalDate().plusWeeks(3)),
                editScope = EventEditScope.UPCOMING,
                sendNotificationEmail = false,
            )))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertProblem(response, "Denne serien bruker en eldre kalendermodell og kan ikke utvides. Opprett en ny serie.")
        assertEquals(before, series.affectedEvents.map { database.getFullEvent(it.id.toString()).getOrNull()!! })
        assertEquals(eventsBefore, database.getEvents().size)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `legacy recurring series can still be edited and shortened`(weeks: Int) = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { eventApi(database, cloudClient, env) } }
        val start = LocalDateTime.now().plusDays(7).withNano(0)
        val draft = futureEvent("legacy-edit-${UUID.randomUUID()}").copy(
            startTime = start,
            endTime = start.plusHours(1),
            recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, start.toLocalDate().plusWeeks(2)),
        )
        val series = database.createRecurringEventSeries(draft, "test@localhost", "Host").getOrNull()!!
        database.markEventsAsLegacy(series.affectedEvents)
        series.affectedEvents.forEach {
            database.setCalendarEventId(it.id.toString(), "test@localhost", "legacy-${it.id}")
        }

        val response = client.post("/admin/event/${series.referenceEventId}") {
            contentType(ContentType.Application.Json)
            setBody(testObjectMapper.writeValueAsString(draft.copy(
                title = "Updated legacy series",
                recurrence = draft.recurrence!!.copy(untilDate = start.toLocalDate().plusWeeks(weeks.toLong())),
                editScope = EventEditScope.UPCOMING,
                sendNotificationEmail = false,
            )))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        series.affectedEvents.take(weeks + 1).forEach {
            val saved = database.getFullEvent(it.id.toString()).getOrNull()!!
            assertEquals("Updated legacy series", saved.event.title)
            assertEquals(InviteMode.PER_PARTICIPANT, saved.event.inviteMode)
            assertEquals(null, saved.event.calendarSyncStatus)
            assertEquals(listOf("test@localhost"), saved.hosts.map { host -> host.email })
            assertEquals("legacy-${it.id}", database.getCalendarEventId(it.id.toString(), "test@localhost").getOrNull())
        }
        series.affectedEvents.drop(weeks + 1).forEach {
            assertTrue(database.getEvent(it.id.toString()).isLeft())
        }
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
        assertProblem(response, "Ugyldig id.")
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

    private fun m2mToken(roles: List<String>): String =
        JWT.create()
            .withClaim("idtyp", "app")
            .withArrayClaim("roles", roles.toTypedArray())
            .sign(Algorithm.HMAC256(testJwtSecret))

    private fun userToken(): String =
        JWT.create()
            .withClaim("preferred_username", "person@example.com")
            .sign(Algorithm.HMAC256(testJwtSecret))

    private fun shortName(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"
}
