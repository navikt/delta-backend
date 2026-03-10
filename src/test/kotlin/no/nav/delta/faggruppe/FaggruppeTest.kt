package no.nav.delta.faggruppe

import no.nav.delta.Environment
import no.nav.delta.plugins.DatabaseConfig
import no.nav.delta.plugins.DatabaseInterface
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers
class FaggruppeTest {

    companion object {
        @Container
        private val postgresContainer = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))

        private lateinit var db: DatabaseInterface

        @JvmStatic
        @BeforeAll
        fun setup() {
            db = DatabaseConfig(Environment(
                dbJdbcUrl = postgresContainer.jdbcUrl,
                dbUsername = postgresContainer.username,
                dbPassword = postgresContainer.password,
            ))
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            postgresContainer.stop()
        }
    }

    private fun createDto(navn: String = "Test Faggruppe") = FaggruppeCreateDTO(
        navn = navn,
        type = "faggruppe",
        beskrivelse = "En testbeskrivelse",
        undertittel = "En undertittel",
        malgruppe = "Utviklere",
        tidspunkt = "Hver fagtorsdag",
        starttid = "10:00",
        sluttid = "11:00",
        slackKanalNavn = "#test-faggruppe",
        slackKanalUrl = "https://nav-it.slack.com/archives/CTEST",
    )

    @Test
    fun `create and get faggruppe`() {
        val ownerEmail = "owner@nav.no"
        val ownerNavn = "Test Owner"

        val created = db.createFaggruppe(createDto("Frontendforum"), ownerEmail, ownerNavn)

        assertNotNull(created.id)
        assertEquals("Frontendforum", created.navn)
        assertEquals("faggruppe", created.type)
        assertEquals("10:00", created.starttid)
        assertEquals("11:00", created.sluttid)
        assertTrue(created.erAktiv)
        assertEquals(1, created.eiere.size)
        assertEquals(ownerEmail, created.eiere[0].epost)
        assertEquals(ownerNavn, created.eiere[0].navn)

        val fetched = db.getFaggruppe(created.id)
        assertNotNull(fetched)
        assertEquals(created.id, fetched!!.id)
        assertEquals("Frontendforum", fetched.navn)
        assertEquals(1, fetched.eiere.size)
    }

    @Test
    fun `get all active faggrupper`() {
        val before = db.getAllActiveFaggrupper().size
        db.createFaggruppe(createDto("Backendforum"), "owner1@nav.no", null)
        db.createFaggruppe(createDto("Data Science"), "owner2@nav.no", null)

        val all = db.getAllActiveFaggrupper()
        assertEquals(before + 2, all.size)
        assertTrue(all.any { it.navn == "Backendforum" })
        assertTrue(all.any { it.navn == "Data Science" })
    }

    @Test
    fun `get non-existent faggruppe returns null`() {
        val result = db.getFaggruppe(UUID.randomUUID())
        assertNull(result)
    }

    @Test
    fun `update faggruppe`() {
        val created = db.createFaggruppe(createDto("Original Navn"), "owner@nav.no", null)

        val updated = db.updateFaggruppe(
            created.id,
            FaggruppeUpdateDTO(navn = "Oppdatert Navn", tidspunkt = "Annenhver uke")
        )

        assertNotNull(updated)
        assertEquals("Oppdatert Navn", updated!!.navn)
        assertEquals("Annenhver uke", updated.tidspunkt)
        assertEquals("10:00", updated.starttid)
        assertEquals("faggruppe", updated.type)
    }

    @Test
    fun `update non-existent faggruppe returns null`() {
        val result = db.updateFaggruppe(UUID.randomUUID(), FaggruppeUpdateDTO(navn = "Ny"))
        assertNull(result)
    }

    @Test
    fun `isEier returns true for owner`() {
        val created = db.createFaggruppe(createDto(), "eier@nav.no", null)
        assertTrue(db.isEier(created.id, "eier@nav.no"))
        assertFalse(db.isEier(created.id, "other@nav.no"))
    }

    @Test
    fun `add and remove eier`() {
        val created = db.createFaggruppe(createDto(), "owner@nav.no", null)

        val newEier = db.addEier(created.id, "new-eier@nav.no", "New Owner")
        assertEquals("new-eier@nav.no", newEier.epost)
        assertEquals("New Owner", newEier.navn)
        assertTrue(db.isEier(created.id, "new-eier@nav.no"))

        val eiere = db.getEiere(created.id)
        assertEquals(2, eiere.size)

        val removed = db.removeEier(created.id, "new-eier@nav.no")
        assertTrue(removed)
        assertFalse(db.isEier(created.id, "new-eier@nav.no"))
    }

    @Test
    fun `remove non-existent eier returns false`() {
        val created = db.createFaggruppe(createDto(), "owner@nav.no", null)
        val removed = db.removeEier(created.id, "ghost@nav.no")
        assertFalse(removed)
    }

    @Test
    fun `fagruppeExists returns correct result`() {
        val created = db.createFaggruppe(createDto(), "owner@nav.no", null)
        assertTrue(db.fagruppeExists(created.id))
        assertFalse(db.fagruppeExists(UUID.randomUUID()))
    }

    @Test
    fun `eierExists returns correct result`() {
        val created = db.createFaggruppe(createDto(), "owner@nav.no", null)
        assertTrue(db.eierExists(created.id, "owner@nav.no"))
        assertFalse(db.eierExists(created.id, "stranger@nav.no"))
    }

    @Test
    fun `delete faggruppe removes it and its eiere`() {
        val created = db.createFaggruppe(createDto("Slett meg"), "owner@nav.no", null)
        db.addEier(created.id, "other@nav.no", null)

        assertTrue(db.fagruppeExists(created.id))
        assertTrue(db.deleteFaggruppe(created.id))
        assertFalse(db.fagruppeExists(created.id))
        assertNull(db.getFaggruppe(created.id))
        assertTrue(db.getEiere(created.id).isEmpty())
    }

    @Test
    fun `delete non-existent faggruppe returns false`() {
        assertFalse(db.deleteFaggruppe(UUID.randomUUID()))
    }
}
