package no.nav.delta

data class Environment(
    val dbUsername: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_USERNAME", "delta"),
    val dbPassword: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_PASSWORD", "delta"),
    val dbJdbcUrl: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_JDBC_URL", "jdbc:postgresql://localhost:5432/delta"),
    val applicationPort: Int = getEnvVar("APPLICATION_PORT", "8080").toInt(),
    val azureAppClientId: String = getEnvVar("AZURE_APP_CLIENT_ID", "clientId"),
    val azureAppTenantId: String = getEnvVar("AZURE_APP_TENANT_ID", "tenantId"),
    val azureAppClientSecret: String = getEnvVar("AZURE_APP_CLIENT_SECRET", "clientSecret"),
    val azureTokenEndpoint: String = getEnvVar("AZURE_OPENID_CONFIG_TOKEN_ENDPOINT", "tokenEndpoint"),
    val jwkKeysUrl: String = getEnvVar("AZURE_OPENID_CONFIG_JWKS_URI", "http://localhost/"),
    val jwtIssuer: String = getEnvVar("AZURE_OPENID_CONFIG_ISSUER", "configIssuer"),
    val deltaEmailAddress: String = getEnvVar("DELTA_EMAIL_ADDRESS", "email"),
    val isDev: Boolean = getEnvVar("NAIS_CLUSTER_NAME", "localhost") == "dev-gcp",
) {
    companion object {
        fun getEnvVar(varName: String, defaultValue: String? = null) =
            System.getenv(varName)
                ?: defaultValue ?: throw RuntimeException("Missing required variable [$varName]")
    }
}


