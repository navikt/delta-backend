package no.nav.delta

data class Environment (
    val dbUsername: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_USERNAME", "delta_backend"),
    val dbPassword: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_PASSWORD", "ioDahceix5phiYoo6bee"),
    val dbHost: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_HOST", "localhost"),
    val dbPort: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_PORT", "5432"),
    val dbName: String = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_DATABASE", "delta_backend"),
    val applicationPort: Int = getEnvVar("APPLICATION_PORT", "8080").toInt(),
){
    fun jdbcUrl() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
}

fun getEnvVar(varName: String, defaultValue: String? = null) =
    System.getenv(varName)
        ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")