package no.nav.delta.plugins

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.util.*


fun addDummyPrincipal(call: ApplicationCall) {
    val principal = JWTPrincipal(object : Payload {
        override fun getAudience(): MutableList<String> = mutableListOf("delta-backend-client")
        override fun getIssuer(): String = "https://login.microsoftonline.com/organizations/v2.0"
        override fun getSubject(): String = "123456789"
        override fun getExpiresAt(): Date = Date(System.currentTimeMillis() + 1000000)
        override fun getNotBefore(): Date = Date(System.currentTimeMillis() - 1000000)
        override fun getIssuedAt(): Date = Date(System.currentTimeMillis() - 1000000)
        override fun getId(): String = "123456789"
        override fun getClaim(name: String?): Claim? = claims[name]
        override fun getClaims(): MutableMap<String, Claim> = mutableMapOf(
            "preferred_username" to DummyClaim("dev@localhost"),
        )
    })
    call.authentication.principal("localhost", principal)
}

class DummyClaim(private val value: String) : Claim {
    override fun isNull(): Boolean = false
    override fun isMissing(): Boolean = false
    override fun asBoolean() = false
    override fun asInt() = 0
    override fun asLong() = 0L
    override fun asDouble() = 0.0
    override fun asString() = value
    override fun asDate() = Date()
    override fun asMap(): MutableMap<String, Claim> = mutableMapOf()
    override fun <T : Any?> `as`(p0: Class<T>?): T? = null
    override fun <T : Any?> asArray(clazz: Class<T>?): Array<T>? = null
    override fun <T : Any?> asList(clazz: Class<T>?): MutableList<T>? = null
}
