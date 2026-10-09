package no.nav.delta.admin

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import no.nav.delta.event.InviteMode
import no.nav.delta.plugins.DatabaseInterface

data class EventTypeCount(
    val inviteMode: InviteMode,
    val total: Long,
    val upcomingOrOngoing: Long,
)

data class AdminStatistics(
    val generatedAt: Instant,
    val eventTypes: List<EventTypeCount>,
)

fun DatabaseInterface.getAdminStatistics(at: Instant = Instant.now()): AdminStatistics =
    connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT invite_mode, COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE end_time > ?) AS upcoming_or_ongoing
            FROM event
            GROUP BY invite_mode
            """.trimIndent()
        ).use { statement ->
            // Event timestamps are stored as local calendar times in Europe/Oslo.
            statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneId.of("Europe/Oslo"))))
            statement.executeQuery().use { result ->
                val counts = mutableMapOf<InviteMode, EventTypeCount>()
                while (result.next()) {
                    val mode = InviteMode.valueOf(result.getString("invite_mode"))
                    counts[mode] = EventTypeCount(mode, result.getLong("total"), result.getLong("upcoming_or_ongoing"))
                }
                AdminStatistics(at, InviteMode.entries.map { counts[it] ?: EventTypeCount(it, 0, 0) })
            }
        }
    }
