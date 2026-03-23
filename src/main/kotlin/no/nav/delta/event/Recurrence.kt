package no.nav.delta.event

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

private const val MAX_RECURRING_OCCURRENCES = 130

data class GeneratedOccurrence(
    val occurrenceIndex: Int,
    val occurrenceDate: LocalDate,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val signupDeadline: LocalDateTime?,
)

data class RecurringSeriesDraft(
    val title: String,
    val description: String,
    val location: String,
    val public: Boolean,
    val participantLimit: Int,
    val categories: List<Int>,
    val frequency: RecurrenceFrequency,
    val startDate: LocalDate,
    val untilDate: LocalDate,
    val startTime: LocalDateTime,
    val occurrenceDurationMinutes: Long,
    val signupDeadlineOffsetMinutes: Long?,
    val createdByEmail: String,
    val occurrences: List<GeneratedOccurrence>,
)

fun CreateEvent.toRecurringSeriesDraft(
    createdByEmail: String,
): Either<RecurringEventValidationException, RecurringSeriesDraft> {
    val recurrence = recurrence
        ?: return RecurringEventValidationException("Recurring events require a recurrence definition").left()

    val occurrenceDurationMinutes =
        Duration.between(startTime, endTime).toMinutes().takeIf { it > 0 }
            ?: return RecurringEventValidationException("Start time must be before end time").left()

    if (recurrence.untilDate.isBefore(startTime.toLocalDate())) {
        return RecurringEventValidationException(
            "Recurrence end date must be on or after the first occurrence"
        ).left()
    }

    val signupDeadlineOffsetMinutes = signupDeadline?.let { Duration.between(startTime, it).toMinutes() }
    val occurrences =
        when (
            val generatedOccurrences =
                generateOccurrences(
                    firstStartTime = startTime,
                    occurrenceDurationMinutes = occurrenceDurationMinutes,
                    recurrence = recurrence,
                    signupDeadlineOffsetMinutes = signupDeadlineOffsetMinutes,
                )
        ) {
            is Either.Left -> return generatedOccurrences
            is Either.Right -> generatedOccurrences.value
        }

    return RecurringSeriesDraft(
        title = title,
        description = description,
        location = location,
        public = public,
        participantLimit = participantLimit,
        categories = categories.orEmpty(),
        frequency = recurrence.frequency,
        startDate = startTime.toLocalDate(),
        untilDate = recurrence.untilDate,
        startTime = startTime,
        occurrenceDurationMinutes = occurrenceDurationMinutes,
        signupDeadlineOffsetMinutes = signupDeadlineOffsetMinutes,
        createdByEmail = createdByEmail,
        occurrences = occurrences,
    ).right()
}

fun generateOccurrences(
    firstStartTime: LocalDateTime,
    occurrenceDurationMinutes: Long,
    recurrence: RecurrenceRequest,
    signupDeadlineOffsetMinutes: Long?,
): Either<RecurringEventValidationException, List<GeneratedOccurrence>> {
    val generatedOccurrences = mutableListOf<GeneratedOccurrence>()
    var occurrenceDate = firstStartTime.toLocalDate()
    var occurrenceIndex = 0

    while (!occurrenceDate.isAfter(recurrence.untilDate)) {
        val startTime = LocalDateTime.of(occurrenceDate, firstStartTime.toLocalTime())
        generatedOccurrences +=
            GeneratedOccurrence(
                occurrenceIndex = occurrenceIndex,
                occurrenceDate = occurrenceDate,
                startTime = startTime,
                endTime = startTime.plusMinutes(occurrenceDurationMinutes),
                signupDeadline = signupDeadlineOffsetMinutes?.let { startTime.plusMinutes(it) },
            )

        if (generatedOccurrences.size > MAX_RECURRING_OCCURRENCES) {
            return RecurringEventValidationException(
                "Recurring events are limited to $MAX_RECURRING_OCCURRENCES occurrences"
            ).left()
        }

        occurrenceIndex += 1
        occurrenceDate =
            when (recurrence.frequency) {
                RecurrenceFrequency.WEEKLY -> occurrenceDate.plusWeeks(1)
                RecurrenceFrequency.BIWEEKLY -> occurrenceDate.plusWeeks(2)
                RecurrenceFrequency.MONTHLY -> occurrenceDate.plusMonths(1)
            }
    }

    return if (generatedOccurrences.isEmpty()) {
        RecurringEventValidationException("Recurring event must generate at least one occurrence").left()
    } else {
        generatedOccurrences.right()
    }
}
