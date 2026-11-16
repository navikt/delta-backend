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
    // The option the caller is queued for or registered in, when the event has signup options.
    val signupOptionId: UUID? = null,
)

data class WaitlistEntry(val email: String, val name: String, val position: Int, val signupOptionId: UUID? = null)
data class MyWaitlistEvent(
    val event: Event,
    val position: Int,
    val open: Boolean,
    val hosts: List<Participant>,
    val signupOptionId: UUID? = null,
)

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
        val scope = resolveScope(c, event, participant.signupOptionId)
        if (!scope.waitlistEnabled || scope.limit <= 0)
            throw SharedCalendarValidationException(409, "Arrangementet har ikke venteliste.")
        if (!waitlistOpen(event)) throw DeadlinePassedException
        val current = state(c, event, email)
        if (current.registered || current.position != null) return@repeatableReadTransaction current
        if (hasSeatReservation(c, scope, email))
            throw SharedCalendarValidationException(409, "Du har en reservert plass. Meld deg på arrangementet.")
        if (!queueHasEntries(c, scope) && allocatedSeats(c, scope) < scope.limit)
            throw SharedCalendarValidationException(409, "Det er en ledig plass. Meld deg på arrangementet.")
        c.exec("""
            INSERT INTO event_waitlist(event_id,email,name,signup_option_id) VALUES (?,?,?,?::uuid)
            ON CONFLICT(event_id,email) DO NOTHING
        """.trimIndent(), id, email, participant.name, scope.optionId)
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
            SELECT e.*, w.signup_option_id AS queued_option, (SELECT COUNT(*) FROM event_waitlist before
                WHERE before.event_id=w.event_id AND before.id<=w.id
                AND before.signup_option_id IS NOT DISTINCT FROM w.signup_option_id) AS position
            FROM event_waitlist w JOIN event e ON e.id=w.event_id
            WHERE w.email=? AND e.end_time>? ORDER BY e.start_time,e.id
        """.trimIndent()).use { s ->
            s.setString(1, email.trim().lowercase())
            s.setObject(2, allocationNow())
            s.executeQuery().use { r -> r.toList {
                val event = toEvent()
                MyWaitlistEvent(event.copy(teamsJoinUrl = null, teamsConferenceId = null, teamsDialIn = null),
                    getInt("position"), waitlistOpen(event), emptyList(), getObject("queued_option", UUID::class.java))
            } }
        }.let { events ->
            val ids = events.map { it.event.id }
            val hosts = hosts(c, ids)
            val options = loadSignupOptions(c, ids)
            events.map {
                it.copy(event = it.event.copy(signupOptions = options[it.event.id].orEmpty()),
                    hosts = hosts[it.event.id].orEmpty())
            }
        }
    }

    private fun hosts(c: Connection, eventIds: List<UUID>): Map<UUID, List<Participant>> {
        if (eventIds.isEmpty()) return emptyMap()
        return c.prepareStatement(
            "SELECT event_id,email,name FROM participant WHERE type='HOST' AND event_id = ANY(?) ORDER BY name,email"
        ).use { s ->
            s.setArray(1, c.createArrayOf("uuid", eventIds.toTypedArray()))
            s.executeQuery().use { r ->
                r.toList { getObject("event_id", UUID::class.java) to Participant(getString("email"), getString("name")) }
            }
        }.groupBy({ it.first }, { it.second })
    }

    private fun entries(c: Connection, id: UUID): List<WaitlistEntry> =
        c.prepareStatement("""
            SELECT email,name,signup_option_id,
                ROW_NUMBER() OVER(PARTITION BY signup_option_id ORDER BY id) AS position
            FROM event_waitlist WHERE event_id=? ORDER BY id
        """.trimIndent()).use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r ->
                r.toList {
                    WaitlistEntry(getString("email"), getString("name"), getInt("position"),
                        getObject("signup_option_id", UUID::class.java))
                }
            }
        }

    private fun state(c: Connection, event: Event, email: String): WaitlistState {
        val options = signupOptions(c, event.id)
        val queued = c.prepareStatement("""
            SELECT signup_option_id,(SELECT COUNT(*) FROM event_waitlist before WHERE before.event_id=w.event_id
                AND before.id<=w.id AND before.signup_option_id IS NOT DISTINCT FROM w.signup_option_id)
            FROM event_waitlist w WHERE event_id=? AND email=?
        """.trimIndent()).use { s ->
            s.setObject(1, event.id); s.setString(2, email)
            s.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getInt(2) else null }
        }
        val registration = c.prepareStatement("""
            SELECT signup_option_id FROM participant WHERE event_id=? AND lower(email)=?
            AND (type='HOST' OR status='REGISTERED')
        """.trimIndent()).use { s ->
            s.setObject(1, event.id); s.setString(2, email)
            s.executeQuery().use { if (it.next()) listOf(it.getObject(1, UUID::class.java)) else null }
        }
        val optionId = queued?.first ?: registration?.firstOrNull()
        val count = c.prepareStatement("""
            SELECT COUNT(*) FROM event_waitlist WHERE event_id=?
            AND (?::uuid IS NULL OR signup_option_id=?::uuid)
        """.trimIndent()).use { s ->
            s.setObject(1, event.id); s.setObject(2, optionId); s.setObject(3, optionId)
            s.executeQuery().use { it.next(); it.getInt(1) }
        }
        val enabled = if (options.isEmpty()) event.waitlistEnabled else options.any { it.waitlistEnabled }
        return WaitlistState(enabled, count, queued?.second, registration != null, waitlistOpen(event), optionId)
    }
}

internal fun DatabaseInterface.legacyCalendarNotificationRecipients(
    eventId: UUID,
    candidates: List<Pair<Participant, String?>>,
): List<Pair<Participant, String?>> = connection.use { c ->
    val current = c.prepareStatement("""
        SELECT p.email,p.calendar_event_id,p.waitlist_calendar_event_id IS NOT NULL AS managed,
            EXISTS(SELECT 1 FROM waitlist_notification n
                WHERE n.id=p.waitlist_notification_id AND n.calendar_event_id IS NULL) AS pending
        FROM participant p WHERE p.event_id=?
    """.trimIndent()).use { s ->
        s.setObject(1, eventId)
        s.executeQuery().use { r ->
            r.toList { getString("email") to Triple(getString("calendar_event_id"),getBoolean("managed"),getBoolean("pending")) }.toMap()
        }
    }
    candidates.mapNotNull { (participant, calendarId) ->
        val saved = current[participant.email] ?: return@mapNotNull null
        if (saved.third) null
        else participant to if (saved.second) requireNotNull(saved.first) { "Promoted participant has no calendar ID" } else calendarId
    }
}

internal fun allocationNow(): LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Oslo"))

internal fun cancelWaitlistNotification(c: Connection, id: UUID, email: String) {
    c.exec("""
        UPDATE waitlist_notification SET cancel=TRUE,state='PENDING',attempts=0,next_attempt=NOW()
        WHERE event_id=? AND email=? AND NOT cancel
    """.trimIndent(), id, email)
}

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

/** Whether the person holds an active invitation reservation in this allocation scope. */
internal fun hasSeatReservation(c: Connection, scope: AllocationScope, email: String): Boolean =
    c.prepareStatement("""
        SELECT 1 FROM participant WHERE event_id=? AND lower(email)=? AND status='INVITED'
        AND signup_option_id IS NOT DISTINCT FROM ?::uuid
        AND (?::timestamp IS NULL OR ?::timestamp>?)
    """.trimIndent()).use { s ->
        val deadline = scope.event.signupDeadline
        s.setObject(1, scope.event.id); s.setString(2, email); s.setObject(3, scope.optionId)
        s.setObject(4, deadline); s.setObject(5, deadline); s.setObject(6, allocationNow())
        s.executeQuery().use { it.next() }
    }

/**
 * Seats taken in a scope. Event-level scopes count hosts, registrations and active invitations;
 * option scopes count only non-host registrations and active invitations in that option.
 */
internal fun allocatedSeats(c: Connection, scope: AllocationScope): Int =
    c.prepareStatement("""
        SELECT COUNT(*) FROM participant WHERE event_id=? AND (
            (?::uuid IS NULL AND (type='HOST' OR status='REGISTERED' OR
                (status='INVITED' AND (?::timestamp IS NULL OR ?::timestamp>?))))
            OR (signup_option_id=?::uuid AND type<>'HOST' AND (status='REGISTERED' OR
                (status='INVITED' AND (?::timestamp IS NULL OR ?::timestamp>?))))
        )
    """.trimIndent()).use { s ->
        val deadline = scope.event.signupDeadline
        val now = allocationNow()
        s.setObject(1, scope.event.id); s.setObject(2, scope.optionId)
        s.setObject(3, deadline); s.setObject(4, deadline); s.setObject(5, now)
        s.setObject(6, scope.optionId)
        s.setObject(7, deadline); s.setObject(8, deadline); s.setObject(9, now)
        s.executeQuery().use { it.next(); it.getInt(1) }
    }

internal fun validateWaitlistEdit(c: Connection, event: Event, enabled: Boolean, limit: Int) {
    if (limit < 0) throw SharedCalendarValidationException(400, "Antall plasser kan ikke være negativt.")
    if (enabled && limit <= 0)
        throw SharedCalendarValidationException(400, "Venteliste krever et begrenset antall plasser.")
    if (!enabled || limit <= 0) {
        val queued = c.prepareStatement(
            "SELECT 1 FROM event_waitlist WHERE event_id=? AND signup_option_id IS NULL LIMIT 1"
        ).use { s ->
            s.setObject(1, event.id); s.executeQuery().use { it.next() }
        }
        if (queued) throw SharedCalendarValidationException(409,
            "Ventelisten må være tom før du kan slå den av eller fjerne plassbegrensningen.")
    }
}

internal fun sharedCalendarAttendeeCount(c: Connection, event: Event): Int =
    c.prepareStatement("""
        SELECT COUNT(*) FROM participant p WHERE p.event_id=? AND NOT EXISTS(
            SELECT 1 FROM shared_calendar_removal r
            WHERE r.event_id=p.event_id AND r.email=p.email AND r.phase='REMOVED'
        )
    """.trimIndent()).use { s ->
        s.setObject(1, event.id)
        s.executeQuery().use { it.next(); it.getInt(1) + if (event.roomEmail == null) 0 else 1 }
    }

internal fun sharedCalendarSlotNeeded(c: Connection, id: UUID, email: String): Int =
    c.prepareStatement("""
        SELECT 1 FROM participant p WHERE p.event_id=? AND p.email=? AND NOT EXISTS(
            SELECT 1 FROM shared_calendar_removal r
            WHERE r.event_id=p.event_id AND r.email=p.email AND r.phase='REMOVED'
        )
    """.trimIndent()).use { s ->
        s.setObject(1, id); s.setString(2, email)
        s.executeQuery().use { if (it.next()) 0 else 1 }
    }

private fun reclaimWaitlistCalendarSlot(c: Connection, event: Event) {
    val pending = c.prepareStatement("""
        SELECT COUNT(*) FROM shared_calendar_removal r JOIN participant p
        ON p.event_id=r.event_id AND p.email=r.email
        WHERE r.event_id=? AND r.phase='REMOVING' AND NOT r.readd
    """.trimIndent()).use { s ->
        s.setObject(1, event.id); s.executeQuery().use { it.next(); it.getInt(1) }
    }
    val needed = sharedCalendarAttendeeCount(c, event) + 1 - 490 - pending
    if (needed <= 0) return
    val victims = c.prepareStatement("""
        SELECT p.email FROM participant p WHERE p.event_id=? AND p.type<>'HOST'
        AND p.status IN ('DECLINED','FORWARDED') AND NOT EXISTS(
            SELECT 1 FROM shared_calendar_removal r WHERE r.event_id=p.event_id AND r.email=p.email
        ) ORDER BY p.email LIMIT ?
    """.trimIndent()).use { s ->
        s.setObject(1, event.id); s.setInt(2, needed)
        s.executeQuery().use { it.toList { getString(1) } }
    }
    if (victims.isEmpty()) return
    enqueue(c, event.id)
    victims.forEach { email ->
        c.exec("""
            INSERT INTO shared_calendar_removal(event_id,email,revision)
            SELECT event_id,?,revision FROM shared_calendar_outbox WHERE event_id=?
        """.trimIndent(), email, event.id)
    }
}

internal fun promoteWaitlist(c: Connection, event: Event) {
    if (!waitlistOpen(event)) return
    // A queue blocked by calendar room must not stop other options from promoting.
    allocationScopes(c, event).forEach { promoteWaitlist(c, it) }
}

/** Promotes queued people into free seats of one scope, stopping when calendar room runs out. */
private fun promoteWaitlist(c: Connection, scope: AllocationScope) {
    val event = scope.event
    if (!scope.waitlistEnabled || scope.limit <= 0) return
    val available = scope.limit - allocatedSeats(c, scope)
    if (available <= 0) return
    val queue = c.prepareStatement("""
        SELECT * FROM event_waitlist WHERE event_id=? AND signup_option_id IS NOT DISTINCT FROM ?::uuid
        ORDER BY id LIMIT ?
    """.trimIndent()).use { s ->
        s.setObject(1, event.id); s.setObject(2, scope.optionId); s.setInt(3, available)
        s.executeQuery().use { it.toList { Participant(getString("email"), getString("name")) } }
    }
    queue.forEach { participant ->
        val previousStatus = c.prepareStatement("SELECT status FROM participant WHERE event_id=? AND email=?").use { s ->
            s.setObject(1, event.id); s.setString(2, participant.email)
            s.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        if (event.inviteMode == InviteMode.SHARED && sharedCalendarSlotNeeded(c, event.id, participant.email) > 0) {
            if (sharedCalendarAttendeeCount(c, event) >= 490) {
                reclaimWaitlistCalendarSlot(c, event)
                return
            }
        }
        val deliveryId = UUID.randomUUID()
        c.exec("""
            INSERT INTO participant(event_id,email,name,type,status,last_response_time,waitlist_notification_id,signup_option_id)
            VALUES (?,?,?,'PARTICIPANT','REGISTERED',NOW(),?,?::uuid)
            ON CONFLICT(event_id,email) DO UPDATE SET status='REGISTERED',last_response_time=NOW(),
                waitlist_notification_id=EXCLUDED.waitlist_notification_id,
                signup_option_id=EXCLUDED.signup_option_id,
                calendar_event_id=NULL,waitlist_calendar_event_id=NULL
        """.trimIndent(), event.id, participant.email, participant.name, deliveryId, scope.optionId)
        c.exec("DELETE FROM event_waitlist WHERE event_id=? AND email=?", event.id, participant.email)
        c.exec("INSERT INTO waitlist_notification(id,event_id,email,name) VALUES (?,?,?,?)",
            deliveryId, event.id, participant.email, participant.name)
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
