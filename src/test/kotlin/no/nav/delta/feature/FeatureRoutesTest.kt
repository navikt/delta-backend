package no.nav.delta.feature

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.delta.Environment
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installTestApi
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeatureRoutesTest {
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
    fun `both features are off by default`() = testApplication {
        val env = localTestEnvironment()
        application { installTestApi(env, database) { featureApi(env) } }

        val response = client.get("/features")

        assertEquals(HttpStatusCode.OK, response.status)
        val features = readJson<Features>(response.bodyAsText())
        assertEquals(Features(roomBooking = false, teamsMeeting = false), features)
    }

    @Test
    fun `shared calendar and people search follow shared rollout access`() = testApplication {
        val env = localTestEnvironment().copy(featureSharedCalendar = no.nav.delta.FeatureAccess.MAINTAINERS)
        application { installTestApi(env, database) { featureApi(env) } }
        val features = readJson<Features>(client.get("/features").bodyAsText())
        assertEquals(true, features.sharedCalendar)
        assertEquals(true, features.peopleSearch)
        assertEquals(false, features.roomBooking)
    }

    @Test
    fun `room booking is reported enabled for a maintainer`() = testApplication {
        val env =
            localTestEnvironment()
                .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS)
        application { installTestApi(env, database) { featureApi(env) } }

        val response = client.get("/features")

        val features = readJson<Features>(response.bodyAsText())
        assertEquals(true, features.roomBooking)
        assertEquals(false, features.teamsMeeting)
    }

    @Test
    fun `room booking is reported disabled for a caller outside the maintainers group`() = testApplication {
        val env =
            localTestEnvironment()
                .copy(featureRoomBooking = no.nav.delta.FeatureAccess.MAINTAINERS, maintainersGroupId = "another-group")
        application { installTestApi(env, database) { featureApi(env) } }

        val response = client.get("/features")

        val features = readJson<Features>(response.bodyAsText())
        assertEquals(false, features.roomBooking)
    }

    @Test
    fun `teams meeting alone is reported enabled when toggled to all`() = testApplication {
        val env = localTestEnvironment().copy(featureTeamsMeeting = no.nav.delta.FeatureAccess.ALL)
        application { installTestApi(env, database) { featureApi(env) } }

        val features = readJson<Features>(client.get("/features").bodyAsText())
        assertEquals(Features(roomBooking = false, teamsMeeting = true), features)
    }

    @Test
    fun `both features are reported enabled when both are on`() = testApplication {
        val env =
            localTestEnvironment().copy(
                featureRoomBooking = no.nav.delta.FeatureAccess.ALL,
                featureTeamsMeeting = no.nav.delta.FeatureAccess.MAINTAINERS,
            )
        application { installTestApi(env, database) { featureApi(env) } }

        val features = readJson<Features>(client.get("/features").bodyAsText())
        assertEquals(Features(roomBooking = true, teamsMeeting = true), features)
    }
}
