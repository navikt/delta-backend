package no.nav.delta.event

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.DayOfWeek
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
    val firstDate = firstStartTime.toLocalDate()
    // For monthly recurrence: preserve the Nth weekday-of-month (e.g. "2nd Thursday").
    // If the start date is the last occurrence of its weekday in the month, always use
    // the last occurrence in subsequent months too.
    val dayOfWeek = firstDate.dayOfWeek
    val nthWeekday = (firstDate.dayOfMonth - 1) / 7 + 1
    val useLastWeekday = firstDate.dayOfMonth + 7 > firstDate.lengthOfMonth()
    var occurrenceDate = firstDate
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
        // Anchor weekly/biweekly to firstDate to prevent any drift.
        // Monthly uses Nth-weekday-of-month so the day of week is always preserved.
        occurrenceDate =
            when (recurrence.frequency) {
                RecurrenceFrequency.WEEKLY -> firstDate.plusWeeks(occurrenceIndex.toLong())
                RecurrenceFrequency.BIWEEKLY -> firstDate.plusWeeks(occurrenceIndex.toLong() * 2)
                RecurrenceFrequency.MONTHLY -> nthWeekdayOfMonth(
                    monthRef = firstDate.plusMonths(occurrenceIndex.toLong()),
                    dayOfWeek = dayOfWeek,
                    n = nthWeekday,
                    useLast = useLastWeekday,
                )
            }
    }

    return if (generatedOccurrences.isEmpty()) {
        RecurringEventValidationException("Recurring event must generate at least one occurrence").left()
    } else {
        generatedOccurrences.right()
    }
}

/**
 * Returns the Nth occurrence of [dayOfWeek] in the month of [monthRef].
 * If [useLast] is true, returns the last occurrence of [dayOfWeek] in the month.
 * If the Nth occurrence doesn't exist in the month (e.g. 5th Monday in a short month),
 * falls back to the last occurrence.
 */
private fun nthWeekdayOfMonth(
    monthRef: LocalDate,
    dayOfWeek: DayOfWeek,
    n: Int,
    useLast: Boolean,
): LocalDate {
    val firstOfMonth = monthRef.withDayOfMonth(1)
    val daysUntilWeekday = ((dayOfWeek.value - firstOfMonth.dayOfWeek.value) + 7) % 7
    val firstOccurrence = firstOfMonth.plusDays(daysUntilWeekday.toLong())

    if (useLast) {
        val lastOfMonth = monthRef.withDayOfMonth(monthRef.lengthOfMonth())
        val daysBack = ((lastOfMonth.dayOfWeek.value - dayOfWeek.value) + 7) % 7
        return lastOfMonth.minusDays(daysBack.toLong())
    }

    val candidate = firstOccurrence.plusWeeks((n - 1).toLong())
    // If Nth weekday doesn't exist in this month, fall back to the last occurrence
    return if (candidate.month != monthRef.month) {
        firstOccurrence.plusWeeks((n - 2).toLong())
    } else {
        candidate
    }
}
