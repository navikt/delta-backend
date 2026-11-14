package no.nav.delta.event

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.delta.calendar.SharedCalendarRepository
import no.nav.delta.email.DummyCloudClient
import no.nav.delta.support.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class WaitlistRoutesTest {
    @Test
    fun `user endpoints derive identity and never expose the queue to non hosts`() = testApplication {
        TestDatabase.create().use { fixture ->
            val database = fixture.database
            val calendar = SharedCalendarRepository(database)
            val event = calendar.create(CreateEvent(
                title = "Route queue", description = "Synthetic event", location = "Office", public = true,
                startTime = LocalDateTime.now().plusDays(2), endTime = LocalDateTime.now().plusDays(2).plusHours(1),
                signupDeadline = LocalDateTime.now().plusDays(1), participantLimit = 1, waitlistEnabled = true,
            ), "host@nav.no", "Host").getOrNull()!!
            application { installFullTestApplication(localTestEnvironment(), database, DummyCloudClient()) }
            val joined = client.post("/user/event/${event.id}/waitlist")
            assertEquals(HttpStatusCode.OK, joined.status)
            assertEquals("no-store", joined.headers["Cache-Control"])
            assertEquals(1, readJson<WaitlistState>(joined.bodyAsText()).position)
            assertEquals(1, readJson<List<MyWaitlistEvent>>(client.get("/user/waitlist").bodyAsText()).size)
            assertEquals(HttpStatusCode.Forbidden, client.get("/admin/event/${event.id}/waitlist").status)
            assertEquals(HttpStatusCode.OK, client.delete("/user/event/${event.id}/waitlist").status)
            assertTrue(readJson<List<MyWaitlistEvent>>(client.get("/user/waitlist").bodyAsText()).isEmpty())
            val missing = client.get("/user/event/not-a-uuid/waitlist")
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertProblem(missing, code = "INVALID_ID")
        }
    }

    @Test
    fun `waitlist endpoints deny machine tokens regardless of read role`() = testApplication {
        TestDatabase.create().use { fixture ->
            val algorithm = Algorithm.HMAC256("waitlist-route-test-secret-long-enough")
            val verifier = JWT.require(algorithm).build()
            application {
                installTestApi(localTestEnvironment(), fixture.database, verifier) { waitlistApi(fixture.database) }
            }
            val id = java.util.UUID.randomUUID()
            for (roles in listOf(emptyList(), listOf("delta.read"))) {
                val token = JWT.create().withClaim("idtyp", "app")
                    .withArrayClaim("roles", roles.toTypedArray()).sign(algorithm)
                assertEquals(HttpStatusCode.Forbidden, client.get("/user/waitlist") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/user/event/$id/waitlist") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.post("/user/event/$id/waitlist") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.delete("/user/event/$id/waitlist") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/admin/event/$id/waitlist") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.delete("/admin/event/$id/waitlist") { bearerAuth(token) }.status)
            }
        }
    }
}
