package no.nav.delta.application

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.serialization.ContentConvertException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.util.AttributeKey
import java.util.UUID
import no.nav.delta.event.ExceptionWithDefaultResponse

/** RFC 7807 problem details. `detail` is safe, user-facing text and never contains internals. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemDetail(
    val title: String,
    val status: Int,
    val detail: String,
    val code: String? = null,
    val requestId: String? = null,
    val type: String = "about:blank",
)

val ProblemJson: ContentType = ContentType("application", "problem+json")

private val problemMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
private val ProblemSentKey = AttributeKey<Boolean>("delta.problemSent")
private val RequestIdKey = AttributeKey<String>("delta.requestId")

fun ApplicationCall.requestId(): String =
    attributes.computeIfAbsent(RequestIdKey) {
        request.header("X-Request-Id")?.takeIf { it.length <= 100 && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
            ?: UUID.randomUUID().toString()
    }

suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String = defaultProblemDetail(status),
    code: String? = null,
    title: String = status.description,
) {
    respondProblem(status, ProblemDetail(title, status.value, detail, code, requestId()))
}

/** Responds with any problem body (e.g. a ProblemDetail subtype-compatible shape) as application/problem+json. */
suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, body: Any) {
    attributes.put(ProblemSentKey, true)
    respondText(problemMapper.writeValueAsString(body), ProblemJson, status)
}

fun Application.installProblemDetails() {
    install(StatusPages) {
        exception<ExceptionWithDefaultResponse> { call, cause ->
            cause.defaultResponse(call)
        }
        exception<NotFoundException> { call, _ ->
            call.respondProblem(HttpStatusCode.NotFound, "Fant ikke ressursen.", "NOT_FOUND")
        }
        exception<ContentTransformationException> { call, _ ->
            call.respondProblem(HttpStatusCode.BadRequest, "Forespørselen har ugyldig innhold.", "INVALID_BODY")
        }
        exception<BadRequestException> { call, cause ->
            val invalidBody = generateSequence<Throwable>(cause) { it.cause }.any { it is ContentTransformationException || it is ContentConvertException }
            if (invalidBody) {
                call.respondProblem(HttpStatusCode.BadRequest, "Forespørselen har ugyldig innhold.", "INVALID_BODY")
            } else {
                call.respondProblem(HttpStatusCode.BadRequest, "Forespørselen er ugyldig.", "BAD_REQUEST")
            }
        }
        exception<Throwable> { call, cause ->
            val requestId = call.requestId()
            call.application.log.error("Unhandled error, requestId=$requestId", cause)
            call.respondProblem(
                HttpStatusCode.InternalServerError,
                "Noe gikk galt. Prøv igjen senere.",
                "INTERNAL_ERROR",
            )
        }
        status(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound,
            HttpStatusCode.MethodNotAllowed,
            HttpStatusCode.NotAcceptable,
            HttpStatusCode.Conflict,
            HttpStatusCode.UnsupportedMediaType,
            HttpStatusCode.InternalServerError,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
        ) { call, status ->
            // Fallback for empty-bodied error responses on API paths (everything but internal, webhook and docs); explicit problems are already sent.
            if (call.isApiCall() && !call.attributes.contains(ProblemSentKey)) {
                call.respondProblem(status)
            }
        }
    }
}

fun defaultProblemDetail(status: HttpStatusCode): String =
    when (status) {
        HttpStatusCode.BadRequest -> "Forespørselen er ugyldig."
        HttpStatusCode.Unauthorized -> "Du må logge inn for å få tilgang."
        HttpStatusCode.Forbidden -> "Du har ikke tilgang til denne ressursen."
        HttpStatusCode.NotFound -> "Fant ikke ressursen."
        HttpStatusCode.MethodNotAllowed -> "Metoden støttes ikke."
        HttpStatusCode.NotAcceptable -> "Kan ikke levere ønsket innholdstype."
        HttpStatusCode.Conflict -> "Forespørselen er i konflikt med dagens tilstand."
        HttpStatusCode.UnsupportedMediaType -> "Innholdstypen støttes ikke."
        HttpStatusCode.BadGateway -> "En ekstern tjeneste svarte ikke som forventet."
        HttpStatusCode.ServiceUnavailable -> "Tjenesten er midlertidig utilgjengelig."
        else -> "Noe gikk galt. Prøv igjen senere."
    }

private val NON_API_PREFIXES = listOf("/internal", "/webhook", "/openapi")

private fun ApplicationCall.isApiCall(): Boolean {
    val path = request.local.uri
    return NON_API_PREFIXES.none { path == it || path.startsWith("$it/") || path.startsWith("$it?") }
}
