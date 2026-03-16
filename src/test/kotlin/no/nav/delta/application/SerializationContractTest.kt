package no.nav.delta.application

import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installFullTestApplication
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.testObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SerializationContractTest {
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

    @Test
    fun `event endpoints use ISO date strings and preserve null signupDeadline`() = testApplication {
        val env = localTestEnvironment()
        application {
            installFullTestApplication(env, database, cloudClient)
        }

        val startTime = LocalDateTime.now().plusDays(2).withNano(0)
        val endTime = startTime.plusHours(1)

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "title": "serdes-${UUID.randomUUID()}",
                      "description": "Serializer contract",
                      "startTime": "$startTime",
                      "endTime": "$endTime",
                      "location": "room-contract",
                      "public": true,
                      "participantLimit": 5,
                      "signupDeadline": null,
                      "sendNotificationEmail": false
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith(ContentType.Application.Json.toString()))

        val body = response.bodyAsText()
        val json = testObjectMapper.readTree(body)
        val event = json["event"]

        assertTrue(event["startTime"].isTextual)
        assertTrue(event["endTime"].isTextual)
        assertEquals(startTime.toString(), event["startTime"].asText())
        assertEquals(endTime.toString(), event["endTime"].asText())
        assertTrue(event.has("signupDeadline"))
        assertTrue(event["signupDeadline"].isNull)
        assertFalse(body.contains("\"startTime\" : 1"))
    }

    @Test
    fun `event endpoints reject non ISO date payloads`() = testApplication {
        val env = localTestEnvironment()
        application {
            installFullTestApplication(env, database, cloudClient)
        }

        val response =
            client.put("/admin/event") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "title": "bad-date",
                      "description": "Serializer contract",
                      "startTime": 1711111111,
                      "endTime": "2026-03-20T10:00:00",
                      "location": "room-contract",
                      "public": true,
                      "participantLimit": 5,
                      "signupDeadline": null,
                      "sendNotificationEmail": false
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `faggruppe endpoints keep snake case json field names`() = testApplication {
        val env = localTestEnvironment()
        application {
            installFullTestApplication(env, database, cloudClient)
        }

        val response =
            client.post("/api/faggrupper") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "navn": "contract-${UUID.randomUUID()}",
                      "type": "faggruppe",
                      "slack_kanal_navn": "#contract",
                      "slack_kanal_url": "https://nav-it.slack.com/archives/CTEST"
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith(ContentType.Application.Json.toString()))

        val body = response.bodyAsText()
        val json = testObjectMapper.readTree(body)

        assertTrue(json.has("slack_kanal_navn"))
        assertTrue(json.has("slack_kanal_url"))
        assertTrue(json.has("er_aktiv"))
        assertFalse(json.has("slackKanalNavn"))
        assertFalse(json.has("slackKanalUrl"))
        assertFalse(json.has("erAktiv"))
        assertEquals("#contract", json["slack_kanal_navn"].asText())
        assertTrue(json["eiere"].isArray)
    }

    @Test
    fun `webhook payload accepts unknown json fields`() = testApplication {
        val env = localTestEnvironment()
        application {
            installFullTestApplication(env, database, cloudClient)
        }

        val response =
            client.post("/webhook/calendar") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "value": [
                        {
                          "clientState": "${env.webhookClientState}",
                          "resource": "users/${env.deltaEmailAddress}/events/unknown",
                          "changeType": "updated",
                          "subscriptionId": "sub-1",
                          "extraField": "should-be-ignored"
                        }
                      ],
                      "topLevelExtra": true
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }
}
