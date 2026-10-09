package no.nav.delta.calendar

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import no.nav.delta.event.*
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.room.MasterEventResult
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class SharedCalendarValidationException(val statusCode: Int, override val message: String) : RuntimeException(message)
enum class SharedCalendarResponse { ACCEPTED, TENTATIVE, DECLINED, NONE }
data class SharedCalendarAttendeeSnapshot(
    val email: String, val name: String, val response: SharedCalendarResponse,
    val isIndividual: Boolean = true,
    val respondedAt: Instant? = null,
)
data class SharedCalendarAttendee(
    val email: String, val name: String, val type: ParticipantType, val status: ParticipantStatus,
)
data class SharedCalendarDesiredState(
    val event: Event?, val attendees: List<SharedCalendarAttendee>, val removals: List<String>,
)
data class SharedCalendarWork(
    val eventId: UUID, val revision: Long, val token: UUID, val graphEventId: String?, val cancel: Boolean,
    val desired: SharedCalendarDesiredState,
    val reconcile: Boolean = false,
    val write: Boolean = true,
    val reconciliationRevision: Long = 0,
    val details: Boolean = false,
    val detailsRevision: Long = 0,
    val attempts: Int = 1,
)
data class SharedCalendarMetricsSnapshot(
    val pendingRows: Long,
    val failedRows: Long,
    val oldestPendingAgeSeconds: Long,
    val oldestFailureAgeSeconds: Long,
    val estimatedRecipientsToday: Long,
)
data class CalendarRefusal(val email: String, val reason: String)

/** Transactions are short; leased immutable work is consumed outside database transactions. */
class SharedCalendarRepository(private val db: DatabaseInterface) {
    private val snapshotMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    fun statistics(): CalendarStatistics = metrics().let {
        CalendarStatistics(it.pendingRows, it.failedRows, it.oldestPendingAgeSeconds.toDouble(),
            it.oldestFailureAgeSeconds.toDouble(), it.estimatedRecipientsToday)
    }

