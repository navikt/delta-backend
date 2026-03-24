package no.nav.delta.event

import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.right
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface

data class RecurringEventMutationResult(
    val referenceEventId: UUID,
    val affectedEvents: List<Event>,
)

private data class RecurringSeriesRecord(
    val id: UUID,
    val title: String,
    val description: String,
    val location: String,
    val public: Boolean,
    val participantLimit: Int,
    val frequency: RecurrenceFrequency,
    val startDate: LocalDate,
    val untilDate: LocalDate,
    val occurrenceDurationMinutes: Long,
    val signupDeadlineOffsetMinutes: Long?,
    val createdByEmail: String,
)

private data class RecurringOccurrenceRecord(
    val seriesId: UUID,
    val event: Event,
    val occurrenceIndex: Int,
    val occurrenceDate: LocalDate,
)

fun DatabaseInterface.createRecurringEventSeries(
    createEvent: CreateEvent,
    hostEmail: String,
    hostName: String,
): Either<ExceptionWithDefaultResponse, RecurringEventMutationResult> {
    val draft =
        when (val seriesDraft = createEvent.toRecurringSeriesDraft(hostEmail)) {
            is Either.Left -> return seriesDraft
            is Either.Right -> seriesDraft.value
        }

    return connection.use { connection ->
        val seriesId = insertRecurringSeries(connection, draft)
        replaceSeriesCategories(connection, seriesId, draft.categories)

        val createdEvents =
            draft.occurrences.map { occurrence ->
                val event =
                    insertEvent(
                        connection,
                        createEvent.copy(
                            startTime = occurrence.startTime,
                            endTime = occurrence.endTime,
                            signupDeadline = occurrence.signupDeadline,
                            recurrence = null,
                            editScope = null,
                        ),
                    )

                insertRecurringOccurrence(connection, seriesId, event.id, occurrence.occurrenceIndex, occurrence.occurrenceDate)
                insertParticipant(connection, event.id, hostEmail, hostName, ParticipantType.HOST)
                replaceEventCategories(connection, event.id, draft.categories)
                event
            }

        connection.commit()
        RecurringEventMutationResult(
            referenceEventId = createdEvents.first().id,
            affectedEvents = createdEvents,
        ).right()
    }
}

