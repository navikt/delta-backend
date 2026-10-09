package no.nav.delta.calendar

import java.util.concurrent.atomic.AtomicLong

data class CalendarStatistics(
    val pending: Long = 0,
    val failed: Long = 0,
    val oldestPendingSeconds: Double = 0.0,
    val oldestFailedSeconds: Double = 0.0,
    val recipientsToday: Long = 0,
)

class CalendarMetrics {
    private val succeeded = AtomicLong()
    private val retried = AtomicLong()
    private val failed = AtomicLong()
    private val reconciled = AtomicLong()

    fun succeeded() { succeeded.incrementAndGet() }
    fun retried() { retried.incrementAndGet() }
    fun failed() { failed.incrementAndGet() }
    fun reconciled() { reconciled.incrementAndGet() }

    fun scrape(statistics: CalendarStatistics): String = buildString {
        appendLine("# TYPE delta_calendar_pending gauge")
        appendLine("delta_calendar_pending ${statistics.pending}")
        appendLine("# TYPE delta_calendar_failed gauge")
        appendLine("delta_calendar_failed ${statistics.failed}")
        appendLine("# TYPE delta_calendar_oldest_pending_seconds gauge")
        appendLine("delta_calendar_oldest_pending_seconds ${statistics.oldestPendingSeconds}")
        appendLine("# TYPE delta_calendar_oldest_failed_seconds gauge")
        appendLine("delta_calendar_oldest_failed_seconds ${statistics.oldestFailedSeconds}")
        appendLine("# TYPE delta_calendar_recipients_today gauge")
        appendLine("delta_calendar_recipients_today ${statistics.recipientsToday}")
        appendLine("# TYPE delta_calendar_sync_attempts_total counter")
        appendLine("delta_calendar_sync_attempts_total{outcome=\"synced\"} ${succeeded.get()}")
        appendLine("delta_calendar_sync_attempts_total{outcome=\"retry\"} ${retried.get()}")
        appendLine("delta_calendar_sync_attempts_total{outcome=\"failed\"} ${failed.get()}")
        appendLine("# TYPE delta_calendar_reconciliations_total counter")
        appendLine("delta_calendar_reconciliations_total ${reconciled.get()}")
    }
}
