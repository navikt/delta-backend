package no.nav.delta

data class Environment(
    val dbUsername: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_USERNAME", "delta"),
    val dbPassword: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_PASSWORD", "delta"),
    val dbHost: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_HOST", "localhost"),
    val dbPort: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_PORT", "5432"),
    val dbName: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_DATABASE", "delta"),
    val applicationPort: Int = getEnvVar("APPLICATION_PORT", "8080").toInt(),
    val azureAppClientId: String = getEnvVar("AZURE_APP_CLIENT_ID", ""),
    val azureAppTenantId: String = getEnvVar("AZURE_APP_TENANT_ID", ""),
    val azureAppClientSecret: String = getEnvVar("AZURE_APP_CLIENT_SECRET", ""),
    val azureTokenEndpoint: String = getEnvVar("AZURE_OPENID_CONFIG_TOKEN_ENDPOINT", ""),
    val jwkKeysUrl: String = getEnvVar("AZURE_OPENID_CONFIG_JWKS_URI", "http://localhost/"),
    val jwtIssuer: String = getEnvVar("AZURE_OPENID_CONFIG_ISSUER", ""),
    val development: Boolean = getEnvVar("DEV_MODE", "true") == "true",
    val deltaEmailAddress: String = getEnvVar("DELTA_EMAIL_ADDRESS", "")
) {
    fun jdbcUrl() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
}

fun getEnvVar(varName: String, defaultValue: String? = null) =
    System.getenv(varName)
        ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")
