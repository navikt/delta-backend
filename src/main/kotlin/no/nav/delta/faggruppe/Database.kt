package no.nav.delta.faggruppe

import java.sql.Time
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun LocalTime.toHHmm(): String = format(TIME_FORMAT)

internal fun String.toSqlTime(): Time = Time.valueOf(LocalTime.parse(this, TIME_FORMAT))

internal fun String.isValidHHmm(): Boolean = try {
    LocalTime.parse(this, TIME_FORMAT); true
} catch (_: Exception) { false }

fun DatabaseInterface.getAllActiveFaggrupper(): List<FaggruppeDTO> {
    return connection.use { connection ->
        val rs = connection.prepareStatement(
            """
            SELECT f.*, e.epost AS eier_epost, e.navn AS eier_navn
            FROM faggrupper f
            LEFT JOIN faggruppe_eiere e ON e.faggruppe_id = f.id
            WHERE f.er_aktiv = true
            ORDER BY f.navn, e.epost
            """
        ).executeQuery()

        val faggrupper = linkedMapOf<UUID, Pair<FaggruppeDTO, MutableList<EierDTO>>>()
        while (rs.next()) {
            val id = UUID.fromString(rs.getString("id"))
            val entry = faggrupper.getOrPut(id) { rs.toFaggruppeDTO(emptyList()) to mutableListOf() }
            val epost = rs.getString("eier_epost")
            if (epost != null) {
                entry.second.add(EierDTO(epost = epost, navn = rs.getString("eier_navn")))
            }
        }
        faggrupper.values.map { (fg, eiere) -> fg.copy(eiere = eiere) }
    }
}

fun DatabaseInterface.getFaggruppe(id: UUID): FaggruppeDTO? {
    return connection.use { connection ->
        val rs = connection.prepareStatement(
            "SELECT * FROM faggrupper WHERE id = ?::uuid"
        ).also { it.setString(1, id.toString()) }.executeQuery()

        if (!rs.next()) return null
        val fg = rs.toFaggruppeDTO(emptyList())
        fg.copy(eiere = getEiereWithConnection(connection, id))
    }
}

fun DatabaseInterface.createFaggruppe(
    dto: FaggruppeCreateDTO,
    ownerEmail: String,
    ownerNavn: String?,
): FaggruppeDTO {
    return connection.use { connection ->
        val rs = connection.prepareStatement(
            """
            INSERT INTO faggrupper (navn, beskrivelse, undertittel, malgruppe, type, tidspunkt,
                                    starttid, sluttid, slack_kanal_navn, slack_kanal_url)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *
            """
        ).also { ps ->
            ps.setString(1, dto.navn)
            ps.setString(2, dto.beskrivelse)
            ps.setString(3, dto.undertittel)
            ps.setString(4, dto.malgruppe)
            ps.setString(5, dto.type)
            ps.setString(6, dto.tidspunkt)
            ps.setTime(7, dto.starttid?.toSqlTime())
            ps.setTime(8, dto.sluttid?.toSqlTime())
            ps.setString(9, dto.slackKanalNavn)
            ps.setString(10, dto.slackKanalUrl)
        }.executeQuery()

        rs.next()
        val faggruppeId = UUID.fromString(rs.getString("id"))
        val faggruppe = rs.toFaggruppeDTO(emptyList())

        connection.prepareStatement(
            "INSERT INTO faggruppe_eiere (faggruppe_id, epost, navn) VALUES (?::uuid, ?, ?)"
        ).also { ps ->
            ps.setString(1, faggruppeId.toString())
            ps.setString(2, ownerEmail)
            ps.setString(3, ownerNavn)
        }.executeUpdate()

        connection.commit()
        faggruppe.copy(eiere = listOf(EierDTO(epost = ownerEmail, navn = ownerNavn)))
    }
}

