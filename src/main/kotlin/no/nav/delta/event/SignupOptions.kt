package no.nav.delta.event

import no.nav.delta.calendar.SharedCalendarValidationException
import no.nav.delta.calendar.exec
import no.nav.delta.plugins.DatabaseInterface
import java.sql.Connection
import java.util.UUID

private const val MAX_SIGNUP_OPTIONS = 10

/**
 * Capacity, waiting list and reservations are allocated within one scope: the whole event for
 * events without signup options, otherwise one option. Hosts consume event-level capacity only.
 */
internal data class AllocationScope(val event: Event, val option: SignupOption?) {
    val limit: Int get() = option?.participantLimit ?: event.participantLimit
    val waitlistEnabled: Boolean get() = option?.waitlistEnabled ?: event.waitlistEnabled
    val optionId: UUID? get() = option?.id
}

internal data class StoredSignupOption(val option: SignupOption, val seriesKey: UUID)

internal fun storedSignupOptions(c: Connection, eventId: UUID): List<StoredSignupOption> =
    c.prepareStatement("SELECT * FROM event_signup_option WHERE event_id=? ORDER BY position,id").use { s ->
        s.setObject(1, eventId)
        s.executeQuery().use { r -> r.toList {
            StoredSignupOption(
                SignupOption(getObject("id", UUID::class.java), getString("label"),
                    getInt("participant_limit"), getBoolean("waitlist_enabled")),
                getObject("series_key", UUID::class.java),
            )
        } }
    }

internal fun signupOptions(c: Connection, eventId: UUID): List<SignupOption> =
    storedSignupOptions(c, eventId).map { it.option }

internal fun allocationScopes(c: Connection, event: Event): List<AllocationScope> =
    signupOptions(c, event.id).map { AllocationScope(event, it) }.ifEmpty { listOf(AllocationScope(event, null)) }

internal fun DatabaseInterface.usesSignupOptions(eventId: UUID): Boolean = connection.use { usesSignupOptions(it, eventId) }

internal fun usesSignupOptions(c: Connection, eventId: UUID): Boolean =
    c.prepareStatement("SELECT 1 FROM event_signup_option WHERE event_id=? LIMIT 1").use { s ->
        s.setObject(1, eventId); s.executeQuery().use { it.next() }
    }

/** Resolves a requested option, defaulting only when the event has a single allocation scope. */
internal fun resolveScope(c: Connection, event: Event, requested: UUID?): AllocationScope {
    val options = signupOptions(c, event.id)
    if (options.isEmpty()) {
        if (requested != null) throw invalidSignupOption()
        return AllocationScope(event, null)
    }
    if (requested == null) {
        if (options.size == 1) return AllocationScope(event, options.single())
        throw SharedCalendarValidationException(400, "Velg hvordan du vil delta.")
    }
    return AllocationScope(event, options.firstOrNull { it.id == requested } ?: throw invalidSignupOption())
}

internal fun invalidSignupOption() = SharedCalendarValidationException(400, "Ugyldig påmeldingsalternativ.")

internal fun participantSignupOption(c: Connection, eventId: UUID, email: String): UUID? =
    c.prepareStatement("SELECT signup_option_id FROM participant WHERE event_id=? AND email=?").use { s ->
        s.setObject(1, eventId); s.setString(2, email)
        s.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
    }

internal fun setParticipantSignupOption(c: Connection, eventId: UUID, email: String, optionId: UUID?) {
    c.exec("UPDATE participant SET signup_option_id=?::uuid WHERE event_id=? AND email=?", optionId, eventId, email)
}

internal fun queueHasEntries(c: Connection, scope: AllocationScope): Boolean =
    c.prepareStatement(
        "SELECT 1 FROM event_waitlist WHERE event_id=? AND signup_option_id IS NOT DISTINCT FROM ?::uuid LIMIT 1"
    ).use { s ->
        s.setObject(1, scope.event.id); s.setObject(2, scope.optionId)
        s.executeQuery().use { it.next() }
    }

/** Whether any limited scope applies, used for event-level calendar policies such as forwarding. */
internal fun seatLimited(c: Connection, eventId: UUID): Boolean =
    c.prepareStatement("""
        SELECT participant_limit>0 OR EXISTS(
            SELECT 1 FROM event_signup_option WHERE event_id=e.id AND participant_limit>0)
        FROM event e WHERE id=?
    """.trimIndent()).use { s ->
        s.setObject(1, eventId); s.executeQuery().use { it.next() && it.getBoolean(1) }
    }