fun DatabaseInterface.updateRecurringSeriesFromOccurrence(
    eventId: String,
    createEvent: CreateEvent,
    updatedByEmail: String,
): Either<ExceptionWithDefaultResponse, RecurringEventMutationResult> {
    return connection.use { connection ->
        val selectedOccurrence =
            loadRecurringOccurrence(connection, UUID.fromString(eventId))
                ?: return UnsupportedRecurringOperationException(
                    "Only recurring events can be updated with UPCOMING scope"
                ).left()

        val existingSeries =
            loadRecurringSeries(connection, selectedOccurrence.seriesId)
                ?: return UnsupportedRecurringOperationException("Recurring series not found").left()

        createEvent.recurrence?.let { recurrence ->
            if (recurrence.frequency != existingSeries.frequency || recurrence.untilDate != existingSeries.untilDate) {
                return UnsupportedRecurringOperationException(
                    "Updating the recurrence rule itself is not supported yet"
                ).left()
            }
        }

        val newDurationMinutes =
            Duration.between(createEvent.startTime, createEvent.endTime).toMinutes().takeIf { it > 0 }
                ?: return RecurringEventValidationException("Start time must be before end time").left()

        val upcomingOccurrences =
            loadRecurringOccurrencesFromIndex(connection, selectedOccurrence.seriesId, selectedOccurrence.occurrenceIndex)
        if (upcomingOccurrences.isEmpty()) {
            return UnsupportedRecurringOperationException("No upcoming recurring occurrences found").left()
        }

        if (upcomingOccurrences.any { !isHostOf(connection, it.event.id, updatedByEmail) }) {
            return ForbiddenException.left()
        }

        val startShift = Duration.between(selectedOccurrence.event.startTime, createEvent.startTime)
        val signupDeadlineOffsetMinutes =
            createEvent.signupDeadline?.let { Duration.between(createEvent.startTime, it).toMinutes() }
        val targetCategories = createEvent.categories ?: loadSeriesCategoryIds(connection, existingSeries.id)
        val targetUntilDate = upcomingOccurrences.last().event.startTime.plus(startShift).toLocalDate()

        val targetSeriesId =
            if (selectedOccurrence.occurrenceIndex == 0) {
                existingSeries.id.also {
                    updateRecurringSeries(
                        connection = connection,
                        seriesId = it,
                        title = createEvent.title,
                        description = createEvent.description,
                        location = createEvent.location,
                        public = createEvent.public,
                        participantLimit = createEvent.participantLimit,
                        startDate = createEvent.startTime.toLocalDate(),
                        untilDate = targetUntilDate,
                        occurrenceDurationMinutes = newDurationMinutes,
                        signupDeadlineOffsetMinutes = signupDeadlineOffsetMinutes,
                    )
                    if (createEvent.categories != null) {
                        replaceSeriesCategories(connection, it, createEvent.categories)
                    }
                }
            } else {
                insertRecurringSeries(
                    connection = connection,
                    title = createEvent.title,
                    description = createEvent.description,
                    location = createEvent.location,
                    public = createEvent.public,
                    participantLimit = createEvent.participantLimit,
                    frequency = existingSeries.frequency,
                    startDate = createEvent.startTime.toLocalDate(),
                    untilDate = targetUntilDate,
                    occurrenceDurationMinutes = newDurationMinutes,
                    signupDeadlineOffsetMinutes = signupDeadlineOffsetMinutes,
                    createdByEmail = updatedByEmail,
                ).also {
                    replaceSeriesCategories(connection, it, targetCategories)
                }
            }

        val updatedEvents =
            upcomingOccurrences.mapIndexed { index, occurrence ->
                val newStart = occurrence.event.startTime.plus(startShift)
                val updatedEvent =
                    updateEvent(
                        connection = connection,
                        newEvent =
                            Event(
                                id = occurrence.event.id,
                                title = createEvent.title,
                                description = createEvent.description,
                                startTime = newStart,
                                endTime = newStart.plusMinutes(newDurationMinutes),
                                location = createEvent.location,
                                public = createEvent.public,
                                participantLimit = createEvent.participantLimit,
                                signupDeadline = signupDeadlineOffsetMinutes?.let { newStart.plusMinutes(it) },
                            ),
                    )

                if (createEvent.categories != null) {
                    replaceEventCategories(connection, updatedEvent.id, createEvent.categories)
                }

                updateRecurringOccurrence(
                    connection = connection,
                    eventId = updatedEvent.id,
                    seriesId = targetSeriesId,
                    occurrenceIndex = if (selectedOccurrence.occurrenceIndex == 0) occurrence.occurrenceIndex else index,
                    occurrenceDate = newStart.toLocalDate(),
                )

                updatedEvent
            }

        if (selectedOccurrence.occurrenceIndex > 0) {
            val pastOccurrences =
                loadRecurringOccurrencesBeforeIndex(connection, existingSeries.id, selectedOccurrence.occurrenceIndex)
            updateRecurringSeriesUntilDate(connection, existingSeries.id, pastOccurrences.last().occurrenceDate)
        }

        connection.commit()
        RecurringEventMutationResult(
            referenceEventId = selectedOccurrence.event.id,
            affectedEvents = updatedEvents,
        ).right()
    }
}

fun DatabaseInterface.deleteRecurringSeriesFromOccurrence(
    eventId: String,
    deletedByEmail: String,
): Either<ExceptionWithDefaultResponse, List<Pair<Event, List<Pair<Participant, Option<String>>>>>> {
    return connection.use { connection ->
        val selectedOccurrence =
            loadRecurringOccurrence(connection, UUID.fromString(eventId))
                ?: return UnsupportedRecurringOperationException(
                    "Only recurring events can be deleted with UPCOMING scope"
                ).left()

        val upcomingOccurrences =
            loadRecurringOccurrencesFromIndex(connection, selectedOccurrence.seriesId, selectedOccurrence.occurrenceIndex)
        if (upcomingOccurrences.isEmpty()) {
            return UnsupportedRecurringOperationException("No upcoming recurring occurrences found").left()
        }

        if (upcomingOccurrences.any { !isHostOf(connection, it.event.id, deletedByEmail) }) {
            return ForbiddenException.left()
        }

        // Collect participant data before deletion — participant rows cascade-delete with the event
        val notificationData = upcomingOccurrences.map { occurrence ->
            Pair(occurrence.event, loadParticipantsWithCalendarIds(connection, occurrence.event.id))
        }

        val eventIdArray = connection.createArrayOf("uuid", upcomingOccurrences.map { it.event.id }.toTypedArray())
        connection.prepareStatement("DELETE FROM event WHERE id = ANY(?)").use {
            it.setArray(1, eventIdArray)
            it.executeUpdate()
        }

        if (selectedOccurrence.occurrenceIndex == 0) {
            // No past occurrences remain; remove the series record as well
            connection.prepareStatement("DELETE FROM recurring_event_series WHERE id = ?").use {
                it.setObject(1, selectedOccurrence.seriesId)
                it.executeUpdate()
            }
        } else {
            val pastOccurrences =
                loadRecurringOccurrencesBeforeIndex(connection, selectedOccurrence.seriesId, selectedOccurrence.occurrenceIndex)
            updateRecurringSeriesUntilDate(connection, selectedOccurrence.seriesId, pastOccurrences.last().occurrenceDate)
        }

        connection.commit()
        notificationData.right()
    }
}

