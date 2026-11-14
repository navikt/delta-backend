package no.nav.delta.event

import no.nav.delta.calendar.SharedCalendarValidationException
import no.nav.delta.calendar.enqueue
import no.nav.delta.calendar.exec
import no.nav.delta.calendar.repeatableReadTransaction
import no.nav.delta.plugins.DatabaseInterface
import java.sql.Connection
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class WaitlistState(
    val enabled: Boolean,
    val count: Int,
    val position: Int?,
    val registered: Boolean,
    val open: Boolean,
)

data class WaitlistEntry(val email: String, val name: String, val position: Int)
data class MyWaitlistEvent(val event: Event, val position: Int, val open: Boolean)

class WaitlistRepository(private val db: DatabaseInterface) {
    fun state(id: UUID, email: String): WaitlistState = db.repeatableReadTransaction { c ->
        val event = c.prepareStatement("SELECT * FROM event WHERE id=?").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { if (it.next()) it.toEvent() else throw EventNotFoundException }
        }
        state(c, event, email.trim().lowercase())
    }

    fun join(id: UUID, participant: Participant): WaitlistState = db.repeatableReadTransaction { c ->
        val event = lockAllocationEvent(c, id)
        val email = participant.email.trim().lowercase()
        if (!event.waitlistEnabled || event.participantLimit <= 0)
            throw SharedCalendarValidationException(409, "Arrangementet har ikke venteliste.")
        if (!waitlistOpen(event)) throw DeadlinePassedException
        val current = state(c, event, email)
        if (current.registered || current.position != null) return@repeatableReadTransaction current
        if (hasSeatReservation(c, event, email))
            throw SharedCalendarValidationException(409, "Du har en reservert plass. Meld deg på arrangementet.")
        if (allocatedSeats(c, event) < event.participantLimit)
            throw SharedCalendarValidationException(409, "Det er en ledig plass. Meld deg på arrangementet.")
        c.exec("""
            INSERT INTO event_waitlist(event_id,email,name) VALUES (?,?,?)
            ON CONFLICT(event_id,email) DO NOTHING
        """.trimIndent(), id, email, participant.name)
        state(c, event, email)
    }

    fun leave(id: UUID, email: String, actorEmail: String? = null): WaitlistState =
        db.repeatableReadTransaction { c ->
            val event = lockAllocationEvent(c, id)
            if (actorEmail != null) requireWaitlistHost(c, id, actorEmail)
            c.exec("DELETE FROM event_waitlist WHERE event_id=? AND email=?", id, email.trim().lowercase())
            promoteWaitlist(c, event)
            state(c, event, email.trim().lowercase())
        }

    fun entries(id: UUID, actorEmail: String): List<WaitlistEntry> = db.repeatableReadTransaction { c ->
        requireWaitlistHost(c, id, actorEmail)
        entries(c, id)
    }

    fun mine(email: String): List<MyWaitlistEvent> = db.repeatableReadTransaction { c ->
        c.prepareStatement("""
            SELECT e.*, (SELECT COUNT(*) FROM event_waitlist before
                WHERE before.event_id=w.event_id AND before.id<=w.id) AS position
            FROM event_waitlist w JOIN event e ON e.id=w.event_id
            WHERE w.email=? AND e.end_time>? ORDER BY e.start_time,e.id
        """.trimIndent()).use { s ->
            s.setString(1, email.trim().lowercase())
            s.setObject(2, allocationNow())
            s.executeQuery().use { r -> r.toList {
                val event = toEvent()
                MyWaitlistEvent(event.copy(teamsJoinUrl = null, teamsConferenceId = null, teamsDialIn = null),
                    getInt("position"), waitlistOpen(event))
            } }
        }
    }

    private fun entries(c: Connection, id: UUID): List<WaitlistEntry> =
        c.prepareStatement("SELECT email,name FROM event_waitlist WHERE event_id=? ORDER BY id").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r ->
                r.toList { getString("email") to getString("name") }
                    .mapIndexed { index, person -> WaitlistEntry(person.first, person.second, index + 1) }
            }
        }

    private fun state(c: Connection, event: Event, email: String): WaitlistState {
        val (count, position) = c.prepareStatement("""
            WITH queue AS (
                SELECT email,ROW_NUMBER() OVER(ORDER BY id) AS position
                FROM event_waitlist WHERE event_id=?
            )
            SELECT COUNT(*),MAX(position) FILTER(WHERE email=?) FROM queue
        """.trimIndent()).use { s ->
            s.setObject(1, event.id); s.setString(2, email)
            s.executeQuery().use {
                it.next()
                val count = it.getInt(1)
                val position = it.getInt(2).let { position -> if (it.wasNull()) null else position }
                count to position
            }
        }
        val registered = c.prepareStatement("""
            SELECT 1 FROM participant WHERE event_id=? AND lower(email)=?
            AND (type='HOST' OR status='REGISTERED')
        """.trimIndent()).use { s ->
            s.setObject(1, event.id); s.setString(2, email)
            s.executeQuery().use { it.next() }
        }
        return WaitlistState(event.waitlistEnabled, count, position, registered, waitlistOpen(event))
    }
}

internal fun allocationNow(): LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Oslo"))

internal fun waitlistOpen(event: Event): Boolean {
    val now = allocationNow()
    return now < event.startTime && (event.signupDeadline == null || now < event.signupDeadline)
}

