package no.nav.delta.email

import com.microsoft.graph.models.odataerrors.ODataError
import com.microsoft.kiota.ApiException
import java.io.IOException
import java.time.Duration
import no.nav.delta.room.RoomAvailabilityRequest

/** Only structured diagnostics are exposed; Graph messages can contain addresses or other data. */
internal class RoomAvailabilityException(
    request: RoomAvailabilityRequest,
    cause: Throwable? = null,
    code: String? = null,
) : RuntimeException(null, cause) {
    private val api = cause as? ApiException
    val upstreamStatus = api?.responseStatusCode?.takeIf { it > 0 }
    val errorCode = (code ?: (cause as? ODataError)?.error?.code)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) }
    val requestId = (
        (cause as? ODataError)?.error?.innerError?.requestId
            ?: api?.responseHeaders?.entries
                ?.firstOrNull { it.key.equals("request-id", ignoreCase = true) }
                ?.value?.firstOrNull()
        )?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }

    val clientDetail: String = "Failed to get room availability. " + when {
        errorCode == "MissingMailbox" -> "The availability service is not configured. Contact support."
        errorCode == "InvalidGraphResponse" -> "Microsoft Graph returned incomplete availability data. Please retry."
        upstreamStatus == 400 -> "Microsoft Graph rejected the availability request. Check the time range and slot interval."
        upstreamStatus == 401 || upstreamStatus == 403 -> "Microsoft Graph denied calendar access. Contact support."
        upstreamStatus == 404 -> "Microsoft Graph could not find the configured calendar mailbox. Contact support."
        upstreamStatus == 429 -> "Microsoft Graph is rate limiting availability requests. Please retry later."
        upstreamStatus != null && upstreamStatus >= 500 -> "Microsoft Graph is temporarily unavailable. Please retry later."
        cause is IOException -> "Could not connect to Microsoft Graph. Please retry."
        else -> "The availability service failed. Please retry or contact support."
    }

    val clientMessage: String =
        "$clientDetail (status=${upstreamStatus ?: "unknown"} code=${errorCode ?: "unknown"} requestId=${requestId ?: "unknown"})"

    override val message: String =
        "$clientMessage roomCount=${request.roomEmails.size} " +
            "durationSeconds=${Duration.between(request.startTime, request.endTime).seconds} " +
            "intervalMinutes=${request.availabilityViewInterval} " +
            "failureType=${cause?.javaClass?.simpleName ?: errorCode ?: "unknown"}"
}
