package no.nav.delta.event

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

sealed class ExceptionWithDefaultResponse(private val statusCode: HttpStatusCode, override val message: String) :
    Exception(message) {
    fun defaultResponse(call: ApplicationCall) =
        suspend { call.respond(statusCode, message) }
}

sealed interface RegisterForEventError
object ParticipantAlreadyRegisteredException : ExceptionWithDefaultResponse(HttpStatusCode.OK, "Success"), RegisterForEventError
object EventIsFullException : ExceptionWithDefaultResponse(HttpStatusCode.NotFound, "Event not found"), RegisterForEventError

sealed interface UnregisterFromEventError
object InvalidOtpException : ExceptionWithDefaultResponse(HttpStatusCode.Unauthorized, "Invalid one time password"), UnregisterFromEventError

object EventNotFoundException :
    ExceptionWithDefaultResponse(HttpStatusCode.NotFound, "Event not found"), RegisterForEventError, UnregisterFromEventError