/** Option definitions with current availability, keyed by event. */
internal fun loadSignupOptions(c: Connection, eventIds: Collection<UUID>): Map<UUID, List<SignupOption>> {
    if (eventIds.isEmpty()) return emptyMap()
    return c.prepareStatement("""
        SELECT o.*,
            (SELECT COUNT(*) FROM participant p WHERE p.event_id=o.event_id AND p.signup_option_id=o.id
                AND p.type<>'HOST' AND (p.status='REGISTERED' OR (p.status='INVITED'
                    AND (e.signup_deadline IS NULL OR e.signup_deadline>?)))) AS allocated,
            (SELECT COUNT(*) FROM event_waitlist w WHERE w.event_id=o.event_id AND w.signup_option_id=o.id) AS queued
        FROM event_signup_option o JOIN event e ON e.id=o.event_id
        WHERE o.event_id = ANY(?) ORDER BY o.event_id,o.position,o.id
    """.trimIndent()).use { s ->
        s.setObject(1, allocationNow())
        s.setArray(2, c.createArrayOf("uuid", eventIds.toTypedArray()))
        s.executeQuery().use { r -> r.toList {
            getObject("event_id", UUID::class.java) to SignupOption(
                getObject("id", UUID::class.java), getString("label"), getInt("participant_limit"),
                getBoolean("waitlist_enabled"), getInt("allocated"), getInt("queued"),
            )
        } }
    }.groupBy({ it.first }, { it.second })
}

internal fun Event.withSignupOptions(c: Connection): Event =
    copy(signupOptions = loadSignupOptions(c, listOf(id))[id].orEmpty())

/** Whether a request configures signup options, as opposed to the event-level allocation model. */
internal fun CreateEvent.configuresSignupOptions(): Boolean = !signupOptions.isNullOrEmpty()

/**
 * Applies requested options to one event. Options are matched by [match], which returns the
 * series key of the existing option a request updates, or null for a new option. Returns whether
 * the event uses signup options afterwards.
 */
internal fun applySignupOptions(
    c: Connection,
    event: Event,
    requests: List<SignupOptionRequest>?,
    match: (SignupOptionRequest, List<StoredSignupOption>) -> UUID?,
    newOption: (SignupOptionRequest) -> Pair<UUID, UUID>,
    // New occurrences of a recurring series copy the series' options, even a single one.
    minimumForConversion: Int = 2,
): Boolean {
    val existing = storedSignupOptions(c, event.id)
    if (requests == null) return existing.isNotEmpty()
    if (requests.isEmpty()) {
        if (existing.isNotEmpty())
            throw SharedCalendarValidationException(400, "Påmeldingsalternativene kan ikke fjernes. Behold minst ett alternativ.")
        return false
    }
    validateSignupOptions(requests)
    if (existing.isEmpty() && requests.size < minimumForConversion)
        throw SharedCalendarValidationException(400, "Bruk antall plasser når arrangementet bare har ett påmeldingsalternativ.")
    if (event.inviteMode != InviteMode.SHARED)
        throw SharedCalendarValidationException(400, "Påmeldingsalternativer krever en delt kalender.")

    val matched = requests.map { request -> request to match(request, existing)?.let { key ->
        existing.firstOrNull { it.seriesKey == key }
    } }
    val retainedKeys = matched.mapNotNull { it.second?.seriesKey }.toSet()
    existing.filter { it.seriesKey !in retainedKeys }.forEach { removeSignupOption(c, it.option) }

    var firstOptionId: UUID? = null
    matched.forEachIndexed { position, (request, stored) ->
        val label = request.label.trim()
        val optionId = if (stored != null) {
            c.exec("""
                UPDATE event_signup_option SET label=?,participant_limit=?,waitlist_enabled=?,position=?
                WHERE id=?
            """.trimIndent(), label, request.participantLimit, request.waitlistEnabled, position, stored.option.id)
            stored.option.id
        } else {
            val (id, seriesKey) = newOption(request)
            val taken = c.prepareStatement("SELECT 1 FROM event_signup_option WHERE id=?").use { s ->
                s.setObject(1, id); s.executeQuery().use { it.next() }
            }
            if (taken) throw invalidSignupOption()
            c.exec("""
                INSERT INTO event_signup_option(id,event_id,series_key,label,participant_limit,waitlist_enabled,position)
                VALUES (?,?,?,?,?,?,?)
            """.trimIndent(), id, event.id, seriesKey, label, request.participantLimit, request.waitlistEnabled, position)
            id
        }
        if (position == 0) firstOptionId = optionId
    }
    c.exec("UPDATE event SET participant_limit=0,waitlist_enabled=FALSE WHERE id=?", event.id)

    if (existing.isEmpty()) {
        // Converting an event: its participants and queue keep their order in the first option.
        c.exec("""
            UPDATE participant SET signup_option_id=? WHERE event_id=? AND type<>'HOST'
            AND status IN ('REGISTERED','INVITED')
        """.trimIndent(), firstOptionId, event.id)
        c.exec("UPDATE event_waitlist SET signup_option_id=? WHERE event_id=?", firstOptionId, event.id)
    }

    val converted = event.copy(participantLimit = 0, waitlistEnabled = false)
    signupOptions(c, event.id).forEach { option ->
        val scope = AllocationScope(converted, option)
        if (option.participantLimit > 0 && allocatedSeats(c, scope) > option.participantLimit)
            throw SharedCalendarValidationException(409,
                "Antall plasser for «${option.label}» kan ikke være lavere enn antall påmeldte og reserverte plasser.")
        if (!option.waitlistEnabled && queueHasEntries(c, scope))
            throw SharedCalendarValidationException(409,
                "Ventelisten for «${option.label}» må være tom før du kan slå den av eller fjerne plassbegrensningen.")
    }
    return true
}

