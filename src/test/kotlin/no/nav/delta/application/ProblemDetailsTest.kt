package no.nav.delta.application

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import java.util.UUID
import no.nav.delta.faggruppe.faggruppeApi
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.assertProblem
import no.nav.delta.support.installTestApi
import no.nav.delta.support.localTestEnvironment
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProblemDetailsTest {
    private lateinit var testDatabase: TestDatabase
    private lateinit var database: DatabaseInterface

    @BeforeAll
    fun setup() {
        testDatabase = TestDatabase.create()
        database = testDatabase.database
    }

    @AfterAll
    fun tearDown() {
        testDatabase.close()
    }

    @Test
    fun `malformed json is a problem response`() = testApplication {
        val env = localTestEnvironment()
        val cloudClient = RecordingCloudClient()
        application { installTestApi(env, database) { faggruppeApi(database, cloudClient, env) } }

        val response =
            client.post("/api/faggrupper") {
                contentType(ContentType.Application.Json)
                setBody("{not json")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertProblem(response, code = "INVALID_BODY")
    }

    @Test
    fun `unknown faggruppe is a problem response`() = testApplication {
        val env = localTestEnvironment()
        val cloudClient = RecordingCloudClient()
        application { installTestApi(env, database) { faggruppeApi(database, cloudClient, env) } }

        val response = client.get("/api/faggrupper/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertProblem(response)
    }

    @Test
    fun `invalid uuid is a problem response`() = testApplication {
        val env = localTestEnvironment()
        val cloudClient = RecordingCloudClient()
        application { installTestApi(env, database) { faggruppeApi(database, cloudClient, env) } }

        val response = client.get("/api/faggrupper/not-a-uuid")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertProblem(response, detail = "Ugyldig id.")
    }

    @Test
    fun `unknown api route is a problem response`() = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { } }

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertProblem(response)
    }

    @Test
    fun `unhandled exceptions hide internals and carry a request id`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                get("/api/boom") { error("secret internal detail") }
            }
        }

        val response = client.get("/api/boom") { header("X-Request-Id", "req-123") }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = assertProblem(response, code = "INTERNAL_ERROR")
        assertEquals("req-123", body["requestId"])
        assertFalse(response.bodyAsText().contains("secret internal detail"))
    }
}
