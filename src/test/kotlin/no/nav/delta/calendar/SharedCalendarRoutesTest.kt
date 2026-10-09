package no.nav.delta.calendar

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
import no.nav.delta.FeatureAccess
import no.nav.delta.email.DummyCloudClient
import no.nav.delta.event.FullEvent
import no.nav.delta.event.CreateEvent
import no.nav.delta.event.Participant
import no.nav.delta.event.ParticipantStatus
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installFullTestApplication
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import no.nav.delta.support.waitUntilSuspending
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedCalendarRoutesTest {
    @Test
    fun `non hosts cannot invite or see declined invitations and sync errors`() = testApplication {
        TestDatabase.create().use { db ->
            val repository = SharedCalendarRepository(db.database)
            val event = repository.create(
                readJson<CreateEvent>(eventJson()),
                Participant("otherhost@nav.no", "Other Host"),
            )
            repository.reconcile(
                event.id,
                listOf(SharedCalendarAttendeeSnapshot("colleague@nav.no", "Colleague", SharedCalendarResponse.DECLINED)),
            )
            val claim = repository.claimNext()!!
            repository.fail(claim)
            application {
                installFullTestApplication(
                    localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL),
                    db.database,
                    DummyCloudClient(),
                )
            }
            val response = client.get("/event/${event.id}")
            assertEquals(HttpStatusCode.OK, response.status)
            val visible = readJson<FullEvent>(response.bodyAsText())
            assertTrue(visible.invited.none { it.status == ParticipantStatus.DECLINED })
            assertEquals(null, visible.calendarSyncError)
            assertEquals(HttpStatusCode.Forbidden, client.post("/admin/event/${event.id}/invitations") {
                contentType(ContentType.Application.Json)
                setBody("""[{"email":"second@nav.no"}]""")
            }.status)
        }
    }

    @Test
    fun `assembled application saves and synchronizes a shared calendar event`() = testApplication {
        TestDatabase.create().use { db ->
            val env = localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL)
            val cloud = DummyCloudClient()
            application { installFullTestApplication(env, db.database, cloud, startBackgroundTasks = true) }
            val response = client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(eventJson())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val created = readJson<FullEvent>(response.bodyAsText())
            waitUntilSuspending {
                readJson<FullEvent>(client.get("/event/${created.event.id}").bodyAsText())
                    .event.calendarSyncStatus?.name == "SYNCED"
            }
            val metrics = client.get("/internal/metrics")
            assertEquals(HttpStatusCode.OK, metrics.status)
            assertTrue(metrics.bodyAsText().contains("delta_calendar_sync_attempts_total"))
        }
    }

    @Test
    fun `creation saves pending invitations without Graph and rejects group inputs`() = testApplication {
        TestDatabase.create().use { db ->
            val env = localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL)
            application { installFullTestApplication(env, db.database, DummyCloudClient()) }
            val response = client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(eventJson())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val created = readJson<FullEvent>(response.bodyAsText())
            assertEquals("SHARED", created.event.inviteMode.name)
            assertEquals("PENDING", created.event.calendarSyncStatus?.name)
            assertEquals(listOf("colleague@nav.no"), created.invited.map { it.email })
            assertTrue(created.participants.isEmpty())
            val invalid = client.post("/admin/event/${created.event.id}/invitations") {
                contentType(ContentType.Application.Json)
                setBody("""[{"groupId":"not-supported"}]""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals(1, readJson<FullEvent>(client.get("/event/${created.event.id}").bodyAsText()).invited.size)
        }
    }

    @Test
    fun `hosts can add revoke and retry invitations`() = testApplication {
        TestDatabase.create().use { db ->
            val env = localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL)
            application { installFullTestApplication(env, db.database, DummyCloudClient()) }
            val created = readJson<FullEvent>(client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(eventJson())
            }.bodyAsText())
            val added = client.post("/admin/event/${created.event.id}/invitations") {
                contentType(ContentType.Application.Json)
                setBody("""[{"email":"second@nav.no"}]""")
            }
            assertEquals(HttpStatusCode.OK, added.status)
            assertEquals(2, readJson<FullEvent>(added.bodyAsText()).invited.size)
            val revoked = client.delete("/admin/event/${created.event.id}/invitations") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"second@nav.no"}""")
            }
            assertEquals(HttpStatusCode.OK, revoked.status)
            assertEquals(1, readJson<FullEvent>(revoked.bodyAsText()).invited.size)
            assertEquals(HttpStatusCode.OK, client.post("/admin/event/${created.event.id}/calendar/retry").status)
        }
    }

    @Test
    fun `shared creation rejects invitation batches exceeding reserved capacity`() = testApplication {
        TestDatabase.create().use { db ->
            val env = localTestEnvironment().copy(featureSharedCalendar = FeatureAccess.ALL)
            application { installFullTestApplication(env, db.database, DummyCloudClient()) }
            val response = client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(eventJson().replace("\"participantLimit\":5", "\"participantLimit\":1"))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            val listed = readJson<List<FullEvent>>(client.get("/event?onlyMine=true").bodyAsText())
            assertTrue(listed.isEmpty())
        }
    }

    @Test
    fun `shared creation provisions Teams asynchronously and ignores notification opt out`() = testApplication {
        TestDatabase.create().use { db ->
            val env = localTestEnvironment().copy(
                featureSharedCalendar = FeatureAccess.ALL,
                featureTeamsMeeting = FeatureAccess.ALL,
            )
            application { installFullTestApplication(env, db.database, DummyCloudClient()) }
            val response = client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(eventJson().replace("\"public\":true", "\"public\":true,\"isOnlineMeeting\":true"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val created = readJson<FullEvent>(response.bodyAsText())
            assertTrue(created.event.isOnlineMeeting)
            assertEquals(null, created.event.teamsJoinUrl)
            assertEquals("PENDING", created.event.calendarSyncStatus?.name)
        }
    }

    private fun eventJson(): String {
        val start = LocalDateTime.now().plusDays(10).withNano(0)
        return """
            {"title":"Shared test","description":"Details","startTime":"$start",
             "endTime":"${start.plusHours(1)}","location":"Office","public":true,
             "participantLimit":5,"signupDeadline":null,"sendNotificationEmail":false,
             "invitees":[{"email":"colleague@nav.no"}]}
        """.trimIndent()
    }
}