    fun knownAttendeeEmails(id: UUID): Set<String> = transaction { c ->
        c.prepareStatement("SELECT email FROM participant WHERE event_id=?").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { it.toList { getString(1).lowercase() }.toSet() }
        }
    }

    fun recordRecipients(count: Int) {
        if (count < 0) throw SharedCalendarValidationException(400, "Recipient estimate cannot be negative")
        transaction { c -> recordRecipients(c, count, Instant.now()) }
    }

    fun metrics(now: Instant = Instant.now()): SharedCalendarMetricsSnapshot = transaction { c ->
        c.prepareStatement("""
            SELECT COUNT(*) FILTER (WHERE state='PENDING') AS pending,
                COUNT(*) FILTER (WHERE state='FAILED') AS failed,
                MIN(pending_since) FILTER (WHERE state='PENDING') AS oldest_pending,
                MIN(failed_since) FILTER (WHERE state='FAILED') AS oldest_failed,
                COALESCE((SELECT estimated_recipients FROM shared_calendar_recipient_daily WHERE day=?),0) AS recipients
            FROM shared_calendar_outbox
        """.trimIndent()).use { s ->
            s.setObject(1, now.atOffset(ZoneOffset.UTC).toLocalDate())
            s.executeQuery().use { r ->
                r.next()
                fun age(column: String): Long = r.getTimestamp(column)?.toInstant()?.let {
                    Duration.between(it, now).seconds.coerceAtLeast(0)
                } ?: 0
                SharedCalendarMetricsSnapshot(r.getLong("pending"), r.getLong("failed"),
                    age("oldest_pending"), age("oldest_failed"), r.getLong("recipients"))
            }
        }
    }

    /** Conservative attempt accounting, including requests whose Graph response is uncertain. */
    fun recordEstimatedRecipients(work: SharedCalendarWork, recipients: Int, now: Instant = Instant.now()): Boolean {
        if (recipients < 0) throw SharedCalendarValidationException(400, "Recipient estimate cannot be negative")
        return transaction { c ->
            if (!ownsClaim(c, work)) return@transaction false
            recordRecipients(c, recipients, now)
            true
        }
    }

    private fun recordRecipients(c: Connection, count: Int, now: Instant) {
        c.exec("""
            INSERT INTO shared_calendar_recipient_daily(day,estimated_recipients) VALUES (?,?)
            ON CONFLICT(day) DO UPDATE SET estimated_recipients=shared_calendar_recipient_daily.estimated_recipients+EXCLUDED.estimated_recipients
        """.trimIndent(), now.atOffset(ZoneOffset.UTC).toLocalDate(), count.toLong())
    }
    fun create(draft: CreateEvent, host: Participant): Event = routeResult(create(draft, host.email, host.name))
    fun signup(id: UUID, participant: Participant) { routeResult(signup(id, participant.email, participant.name)) }
    fun remove(id: UUID, email: String) { routeResult(removeParticipant(id, email)) }
    fun changeRole(id: UUID, change: ChangeParticipant) { routeResult(changeParticipant(id, change)) }
    fun invite(id: UUID, requests: List<InviteeRequest>, actorEmail: String) {
        routeResult(mutate(id) { c, event ->
            requireHost(c, id, actorEmail)
            requireNonRecurringInvites(c, id, requests)
            if (invite(c, event, requests, identityEmail(actorEmail))) enqueue(c, id)
        })
    }
    fun update(id: UUID, draft: CreateEvent, actorEmail: String): Event =
        routeResult(updateInternal(id, draft, actorEmail))
    fun update(id: UUID, draft: CreateEvent): Either<ExceptionWithDefaultResponse, Event> =
        updateInternal(id, draft, null)
    private fun updateInternal(id: UUID, draft: CreateEvent, actorEmail: String?): Either<ExceptionWithDefaultResponse, Event> =
        mutate(id) { c, original ->
            if (actorEmail != null) requireHost(c, id, actorEmail)
            validateDraft(draft)
            requireNonRecurringInvites(c, id, draft.invitees ?: emptyList())
            if (original.isOnlineMeeting && draft.isOnlineMeeting == false)
                throw SharedCalendarValidationException(400, "An existing Teams meeting cannot be disabled")
            val event = c.prepareStatement("""
                UPDATE event SET title=?,description=?,start_time=?,end_time=?,location=?,public=?,
                    participant_limit=?,signup_deadline=?,room_email=COALESCE(?,room_email),
                    room_name=COALESCE(?,room_name),is_online_meeting=COALESCE(?,is_online_meeting),
                    room_status=CASE WHEN CAST(? AS TEXT) IS NOT NULL AND room_email IS DISTINCT FROM CAST(? AS TEXT)
                        THEN 'PENDING' ELSE room_status END
                WHERE id=? RETURNING *
            """.trimIndent()).use { s ->
                val values = listOf(draft.title, draft.description, draft.startTime, draft.endTime, draft.location,
                    draft.public, draft.participantLimit, draft.signupDeadline, draft.roomEmail, draft.roomName,
                    draft.isOnlineMeeting, draft.roomEmail, draft.roomEmail, id)
                values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
                s.executeQuery().use { it.next(); it.toEvent() }
            }
            draft.categories?.let { replaceCategories(c, id, it) }
            draft.invitees?.let { invite(c, event, it, actorEmail?.let { email -> identityEmail(email) }) }
            requireCapacity(c, event, 0)
            requireAttendeeRoom(c, event, 0)
            val detailsChanged = original.title != event.title || original.description != event.description ||
                original.startTime != event.startTime || original.endTime != event.endTime ||
                original.location != event.location || original.roomEmail != event.roomEmail ||
                original.roomName != event.roomName || original.isOnlineMeeting != event.isOnlineMeeting
            if (detailsChanged) enqueue(c, id, details = true)
            if (detailsChanged) event.copy(calendarSyncStatus = CalendarSyncStatus.PENDING) else event
        }

    fun retry(id: UUID) { routeResult(retryFailed(id)) }
    fun retryFailed(id: UUID): Either<ExceptionWithDefaultResponse, Unit> = mutate(id) { c, _ ->
        c.exec("UPDATE shared_calendar_outbox SET state='PENDING',next_attempt=NOW(),pending_since=NOW(),failed_since=NULL,attempts=0 WHERE event_id=? AND state IN ('FAILED','PENDING')", id)
        c.exec("UPDATE event SET calendar_sync_status='PENDING',calendar_sync_error=NULL WHERE id=? AND calendar_sync_status='FAILED'", id)
        Unit
    }

    fun create(draft: CreateEvent, hostEmail: String, hostName: String): Either<ExceptionWithDefaultResponse, Event> =
        resultTransaction { c ->
            validateDraft(draft)
            if (draft.recurrence != null)
                throw SharedCalendarValidationException(400, "Use recurring series creation for recurring events")
            val host = identityEmail(hostEmail)
            val event = c.prepareStatement("""
                INSERT INTO event(title,description,start_time,end_time,location,public,participant_limit,
                    signup_deadline,room_email,room_name,is_online_meeting,invite_mode,calendar_sync_status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'SHARED','PENDING') RETURNING *
            """.trimIndent()).use { s ->
                s.setString(1, draft.title); s.setString(2, draft.description)
                s.setObject(3, draft.startTime); s.setObject(4, draft.endTime)
                s.setString(5, draft.location); s.setBoolean(6, draft.public)
                s.setInt(7, draft.participantLimit); s.setObject(8, draft.signupDeadline)
                s.setString(9, draft.roomEmail); s.setString(10, draft.roomName)
                s.setBoolean(11, draft.isOnlineMeeting ?: false)
                s.executeQuery().use { it.next(); it.toEvent() }
            }
            putPerson(c, event.id, host, hostName, ParticipantType.HOST, ParticipantStatus.REGISTERED)
            enqueue(c, event.id)
            replaceCategories(c, event.id, draft.categories ?: emptyList())
            invite(c, event, draft.invitees ?: emptyList(), host)
            requireCapacity(c, event, 0)
            requireAttendeeRoom(c, event, 0)
            event.right()
        }

    fun claimNext(now: Instant = Instant.now(), lease: Duration = Duration.ofMinutes(5)): SharedCalendarWork? =
        transaction { c ->
            c.prepareStatement("""
                SELECT * FROM shared_calendar_outbox WHERE state = 'PENDING' AND next_attempt <= ?
                AND (lease_until IS NULL OR lease_until <= ?)
                ORDER BY next_attempt,event_id FOR UPDATE SKIP LOCKED LIMIT 100
            """.trimIndent()).use { s ->
                s.setTimestamp(1, Timestamp.from(now)); s.setTimestamp(2, Timestamp.from(now))
                s.executeQuery().use { r ->
                    while (r.next()) {
                        val id = r.getObject("event_id", UUID::class.java)
                        if (!tryEventLock(c, id)) continue
                        try {
                            val revision = r.getLong("revision")
                            val token = UUID.randomUUID()
                            val cancel = r.getBoolean("cancel")
                            val desired = desired(c, id, cancel)
                            if (!cancel && r.getString("graph_event_id") == null) {
                                c.exec("UPDATE shared_calendar_outbox SET creation_snapshot=COALESCE(creation_snapshot,?) WHERE event_id=?",
                                    snapshotMapper.writeValueAsString(desired), id)
                            }
                            c.exec("UPDATE shared_calendar_outbox SET claim_token=?,claimed_revision=?,lease_until=?,attempts=attempts+1 WHERE event_id=?",
                                token, revision, Timestamp.from(now.plus(lease)), id)
                            return@transaction SharedCalendarWork(id, revision, token, r.getString("graph_event_id"), cancel,
                                desired,
                                attempts = r.getInt("attempts") + 1,
                                reconcile = r.getLong("reconciliation_revision") > r.getLong("reconciled_revision"),
                                write = revision > r.getLong("applied_revision"),
                                reconciliationRevision = r.getLong("reconciliation_revision"),
                                details = r.getLong("details_revision") > r.getLong("applied_details_revision"),
                                detailsRevision = r.getLong("details_revision"))
                        } finally { unlockEvent(c, id) }
                    }
                    null
                }
            }
        }

    /**
     * One dedicated session spans bounded Graph calls, without an open producer transaction.
     * A live session prevents lease takeover; tokens alone cannot fence external Graph writes.
     */
    fun <T> withEventLock(work: SharedCalendarWork, action: () -> T): T? =
        db.connection.use { c ->
            c.autoCommit = true
            if (!tryEventLock(c, work.eventId)) return@use null
            try {
                if (!ownsClaim(c, work)) null else action()
            } finally { unlockEvent(c, work.eventId) }
        }

    private fun tryEventLock(c: Connection, id: UUID): Boolean =
        c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?::text, 7823491))").use { s ->
            s.setString(1, id.toString()); s.executeQuery().use { it.next(); it.getBoolean(1) }
        }

    private fun unlockEvent(c: Connection, id: UUID) {
        c.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?::text, 7823491))").use { s ->
            s.setString(1, id.toString()); s.executeQuery().close()
        }
    }

    fun loadDesired(work: SharedCalendarWork): SharedCalendarDesiredState =
        transaction { c -> desired(c, work.eventId, work.cancel) }

    fun creationState(work: SharedCalendarWork): SharedCalendarDesiredState = transaction { c ->
        c.prepareStatement("SELECT creation_snapshot FROM shared_calendar_outbox WHERE event_id=?").use { s ->
            s.setObject(1, work.eventId)
            s.executeQuery().use { r ->
                if (!r.next()) throw IllegalStateException("Calendar intent no longer exists")
                r.getString(1)?.let { snapshotMapper.readValue<SharedCalendarDesiredState>(it) } ?: work.desired
            }
        }
    }

    fun enqueuePeriodicReconciliation(now: Instant = Instant.now()): Int = transaction { c ->
        c.exec("""
            UPDATE shared_calendar_outbox o SET reconciliation_revision=reconciliation_revision+1,
                next_attempt=?,pending_since=?,state='PENDING',last_reconciliation_queued=?
            WHERE event_id IN (
                SELECT o2.event_id FROM shared_calendar_outbox o2 JOIN event e ON e.id=o2.event_id
                WHERE o2.state='SYNCED' AND NOT o2.cancel AND o2.graph_event_id IS NOT NULL
                    AND e.end_time > CURRENT_TIMESTAMP
                    AND (o2.last_reconciliation_queued IS NULL OR o2.last_reconciliation_queued < ?)
                ORDER BY o2.last_reconciliation_queued NULLS FIRST LIMIT 100
                FOR UPDATE OF o2 SKIP LOCKED
            )
        """.trimIndent(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
            Timestamp.from(now.minusSeconds(300)))
    }

    fun enqueueReconciliation(id: UUID): Either<ExceptionWithDefaultResponse, Unit> = mutate(id) { c, _ ->
        c.exec("""
            UPDATE shared_calendar_outbox SET reconciliation_revision=reconciliation_revision+1,next_attempt=NOW(),
                last_reconciliation_queued=NOW(),
                pending_since=CASE WHEN state='SYNCED' THEN NOW() ELSE pending_since END,
                state=CASE WHEN state='SYNCED' THEN 'PENDING' ELSE state END
            WHERE event_id=?
        """.trimIndent(), id)
        Unit
    }
    fun enqueueReconciliation(calendarGraphId: String): Boolean {
        val id = transaction { c ->
            c.prepareStatement("""
                SELECT id FROM event WHERE invite_mode='SHARED' AND master_calendar_event_id=?
            """.trimIndent()).use { s ->
                s.setString(1, calendarGraphId)
                s.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            }
        } ?: return false
        return enqueueReconciliation(id).isRight()
    }

    internal fun initializeOccurrence(
        c: Connection,
        event: Event,
        requests: List<InviteeRequest>,
        detailsChanged: Boolean = true,
    ): Event {
        c.exec("""
            UPDATE event SET invite_mode='SHARED',
                calendar_sync_status=CASE WHEN ? THEN 'PENDING' ELSE calendar_sync_status END
            WHERE id=?
        """.trimIndent(), detailsChanged, event.id)
        enqueue(c, event.id, details = detailsChanged)
        invite(c, event, requests)
        requireCapacity(c, event, 0)
        return event.copy(
            inviteMode = InviteMode.SHARED,
            calendarSyncStatus = if (detailsChanged) CalendarSyncStatus.PENDING else event.calendarSyncStatus,
        )
    }

    fun reconcile(id: UUID, snapshots: List<SharedCalendarAttendeeSnapshot>): Either<ExceptionWithDefaultResponse, Unit> =
        mutate(id) { c, event ->
            snapshots.distinctBy { it.email.trim().lowercase() }.forEach { snapshot ->
                val email = snapshot.email.trim().lowercase()
                if (email == event.roomEmail?.lowercase()) return@forEach
                val revoked = c.prepareStatement("SELECT 1 FROM shared_calendar_removal WHERE event_id=? AND email=?").use { s ->
                    s.setObject(1, id); s.setString(2, email); s.executeQuery().use { it.next() }
                }
                if (revoked) return@forEach
                var existing = person(c, id, email)
                if (existing?.type == ParticipantType.HOST) return@forEach
                val previous = c.prepareStatement("SELECT last_response_time,last_response_value FROM participant WHERE event_id=? AND email=?").use { s ->
                        s.setObject(1, id); s.setString(2, email)
                        s.executeQuery().use { if (it.next()) it.getTimestamp(1)?.toInstant() to it.getString(2) else null }
                    }
                if (snapshot.respondedAt != null && previous?.first != null &&
                    !snapshot.respondedAt.isAfter(previous.first)) return@forEach
                if (snapshot.respondedAt == null && previous?.second == snapshot.response.name) return@forEach
                if (existing == null) {
                    val supported = snapshot.isIndividual && runCatching { normalize(email) }.isSuccess &&
                        attendeeRoomAvailable(c, event)
                    if (!supported) {
                        revoke(c, id, email)
                        return@forEach
                    }
                    putPerson(c, id, email, snapshot.name, ParticipantType.PARTICIPANT, ParticipantStatus.FORWARDED)
                    existing = person(c, id, email)!!
                }
                val status = when (snapshot.response) {
                    SharedCalendarResponse.DECLINED -> ParticipantStatus.DECLINED
                    SharedCalendarResponse.ACCEPTED -> {
                        if (existing.status == ParticipantStatus.REGISTERED) ParticipantStatus.REGISTERED
                        else {
                            val reserved = existing.status == ParticipantStatus.INVITED &&
                                event.signupDeadline?.let { it <= LocalDateTime.now() } != true
                            val deadlinePassed = !reserved && event.signupDeadline?.let { it <= LocalDateTime.now() } == true
                            if (deadlinePassed || !capacityAvailable(c, event, if (reserved) 0 else 1)) {
                                c.exec("""
                                    INSERT INTO shared_calendar_refusal(event_id,email,reason) VALUES (?,?,?)
                                    ON CONFLICT(event_id,email) DO UPDATE SET reason=EXCLUDED.reason
                                """.trimIndent(), id, email, if (deadlinePassed) "DEADLINE" else "FULL")
                                ParticipantStatus.DECLINED
                            }
                            else ParticipantStatus.REGISTERED
                        }
                    }
                    SharedCalendarResponse.TENTATIVE, SharedCalendarResponse.NONE -> existing.status
                }
                c.exec("UPDATE participant SET status=? WHERE event_id=? AND email=?", status.name, id, email)
                c.exec("UPDATE participant SET last_response_value=? WHERE event_id=? AND email=?", snapshot.response.name, id, email)
                if (snapshot.respondedAt != null)
                    c.exec("UPDATE participant SET last_response_time=? WHERE event_id=? AND email=?",
                        Timestamp.from(snapshot.respondedAt), id, email)
            }
        }

    fun refusals(id: UUID): List<CalendarRefusal> = transaction { c ->
        c.prepareStatement("SELECT email,reason FROM shared_calendar_refusal WHERE event_id=? ORDER BY email").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { it.toList { CalendarRefusal(getString(1), getString(2)) } }
        }
    }

    fun acknowledgeRefusal(id: UUID, notice: CalendarRefusal) = transaction { c ->
        c.exec("DELETE FROM shared_calendar_refusal WHERE event_id=? AND email=? AND reason=?", id, notice.email, notice.reason)
    }

    private fun requireHost(c: Connection, id: UUID, email: String) {
        if (person(c, id, identityEmail(email))?.type != ParticipantType.HOST) throw ForbiddenException
    }

    private fun <T> routeResult(result: Either<ExceptionWithDefaultResponse, T>): T = when (result) {
        is Either.Right -> result.value
        is Either.Left -> throw SharedCalendarValidationException(when (result.value) {
            EventNotFoundException, EmailNotFoundException -> 404
            ForbiddenException -> 403
            EventFullException, DeadlinePassedException, ParticipantAlreadyRegisteredException, EventWillHaveNoHostsException -> 409
            else -> 400
        }, result.value.message)
    }

    fun addInvitations(id: UUID, requests: List<InviteeRequest>, invitedBy: String? = null): Either<ExceptionWithDefaultResponse, Unit> =
        mutate(id) { c, event ->
            requireNonRecurringInvites(c, id, requests)
            if (invitedBy != null) requireHost(c, id, invitedBy)
            if (invite(c, event, requests, invitedBy?.let { identityEmail(it) })) enqueue(c, id)
        }

    private fun requireNonRecurringInvites(c: Connection, id: UUID, requests: List<InviteeRequest>) {
        if (requests.isNotEmpty() && loadRecurringSeriesSummaries(c, listOf(id)).isNotEmpty())
            throw SharedCalendarValidationException(400, "Invitations are not supported for recurring series")
    }

    fun signup(id: UUID, email: String, name: String): Either<ExceptionWithDefaultResponse, Unit> =
        mutate(id) { c, event ->
            val address = identityEmail(email)
            val existing = person(c, id, address)
            if (existing?.type == ParticipantType.HOST || existing?.status == ParticipantStatus.REGISTERED)
                throw ParticipantAlreadyRegisteredException
            if (event.signupDeadline?.let { it <= LocalDateTime.now() } == true) throw DeadlinePassedException
            requireCapacity(c, event, if (existing?.status == ParticipantStatus.INVITED) 0 else 1)
            requireAttendeeRoom(c, event, if (existing == null) 1 else 0)
            putPerson(c, id, address, name, ParticipantType.PARTICIPANT, ParticipantStatus.REGISTERED)
            c.exec("UPDATE participant SET last_response_time=NOW() WHERE event_id=? AND email=?", id, address)
            c.exec("DELETE FROM shared_calendar_refusal WHERE event_id=? AND email=?", id, address)
            if (existing?.status == ParticipantStatus.DECLINED) revoke(c, id, address)
            prepareReadd(c, id, address)
            if (existing == null) enqueue(c, id)
        }

    fun signoff(id: UUID, email: String): Either<ExceptionWithDefaultResponse, Unit> =
        mutate(id) { c, _ ->
            val address = identityEmail(email)
            val existing = person(c, id, address) ?: throw EmailNotFoundException
            guardLastHost(c, id, existing)
            c.exec("DELETE FROM participant WHERE event_id=? AND email=?", id, address)
            revoke(c, id, address)
        }

    fun removeParticipant(id: UUID, email: String): Either<ExceptionWithDefaultResponse, Unit> =
        mutate(id) { c, _ ->
            val address = identityEmail(email)
            val existing = person(c, id, address) ?: throw EmailNotFoundException
            guardLastHost(c, id, existing)
            c.exec("DELETE FROM participant WHERE event_id=? AND email=?", id, address)
            revoke(c, id, address)
        }

    fun changeParticipant(id: UUID, change: ChangeParticipant): Either<ExceptionWithDefaultResponse, Unit> =
        mutate(id) { c, event ->
            val email = identityEmail(change.email)
            val existing = person(c, id, email) ?: throw EmailNotFoundException
            if (existing.type != change.type) {
                if (change.type != ParticipantType.HOST) guardLastHost(c, id, existing)
                if (change.type == ParticipantType.HOST && existing.status != ParticipantStatus.REGISTERED &&
                    !(existing.status == ParticipantStatus.INVITED && event.signupDeadline?.let { it <= LocalDateTime.now() } != true))
                    requireCapacity(c, event)
                putPerson(c, id, email, existing.name, change.type, ParticipantStatus.REGISTERED)
                prepareReadd(c, id, email)
                enqueue(c, id)
            }
        }

    private fun guardLastHost(c: Connection, id: UUID, person: SharedCalendarAttendee) {
        if (person.type != ParticipantType.HOST) return
        val count = c.prepareStatement("SELECT COUNT(*) FROM participant WHERE event_id=? AND type='HOST'").use { s ->
            s.setObject(1, id); s.executeQuery().use { it.next(); it.getInt(1) }
        }
        if (count <= 1) throw EventWillHaveNoHostsException
    }

    private fun revoke(c: Connection, id: UUID, email: String) {
        enqueue(c, id)
        c.exec("""
            INSERT INTO shared_calendar_removal(event_id,email,revision)
            SELECT event_id,?,revision FROM shared_calendar_outbox WHERE event_id=?
            ON CONFLICT(event_id,email) DO UPDATE SET revision=EXCLUDED.revision,phase='REMOVING',readd=FALSE
        """.trimIndent(), email, id)
    }

    private fun prepareReadd(c: Connection, id: UUID, email: String) {
        c.exec("DELETE FROM shared_calendar_removal WHERE event_id=? AND email=? AND phase='REMOVED'", id, email)
        c.exec("UPDATE shared_calendar_removal SET readd=TRUE WHERE event_id=? AND email=? AND phase='REMOVING'", id, email)
    }

    fun delete(id: UUID): Either<ExceptionWithDefaultResponse, Unit> = mutate(id) { c, _ ->
        c.exec("DELETE FROM event WHERE id=?", id)
        Unit
    }

    /** Acknowledges create even after deletion, but never accepts an expired/replaced lease token. */
    fun recordGraphId(work: SharedCalendarWork, graphId: String): Boolean = transaction { c ->
        lockExistingEvent(c, work.eventId)
        if (!ownsClaim(c, work)) return@transaction false
        c.exec("UPDATE shared_calendar_outbox SET graph_event_id=?,creation_snapshot=NULL WHERE event_id=?", graphId, work.eventId)
        c.exec("UPDATE event SET master_calendar_event_id=? WHERE id=?", graphId, work.eventId)
        true
    }

    fun recordMeetingMetadata(work: SharedCalendarWork, result: MasterEventResult): Boolean = transaction { c ->
        lockExistingEvent(c, work.eventId)
        if (!ownsClaim(c, work)) return@transaction false
        c.exec("""
            UPDATE event SET room_status=COALESCE(?,room_status),teams_join_url=COALESCE(?,teams_join_url),
                teams_conference_id=COALESCE(?,teams_conference_id),teams_dial_in=COALESCE(?,teams_dial_in)
            WHERE id=? AND (SELECT revision FROM shared_calendar_outbox WHERE event_id=?)=?
        """.trimIndent(), result.roomStatus?.name, result.teamsJoinUrl, result.teamsConferenceId,
            result.teamsDialIn, work.eventId, work.eventId, work.revision)
        true
    }

    fun complete(work: SharedCalendarWork): Boolean = transaction { c ->
        lockExistingEvent(c, work.eventId)
        if (!ownsClaim(c, work)) return@transaction false
        val readd = if (!work.write) emptyList() else c.prepareStatement("""
            DELETE FROM shared_calendar_removal WHERE event_id=? AND revision<=? AND phase='REMOVING' AND readd
            RETURNING email
        """.trimIndent()).use { s ->
            s.setObject(1, work.eventId); s.setLong(2, work.revision)
            s.executeQuery().use { it.toList { getString(1) } }
        }
        if (work.write) c.exec("UPDATE shared_calendar_removal SET phase='REMOVED' WHERE event_id=? AND revision<=? AND phase='REMOVING'",
            work.eventId, work.revision)
        if (readd.isNotEmpty() && !work.cancel) enqueue(c, work.eventId)
        if (work.cancel) {
            c.exec("DELETE FROM shared_calendar_removal WHERE event_id=?", work.eventId)
            c.exec("UPDATE shared_calendar_outbox SET creation_snapshot=NULL WHERE event_id=?", work.eventId)
        }
        c.exec("""
            UPDATE shared_calendar_outbox SET applied_revision=GREATEST(applied_revision,?),
                applied_details_revision=GREATEST(applied_details_revision,?),
                reconciled_revision=GREATEST(reconciled_revision,?) WHERE event_id=?
        """.trimIndent(), if (work.write) work.revision else 0L,
            if (work.details) work.detailsRevision else 0L,
            if (work.reconcile) work.reconciliationRevision else 0L, work.eventId)
        c.exec("""
            UPDATE shared_calendar_outbox SET
                state=CASE WHEN revision=applied_revision AND reconciliation_revision=reconciled_revision THEN 'SYNCED' ELSE 'PENDING' END,
                claim_token=NULL,claimed_revision=NULL,lease_until=NULL,next_attempt=NOW(),attempts=0 WHERE event_id=?
        """.trimIndent(), work.eventId)
        c.exec("""
            UPDATE event SET calendar_sync_status=CASE WHEN
                (SELECT revision=applied_revision FROM shared_calendar_outbox WHERE event_id=?) THEN 'SYNCED' ELSE 'PENDING' END,
                calendar_sync_error=NULL WHERE id=?
        """.trimIndent(), work.eventId, work.eventId)
        true
    }

    fun acknowledgeDetails(work: SharedCalendarWork): Boolean = transaction { c ->
        lockExistingEvent(c, work.eventId)
        if (!ownsClaim(c, work)) return@transaction false
        c.exec("UPDATE shared_calendar_outbox SET applied_details_revision=GREATEST(applied_details_revision,?) WHERE event_id=?",
            work.detailsRevision, work.eventId)
        true
    }

    fun retry(work: SharedCalendarWork, nextAttempt: Instant): Boolean = transaction { c ->
        lockExistingEvent(c, work.eventId)
        if (!ownsClaim(c, work)) return@transaction false
        c.exec("""
            UPDATE shared_calendar_outbox SET claim_token=NULL,claimed_revision=NULL,lease_until=NULL,
                next_attempt=CASE WHEN revision=? AND reconciliation_revision=? THEN ? ELSE NOW() END
            WHERE event_id=?
        """.trimIndent(), work.revision, work.reconciliationRevision, Timestamp.from(nextAttempt), work.eventId)
        true
    }

    fun fail(work: SharedCalendarWork): Boolean = transaction { c ->
        lockExistingEvent(c, work.eventId)
        if (!ownsClaim(c, work)) return@transaction false
        c.exec("""
            UPDATE shared_calendar_outbox SET state=CASE WHEN revision=? AND reconciliation_revision=? THEN 'FAILED' ELSE 'PENDING' END,
                failed_since=CASE WHEN revision=? AND reconciliation_revision=? THEN NOW() ELSE NULL END,
                claim_token=NULL,claimed_revision=NULL,lease_until=NULL,next_attempt=NOW() WHERE event_id=?
        """.trimIndent(), work.revision, work.reconciliationRevision, work.revision, work.reconciliationRevision, work.eventId)
        c.exec("""
            UPDATE event SET calendar_sync_status=CASE WHEN
                (SELECT state FROM shared_calendar_outbox WHERE event_id=?)='FAILED' THEN 'FAILED' ELSE 'PENDING' END,
                calendar_sync_error=CASE WHEN
                (SELECT state FROM shared_calendar_outbox WHERE event_id=?)='FAILED'
                THEN 'Calendar synchronization failed. Please retry.' ELSE NULL END WHERE id=?
        """.trimIndent(), work.eventId, work.eventId, work.eventId)
        true
    }

    private fun lockExistingEvent(c: Connection, id: UUID) {
        c.prepareStatement("SELECT id FROM event WHERE id=? FOR UPDATE").use { s ->
            s.setObject(1, id); s.executeQuery().close()
        }
    }

    private fun ownsClaim(c: Connection, work: SharedCalendarWork): Boolean =
        c.prepareStatement("""
            SELECT 1 FROM shared_calendar_outbox WHERE event_id=? AND claim_token=? AND claimed_revision=?
            FOR UPDATE
        """.trimIndent()).use { s ->
            s.setObject(1, work.eventId); s.setObject(2, work.token); s.setLong(3, work.revision)
            s.executeQuery().use { it.next() }
        }

    private fun <T> mutate(id: UUID, block: (Connection, Event) -> T): Either<ExceptionWithDefaultResponse, T> =
        resultTransaction { c ->
            val event = c.prepareStatement("SELECT * FROM event WHERE id=? FOR UPDATE").use { s ->
                s.setObject(1, id); s.executeQuery().use { if (it.next()) it.toEvent() else throw EventNotFoundException }
            }
            if (event.inviteMode != InviteMode.SHARED)
                throw SharedCalendarValidationException(400, "Event does not use shared invitations")
            // A row lock alone does not refresh a REPEATABLE_READ snapshot. A write forces stale
            // contenders to retry with a fresh snapshot before counting seats.
            c.exec("UPDATE event SET title=title WHERE id=?", id)
            block(c, event).right()
        }

    private fun <T> resultTransaction(block: (Connection) -> Either<ExceptionWithDefaultResponse, T>): Either<ExceptionWithDefaultResponse, T> =
        try { transaction(block) } catch (e: ExceptionWithDefaultResponse) { e.left() }

    private fun desired(c: Connection, id: UUID, cancel: Boolean): SharedCalendarDesiredState {
        if (cancel) {
            val snapshot = c.prepareStatement("SELECT creation_snapshot FROM shared_calendar_outbox WHERE event_id=?").use { s ->
                s.setObject(1, id); s.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
            return snapshot?.let { snapshotMapper.readValue<SharedCalendarDesiredState>(it) }
                ?: SharedCalendarDesiredState(null, emptyList(), emptyList())
        }
        val event = if (cancel) null else c.prepareStatement("SELECT * FROM event WHERE id=?").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { if (it.next()) it.toEvent() else null }
        }
        val attendees = if (event == null) emptyList() else c.prepareStatement("""
            SELECT p.* FROM participant p WHERE event_id=?
            AND NOT EXISTS(SELECT 1 FROM shared_calendar_removal r WHERE r.event_id=p.event_id AND r.email=p.email)
            ORDER BY email
        """.trimIndent()).use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r -> r.toList { SharedCalendarAttendee(
                getString("email"), getString("name"), ParticipantType.valueOf(getString("type")),
                ParticipantStatus.valueOf(getString("status")),
            ) } }
        }
        val removals = c.prepareStatement("SELECT email FROM shared_calendar_removal WHERE event_id=? AND phase='REMOVING' ORDER BY email").use { s ->
            s.setObject(1, id); s.executeQuery().use { it.toList { getString(1) } }
        }
        return SharedCalendarDesiredState(event, attendees, removals)
    }

    private fun invite(c: Connection, event: Event, requests: List<InviteeRequest>, invitedBy: String? = null): Boolean {
        val emails = requests.map { normalize(it.email) }.distinct()
        var changed = false
        emails.forEach { email ->
            val existing = person(c, event.id, email)
            if (existing == null || (existing.type != ParticipantType.HOST &&
                        existing.status in setOf(ParticipantStatus.DECLINED, ParticipantStatus.FORWARDED))) {
                requireCapacity(c, event, if (event.signupDeadline?.let { it <= LocalDateTime.now() } == true) 0 else 1)
                requireAttendeeRoom(c, event, if (existing == null) 1 else 0)
                if (existing?.status == ParticipantStatus.DECLINED) revoke(c, event.id, email)
                putPerson(c, event.id, email, existing?.name ?: email, ParticipantType.PARTICIPANT, ParticipantStatus.INVITED)
                c.exec("UPDATE participant SET invited_by=?,invited_at=NOW() WHERE event_id=? AND email=?", invitedBy, event.id, email)
                prepareReadd(c, event.id, email)
                changed = true
            }
        }
        return changed
    }

    private fun validateDraft(draft: CreateEvent) {
        if (draft.startTime >= draft.endTime || draft.participantLimit < 0)
            throw SharedCalendarValidationException(400, "Invalid event time or capacity")
        draft.invitees?.forEach { normalize(it.email) }
    }

    private fun normalize(email: String): String {
        val normalized = email.trim().lowercase()
        if (!Regex("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@nav\\.no").matches(normalized))
            throw SharedCalendarValidationException(400, "Invitees must have an individual Nav email address")
        return normalized
    }

    private fun identityEmail(email: String): String = email.trim().lowercase()

    private fun person(c: Connection, id: UUID, email: String): SharedCalendarAttendee? =
        c.prepareStatement("SELECT * FROM participant WHERE event_id=? AND email=?").use { s ->
            s.setObject(1, id); s.setString(2, email)
            s.executeQuery().use { r -> if (!r.next()) null else SharedCalendarAttendee(
                r.getString("email"), r.getString("name"), ParticipantType.valueOf(r.getString("type")),
                ParticipantStatus.valueOf(r.getString("status")),
            ) }
        }

    private fun requireCapacity(c: Connection, event: Event, extra: Int = 1) {
        val count = c.prepareStatement("""
            SELECT COUNT(*) FROM participant WHERE event_id=? AND
            (type='HOST' OR status='REGISTERED' OR (status='INVITED' AND (?::timestamp IS NULL OR ?::timestamp > ?)))
        """.trimIndent()).use { s ->
            s.setObject(1, event.id); s.setObject(2, event.signupDeadline); s.setObject(3, event.signupDeadline)
            s.setObject(4, LocalDateTime.now())
            s.executeQuery().use { it.next(); it.getInt(1) }
        }

        if (event.participantLimit > 0 && count + extra > event.participantLimit) throw EventFullException
    }

    private fun requireAttendeeRoom(c: Connection, event: Event, extra: Int) {
        val count = c.prepareStatement("SELECT COUNT(*) FROM participant WHERE event_id=?").use { s ->
            s.setObject(1, event.id); s.executeQuery().use { it.next(); it.getInt(1) }
        }
        if (count + extra + (if (event.roomEmail == null) 0 else 1) > 490)
            throw SharedCalendarValidationException(409, "Calendar attendee limit reached")
    }

    private fun attendeeRoomAvailable(c: Connection, event: Event): Boolean =
        try { requireAttendeeRoom(c, event, 1); true } catch (_: SharedCalendarValidationException) { false }

    private fun capacityAvailable(c: Connection, event: Event, extra: Int): Boolean =
        try { requireCapacity(c, event, extra); true } catch (e: ExceptionWithDefaultResponse) {
            if (e == EventFullException) false else throw e
        }

    private fun putPerson(c: Connection, id: UUID, email: String, name: String, type: ParticipantType, status: ParticipantStatus) {
        c.exec("""
            INSERT INTO participant(event_id,email,name,type,status) VALUES (?,?,?,?::participant_type,?)
            ON CONFLICT(event_id,email) DO UPDATE SET name=EXCLUDED.name,type=EXCLUDED.type,status=EXCLUDED.status
        """.trimIndent(), id, email, name, type.name, status.name)
    }

    private fun replaceCategories(c: Connection, id: UUID, categories: List<Int>) {
        c.exec("DELETE FROM event_has_category WHERE event_id=?", id)
        categories.distinct().forEach {
            c.exec("INSERT INTO event_has_category(event_id,category_id) SELECT ?,id FROM category WHERE id=?", id, it)
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T {
        return db.repeatableReadTransaction(block)
    }
}

internal inline fun <T> DatabaseInterface.repeatableReadTransaction(block: (Connection) -> T): T {
    repeat(5) { attempt ->
        try {
            return connection.use { c ->
                c.autoCommit = false
                c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                try {
                    val result = block(c)
                    if (result is Either.Left<*>) c.rollback() else c.commit()
                    result
                } catch (e: Exception) { c.rollback(); throw e }
            }
        } catch (e: SQLException) {
            if (e.sqlState !in setOf("40001", "40P01") || attempt == 4) throw e
        }
    }
    error("Unreachable")
}

internal inline fun <T> DatabaseInterface.sharedResultTransaction(
    block: (Connection) -> Either<ExceptionWithDefaultResponse, T>,
): Either<ExceptionWithDefaultResponse, T> =
    try { repeatableReadTransaction(block) } catch (e: ExceptionWithDefaultResponse) { e.left() }

internal fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    args.forEachIndexed { index, value -> s.setObject(index + 1, value) }
    s.executeUpdate()
}

internal fun enqueue(c: Connection, id: UUID, details: Boolean = false) {
    c.exec("""
        INSERT INTO shared_calendar_outbox(event_id) VALUES (?)
        ON CONFLICT(event_id) DO UPDATE SET revision=shared_calendar_outbox.revision+1,
            details_revision=CASE WHEN ? THEN shared_calendar_outbox.revision+1 ELSE shared_calendar_outbox.details_revision END,
            pending_since=CASE WHEN shared_calendar_outbox.state='PENDING' THEN shared_calendar_outbox.pending_since ELSE NOW() END,
            failed_since=NULL,state='PENDING',next_attempt=NOW()
    """.trimIndent(), id, details)
    c.exec("UPDATE event SET calendar_sync_status='PENDING',calendar_sync_error=NULL WHERE id=?", id)
}
