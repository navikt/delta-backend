package no.nav.delta.directory

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import arrow.core.right
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.delta.FeatureAccess
import no.nav.delta.application.installDeltaApiPlugins
import no.nav.delta.email.CloudClient
import no.nav.delta.email.DirectoryPerson
import no.nav.delta.email.DummyCloudClient
import no.nav.delta.support.NoopJwkProvider
import no.nav.delta.support.assertProblem
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DirectoryRoutesTest {
    private val jwtSecret = "directory-routes-test-secret-that-is-long-enough"
    private val jwtVerifier = JWT.require(Algorithm.HMAC256(jwtSecret)).build()

    @Test
    fun `people search requires the rollout feature`() = testApplication {
        val env = localTestEnvironment()
        application {
            installDeltaApiPlugins(env, NoopJwkProvider)
            routing { directoryApi(DummyCloudClient(), env) }
        }
        val disabled = client.get("/directory/search?q=alice")
        assertEquals(HttpStatusCode.BadRequest, disabled.status)
        assertProblem(disabled, "Personsøk er ikke slått på.")
    }

    @Test
    fun `people search validates query and returns basic people only`() = testApplication {
        val env = localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL)
        val cloud = object : CloudClient by DummyCloudClient() {
            override fun searchPeople(query: String) =
                listOf(DirectoryPerson("person-id", "Alice", "alice@nav.no")).right()
        }
        application {
            installDeltaApiPlugins(env, NoopJwkProvider)
            routing { directoryApi(cloud, env) }
        }
        val tooShort = client.get("/directory/search?q=a")
        assertEquals(HttpStatusCode.BadRequest, tooShort.status)
        assertProblem(tooShort, "Søket må inneholde 2 til 100 tegn.")
        val response = client.get("/directory/search?q=alice")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("alice@nav.no", readJson<List<DirectoryPerson>>(response.bodyAsText()).single().email)
    }

    @Test
    fun `people search denies application tokens but allows user tokens`() = testApplication {
        val env = localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL)
        var searchCalls = 0
        val cloud = object : CloudClient by DummyCloudClient() {
            override fun searchPeople(query: String) =
                listOf(DirectoryPerson("person-id", "Alice", "alice@nav.no"))
                    .also { searchCalls++ }
                    .right()
        }
        application {
            installDeltaApiPlugins(env, NoopJwkProvider, jwtVerifier)
            routing { directoryApi(cloud, env) }
        }

        for (token in listOf(applicationToken(emptyList()), applicationToken(listOf("delta.read")))) {
            val response = client.get("/directory/search?q=alice") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertProblem(response, "Personsøk er kun tilgjengelig for brukere.")
        }
        assertEquals(0, searchCalls)

        val userResponse = client.get("/directory/search?q=alice") { bearerAuth(userToken()) }
        assertEquals(HttpStatusCode.OK, userResponse.status)
        assertEquals(1, searchCalls)
    }

    private fun applicationToken(roles: List<String>): String =
        JWT.create()
            .withClaim("idtyp", "app")
            .withArrayClaim("roles", roles.toTypedArray())
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun userToken(): String =
        JWT.create()
            .withClaim("preferred_username", "user@example.com")
            .sign(Algorithm.HMAC256(jwtSecret))
}
