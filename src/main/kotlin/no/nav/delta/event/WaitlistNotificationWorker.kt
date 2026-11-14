package no.nav.delta.event

import com.microsoft.kiota.ApiException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.delta.calendar.exec
import no.nav.delta.calendar.repeatableReadTransaction
import no.nav.delta.email.CalendarEventNotFoundException
import no.nav.delta.email.CloudClient
import no.nav.delta.plugins.DatabaseInterface
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

class WaitlistNotificationWorker(private val db: DatabaseInterface, private val cloud: CloudClient) {
    private val logger = LoggerFactory.getLogger(WaitlistNotificationWorker::class.java)
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    fun initialize(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!runOnce()) delay(2000)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SQLException) {
                    logger.error("Waitlist notification database operation failed", error)
                    delay(5000)
                }
            }
        }
    }

    fun runOnce(): Boolean {
        if (cancelOnce()) return true
        return db.connection.use { c ->
            c.autoCommit = true
            val candidates = c.prepareStatement("""
                SELECT id,event_id FROM waitlist_notification
                WHERE state='PENDING' AND next_attempt<=NOW() ORDER BY next_attempt,id LIMIT 100
            """.trimIndent()).use { s ->
                s.executeQuery().use { it.toList { getObject(1, UUID::class.java) to getObject(2, UUID::class.java) } }
            }
            for ((id, eventId) in candidates) {
                val locked = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?::text, 7823492))").use { s ->
                    s.setObject(1, id); s.executeQuery().use { it.next(); it.getBoolean(1) }
                }
                if (!locked) continue
                try {
                    if (process(id, eventId)) return@use true
                } finally {
                    c.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?::text, 7823492))").use { s ->
                        s.setObject(1, id); s.executeQuery().close()
                    }
                }
            }
            false
        }
    }

    private fun process(id: UUID, eventId: UUID): Boolean {
        // Persist the immutable POST before Graph, then acknowledge by promotion generation.
        val work = db.repeatableReadTransaction { c ->
            val event = lockEvent(c, eventId)
            c.prepareStatement("""
                SELECT email,name,calendar_event_id,creation_snapshot,cancel FROM waitlist_notification
                WHERE id=? AND state='PENDING' AND next_attempt<=NOW() FOR UPDATE SKIP LOCKED
            """.trimIndent()).use { s ->
                s.setObject(1, id)
                s.executeQuery().use { r ->
                    if (!r.next()) return@repeatableReadTransaction null
                    val cancel = r.getBoolean("cancel") || event == null
                    val snapshot = r.getString("creation_snapshot")
                        ?: event?.takeIf { !cancel && it.inviteMode == InviteMode.PER_PARTICIPANT }?.let {
                            mapper.writeValueAsString(it).also { json ->
                                c.exec("UPDATE waitlist_notification SET creation_snapshot=? WHERE id=?", json, id)
                            }
                        }
                    PromotionDelivery(id, eventId, Participant(r.getString("email"), r.getString("name")),
                        event, snapshot, r.getString("calendar_event_id"), cancel)
                }
            }
        } ?: return false
        try {
            val event = work.snapshot?.let { mapper.readValue<Event>(it) } ?: work.event
            var calendarId = work.calendarId
            if (calendarId == null && event?.inviteMode == InviteMode.PER_PARTICIPANT &&
                (!work.cancel || work.snapshot != null)) {
                calendarId = cloud.createWaitlistEvent(event, work.person, id.toString())
                    .fold({ throw WaitlistDeliveryException(it) }, { it })
            }
            val current = acknowledge(work, calendarId)
            if (current != null) {
                if (calendarId != null && current != event) {
                    cloud.updateEvent(calendarId, current, work.person).fold({ throw WaitlistDeliveryException(it) }, {})
                }
                deliverMail(id)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SQLException) {
            throw error
        } catch (error: Exception) {
            db.repeatableReadTransaction { c -> recordFailure(c, id, error) }
        }
        return true
    }

    private fun lockEvent(c: Connection, id: UUID): Event? {
        val event = c.prepareStatement("SELECT * FROM event WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, id); s.executeQuery().use { if (it.next()) it.toEvent() else null }
        }
        if (event != null) c.exec("UPDATE event SET title=title WHERE id=?", id)
        return event
    }

    private fun acknowledge(work: PromotionDelivery, calendarId: String?): Event? = db.repeatableReadTransaction { c ->
        val event = lockEvent(c, work.eventId)
        val cancel = c.prepareStatement("SELECT cancel FROM waitlist_notification WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, work.id); s.executeQuery().use { if (it.next()) it.getBoolean(1) else null }
        }
        if (cancel == null) {
            val stillUsed = calendarId != null && c.prepareStatement("""
                SELECT 1 FROM participant WHERE event_id=? AND email=? AND waitlist_calendar_event_id=?
            """.trimIndent()).use { s ->
                s.setObject(1, work.eventId); s.setString(2, work.person.email); s.setString(3, calendarId)
                s.executeQuery().use { it.next() }
            }
            if (calendarId != null && !stillUsed) enqueueCancellation(c, calendarId)
            return@repeatableReadTransaction null
        }
        val active = c.prepareStatement("""
            SELECT 1 FROM participant WHERE event_id=? AND email=? AND waitlist_notification_id=?
            AND (type='HOST' OR status='REGISTERED')
        """.trimIndent()).use { s ->
            s.setObject(1, work.eventId); s.setString(2, work.person.email); s.setObject(3, work.id)
            s.executeQuery().use { it.next() }
        }
        if (cancel || event == null || !active) {
            if (calendarId != null) enqueueCancellation(c, calendarId)
            c.exec("DELETE FROM waitlist_notification WHERE id=?", work.id)
            return@repeatableReadTransaction null
        }
        if (calendarId != null) {
            c.exec("""
                UPDATE participant SET calendar_event_id=?,waitlist_calendar_event_id=?
                WHERE event_id=? AND email=? AND waitlist_notification_id=?
            """.trimIndent(), calendarId, calendarId, work.eventId, work.person.email, work.id)
            c.exec("UPDATE waitlist_notification SET calendar_event_id=? WHERE id=?", calendarId, work.id)
        }
        event
    }

    private fun enqueueCancellation(c: Connection, calendarId: String) {
        c.exec("INSERT INTO waitlist_calendar_cancellation(calendar_event_id) VALUES (?) ON CONFLICT DO NOTHING", calendarId)
    }

    private fun deliverMail(id: UUID) = db.repeatableReadTransaction { c ->
        val notice = c.prepareStatement("""
            SELECT n.email,e.title,e.id FROM waitlist_notification n JOIN event e ON e.id=n.event_id
            WHERE n.id=? AND n.state='PENDING' AND n.next_attempt<=NOW() AND NOT n.cancel
            FOR UPDATE OF n SKIP LOCKED
        """.trimIndent()).use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r ->
                if (r.next()) Triple(r.getString(1), r.getString(2), r.getObject(3, UUID::class.java)) else null
            }
        } ?: return@repeatableReadTransaction
        try {
            cloud.sendEmail(
                subject = "Du har fått plass på ${notice.second}",
                body = "Du er automatisk meldt på ${notice.second} fra ventelisten.\n" +
                    "Se arrangementet eller meld deg av: https://delta.nav.no/event/${notice.third}",
                toRecipients = listOf(notice.first),
            )
            c.exec("DELETE FROM waitlist_notification WHERE id=?", id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SQLException) {
            throw error
        } catch (error: Exception) {
            recordFailure(c, id, error)
        }
    }

    private fun recordFailure(c: Connection, id: UUID, error: Exception) {
        c.exec("""
            UPDATE waitlist_notification SET attempts=attempts+1,
                state=CASE WHEN attempts>=9 THEN 'FAILED' ELSE 'PENDING' END,
                next_attempt=? WHERE id=?
        """.trimIndent(), java.sql.Timestamp.from(Instant.now().plusSeconds(60)), id)
        logger.warn("Waitlist notification failed; delivery retained for retry ({})", error::class.simpleName)
    }

    private fun cancelOnce(): Boolean = db.repeatableReadTransaction { c ->
        val calendarId = c.prepareStatement("""
            SELECT calendar_event_id FROM waitlist_calendar_cancellation
            WHERE state='PENDING' AND next_attempt<=NOW() ORDER BY next_attempt
            FOR UPDATE SKIP LOCKED LIMIT 1
        """.trimIndent()).use { s ->
            s.executeQuery().use { if (it.next()) it.getString(1) else null }
        } ?: return@repeatableReadTransaction false
        val failure = cloud.deleteEvent(calendarId).leftOrNull()
        val alreadyDeleted = failure?.let { generateSequence(it) { cause -> cause.cause }.any {
            it is CalendarEventNotFoundException || (it is ApiException && it.responseStatusCode == 404)
        } } == true
        if (failure == null || alreadyDeleted) {
            c.exec("DELETE FROM waitlist_calendar_cancellation WHERE calendar_event_id=?", calendarId)
        } else {
            c.exec("""
                UPDATE waitlist_calendar_cancellation SET attempts=attempts+1,
                    state=CASE WHEN attempts>=9 THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt=? WHERE calendar_event_id=?
            """.trimIndent(), java.sql.Timestamp.from(Instant.now().plusSeconds(60)), calendarId)
            logger.warn("Promoted calendar cancellation failed; retained for retry ({})", failure::class.simpleName)
        }
        true
    }

    fun metrics(): String = db.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("""
                SELECT COUNT(*) FILTER(WHERE state='PENDING'),COUNT(*) FILTER(WHERE state='FAILED')
                FROM (SELECT state FROM waitlist_notification UNION ALL SELECT state FROM waitlist_calendar_cancellation) deliveries
            """.trimIndent()).use { r ->
                r.next()
                "delta_waitlist_notifications_pending ${r.getLong(1)}\ndelta_waitlist_notifications_failed ${r.getLong(2)}\n"
            }
        }
    }
}

private class WaitlistDeliveryException(cause: Throwable) : RuntimeException(cause)

private data class PromotionDelivery(
    val id: UUID,
    val eventId: UUID,
    val person: Participant,
    val event: Event?,
    val snapshot: String?,
    val calendarId: String?,
    val cancel: Boolean,
)
