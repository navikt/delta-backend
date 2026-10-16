package no.nav.delta.event

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import no.nav.delta.application.respondProblem

sealed class ExceptionWithDefaultResponse(
    private val statusCode: HttpStatusCode,
    override val message: String,
    private val code: String? = null,
    private val detail: String = message,
) : Exception(message) {
    suspend fun defaultResponse(call: ApplicationCall) {
        call.respondProblem(statusCode, detail, code)
    }
}

sealed interface RegisterCalendarEventIdError

sealed interface RegisterForEventError

object ParticipantAlreadyRegisteredException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Already registered", "ALREADY_REGISTERED", "Du er allerede påmeldt."),
    RegisterForEventError

object EventFullException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Event full", "EVENT_FULL", "Arrangementet er fullt."), RegisterForEventError

object DeadlinePassedException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Deadline passed", "DEADLINE_PASSED", "Påmeldingsfristen har gått ut."), RegisterForEventError

sealed interface ChangeParticipantError

object EventWillHaveNoHostsException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Event will have no hosts", "NO_HOSTS", "Arrangementet må ha minst én vert."),
    ChangeParticipantError

sealed interface UnregisterFromEventError

object EmailNotFoundException :
    ExceptionWithDefaultResponse(HttpStatusCode.NotFound, "Email not found", "EMAIL_NOT_FOUND", "Fant ikke e-postadressen."),
    UnregisterFromEventError,
    RegisterCalendarEventIdError

object EventNotFoundException :
    ExceptionWithDefaultResponse(HttpStatusCode.NotFound, "Event not found", "EVENT_NOT_FOUND", "Fant ikke arrangementet."),
    RegisterForEventError,
    UnregisterFromEventError,
    EventAccessException,
    ChangeParticipantError,
    RegisterCalendarEventIdError

sealed interface IdException : EventAccessException

object InvalidIdException :
    ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, "Invalid id", "INVALID_ID", "Ugyldig id."), IdException

object MissingIdException :
    ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, "Missing id", "MISSING_ID", "Id mangler."), IdException

sealed interface EventAccessException

object ForbiddenException :
    ExceptionWithDefaultResponse(HttpStatusCode.Forbidden, "No access", "FORBIDDEN", "Du har ikke tilgang."), EventAccessException

sealed interface CreateCategoryError

object CategoryAlreadyExistsException :
    ExceptionWithDefaultResponse(HttpStatusCode.Conflict, "Category already exists", "CATEGORY_EXISTS", "Kategorien finnes allerede."),
    CreateCategoryError

object CategoryNameTooLongException :
    ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, "Category name too long", "CATEGORY_NAME_TOO_LONG", "Kategorinavnet er for langt."),
    CreateCategoryError

class RecurringEventValidationException(
    message: String,
) : ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, message)

class UnsupportedRecurringOperationException(
    message: String,
) : ExceptionWithDefaultResponse(HttpStatusCode.BadRequest, message)