fun loadRecurringSeriesSummaries(
    connection: Connection,
    eventIds: Collection<UUID>,
): Map<UUID, RecurringSeriesSummary> {
    if (eventIds.isEmpty()) return emptyMap()

    val eventIdArray = connection.createArrayOf("uuid", eventIds.toTypedArray())
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT reo.event_id,
       rs.id AS series_id,
       rs.recurrence_frequency,
       rs.until_date
FROM   recurring_event_occurrence reo
       JOIN recurring_event_series rs
         ON rs.id = reo.series_id
WHERE  reo.event_id = ANY(?);
"""
        )
    preparedStatement.setArray(1, eventIdArray)

    val result = preparedStatement.executeQuery()
    return result.toList {
        UUID.fromString(getString("event_id")) to
            RecurringSeriesSummary(
                seriesId = UUID.fromString(getString("series_id")),
                frequency = RecurrenceFrequency.valueOf(getString("recurrence_frequency")),
                untilDate = getDate("until_date").toLocalDate(),
            )
    }.toMap()
}

private fun loadRecurringOccurrence(
    connection: Connection,
    eventId: UUID,
): RecurringOccurrenceRecord? {
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT reo.series_id,
       reo.occurrence_index,
       reo.occurrence_date,
       e.*
FROM   recurring_event_occurrence reo
       JOIN event e
         ON e.id = reo.event_id
WHERE  reo.event_id = ?;
"""
        )
    preparedStatement.setObject(1, eventId)

    val result = preparedStatement.executeQuery()
    return if (!result.next()) {
        null
    } else {
        RecurringOccurrenceRecord(
            seriesId = UUID.fromString(result.getString("series_id")),
            event = result.toEvent(),
            occurrenceIndex = result.getInt("occurrence_index"),
            occurrenceDate = result.getDate("occurrence_date").toLocalDate(),
        )
    }
}

private fun loadRecurringOccurrencesFromIndex(
    connection: Connection,
    seriesId: UUID,
    fromOccurrenceIndex: Int,
): List<RecurringOccurrenceRecord> {
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT reo.series_id,
       reo.occurrence_index,
       reo.occurrence_date,
       e.*
FROM   recurring_event_occurrence reo
       JOIN event e
         ON e.id = reo.event_id
WHERE  reo.series_id = ?
       AND reo.occurrence_index >= ?
ORDER  BY reo.occurrence_index;
"""
        )
    preparedStatement.setObject(1, seriesId)
    preparedStatement.setInt(2, fromOccurrenceIndex)

    val result = preparedStatement.executeQuery()
    return result.toList {
        RecurringOccurrenceRecord(
            seriesId = UUID.fromString(getString("series_id")),
            event = toEvent(),
            occurrenceIndex = getInt("occurrence_index"),
            occurrenceDate = getDate("occurrence_date").toLocalDate(),
        )
    }
}

private fun loadRecurringOccurrencesBeforeIndex(
    connection: Connection,
    seriesId: UUID,
    beforeOccurrenceIndex: Int,
): List<RecurringOccurrenceRecord> {
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT reo.series_id,
       reo.occurrence_index,
       reo.occurrence_date,
       e.*
FROM   recurring_event_occurrence reo
       JOIN event e
         ON e.id = reo.event_id
WHERE  reo.series_id = ?
       AND reo.occurrence_index < ?
ORDER  BY reo.occurrence_index;
"""
        )
    preparedStatement.setObject(1, seriesId)
    preparedStatement.setInt(2, beforeOccurrenceIndex)

    val result = preparedStatement.executeQuery()
    return result.toList {
        RecurringOccurrenceRecord(
            seriesId = UUID.fromString(getString("series_id")),
            event = toEvent(),
            occurrenceIndex = getInt("occurrence_index"),
            occurrenceDate = getDate("occurrence_date").toLocalDate(),
        )
    }
}

