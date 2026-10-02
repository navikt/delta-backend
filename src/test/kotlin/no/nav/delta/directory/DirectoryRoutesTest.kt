package no.nav.delta.directory

import arrow.core.right
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
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DirectoryRoutesTest {
    @Test
    fun `people search requires the rollout feature`() = testApplication {
        val env = localTestEnvironment()
        application {
            installDeltaApiPlugins(env, NoopJwkProvider)
            routing { directoryApi(DummyCloudClient(), env) }
        }
        assertEquals(HttpStatusCode.BadRequest, client.get("/directory/search?q=alice").status)
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
        assertEquals(HttpStatusCode.BadRequest, client.get("/directory/search?q=a").status)
        val response = client.get("/directory/search?q=alice")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("alice@nav.no", readJson<List<DirectoryPerson>>(response.bodyAsText()).single().email)
    }
}