internal fun requireWaitlistHost(c: Connection, id: UUID, email: String) {
    val host = c.prepareStatement("SELECT 1 FROM participant WHERE event_id=? AND lower(email)=? AND type='HOST'").use { s ->
        s.setObject(1, id); s.setString(2, email.trim().lowercase())
        s.executeQuery().use { it.next() }
    }
    if (!host) throw ForbiddenException
}

internal fun lockAllocationEvent(c: Connection, id: UUID): Event {
    val event = c.prepareStatement("SELECT * FROM event WHERE id=? FOR UPDATE").use { s ->
        s.setObject(1, id)
        s.executeQuery().use { if (it.next()) it.toEvent() else throw EventNotFoundException }
    }
    c.exec("UPDATE event SET title=title WHERE id=?", id)
    return event
}

internal fun hasSeatReservation(c: Connection, event: Event, email: String): Boolean =
    c.prepareStatement("""
        SELECT 1 FROM participant WHERE event_id=? AND lower(email)=? AND status='INVITED'
        AND (?::timestamp IS NULL OR ?::timestamp>?)
    """.trimIndent()).use { s ->
        s.setObject(1, event.id); s.setString(2, email)
        s.setObject(3, event.signupDeadline); s.setObject(4, event.signupDeadline); s.setObject(5, allocationNow())
        s.executeQuery().use { it.next() }
    }

internal fun allocatedSeats(c: Connection, event: Event): Int =
    c.prepareStatement("""
        SELECT COUNT(*) FROM participant WHERE event_id=? AND
        (type='HOST' OR status='REGISTERED' OR
            (status='INVITED' AND (?::timestamp IS NULL OR ?::timestamp>?)))
    """.trimIndent()).use { s ->
        s.setObject(1, event.id); s.setObject(2, event.signupDeadline)
        s.setObject(3, event.signupDeadline); s.setObject(4, allocationNow())
        s.executeQuery().use { it.next(); it.getInt(1) }
    }

internal fun validateWaitlistEdit(c: Connection, event: Event, enabled: Boolean, limit: Int) {
    if (limit < 0) throw SharedCalendarValidationException(400, "Antall plasser kan ikke være negativt.")
    if (enabled && limit <= 0)
        throw SharedCalendarValidationException(400, "Venteliste krever et begrenset antall plasser.")
    if (!enabled || limit <= 0) {
        val queued = c.prepareStatement("SELECT 1 FROM event_waitlist WHERE event_id=? LIMIT 1").use { s ->
            s.setObject(1, event.id); s.executeQuery().use { it.next() }
        }
        if (queued) throw SharedCalendarValidationException(409,
            "Ventelisten må være tom før du kan slå den av eller fjerne plassbegrensningen.")
    }
}

internal fun promoteWaitlist(c: Connection, event: Event) {
    if (!event.waitlistEnabled || event.participantLimit <= 0 || !waitlistOpen(event)) return
    val available = event.participantLimit - allocatedSeats(c, event)
    if (available <= 0) return
    val queue = c.prepareStatement("SELECT * FROM event_waitlist WHERE event_id=? ORDER BY id LIMIT ?").use { s ->
        s.setObject(1, event.id); s.setInt(2, available)
        s.executeQuery().use { it.toList { Participant(getString("email"), getString("name")) } }
    }
    queue.forEach { participant ->
        val previousStatus = c.prepareStatement("SELECT status FROM participant WHERE event_id=? AND email=?").use { s ->
            s.setObject(1, event.id); s.setString(2, participant.email)
            s.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        if (event.inviteMode == InviteMode.SHARED && previousStatus == null) {
            val calendarCount = c.prepareStatement("SELECT COUNT(*) FROM participant WHERE event_id=?").use { s ->
                s.setObject(1, event.id); s.executeQuery().use { it.next(); it.getInt(1) }
            }
            if (calendarCount + (if (event.roomEmail == null) 0 else 1) >= 490) return
        }
        c.exec("""
            INSERT INTO participant(event_id,email,name,type,status,last_response_time)
            VALUES (?,?,?,'PARTICIPANT','REGISTERED',NOW())
            ON CONFLICT(event_id,email) DO UPDATE SET status='REGISTERED',last_response_time=NOW()
        """.trimIndent(), event.id, participant.email, participant.name)
        c.exec("DELETE FROM event_waitlist WHERE event_id=? AND email=?", event.id, participant.email)
        c.exec("INSERT INTO waitlist_notification(id,event_id,email,name) VALUES (?,?,?,?)",
            UUID.randomUUID(), event.id, participant.email, participant.name)
        if (event.inviteMode == InviteMode.SHARED) {
            c.exec("DELETE FROM shared_calendar_refusal WHERE event_id=? AND email=?", event.id, participant.email)
            enqueue(c, event.id)
            if (previousStatus == "DECLINED") {
                c.exec("""
                    INSERT INTO shared_calendar_removal(event_id,email,revision,phase,readd)
                    SELECT event_id,?,revision,'REMOVING',TRUE FROM shared_calendar_outbox WHERE event_id=?
                    ON CONFLICT(event_id,email) DO UPDATE SET revision=EXCLUDED.revision,phase='REMOVING',readd=TRUE
                """.trimIndent(), participant.email, event.id)
            }
            c.exec("DELETE FROM shared_calendar_removal WHERE event_id=? AND email=? AND phase='REMOVED'", event.id, participant.email)
            c.exec("UPDATE shared_calendar_removal SET readd=TRUE WHERE event_id=? AND email=?", event.id, participant.email)
        }
    }
}