private fun loadRecurringSeries(
    connection: Connection,
    seriesId: UUID,
): RecurringSeriesRecord? {
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT *
FROM   recurring_event_series
WHERE  id = ?;
"""
        )
    preparedStatement.setObject(1, seriesId)

    val result = preparedStatement.executeQuery()
    return if (!result.next()) {
        null
    } else {
        RecurringSeriesRecord(
            id = UUID.fromString(result.getString("id")),
            title = result.getString("title"),
            description = result.getString("description"),
            location = result.getString("location"),
            public = result.getBoolean("public"),
            participantLimit = result.getInt("participant_limit"),
            frequency = RecurrenceFrequency.valueOf(result.getString("recurrence_frequency")),
            startDate = result.getDate("start_date").toLocalDate(),
            untilDate = result.getDate("until_date").toLocalDate(),
            occurrenceDurationMinutes = result.getLong("occurrence_duration_minutes"),
            signupDeadlineOffsetMinutes = result.getLongOrNull("signup_deadline_offset_minutes"),
            createdByEmail = result.getString("created_by_email"),
        )
    }
}

private fun insertRecurringSeries(
    connection: Connection,
    draft: RecurringSeriesDraft,
): UUID =
    insertRecurringSeries(
        connection = connection,
        title = draft.title,
        description = draft.description,
        location = draft.location,
        public = draft.public,
        participantLimit = draft.participantLimit,
        frequency = draft.frequency,
        startDate = draft.startDate,
        untilDate = draft.untilDate,
        occurrenceDurationMinutes = draft.occurrenceDurationMinutes,
        signupDeadlineOffsetMinutes = draft.signupDeadlineOffsetMinutes,
        createdByEmail = draft.createdByEmail,
    )

private fun insertRecurringSeries(
    connection: Connection,
    title: String,
    description: String,
    location: String,
    public: Boolean,
    participantLimit: Int,
    frequency: RecurrenceFrequency,
    startDate: LocalDate,
    untilDate: LocalDate,
    occurrenceDurationMinutes: Long,
    signupDeadlineOffsetMinutes: Long?,
    createdByEmail: String,
): UUID {
    val preparedStatement =
        connection.prepareStatement(
            """
INSERT INTO recurring_event_series
            (
                        title,
                        description,
                        location,
                        public,
                        participant_limit,
                        recurrence_frequency,
                        start_date,
                        until_date,
                        occurrence_duration_minutes,
                        signup_deadline_offset_minutes,
                        created_by_email
            )
            VALUES
            (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?
            )
            returning id;
"""
        )
    preparedStatement.setString(1, title)
    preparedStatement.setString(2, description)
    preparedStatement.setString(3, location)
    preparedStatement.setBoolean(4, public)
    preparedStatement.setInt(5, participantLimit)
    preparedStatement.setString(6, frequency.name)
    preparedStatement.setObject(7, startDate)
    preparedStatement.setObject(8, untilDate)
    preparedStatement.setLong(9, occurrenceDurationMinutes)
    preparedStatement.setObject(10, signupDeadlineOffsetMinutes)
    preparedStatement.setString(11, createdByEmail)

    val result = preparedStatement.executeQuery()
    result.next()
    return UUID.fromString(result.getString("id"))
}

private fun updateRecurringSeries(
    connection: Connection,
    seriesId: UUID,
    title: String,
    description: String,
    location: String,
    public: Boolean,
    participantLimit: Int,
    startDate: LocalDate,
    untilDate: LocalDate,
    occurrenceDurationMinutes: Long,
    signupDeadlineOffsetMinutes: Long?,
) {
    val preparedStatement =
        connection.prepareStatement(
            """
UPDATE recurring_event_series
SET    title = ?,
       description = ?,
       location = ?,
       public = ?,
       participant_limit = ?,
       start_date = ?,
       until_date = ?,
       occurrence_duration_minutes = ?,
       signup_deadline_offset_minutes = ?
WHERE  id = ?;
"""
        )
    preparedStatement.setString(1, title)
    preparedStatement.setString(2, description)
    preparedStatement.setString(3, location)
    preparedStatement.setBoolean(4, public)
    preparedStatement.setInt(5, participantLimit)
    preparedStatement.setObject(6, startDate)
    preparedStatement.setObject(7, untilDate)
    preparedStatement.setLong(8, occurrenceDurationMinutes)
    preparedStatement.setObject(9, signupDeadlineOffsetMinutes)
    preparedStatement.setObject(10, seriesId)
    preparedStatement.executeUpdate()
}

private fun updateRecurringSeriesUntilDate(
    connection: Connection,
    seriesId: UUID,
    untilDate: LocalDate,
) {
    val preparedStatement =
        connection.prepareStatement(
            """
UPDATE recurring_event_series
SET    until_date = ?
WHERE  id = ?;
"""
        )
    preparedStatement.setObject(1, untilDate)
    preparedStatement.setObject(2, seriesId)
    preparedStatement.executeUpdate()
}

private fun insertRecurringOccurrence(
    connection: Connection,
    seriesId: UUID,
    eventId: UUID,
    occurrenceIndex: Int,
    occurrenceDate: LocalDate,
) {
    val preparedStatement =
        connection.prepareStatement(
            """
INSERT INTO recurring_event_occurrence
            (
                        series_id,
                        event_id,
                        occurrence_index,
                        occurrence_date
            )
            VALUES
            (
                        ?,
                        ?,
                        ?,
                        ?
            );
"""
        )
    preparedStatement.setObject(1, seriesId)
    preparedStatement.setObject(2, eventId)
    preparedStatement.setInt(3, occurrenceIndex)
    preparedStatement.setObject(4, occurrenceDate)
    preparedStatement.executeUpdate()
}

private fun updateRecurringOccurrence(
    connection: Connection,
    eventId: UUID,
    seriesId: UUID,
    occurrenceIndex: Int,
    occurrenceDate: LocalDate,
) {
    val preparedStatement =
        connection.prepareStatement(
            """
UPDATE recurring_event_occurrence
SET    series_id = ?,
       occurrence_index = ?,
       occurrence_date = ?
WHERE  event_id = ?;
"""
        )
    preparedStatement.setObject(1, seriesId)
    preparedStatement.setInt(2, occurrenceIndex)
    preparedStatement.setObject(3, occurrenceDate)
    preparedStatement.setObject(4, eventId)
    preparedStatement.executeUpdate()
}

private fun insertEvent(
    connection: Connection,
    createEvent: CreateEvent,
): Event {
    val preparedStatement =
        connection.prepareStatement(
            """
 INSERT INTO event
            (
                        title,
                        description,
                        start_time,
                        end_time,
                        location,
                        public,
                        participant_limit,
                        signup_deadline
            )
            VALUES
            (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?
            )
            returning *;
"""
        )

    preparedStatement.setString(1, createEvent.title)
    preparedStatement.setString(2, createEvent.description)
    preparedStatement.setTimestamp(3, Timestamp.valueOf(createEvent.startTime))
    preparedStatement.setTimestamp(4, Timestamp.valueOf(createEvent.endTime))
    preparedStatement.setString(5, createEvent.location)
    preparedStatement.setBoolean(6, createEvent.public)
    preparedStatement.setInt(7, createEvent.participantLimit)
    preparedStatement.setTimestamp(8, createEvent.signupDeadline?.let { Timestamp.valueOf(it) })

    val result = preparedStatement.executeQuery()
    result.next()
    return result.toEvent()
}

private fun updateEvent(
    connection: Connection,
    newEvent: Event,
): Event {
    val preparedStatement =
        connection.prepareStatement(
            """
UPDATE event
SET    title=?,
       description=?,
       start_time=?,
       end_time=?,
       location=?,
       public=?,
       participant_limit=?,
       signup_deadline=?
WHERE  id=Uuid(?) returning *;
"""
        )
    preparedStatement.setString(1, newEvent.title)
    preparedStatement.setString(2, newEvent.description)
    preparedStatement.setTimestamp(3, Timestamp.valueOf(newEvent.startTime))
    preparedStatement.setTimestamp(4, Timestamp.valueOf(newEvent.endTime))
    preparedStatement.setString(5, newEvent.location)
    preparedStatement.setBoolean(6, newEvent.public)
    preparedStatement.setInt(7, newEvent.participantLimit)
    preparedStatement.setTimestamp(8, newEvent.signupDeadline?.let { Timestamp.valueOf(it) })
    preparedStatement.setString(9, newEvent.id.toString())

    val result = preparedStatement.executeQuery()
    result.next()
    return result.toEvent()
}

private fun insertParticipant(
    connection: Connection,
    eventId: UUID,
    email: String,
    name: String,
    type: ParticipantType,
) {
    val preparedStatement =
        connection.prepareStatement(
            """
INSERT INTO participant
            (event_id,
             email,
             name,
             type)
VALUES      (?,
             ?,
             ?,
             ?::participant_type);
"""
        )
    preparedStatement.setObject(1, eventId)
    preparedStatement.setString(2, email)
    preparedStatement.setString(3, name)
    preparedStatement.setString(4, type.name)
    preparedStatement.executeUpdate()
}

private fun replaceSeriesCategories(
    connection: Connection,
    seriesId: UUID,
    categoryIds: List<Int>,
) {
    val validCategoryIds = filterValidCategoryIds(connection, categoryIds)
    connection.prepareStatement(
        """
DELETE FROM recurring_event_series_category
WHERE  series_id = ?;
"""
    ).use {
        it.setObject(1, seriesId)
        it.executeUpdate()
    }

    if (validCategoryIds.isEmpty()) return

    val preparedStatement =
        connection.prepareStatement(
            """
INSERT INTO recurring_event_series_category
            (
                        series_id,
                        category_id
            )
            VALUES
            ${validCategoryIds.joinToString(",") { "(?, ?)" }};
"""
        )
    validCategoryIds.forEachIndexed { index, categoryId ->
        preparedStatement.setObject(index * 2 + 1, seriesId)
        preparedStatement.setInt(index * 2 + 2, categoryId)
    }
    preparedStatement.executeUpdate()
}

private fun replaceEventCategories(
    connection: Connection,
    eventId: UUID,
    categoryIds: List<Int>,
) {
    val validCategoryIds = filterValidCategoryIds(connection, categoryIds)
    connection.prepareStatement(
        """
DELETE FROM event_has_category
WHERE  event_id = ?;
"""
    ).use {
        it.setObject(1, eventId)
        it.executeUpdate()
    }

    if (validCategoryIds.isEmpty()) return

    val preparedStatement =
        connection.prepareStatement(
            """
INSERT INTO event_has_category
            (
                        event_id,
                        category_id
            )
            VALUES
            ${validCategoryIds.joinToString(",") { "(?, ?)" }};
"""
        )
    validCategoryIds.forEachIndexed { index, categoryId ->
        preparedStatement.setObject(index * 2 + 1, eventId)
        preparedStatement.setInt(index * 2 + 2, categoryId)
    }
    preparedStatement.executeUpdate()
}

private fun loadSeriesCategoryIds(
    connection: Connection,
    seriesId: UUID,
): List<Int> {
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT category_id
FROM   recurring_event_series_category
WHERE  series_id = ?
ORDER  BY category_id;
"""
        )
    preparedStatement.setObject(1, seriesId)
    val result = preparedStatement.executeQuery()
    return result.toList { getInt("category_id") }
}

