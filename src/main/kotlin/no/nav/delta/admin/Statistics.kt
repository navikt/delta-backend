package no.nav.delta.admin

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import no.nav.delta.event.InviteMode
import no.nav.delta.event.toList
import no.nav.delta.plugins.DatabaseInterface

data class EventTypeCount(
    val inviteMode: InviteMode,
    val total: Long,
    val upcomingOrOngoing: Long,
)

data class AdminStatistics(
    val generatedAt: Instant,
    val eventTypes: List<EventTypeCount>,
    val publicLegacyEvents: List<AdminEventSummary>,
)

data class AdminEventSummary(
    val id: UUID,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
)

fun DatabaseInterface.getAdminStatistics(at: Instant = Instant.now()): AdminStatistics =
    connection.use { connection ->
        // Event timestamps are stored as local calendar times in Europe/Oslo.
        val localNow = Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneId.of("Europe/Oslo")))
        val eventTypes = connection.prepareStatement(
            """
            SELECT invite_mode, COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE end_time > ?) AS upcoming_or_ongoing
            FROM event
            GROUP BY invite_mode
            """.trimIndent()
        ).use { statement ->
            statement.setTimestamp(1, localNow)
            statement.executeQuery().use { result ->
                val counts = mutableMapOf<InviteMode, EventTypeCount>()
                while (result.next()) {
                    val mode = InviteMode.valueOf(result.getString("invite_mode"))
                    counts[mode] = EventTypeCount(mode, result.getLong("total"), result.getLong("upcoming_or_ongoing"))
                }
                InviteMode.entries.map { counts[it] ?: EventTypeCount(it, 0, 0) }
            }
        }
        val publicLegacyEvents = connection.prepareStatement(
            """
            SELECT id, title, start_time, end_time
            FROM event
            WHERE public = TRUE AND invite_mode = 'PER_PARTICIPANT' AND start_time > ?
            ORDER BY start_time, id
            """.trimIndent()
        ).use { statement ->
            statement.setTimestamp(1, localNow)
            statement.executeQuery().use { result ->
                result.toList {
                    AdminEventSummary(
                        getObject("id", UUID::class.java),
                        getString("title"),
                        getTimestamp("start_time").toLocalDateTime(),
                        getTimestamp("end_time").toLocalDateTime(),
                    )
                }
            }
        }
        AdminStatistics(at, eventTypes, publicLegacyEvents)
    }
