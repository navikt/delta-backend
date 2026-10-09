package no.nav.delta.calendar

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarMetricsTest {
    @Test
    fun `metrics expose bounded outcome labels and persistent mailbox counts`() {
        val metrics = CalendarMetrics()
        metrics.succeeded()
        metrics.retried()
        metrics.reconciled()
        val result = metrics.scrape(CalendarStatistics(pending = 2, recipientsToday = 7001))
        assertTrue(result.contains("delta_calendar_pending 2\n"))
        assertTrue(result.contains("delta_calendar_recipients_today 7001\n"))
        assertTrue(result.contains("outcome=\"synced\"} 1\n"))
        assertTrue(result.contains("delta_calendar_reconciliations_total 1\n"))
    }
}