private fun filterValidCategoryIds(
    connection: Connection,
    categoryIds: List<Int>,
): List<Int> {
    if (categoryIds.isEmpty()) return emptyList()

    val distinctCategoryIds = categoryIds.distinct()
    val preparedStatement =
        connection.prepareStatement(
            """
SELECT id
FROM   category
WHERE  id IN (${distinctCategoryIds.joinToString(",") { "?" }});
"""
        )
    distinctCategoryIds.forEachIndexed { index, categoryId ->
        preparedStatement.setInt(index + 1, categoryId)
    }

    val result = preparedStatement.executeQuery()
    return result.toList { getInt("id") }
}

private fun java.sql.ResultSet.getLongOrNull(columnLabel: String): Long? {
    val value = getLong(columnLabel)
    return if (wasNull()) null else value
}

private fun loadParticipantsWithCalendarIds(
    connection: Connection,
    eventId: UUID,
): List<Pair<Participant, Option<String>>> {
    val ps = connection.prepareStatement(
        "SELECT email, name, calendar_event_id FROM participant WHERE event_id = ?"
    )
    ps.setObject(1, eventId)
    return ps.executeQuery().toList {
        Pair(
            Participant(email = getString("email"), name = getString("name")),
            Option.fromNullable(getString("calendar_event_id")),
        )
    }
}

private fun isHostOf(connection: Connection, eventId: UUID, email: String): Boolean {
    val preparedStatement =
        connection.prepareStatement(
            "SELECT 1 FROM participant WHERE event_id = ? AND email = ? AND type = 'HOST'"
        )
    preparedStatement.setObject(1, eventId)
    preparedStatement.setString(2, email)
    return preparedStatement.executeQuery().next()
}
