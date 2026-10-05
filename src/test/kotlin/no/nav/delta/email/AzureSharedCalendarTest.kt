package no.nav.delta.email

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.microsoft.graph.serviceclient.GraphServiceClient
import com.microsoft.graph.models.ResponseType
import java.time.OffsetDateTime
import no.nav.delta.event.Participant
import no.nav.delta.event.RoomBookingStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AzureSharedCalendarTest {
    private val mapper = jacksonObjectMapper()
    private val requests = mutableListOf<Request>()
    private val payloads = mutableListOf<JsonNode>()
    private var responseHeaders = emptyMap<String, String>()

    @Test
    fun `typed distribution list is rejected before sending a meeting invitation`() {
        val client = client(200 to """{"value":[{"id":"group-id"}]}""")
        val error = client.createSharedEvent(
            sharedTestEvent(), listOf(Participant("group@nav.no", "Group")), "persisted",
        ).leftOrNull() as SharedGraphException
        assertEquals(400, error.httpStatus)
        assertEquals("GroupInvitationsNotSupported", error.code)
        assertTrue(requests.all { it.method == "GET" })
    }

    private fun client(vararg responses: Pair<Int, String>, individualLookups: Boolean = false): AzureCloudClient {
        val queue = ArrayDeque(responses.toList())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                payloads.add(mapper.readTree(buffer.readUtf8()))
            }
            val (status, body) = if (individualLookups && request.url.encodedPath.endsWith("/groups")) {
                200 to """{"value":[]}"""
            } else queue.removeFirst()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("stub")
                .body(body.toResponseBody("application/json".toMediaType()))
                .apply { responseHeaders.forEach { (name, value) -> header(name, value) } }.build()
        }.build()
        return AzureCloudClient("delta@nav.no", GraphServiceClient(http))
    }

    @Test
    fun `shared creation SDK payload addresses humans and resource and retains transaction id on retries`() {
        val client = client(
            201 to """{"id":"shared","onlineMeeting":{"joinUrl":"https://teams.microsoft.com/native"}}""",
            201 to """{"id":"shared","onlineMeeting":{"joinUrl":"https://teams.microsoft.com/native"}}""",
            individualLookups = true,
        )
        val attendees = listOf(Participant("host@nav.no", "Host"), Participant("person@nav.no", "Person"))
        repeat(2) {
            assertEquals("shared", client.createSharedEvent(sharedTestEvent(), attendees, "persisted").getOrNull()?.calendarEventId)
        }
        payloads.forEach { payload ->
            assertEquals("persisted", payload["transactionId"].asText())
            assertTrue(payload["responseRequested"].asBoolean())
            assertTrue(payload["isOnlineMeeting"].asBoolean())
            assertEquals("teamsForBusiness", payload["onlineMeetingProvider"].asText())
            assertEquals(listOf("host@nav.no", "person@nav.no", "room@nav.no"),
                payload["attendees"].map { it["emailAddress"]["address"].asText() })
            assertEquals(listOf("required", "required", "resource"), payload["attendees"].map { it["type"].asText() })
            assertFalse(payload["body"]["content"].asText().contains("teams.microsoft.com"))
        }
        assertTrue(requests.filter { it.method != "GET" }.all { it.method == "POST" && it.url.encodedPath.endsWith("/calendar/events") })
    }

    @Test
    fun `attendees PATCH contains only attendees and round trips Graph status without invented responses`() {
        val client = client(200 to "{}", 200 to "{}", individualLookups = true)
        val time = OffsetDateTime.parse("2026-10-01T08:00:00Z")
        val attendees = listOf(
            SharedCalendarAttendee("person@nav.no", "Person", ResponseType.Accepted, time),
            SharedCalendarAttendee("new@nav.no", "New"),
            SharedCalendarAttendee("room@nav.no", "Room", ResponseType.Declined, time, true),
        )
        assertTrue(client.updateSharedAttendees("shared", attendees, "opaque-change-key").isRight())
        assertTrue(client.updateSharedAttendees("shared", attendees, """W/"real-etag"""").isRight())
        payloads.forEach { payload ->
            assertEquals(setOf("attendees"), payload.fieldNames().asSequence().toSet())
            assertEquals("accepted", payload["attendees"][0]["status"]["response"].asText())
            assertEquals(time.toInstant().toString(), OffsetDateTime.parse(payload["attendees"][0]["status"]["time"].asText()).toInstant().toString())
            assertFalse(payload["attendees"][1].has("status"))
            assertEquals("declined", payload["attendees"][2]["status"]["response"].asText())
        }
        val patches = requests.filter { it.method == "PATCH" }
        assertNull(patches[0].header("If-Match"))
        assertEquals("""W/"real-etag"""", patches[1].header("If-Match"))
    }

    @Test
    fun `snapshot exposes existing Graph responses version Teams and cancellation without assumptions`() {
        val client = client(200 to """
            {"id":"shared","changeKey":"opaque","@odata.etag":"W/\"actual\"","isCancelled":true,
             "body":{"content":"<p>existing</p>","contentType":"html"},
             "attendees":[
               {"emailAddress":{"address":"person@nav.no","name":"Person"},"type":"required",
                "status":{"response":"tentativelyAccepted","time":"2026-10-01T08:00:00Z"}},
               {"emailAddress":{"address":"room@nav.no","name":"Room"},"type":"resource","status":{"response":"declined"}}],
             "onlineMeeting":{"joinUrl":"https://teams.microsoft.com/native","conferenceId":"123",
                              "tollNumber":"+47","quickDial":"+47,,123#"}}
        """.trimIndent(), 200 to """{"value":[]}""")
        val snapshot = client.getSharedEvent("shared").getOrNull()!!
        assertEquals("opaque", snapshot.changeKey)
        assertEquals("""W/"actual"""", snapshot.etag)
        assertEquals("<p>existing</p>", snapshot.body)
        assertEquals(ResponseType.TentativelyAccepted, snapshot.attendees[0].response)
        assertEquals(OffsetDateTime.parse("2026-10-01T08:00:00Z"), snapshot.attendees[0].responseTime)
        assertEquals(RoomBookingStatus.DECLINED, snapshot.roomStatus)
        assertEquals("https://teams.microsoft.com/native", snapshot.teamsJoinUrl)
        assertEquals("123", snapshot.teamsConferenceId)
        assertEquals("+47,,123#", snapshot.teamsDialIn)
        assertTrue(snapshot.isCancelled)
    }

    @Test
    fun `details edit fetches body first and preserves native Teams HTML once without attendee patch`() {
        val native = """<div class="teams-native"><a href="https://teams.microsoft.com/native">Join</a></div>"""
        val body = """<html><body><div id="delta-shared-description"><div>Old description</div></div>$native</body></html>"""
        val existing = mapper.writeValueAsString(mapOf("id" to "shared", "isOnlineMeeting" to true,
            "body" to mapOf("contentType" to "html", "content" to body)))
        val returned = """{"id":"shared","onlineMeeting":{"joinUrl":"https://teams.microsoft.com/native"}}"""
        val client = client(200 to existing, 200 to returned, 200 to returned)
        val edited = sharedTestEvent().copy(description = "<div>New description</div>",
            teamsJoinUrl = "https://teams.microsoft.com/legacy-do-not-append")
        assertEquals("https://teams.microsoft.com/native",
            client.updateSharedDetails("shared", edited).getOrNull()?.teamsJoinUrl)
        assertEquals(listOf("GET", "PATCH", "GET"), requests.map { it.method })
        val payload = payloads.single()
        assertFalse(payload.has("attendees"))
        val updatedBody = payload["body"]["content"].asText()
        assertTrue(updatedBody.contains(native))
        assertEquals(1, "teams-native".toRegex().findAll(updatedBody).count())
        assertTrue(updatedBody.contains("<div>New description</div>"))
        assertFalse(updatedBody.contains("Old description"))
        assertFalse(updatedBody.contains("legacy-do-not-append"))
    }

    @Test
    fun `cancellation uses cancel action not deletion and retains structured retry 404`() {
        val client = client(202 to "", 404 to """{"error":{"code":"ErrorItemNotFound","message":"person@nav.no private payload"}}""")
        assertTrue(client.cancelSharedEvent("shared").isRight())
        val error = client.cancelSharedEvent("shared").leftOrNull() as SharedGraphException
        assertEquals(404, error.httpStatus)
        assertEquals("ErrorItemNotFound", error.code)
        assertFalse(error.message!!.contains("person@nav.no"))
        assertTrue(requests.all { it.method == "POST" && it.url.encodedPath.endsWith("/events/shared/cancel") })
    }

    @Test
    fun `people search escapes filter quotes selects basic user fields and only returns Nav mail over pages`() {
        val client = client(
            200 to """{"value":[
                {"id":"1","displayName":"O'Connor","mail":"one@nav.no"},
                {"id":"external","displayName":"External","mail":"one@other.no"},
                {"id":"no-mail","displayName":"No mail","userPrincipalName":"fallback@nav.no"}],
                "@odata.nextLink":"https://graph.microsoft.com/v1.0/users?%24skiptoken=second"}""",
            200 to """{"value":[{"id":"2","displayName":"Two","mail":"two@NAV.NO"},
                {"id":"group","displayName":"Group","mail":"group@nav.no","@odata.type":"#microsoft.graph.group"}]}""",
        )
        val people = client.searchPeople("O'Connor").getOrNull()!!
        assertEquals(listOf(DirectoryPerson("1", "O'Connor", "one@nav.no"), DirectoryPerson("2", "Two", "two@NAV.NO")), people)
        assertEquals("id,displayName,mail", requests[0].url.queryParameter("\$select"))
        assertEquals("startswith(displayName,'O''Connor') or startswith(mail,'O''Connor')", requests[0].url.queryParameter("\$filter"))
        assertEquals("50", requests[0].url.queryParameter("\$top"))
        assertEquals("second", requests[1].url.queryParameter("\$skiptoken"))
        assertTrue(requests.all { it.url.encodedPath == "/v1.0/users" })
    }

    @Test
    fun `unanswered resource reports pending room without synthesizing its Graph response`() {
        val client = client(200 to """{"attendees":[{"type":"resource","emailAddress":{"address":"room@nav.no","name":"Room"}}]}""")
        val snapshot = client.getSharedEvent("shared").getOrNull()!!
        assertEquals(RoomBookingStatus.PENDING, snapshot.roomStatus)
        assertNull(snapshot.attendees.single().response)
        assertNull(snapshot.attendees.single().responseTime)
    }

    @Test
    fun `create refreshes native meeting metadata when Teams provisioning lags`() {
        val client = client(
            201 to """{"id":"shared"}""",
            200 to """{"id":"shared","onlineMeeting":{"joinUrl":"https://teams.microsoft.com/native"}}""",
        )
        assertEquals("https://teams.microsoft.com/native",
            client.createSharedEvent(sharedTestEvent(), emptyList(), "persisted").getOrNull()?.teamsJoinUrl)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals("persisted", payloads.single()["transactionId"].asText())
    }

    @Test
    fun `failures retain HTTP status error code and Retry-After without exposing error messages`() {
        responseHeaders = mapOf("Retry-After" to "37")
        val statuses = listOf(401, 403, 404, 412, 429, 503)
        val client = client(*statuses.map {
            it to """{"error":{"code":"RetryableCode","message":"private payload person@nav.no"}}"""
        }.toTypedArray())
        statuses.forEach { status ->
            val error = client.getSharedEvent("shared").leftOrNull() as SharedGraphException
            assertEquals(status, error.httpStatus)
            assertEquals("RetryableCode", error.code)
            assertEquals(37L, error.retryAfterSeconds)
            assertNotNull(error.cause)
            assertFalse(error.message!!.contains("person"))
            assertFalse(error.message!!.contains("payload"))
        }
    }

    @Test
    fun `empty attendees PATCH explicitly clears list and never emits opaque or malformed If-Match`() {
        val client = client(200 to "{}", 200 to "{}")
        assertTrue(client.updateSharedAttendees("shared", emptyList(), "\"tag\"\r\nX-Injected: value").isRight())
        assertTrue(client.updateSharedAttendees("shared", emptyList(), "*").isRight())
        assertTrue(requests.all { it.header("If-Match") == null })
        payloads.forEach { assertEquals(mapper.readTree("""{"attendees":[]}"""), it) }
    }

    @Test
    fun `details edit refuses to overwrite missing native meeting body`() {
        val client = client(200 to """{"isOnlineMeeting":true}""")
        val error = client.updateSharedDetails("shared", sharedTestEvent()).leftOrNull() as SharedGraphException
        assertEquals("MissingMeetingBody", error.code)
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test
    fun `directory search stops at result and page bounds`() {
        val users = (1..60).map { mapOf("id" to "$it", "displayName" to "Person $it", "mail" to "person$it@nav.no") }
        val client = client(200 to mapper.writeValueAsString(mapOf("value" to users,
            "@odata.nextLink" to "https://graph.microsoft.com/v1.0/users?%24skiptoken=unused")))
        assertEquals(50, client.searchPeople("Person").getOrNull()!!.size)
        assertEquals(1, requests.size)
        requests.clear()
        val pages = (1..5).map { page ->
            200 to """{"value":[],"@odata.nextLink":"https://graph.microsoft.com/v1.0/users?%24skiptoken=$page"}"""
        }
        assertTrue(client(*pages.toTypedArray()).searchPeople("Person").isRight())
        assertEquals(5, requests.size)
    }

    @Test
    fun `directory search never follows a foreign host or non-user nextLink`() {
        val client = client(200 to """{"value":[],"@odata.nextLink":"https://example.org/steal"}""")
        assertEquals("InvalidDirectoryNextLink", (client.searchPeople("Person").leftOrNull() as SharedGraphException).code)
        assertEquals(1, requests.size)
    }

    @Test
    fun `missing transaction id and null Graph response fail explicitly rather than inventing event ids`() {
        val client = client(201 to "{}")
        assertEquals("MissingTransactionId",
            (client.createSharedEvent(sharedTestEvent(), emptyList(), " ").leftOrNull() as SharedGraphException).code)
        assertTrue(requests.isEmpty())
        assertEquals("InvalidGraphResponse",
            (client.createSharedEvent(sharedTestEvent(), emptyList(), "persisted").leftOrNull() as SharedGraphException).code)
    }

    @Test
    fun `forwarded Nav group is not an individual even though mail suffix is Nav`() {
        val client = client(
            200 to """{"attendees":[
                {"emailAddress":{"address":"group@nav.no","name":"Forwarded group"},"type":"required"},
                {"emailAddress":{"address":"person@nav.no","name":"Person"},"type":"required"},
                {"emailAddress":{"address":"room@nav.no","name":"Room"},"type":"resource"}]}""",
            200 to """{"value":[{"id":"group-id"}]}""",
            200 to """{"value":[]}""",
        )
        val snapshot = client.getSharedEvent("shared").getOrNull()!!
        assertFalse(snapshot.attendees[0].isIndividual)
        assertTrue(snapshot.attendees[1].isIndividual)
        assertTrue(snapshot.attendees[2].isResource)
        assertEquals(3, requests.size)
        requests.drop(1).forEach { request ->
            assertEquals("/v1.0/groups", request.url.encodedPath)
            assertEquals("id", request.url.queryParameter("\$select"))
            assertEquals("1", request.url.queryParameter("\$top"))
        }
        assertTrue(requests[1].url.queryParameter("\$filter")!!.contains("mail eq 'group@nav.no'"))
        assertTrue(requests[1].url.queryParameter("\$filter")!!.contains("proxyAddresses/any"))
    }

    @Test
    fun `reconciliation checks only previously unknown forwarded attendees`() {
        val client = client(
            200 to """{"attendees":[
                {"emailAddress":{"address":"host@nav.no"},"type":"required"},
                {"emailAddress":{"address":"person@nav.no"},"type":"required"},
                {"emailAddress":{"address":"forwarded-group@nav.no"},"type":"required"}]}""",
            200 to """{"value":[{"id":"group-id"}]}""",
        )
        val snapshot = client.getSharedEventForSync(
            "shared", setOf("host@nav.no", "person@nav.no"),
        ).getOrNull()!!
        assertTrue(snapshot.attendees[0].isIndividual)
        assertTrue(snapshot.attendees[1].isIndividual)
        assertFalse(snapshot.attendees[2].isIndividual)
        assertEquals(2, requests.size)
        assertTrue(requests.last().url.queryParameter("\$filter")!!.contains("forwarded-group@nav.no"))
    }

    @Test
    fun `cancellation snapshot does not perform per-attendee group lookups`() {
        val client = client(
            200 to """{"attendees":[
                {"emailAddress":{"address":"host@nav.no"},"type":"required"},
                {"emailAddress":{"address":"group@nav.no"},"type":"required"}]}""",
        )
        val snapshot = client.getSharedEventForCancellation("shared").getOrNull()!!
        assertEquals(2, snapshot.attendees.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun `attendee patch validates only newly added addresses`() {
        val client = client(
            200 to """{"value":[]}""",
            200 to "",
        )
        assertTrue(client.updateSharedAttendees(
            "shared",
            listOf(
                SharedCalendarAttendee("existing@nav.no", "Existing"),
                SharedCalendarAttendee("new@nav.no", "New"),
            ),
            null,
            setOf("existing@nav.no"),
        ).isRight())
        assertEquals(2, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("/groups"))
        assertTrue(requests[1].url.encodedPath.endsWith("/events/shared"))
    }

    @Test
    fun `group classification directory failures never silently classify attendee as user`() {
        val client = client(
            200 to """{"attendees":[{"emailAddress":{"address":"group@nav.no"},"type":"required"}]}""",
            403 to """{"error":{"code":"Authorization_RequestDenied","message":"private group"}}""",
        )
        val error = client.getSharedEvent("shared").leftOrNull() as SharedGraphException
        assertEquals(403, error.httpStatus)
        assertEquals("Authorization_RequestDenied", error.code)
        assertFalse(error.message!!.contains("private"))
    }

    @Test
    fun `classification memoizes duplicate addresses within snapshot and escapes mail filter`() {
        val client = client(
            200 to """{"attendees":[
                {"emailAddress":{"address":"o'connor@nav.no"},"type":"required"},
                {"emailAddress":{"address":"O'CONNOR@NAV.NO"},"type":"required"}]}""",
            200 to """{"value":[]}""",
        )
        assertTrue(client.getSharedEvent("shared").getOrNull()!!.attendees.all { it.isIndividual })
        assertEquals(2, requests.size)
        assertTrue(requests[1].url.queryParameter("\$filter")!!.contains("mail eq 'o''connor@nav.no'"))
    }

    @Test
    fun `creation retains graph id but never invents missing native Teams details as sync success`() {
        val client = client(201 to """{"id":"created"}""", 200 to """{"id":"created"}""")
        val result = client.createSharedEvent(sharedTestEvent(), emptyList(), "persisted").getOrNull()!!
        assertEquals("created", result.calendarEventId)
        assertNull(result.teamsJoinUrl)
        assertNull(result.teamsConferenceId)
        assertNull(result.teamsDialIn)
    }
}
