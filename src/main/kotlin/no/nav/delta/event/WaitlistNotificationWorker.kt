package no.nav.delta.event

import com.microsoft.kiota.ApiException
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
        val candidate = db.connection.use { c ->
            c.prepareStatement("""
                SELECT id,event_id FROM waitlist_notification
                WHERE state='PENDING' AND next_attempt<=NOW() ORDER BY next_attempt,id LIMIT 1
            """.trimIndent()).use { s -> s.executeQuery().use {
                if (it.next()) it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) else null
            } }
        } ?: return false
        val (id, eventId) = candidate
        // Event-before-job lock order also fences signoff while a legacy invite is being sent.
        // Graph creation uses a stable transactionId; mail runs separately without the event lock.
        val ready = db.repeatableReadTransaction { c ->
            val event = try { lockAllocationEvent(c, eventId) } catch (_: EventNotFoundException) {
                return@repeatableReadTransaction false
            }
            val person = c.prepareStatement("""
                SELECT email,name,calendar_event_id FROM waitlist_notification
                WHERE id=? AND state='PENDING' AND next_attempt<=NOW() FOR UPDATE SKIP LOCKED
            """.trimIndent()).use { s ->
                s.setObject(1, id)
                s.executeQuery().use { r ->
                    if (r.next()) Triple(r.getString(1), r.getString(2), r.getString(3)) else null
                }
            } ?: return@repeatableReadTransaction false
            try {
                if (event.inviteMode == InviteMode.PER_PARTICIPANT && person.third == null) {
                    val calendarId = cloud.createWaitlistEvent(event, Participant(person.first, person.second), id.toString())
                        .fold({ throw WaitlistDeliveryException(it) }, { it })
                    c.exec("UPDATE participant SET calendar_event_id=?,waitlist_calendar_event_id=? WHERE event_id=? AND email=?",
                        calendarId, calendarId, eventId, person.first)
                    c.exec("UPDATE waitlist_notification SET calendar_event_id=? WHERE id=?", calendarId, id)
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: SQLException) {
                throw error
            } catch (error: Exception) {
                recordFailure(c, id, error)
                false
            }
        }
        if (ready) deliverMail(id)
        return true
    }

    private fun deliverMail(id: UUID) = db.repeatableReadTransaction { c ->
        val notice = c.prepareStatement("""
            SELECT n.email,e.title,e.id FROM waitlist_notification n JOIN event e ON e.id=n.event_id
            WHERE n.id=? AND n.state='PENDING' AND n.next_attempt<=NOW()
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
