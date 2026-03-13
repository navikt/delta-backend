package no.nav.delta.faggruppe

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
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.support.RecordingCloudClient
import no.nav.delta.support.TestDatabase
import no.nav.delta.support.installTestApi
import no.nav.delta.support.localTestEnvironment
import no.nav.delta.support.readJson
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FaggruppeRoutesTest {
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
    fun `post creates faggruppe and caller becomes owner`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                faggruppeApi(database, cloudClient, env)
            }
        }

        val response =
            client.post("/api/faggrupper") {
                contentType(ContentType.Application.Json)
                setBody(createDtoJson("Faggruppe ${UUID.randomUUID()}"))
            }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = readJson<FaggruppeDTO>(response.bodyAsText())
        assertEquals("test@localhost", created.eiere.single().epost)
    }

    @Test
    fun `post rejects invalid time format`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                faggruppeApi(database, cloudClient, env)
            }
        }

        val response =
            client.post("/api/faggrupper") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "navn": "Invalid",
                      "type": "faggruppe",
                      "starttid": "25:61"
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("starttid must be in HH:mm format", response.bodyAsText())
    }

    @Test
    fun `post owner route normalizes email and uses cloud lookup`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                faggruppeApi(database, cloudClient, env)
            }
        }

        val created =
            database.createFaggruppe(
                FaggruppeCreateDTO(navn = "Owners", type = "faggruppe"),
                ownerEmail = "test@localhost",
                ownerNavn = "Test User",
            )
        cloudClient.userDisplayNames["new.owner@nav.no"] = "New Owner"

        val response =
            client.post("/api/faggrupper/${created.id}/eiere") {
                contentType(ContentType.Application.Json)
                setBody("""{"epost":"  NEW.Owner@nav.no  "}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val owner = readJson<EierDTO>(response.bodyAsText())
        assertEquals("new.owner@nav.no", owner.epost)
        assertEquals("New Owner", owner.navn)
    }

    @Test
    fun `put returns forbidden for non-owner`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                faggruppeApi(database, cloudClient, env)
            }
        }

        val created =
            database.createFaggruppe(
                FaggruppeCreateDTO(navn = "Foreign Owners", type = "faggruppe"),
                ownerEmail = "someoneelse@nav.no",
                ownerNavn = "Other User",
            )

        val response =
            client.put("/api/faggrupper/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody("""{"navn":"Denied"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `delete owner route removes owner`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                faggruppeApi(database, cloudClient, env)
            }
        }

        val created =
            database.createFaggruppe(
                FaggruppeCreateDTO(navn = "Delete Owner", type = "faggruppe"),
                ownerEmail = "test@localhost",
                ownerNavn = "Test User",
            )
        database.addEier(created.id, "remove@nav.no", "Remove Me")

        val response = client.delete("/api/faggrupper/${created.id}/eiere/remove@nav.no")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(database.getEiere(created.id).none { it.epost == "remove@nav.no" })
    }

    @Test
    fun `get owner route reports ownership`() = testApplication {
        val env = localTestEnvironment()
        application {
            installTestApi(env, database) {
                faggruppeApi(database, cloudClient, env)
            }
        }

        val created =
            database.createFaggruppe(
                FaggruppeCreateDTO(navn = "Ownership", type = "faggruppe"),
                ownerEmail = "test@localhost",
                ownerNavn = "Test User",
            )

        val response = client.get("/api/faggrupper/${created.id}/eier")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"isOwner":true}""", response.bodyAsText().replace("\n", "").replace(" ", ""))
    }

    private fun createDtoJson(name: String) =
        """
        {
          "navn": "$name",
          "type": "faggruppe",
          "beskrivelse": "Beskrivelse",
          "undertittel": "Undertittel",
          "malgruppe": "Utviklere",
          "tidspunkt": "Hver uke",
          "starttid": "10:00",
          "sluttid": "11:00",
          "slack_kanal_navn": "#test",
          "slack_kanal_url": "https://nav-it.slack.com/archives/CTEST"
        }
        """.trimIndent()
}
