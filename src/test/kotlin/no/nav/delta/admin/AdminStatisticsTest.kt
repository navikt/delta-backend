package no.nav.delta.admin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import no.nav.delta.calendar.SharedCalendarRepository
import no.nav.delta.event.CreateEvent
import no.nav.delta.event.InviteMode
import no.nav.delta.event.ParticipantType
import no.nav.delta.event.RecurrenceFrequency
import no.nav.delta.event.RecurrenceRequest
import no.nav.delta.event.addEvent
import no.nav.delta.event.createRecurringEventSeries
import no.nav.delta.event.registerForEvent
import no.nav.delta.plugins.DatabaseInterface
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
class AdminStatisticsTest {
    private lateinit var testDatabase: TestDatabase
    private lateinit var database: DatabaseInterface
    private val algorithm = Algorithm.HMAC256("synthetic-admin-statistics-test-signing-key")
    private val verifier = JWT.require(algorithm).build()
    private val env = localTestEnvironment()
    private val at = Instant.parse("2030-07-01T10:00:00Z")
    private val localNow = LocalDateTime.ofInstant(at, ZoneId.of("Europe/Oslo"))

    @BeforeAll
    fun setup() {
        testDatabase = TestDatabase.create()
        database = testDatabase.database
    }

    @BeforeEach
    fun reset() {
        database.connection.use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE event, shared_calendar_outbox, shared_calendar_recipient_daily CASCADE")
            }
            connection.commit()
        }
    }

    @AfterAll
    fun close() {
        testDatabase.close()
    }

    @Test
    fun `empty database returns both types with zero counts`() {
        assertEquals(
            AdminStatistics(at, listOf(EventTypeCount(InviteMode.PER_PARTICIPANT, 0, 0), EventTypeCount(InviteMode.SHARED, 0, 0))),
            database.getAdminStatistics(at),
        )
    }

    @Test
    fun `counts all events without participant multiplication and excludes ended events from active counts`() {
        val legacy = database.addEvent(draft(localNow.minusHours(1), localNow.plusHours(1), public = false))
        database.registerForEvent(legacy.id.toString(), "host@example.com", "Host", ParticipantType.HOST)
        database.registerForEvent(legacy.id.toString(), "person@example.com", "Person", ParticipantType.PARTICIPANT)
        database.addEvent(draft(localNow.minusHours(2), localNow.minusSeconds(1)))
        database.addEvent(draft(localNow.minusHours(1), localNow))
        database.addEvent(draft(localNow.plusHours(2), localNow.plusHours(3)))
        val shared = SharedCalendarRepository(database)
        shared.create(draft(localNow.minusHours(1), localNow.plusSeconds(1)), "host@example.com", "Host").getOrNull()!!
        shared.create(draft(localNow.minusHours(2), localNow.minusHours(1)), "host@example.com", "Host").getOrNull()!!

        assertEquals(
            listOf(EventTypeCount(InviteMode.PER_PARTICIPANT, 4, 2), EventTypeCount(InviteMode.SHARED, 2, 1)),
            database.getAdminStatistics(at).eventTypes,
        )
    }

    @Test
    fun `active cutoff uses Oslo winter time`() {
        val winter = Instant.parse("2030-01-01T10:00:00Z")
        database.addEvent(draft(LocalDateTime.parse("2030-01-01T10:00:00"), LocalDateTime.parse("2030-01-01T11:00:00")))
        database.addEvent(draft(LocalDateTime.parse("2030-01-01T10:00:00"), LocalDateTime.parse("2030-01-01T11:00:01")))
        assertEquals(EventTypeCount(InviteMode.PER_PARTICIPANT, 2, 1), database.getAdminStatistics(winter).eventTypes.first())
    }

    @Test
    fun `each recurring occurrence counts once for its calendar model`() {
        for (mode in InviteMode.entries) {
            database.createRecurringEventSeries(
                draft(localNow.plusDays(1), localNow.plusDays(1).plusHours(1)).copy(
                    recurrence = RecurrenceRequest(RecurrenceFrequency.WEEKLY, localNow.toLocalDate().plusDays(15)),
                ),
                "host@example.com", "Host", mode,
            ).getOrNull()!!
        }
        assertEquals(
            listOf(EventTypeCount(InviteMode.PER_PARTICIPANT, 3, 3), EventTypeCount(InviteMode.SHARED, 3, 3)),
            database.getAdminStatistics(at).eventTypes,
        )
    }

    @Test
    fun `maintainer user gets uncached serialized statistics`() = testApplication {
        application { installTestApi(env, database, verifier) { adminApi(database, env) } }
        val response = client.get("/admin/statistics") { bearerAuth(token(listOf(env.maintainersGroupId))) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers["Cache-Control"])
        assertEquals(
            listOf(EventTypeCount(InviteMode.PER_PARTICIPANT, 0, 0), EventTypeCount(InviteMode.SHARED, 0, 0)),
            readJson<AdminStatistics>(response.bodyAsText()).eventTypes,
        )
        database.addEvent(draft(localNow.plusHours(1), localNow.plusHours(2)))
        val updated = client.get("/admin/statistics") { bearerAuth(token(listOf(env.maintainersGroupId))) }
        assertEquals(1L, readJson<AdminStatistics>(updated.bodyAsText()).eventTypes.first().total)
    }

    @Test
    fun `local development requires explicitly configured local maintainer group`() = testApplication {
        application { installTestApi(env, database) { adminApi(database, env) } }
        assertEquals(HttpStatusCode.OK, client.get("/admin/statistics").status)
    }

    @Test
    fun `users outside maintainer group are forbidden including missing groups`() = testApplication {
        application { installTestApi(env, database, verifier) { adminApi(database, env) } }
        for (groups in listOf(null, emptyList(), listOf("other-group"))) {
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/admin/statistics") { bearerAuth(token(groups)) }.status,
            )
        }
    }

    @Test
    fun `unconfigured maintainer group denies access`() = testApplication {
        val unconfigured = env.copy(maintainersGroupId = "")
        application { installTestApi(unconfigured, database, verifier) { adminApi(database, unconfigured) } }
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/admin/statistics") { bearerAuth(token(listOf(env.maintainersGroupId))) }.status,
        )
    }

    @Test
    fun `application tokens cannot access statistics even with maintainer groups`() = testApplication {
        application { installTestApi(env, database, verifier) { adminApi(database, env) } }
        for (roles in listOf(emptyList(), listOf("delta.read"))) {
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/admin/statistics") {
                    bearerAuth(token(listOf(env.maintainersGroupId), app = true, roles = roles))
                }.status,
            )
        }
    }

    @Test
    fun `missing or invalid token is unauthorized`() = testApplication {
        application { installTestApi(env, database, verifier) { adminApi(database, env) } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/statistics").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/statistics") { bearerAuth("invalid") }.status)
    }

    private fun draft(start: LocalDateTime, end: LocalDateTime, public: Boolean = true) =
        CreateEvent("Synthetic event", "Description", start, end, "Office", public, 0, null)

    private fun token(groups: List<String>?, app: Boolean = false, roles: List<String> = emptyList()): String {
        val builder = JWT.create().withClaim("idtyp", if (app) "app" else "user")
        if (groups != null) builder.withArrayClaim("groups", groups.toTypedArray())
        if (roles.isNotEmpty()) builder.withArrayClaim("roles", roles.toTypedArray())
        return builder.sign(algorithm)
    }
}