fun DatabaseInterface.updateFaggruppe(id: UUID, dto: FaggruppeUpdateDTO): FaggruppeDTO? {
    return connection.use { connection ->
        val rs = connection.prepareStatement(
            """
            UPDATE faggrupper SET
                navn             = COALESCE(?, navn),
                type             = COALESCE(?, type),
                beskrivelse      = COALESCE(?, beskrivelse),
                undertittel      = COALESCE(?, undertittel),
                malgruppe        = COALESCE(?, malgruppe),
                tidspunkt        = COALESCE(?, tidspunkt),
                starttid         = COALESCE(?, starttid),
                sluttid          = COALESCE(?, sluttid),
                slack_kanal_navn = COALESCE(?, slack_kanal_navn),
                slack_kanal_url  = COALESCE(?, slack_kanal_url)
            WHERE id = ?::uuid
            RETURNING *
            """
        ).also { ps ->
            ps.setString(1, dto.navn)
            ps.setString(2, dto.type)
            ps.setString(3, dto.beskrivelse)
            ps.setString(4, dto.undertittel)
            ps.setString(5, dto.malgruppe)
            ps.setString(6, dto.tidspunkt)
            ps.setTime(7, dto.starttid?.toSqlTime())
            ps.setTime(8, dto.sluttid?.toSqlTime())
            ps.setString(9, dto.slackKanalNavn)
            ps.setString(10, dto.slackKanalUrl)
            ps.setString(11, id.toString())
        }.executeQuery()

        if (!rs.next()) return null
        val faggruppe = rs.toFaggruppeDTO(emptyList())
        connection.commit()
        faggruppe.copy(eiere = getEiereWithConnection(connection, id))
    }
}

fun DatabaseInterface.getEiere(faggruppeId: UUID): List<EierDTO> {
    return connection.use { connection ->
        getEiereWithConnection(connection, faggruppeId)
    }
}

fun DatabaseInterface.isEier(faggruppeId: UUID, email: String): Boolean {
    return connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM faggruppe_eiere WHERE faggruppe_id = ?::uuid AND epost = ?"
        ).also {
            it.setString(1, faggruppeId.toString())
            it.setString(2, email)
        }.executeQuery().next()
    }
}

fun DatabaseInterface.addEier(faggruppeId: UUID, epost: String, navn: String?): EierDTO {
    return connection.use { connection ->
        connection.prepareStatement(
            "INSERT INTO faggruppe_eiere (faggruppe_id, epost, navn) VALUES (?::uuid, ?, ?) RETURNING epost, navn"
        ).also { ps ->
            ps.setString(1, faggruppeId.toString())
            ps.setString(2, epost)
            ps.setString(3, navn)
        }.executeQuery().let { rs ->
            rs.next()
            connection.commit()
            EierDTO(epost = rs.getString("epost"), navn = rs.getString("navn"))
        }
    }
}

fun DatabaseInterface.removeEier(faggruppeId: UUID, epost: String): Boolean {
    return connection.use { connection ->
        val count = connection.prepareStatement(
            "DELETE FROM faggruppe_eiere WHERE faggruppe_id = ?::uuid AND epost = ?"
        ).also {
            it.setString(1, faggruppeId.toString())
            it.setString(2, epost)
        }.executeUpdate()
        connection.commit()
        count > 0
    }
}

fun DatabaseInterface.deleteFaggruppe(id: UUID): Boolean {
    return connection.use { connection ->
        val count = connection.prepareStatement(
            "DELETE FROM faggrupper WHERE id = ?::uuid"
        ).also { it.setString(1, id.toString()) }.executeUpdate()
        connection.commit()
        count > 0
    }
}

fun DatabaseInterface.fagruppeExists(id: UUID): Boolean {
    return connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM faggrupper WHERE id = ?::uuid"
        ).also { it.setString(1, id.toString()) }.executeQuery().next()
    }
}

fun DatabaseInterface.eierExists(faggruppeId: UUID, epost: String): Boolean {
    return isEier(faggruppeId, epost)
}

private fun getEiereWithConnection(
    connection: java.sql.Connection,
    faggruppeId: UUID,
): List<EierDTO> {
    return connection.prepareStatement(
        "SELECT epost, navn FROM faggruppe_eiere WHERE faggruppe_id = ?::uuid"
    ).also { it.setString(1, faggruppeId.toString()) }.executeQuery().let { rs ->
        val list = mutableListOf<EierDTO>()
        while (rs.next()) {
            list.add(EierDTO(epost = rs.getString("epost"), navn = rs.getString("navn")))
        }
        list
    }
}

private fun java.sql.ResultSet.toFaggruppeDTO(eiere: List<EierDTO>): FaggruppeDTO {
    return FaggruppeDTO(
        id = UUID.fromString(getString("id")),
        navn = getString("navn"),
        beskrivelse = getString("beskrivelse"),
        undertittel = getString("undertittel"),
        malgruppe = getString("malgruppe"),
        type = getString("type"),
        tidspunkt = getString("tidspunkt"),
        starttid = getTime("starttid")?.toLocalTime()?.toHHmm(),
        sluttid = getTime("sluttid")?.toLocalTime()?.toHHmm(),
        slackKanalNavn = getString("slack_kanal_navn"),
        slackKanalUrl = getString("slack_kanal_url"),
        erAktiv = getBoolean("er_aktiv"),
        eiere = eiere,
    )
}
