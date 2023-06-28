package no.nav.delta

data class Environment (
    val dbUsername: String = getEnvVar("DB_USERNAME"),
    val dbPassword: String = getEnvVar("DB_PASSWORD"),
    val dbHost: String = getEnvVar("DB_HOST"),
    val dbPort: String = getEnvVar("DB_PORT"),
    val dbName: String = getEnvVar("DB_DATABASE"),
    val applicationPort: Int = getEnvVar("APPLICATION_PORT", "8080").toInt(),
){
    fun jdbcUrl() = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
}

fun getEnvVar(varName: String, defaultValue: String? = null) =
    System.getenv(varName)
        ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")