private fun validateSignupOptions(requests: List<SignupOptionRequest>) {
    if (requests.size > MAX_SIGNUP_OPTIONS)
        throw SharedCalendarValidationException(400, "Et arrangement kan ha maks $MAX_SIGNUP_OPTIONS påmeldingsalternativer.")
    requests.forEach { request ->
        val label = request.label.trim()
        if (label.isEmpty() || label.length > 100)
            throw SharedCalendarValidationException(400, "Påmeldingsalternativer må ha et navn på 1–100 tegn.")
        if (request.participantLimit < 0)
            throw SharedCalendarValidationException(400, "Antall plasser kan ikke være negativt.")
        if (request.waitlistEnabled && request.participantLimit <= 0)
            throw SharedCalendarValidationException(400, "Venteliste krever et begrenset antall plasser.")
    }
    if (requests.map { it.label.trim().lowercase() }.toSet().size != requests.size)
        throw SharedCalendarValidationException(400, "Påmeldingsalternativene må ha ulike navn.")
    val ids = requests.mapNotNull { it.id }
    if (ids.toSet().size != ids.size) throw invalidSignupOption()
}

private fun removeSignupOption(c: Connection, option: SignupOption) {
    val occupied = c.prepareStatement("""
        SELECT EXISTS(SELECT 1 FROM participant WHERE signup_option_id=? AND type<>'HOST'
            AND status IN ('REGISTERED','INVITED'))
        OR EXISTS(SELECT 1 FROM event_waitlist WHERE signup_option_id=?)
    """.trimIndent()).use { s ->
        s.setObject(1, option.id); s.setObject(2, option.id)
        s.executeQuery().use { it.next(); it.getBoolean(1) }
    }
    if (occupied) throw SharedCalendarValidationException(409,
        "«${option.label}» har påmeldte, reserverte plasser eller venteliste og kan ikke fjernes.")
    c.exec("UPDATE participant SET signup_option_id=NULL WHERE signup_option_id=?", option.id)
    c.exec("DELETE FROM event_signup_option WHERE id=?", option.id)
}

/** Matches requests by option id within one event; unknown ids become new options with that id. */
internal fun matchSignupOptionById(request: SignupOptionRequest, existing: List<StoredSignupOption>): UUID? =
    request.id?.let { id -> existing.firstOrNull { it.option.id == id }?.seriesKey }

internal fun newSignupOptionWithRequestedId(request: SignupOptionRequest): Pair<UUID, UUID> =
    (request.id ?: UUID.randomUUID()) to UUID.randomUUID()

/**
 * Maps option requests for a recurring series edit to series keys. Requests carry option ids of
 * the occurrence being edited; the corresponding options of other occurrences share its series key.
 */
internal class SeriesSignupOptions(
    selectedOptions: List<StoredSignupOption>,
    val requests: List<SignupOptionRequest>,
) {
    private val keys = requests.map { request ->
        selectedOptions.firstOrNull { it.option.id == request.id }?.seriesKey ?: UUID.randomUUID()
    }

    private fun keyOf(request: SignupOptionRequest): UUID = keys[requests.indexOfFirst { it === request }]

    fun match(request: SignupOptionRequest, existing: List<StoredSignupOption>): UUID? =
        keyOf(request).takeIf { key -> existing.any { it.seriesKey == key } }

    fun newOption(request: SignupOptionRequest): Pair<UUID, UUID> = UUID.randomUUID() to keyOf(request)

    fun apply(c: Connection, event: Event, minimumForConversion: Int = 2): Boolean =
        applySignupOptions(c, event, requests, ::match, ::newOption, minimumForConversion)

    companion object {
        /** The options of an existing occurrence, as requests that keep their series keys. */
        fun copyOf(options: List<StoredSignupOption>): SeriesSignupOptions = SeriesSignupOptions(options,
            options.map { SignupOptionRequest(it.option.id, it.option.label, it.option.participantLimit, it.option.waitlistEnabled) })
    }
}
