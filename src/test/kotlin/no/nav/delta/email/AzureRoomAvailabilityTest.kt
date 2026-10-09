package no.nav.delta.email

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.microsoft.graph.serviceclient.GraphServiceClient
import java.time.LocalDateTime
import java.net.SocketTimeoutException
import no.nav.delta.room.RoomAvailabilityRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AzureRoomAvailabilityTest {
    private val start = LocalDateTime.of(2026, 1, 1, 9, 0)
    private val requests = mutableListOf<Request>()
    private val payloads = mutableListOf<String>()

    private fun client(status: Int, body: String, headers: Map<String, String> = emptyMap()): AzureCloudClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            payloads.add(buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("stub")
                .body(body.toResponseBody("application/json".toMediaType()))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        }.build()
        return AzureCloudClient("delta@example.com", GraphServiceClient(http))
    }

    @Test
    fun `Graph failure includes diagnostic fields and short request context without raw messages`() {
        val client = client(
            400,
            """{"error":{"code":"ErrorInvalidTimeInterval","message":"private upstream details"}}""",
            mapOf("request-id" to "req-short"),
        )

        val error = client.getRoomAvailability(
            listOf("room@example.com"), start, start.plusMinutes(5), 30,
        ).leftOrNull()

        assertNotNull(error)
        val message = error!!.message.orEmpty()
        listOf("status=400", "code=ErrorInvalidTimeInterval", "requestId=req-short",
            "roomCount=1", "durationSeconds=300", "intervalMinutes=30").forEach {
            assertTrue(message.contains(it), message)
        }
        assertFalse(message.contains("private upstream details"), message)
        assertFalse(message.contains("room@example.com"), message)
        assertNotNull(error.cause)
    }

    @Test
    fun `five and ten minute requests preserve the exact time range and interval`() {
        listOf(5L, 10L).forEach { minutes ->
            requests.clear()
            payloads.clear()
            val client = client(
                200, """{"value":[{"scheduleId":"room@example.com","availabilityView":"0"}]}""",
            )

            val result = client.getRoomAvailability(
                listOf("room@example.com"), start, start.plusMinutes(minutes), 30,
            )

            assertTrue(result.isRight(), result.leftOrNull()?.message)
            assertEquals("0", result.getOrNull()?.single()?.availabilityView)
            assertTrue(requests.single().url.encodedPath.endsWith("/calendar/getSchedule"))
            val payload = jacksonObjectMapper().readTree(payloads.single())
            assertEquals(start.toString(), payload["StartTime"]["dateTime"].asText())
            assertEquals(start.plusMinutes(minutes).toString(), payload["EndTime"]["dateTime"].asText())
            assertEquals(30, payload["AvailabilityViewInterval"].asInt())
        }
    }

    @Test
    fun `missing Graph results are failures rather than successful empty availability`() {
        listOf("{}", """{"value":null}""").forEach { body ->
            val result = client(200, body).getRoomAvailability(
                listOf("room@example.com"), start, start.plusMinutes(10), 30,
            )

            assertTrue(result.isLeft(), "Expected failure for $body")
            assertTrue(result.leftOrNull()?.message.orEmpty().contains("InvalidGraphResponse"))
        }
    }

    @Test
    fun `per-room errors retain their code as well as their message`() {
        val result = client(
            200,
            """{"value":[{"scheduleId":"room@example.com","error":{"responseCode":"5006","message":"Too many calendar entries"}}]}""",
        ).getRoomAvailability(listOf("room@example.com"), start, start.plusHours(1), 30)

        assertTrue(result.isRight(), result.leftOrNull()?.message)
        assertEquals("5006: Too many calendar entries", result.getOrNull()?.single()?.error)
    }

    @Test
    fun `availability failures distinguish access throttling and upstream outages`() {
        listOf(
            403 to "denied calendar access",
            404 to "configured calendar mailbox",
            429 to "rate limiting",
            503 to "temporarily unavailable",
        ).forEach { (status, explanation) ->
            val error = client(
                status, """{"error":{"code":"SyntheticFailure","message":"private upstream details"}}""",
            ).getRoomAvailability(listOf("room@example.com"), start, start.plusHours(1), 30)
                .leftOrNull()

            assertTrue(error?.message.orEmpty().contains(explanation), error?.message)
            assertTrue(error?.message.orEmpty().contains("status=$status"), error?.message)
            assertFalse(error?.message.orEmpty().contains("private upstream details"))
        }
    }

    @Test
    fun `request id in the error body takes precedence over the header`() {
        val error = client(
            400,
            """{"error":{"code":"ErrorInvalidTimeInterval","innerError":{"request-id":"req-body"}}}""",
            mapOf("Request-Id" to "req-header"),
        ).getRoomAvailability(listOf("room@example.com"), start, start.plusMinutes(5), 30)
            .leftOrNull()

        assertTrue(error?.message.orEmpty().contains("requestId=req-body"), error?.message)
    }

    @Test
    fun `unsafe upstream codes and request ids are not exposed as structured fields`() {
        val error = client(
            400,
            """{"error":{"code":"private@example.com","message":"private upstream details"}}""",
            mapOf("request-id" to "private@example.com"),
        ).getRoomAvailability(listOf("room@example.com"), start, start.plusMinutes(11), 15)
            .leftOrNull() as RoomAvailabilityException

        assertEquals(400, error.upstreamStatus)
        assertNull(error.errorCode)
        assertNull(error.requestId)
        assertFalse(error.clientDetail.contains("private"))
        assertFalse(error.message.contains("private"))
    }

    @Test
    fun `timeouts without a message still provide a useful diagnostic`() {
        val error = RoomAvailabilityException(
            RoomAvailabilityRequest(listOf("room@example.com"), start, start.plusMinutes(5)),
            SocketTimeoutException(),
        )

        assertTrue(error.clientMessage.contains("Could not connect"))
        assertTrue(error.message.contains("failureType=SocketTimeoutException"))
        assertFalse(error.message.contains("null"))
    }
}
