package no.nav.delta.room

import arrow.core.left
import arrow.core.right
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installTestApi
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoomRoutesTest {
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

    private fun enabledEnv() =
        localTestEnvironment()
            .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS)

    @Test
    fun `get rooms lists returns 400 when feature is disabled`() = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response = client.get("/rooms")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get rooms lists returns 400 for a caller outside the maintainers group`() = testApplication {
        val env =
            localTestEnvironment()
                .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS, maintainersGroupId = "another-group")
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response = client.get("/rooms")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get room lists returns rooms lists from the cloud client`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomListsResult =
            listOf(RoomList(displayName = "Bygg A", emailAddress = "bygg-a@nav.no")).right()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response = client.get("/rooms")

        assertEquals(HttpStatusCode.OK, response.status)
        val roomLists = readJson<List<RoomList>>(response.bodyAsText())
        assertEquals(1, roomLists.size)
        assertEquals("bygg-a@nav.no", roomLists.single().emailAddress)
    }

    @Test
    fun `get rooms in a room list returns rooms from the cloud client`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomsResult["bygg-a@nav.no"] =
            listOf(
                RoomInfo(
                    displayName = "Møterom 1",
                    emailAddress = "room1@nav.no",
                    capacity = 6,
                    building = "Bygg A",
                    floorLabel = "3. etasje",
                    isWheelChairAccessible = true,
                )
            ).right()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response = client.get("/rooms/bygg-a@nav.no")

        assertEquals(HttpStatusCode.OK, response.status)
        val rooms = readJson<List<RoomInfo>>(response.bodyAsText())
        assertEquals("room1@nav.no", rooms.single().emailAddress)
    }

    @Test
    fun `post availability returns 400 when feature is disabled`() = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response =
            client.post("/rooms/availability") {
                contentType(ContentType.Application.Json)
                setBody(availabilityRequestJson())
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `post availability returns 400 when roomEmails is empty`() = testApplication {
        val env = enabledEnv()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response =
            client.post("/rooms/availability") {
                contentType(ContentType.Application.Json)
                setBody(availabilityRequestJson(roomEmails = "[]"))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `post availability returns 400 when startTime is after endTime`() = testApplication {
        val env = enabledEnv()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response =
            client.post("/rooms/availability") {
                contentType(ContentType.Application.Json)
                setBody(
                    availabilityRequestJson(
                        startTime = "2026-01-02T10:00:00",
                        endTime = "2026-01-01T11:00:00",
                    )
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `post availability returns availability from the cloud client`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomAvailabilityResult =
            listOf(RoomAvailability(emailAddress = "room1@nav.no", availabilityView = "0", error = null)).right()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response =
            client.post("/rooms/availability") {
                contentType(ContentType.Application.Json)
                setBody(availabilityRequestJson())
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val availability = readJson<List<RoomAvailability>>(response.bodyAsText())
        assertEquals("room1@nav.no", availability.single().emailAddress)
    }

    @Test
    fun `post availability returns 502 when the cloud client fails`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomAvailabilityResult = RuntimeException("graph failure").left()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response =
            client.post("/rooms/availability") {
                contentType(ContentType.Application.Json)
                setBody(availabilityRequestJson())
            }

        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `get rooms in a list and availability return 400 for a caller outside the maintainers group`() = testApplication {
        val env =
            localTestEnvironment()
                .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS, maintainersGroupId = "another-group")
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        assertEquals(HttpStatusCode.BadRequest, client.get("/rooms/bygg-a@nav.no").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/rooms/availability") {
                contentType(ContentType.Application.Json)
                setBody(availabilityRequestJson())
            }.status,
        )
    }

    @Test
    fun `room lists are cached after a successful load`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomListsResult = listOf(RoomList(displayName = "Bygg A", emailAddress = "bygg-a@nav.no")).right()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        client.get("/rooms")
        client.get("/rooms")

        assertEquals(1, cloudClient.roomListsCalls)
    }

    @Test
    fun `a failed room list load returns 502 and is not cached`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomListsResult = RuntimeException("graph failure").left()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        assertEquals(HttpStatusCode.BadGateway, client.get("/rooms").status)

        cloudClient.roomListsResult = listOf(RoomList(displayName = "Bygg A", emailAddress = "bygg-a@nav.no")).right()
        val retry = client.get("/rooms")

        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals("bygg-a@nav.no", readJson<List<RoomList>>(retry.bodyAsText()).single().emailAddress)
        assertEquals(2, cloudClient.roomListsCalls)
    }

    @Test
    fun `a failed rooms load returns 502 and is not cached`() = testApplication {
        val env = enabledEnv()
        cloudClient.roomsResult["bygg-a@nav.no"] = RuntimeException("graph failure").left()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        assertEquals(HttpStatusCode.BadGateway, client.get("/rooms/bygg-a@nav.no").status)
        cloudClient.roomsResult["bygg-a@nav.no"] = emptyList<RoomInfo>().right()
        assertEquals(HttpStatusCode.OK, client.get("/rooms/bygg-a@nav.no").status)
        assertEquals(2, cloudClient.roomsCalls)
    }

    @Test
    fun `search returns matching rooms, caches the full room list and validates input`() = testApplication {
        val env = enabledEnv()
        cloudClient.allRoomsResult =
            listOf(
                RoomInfo("(RV) FYA1 - A347 Kaptein - Videokonf", "a347@nav.no", 6, null, null, null),
                RoomInfo("(RV) FYA1 - AU01", "au01@nav.no", 100, null, null, null),
            ).right()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        val response = client.get("/rooms/search?q=fya1%20kaptein")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("a347@nav.no", readJson<List<RoomInfo>>(response.bodyAsText()).single().emailAddress)

        assertEquals(2, readJson<List<RoomInfo>>(client.get("/rooms/search?q=fya1").bodyAsText()).size)
        assertEquals(1, cloudClient.allRoomsCalls)
        assertEquals(HttpStatusCode.BadRequest, client.get("/rooms/search?q=f").status)
    }

    @Test
    fun `search returns 400 when room booking is disabled`() = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { roomApi(cloudClient, env) } }

        assertEquals(HttpStatusCode.BadRequest, client.get("/rooms/search?q=fya1").status)
    }

    private fun availabilityRequestJson(
        roomEmails: String = """["room1@nav.no"]""",
        startTime: String = "2026-01-01T09:00:00",
        endTime: String = "2026-01-01T10:00:00",
    ) = """
        {
          "roomEmails": $roomEmails,
          "startTime": "$startTime",
          "endTime": "$endTime",
          "availabilityViewInterval": 30
        }
    """.trimIndent()
}
