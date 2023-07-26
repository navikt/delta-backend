package no.nav.delta.event

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

sealed class ExceptionWithDefaultResponse(
    private val statusCode: HttpStatusCode,
    override val message: String
) : Exception(message) {
    suspend fun defaultResponse(call: ApplicationCall) {
        call.respond(statusCode, message)
    }
}

sealed interface RegisterForEventError

object ParticipantAlreadyRegisteredException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Already registered"),
    RegisterForEventError

object EventFullException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Event full"), RegisterForEventError

object DeadlinePassedException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Deadline passed"), RegisterForEventError

sealed interface ChangeParticipantError

object EventWillHaveNoHostsException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Event will have no hosts"),
    ChangeParticipantError

sealed interface UnregisterFromEventError

object EmailNotFoundException :
    ExceptionWithDefaultResponse(HttpStatusCode.NotFound, "Email not found"),
    UnregisterFromEventError

object EventNotFoundException :
    ExceptionWithDefaultResponse(HttpStatusCode.NotFound, "Event not found"),
    RegisterForEventError,
    UnregisterFromEventError,
    EventAccessException,
    ChangeParticipantError

sealed interface IdException : EventAccessException

object InvalidIdException :
    ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, "Invalid id"), IdException

object MissingIdException :
    ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, "Missing id"), IdException

sealed interface EventAccessException

object ForbiddenException :
    ExceptionWithDefaultResponse(HttpStatusCode.Forbidden, "No access"), EventAccessException
