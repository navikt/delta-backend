package no.nav.delta.calendar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.delta.email.CloudClient
import no.nav.delta.email.SharedGraphException
import com.microsoft.graph.models.ResponseType
import com.microsoft.kiota.ApiException
import no.nav.delta.event.Participant
import no.nav.delta.room.MasterEventResult
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class CalendarSyncWorker(
    private val repository: SharedCalendarRepository,
    private val cloudClient: CloudClient,
    val metrics: CalendarMetrics = CalendarMetrics(),
) {
    private val logger = LoggerFactory.getLogger("no.nav.delta.calendar.worker")
    private val nextReconciliation = AtomicLong()

    fun initialize(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val now = Instant.now()
                    if (now.epochSecond >= nextReconciliation.get()) {
                        repository.enqueuePeriodicReconciliation(now)
                        nextReconciliation.set(now.epochSecond + 60)
                    }
                    if (!runOnce()) delay(2.seconds)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: java.sql.SQLException) {
                    logger.error("Calendar worker database operation failed", error)
                    delay(5.seconds)
                }
            }
        }
    }

    fun runOnce(): Boolean {
        val work = repository.claimNext() ?: return false
        try {
            repository.withEventLock(work) { process(work) }
        } catch (error: SharedGraphException) {
            handleGraphFailure(work, error)
        } catch (error: ApiException) {
            val retryAfter = error.responseHeaders?.entries
                ?.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 }
            handleGraphFailure(work, SharedGraphException(error.responseStatusCode, "MailDeliveryFailed", retryAfter))
        } catch (error: UnsupportedOperationException) {
            repository.fail(work)
            metrics.failed()
            logger.error("Shared calendar adapter is unavailable", error)
        } catch (error: java.io.IOException) {
            retryOrFail(work)
            logger.warn("Calendar transport failed; retry scheduled")
        } catch (error: IllegalStateException) {
            repository.fail(work)
            metrics.failed()
            logger.error("Shared calendar reconciliation failed", error)
        }
        return true
    }

    private fun handleGraphFailure(work: SharedCalendarWork, error: SharedGraphException) {
        if (work.cancel && error.httpStatus == 404) {
            repository.complete(work)
            metrics.succeeded()
        } else if (error.httpStatus == 429 || error.httpStatus in 500..599 ||
            error.httpStatus == 408 || error.httpStatus == 412 || error.httpStatus == null
        ) {
            retryOrFail(work, error.retryAfterSeconds)
        } else {
            repository.fail(work)
            metrics.failed()
            logger.warn("Shared calendar operation failed with status {}", error.httpStatus)
        }
    }

    private fun retryOrFail(work: SharedCalendarWork, retryAfterSeconds: Long? = null) {
        if (work.attempts >= 10) {
            repository.fail(work)
            metrics.failed()
            logger.warn("Shared calendar retry budget exhausted")
        } else {
            val backoff = (30L shl (work.attempts - 1).coerceIn(0, 5)).coerceAtMost(900)
            repository.retry(work, Instant.now().plusSeconds(maxOf(backoff, retryAfterSeconds ?: 0)))
            metrics.retried()
        }
    }

    private fun process(work: SharedCalendarWork) {
        if (work.cancel) {
            var graphId = work.graphEventId
            if (graphId == null && work.desired.event != null) {
                // Recover an ambiguous POST with the same transaction ID before compensating.
                repository.recordEstimatedRecipients(work, work.desired.attendees.size + if (work.desired.event.roomEmail != null) 1 else 0)
                val created = cloudClient.createSharedEvent(
                    work.desired.event, work.desired.attendees.map { Participant(it.email, it.name) },
                    work.eventId.toString(),
                ).fold({ throw it }, { it })
                graphId = created.calendarEventId
                if (!repository.recordGraphId(work, graphId)) return
            }
            graphId?.let {
                val snapshot = cloudClient.getSharedEventForCancellation(it).fold({ throw it }, { it })
                repository.recordEstimatedRecipients(work, snapshot.attendees.size)
                if (!snapshot.isCancelled) cloudClient.cancelSharedEvent(it).fold({ throw it }, {})
            }
            repository.complete(work)
            metrics.succeeded()
            return
        }
        var desired = repository.loadDesired(work)
        val event = desired.event ?: return
        var graphId = work.graphEventId
        val createsCalendar = graphId == null
        val creation = if (createsCalendar) repository.creationState(work) else null
        if (graphId == null) {
            val creationEvent = creation?.event ?: event
            repository.recordEstimatedRecipients(work, (creation?.attendees ?: desired.attendees).size + if (creationEvent.roomEmail != null) 1 else 0)
            val created = cloudClient.createSharedEvent(
                creation?.event ?: event,
                (creation?.attendees ?: desired.attendees).map { Participant(it.email, it.name) },
                work.eventId.toString(),
            ).fold({ throw it }, { it })
            graphId = created.calendarEventId
            if (!repository.recordGraphId(work, graphId)) {
                logger.warn("Calendar creation completed after its claim changed")
                return
            }
        }
        val knownAttendees = repository.knownAttendeeEmails(work.eventId)
        val snapshot = cloudClient.getSharedEventForSync(graphId, knownAttendees).fold({ throw it }, { it })
        if (snapshot.isCancelled) {
            repository.fail(work)
            metrics.failed()
            return
        }
        repository.recordMeetingMetadata(work, MasterEventResult(
            calendarEventId = graphId,
            roomStatus = snapshot.roomStatus.takeIf {
                snapshot.attendees.any { attendee -> attendee.isResource && attendee.email.equals(event.roomEmail, true) }
            },
            teamsJoinUrl = snapshot.teamsJoinUrl,
            teamsConferenceId = snapshot.teamsConferenceId,
            teamsDialIn = snapshot.teamsDialIn,
        ))
        repository.reconcile(work.eventId, snapshot.attendees.filterNot { it.isResource }.map {
            SharedCalendarAttendeeSnapshot(
                email = it.email,
                name = it.name,
                response = when (it.response) {
                    ResponseType.Accepted -> SharedCalendarResponse.ACCEPTED
                    ResponseType.Declined -> SharedCalendarResponse.DECLINED
                    ResponseType.TentativelyAccepted -> SharedCalendarResponse.TENTATIVE
                    else -> SharedCalendarResponse.NONE
                },
                respondedAt = it.responseTime?.toInstant(),
                isIndividual = it.isIndividual,
            )
        }).fold({ throw IllegalStateException("Shared RSVP reconciliation failed: ${it::class.simpleName}") }, {})
        repository.refusals(work.eventId).forEach { refusal ->
            repository.recordEstimatedRecipients(work, 1)
            cloudClient.sendEmail(
                subject = "Delta registration was not accepted",
                body = if (refusal.reason == "FULL") {
                    "Your Outlook acceptance could not register you in Delta because the event is full."
                } else {
                    "Your Outlook acceptance could not register you in Delta because the signup deadline has passed."
                },
                toRecipients = listOf(refusal.email),
            )
            repository.acknowledgeRefusal(work.eventId, refusal)
        }
        if (event.isOnlineMeeting && snapshot.teamsJoinUrl == null &&
            (creation?.event?.isOnlineMeeting == true || !work.details)
        ) {
            if (createsCalendar) repository.acknowledgeDetails(work)
            retryOrFail(work)
            return
        }
        // Reconciliation and outgoing sync have independent durable revisions.
        if (!work.write) {
            repository.complete(work)
            metrics.reconciled()
            return
        }
        desired = repository.loadDesired(work)
        val target = desired.attendees.map { attendee ->
            val existing = snapshot.attendees.firstOrNull { it.email.equals(attendee.email, true) }
            no.nav.delta.email.SharedCalendarAttendee(
                attendee.email, attendee.name,
                response = existing?.response,
                responseTime = existing?.responseTime,
            )
        } + listOfNotNull(desired.event?.roomEmail?.let {
            no.nav.delta.email.SharedCalendarAttendee(it, desired.event?.roomName ?: it, isResource = true)
        })
        val before = snapshot.attendees.map { it.email.lowercase() to it.isResource }.toSet()
        val after = target.map { it.email.lowercase() to it.isResource }.toSet()
        if (before != after) {
            repository.recordEstimatedRecipients(work, (before - after).size + (after - before).size)
            cloudClient.updateSharedAttendees(
                graphId, target, snapshot.etag, snapshot.attendees.map { it.email }.toSet(),
            ).fold({ throw it }, {})
        }
        val creationDetailsChanged = creation?.event?.let { original ->
            original.title != event.title || original.description != event.description ||
                original.startTime != event.startTime || original.endTime != event.endTime ||
                original.location != event.location || original.roomEmail != event.roomEmail ||
                original.isOnlineMeeting != event.isOnlineMeeting
        } == true
        if (work.details && (!createsCalendar || creationDetailsChanged)) {
            repository.recordEstimatedRecipients(work, target.size)
            val details = cloudClient.updateSharedDetails(graphId, desired.event ?: event).fold({ throw it }, { it })
            repository.recordMeetingMetadata(work, details)
            repository.acknowledgeDetails(work)
            if ((desired.event ?: event).isOnlineMeeting && details.teamsJoinUrl == null) {
                retryOrFail(work)
                return
            }
        }
        repository.complete(work)
        metrics.succeeded()
    }
}
