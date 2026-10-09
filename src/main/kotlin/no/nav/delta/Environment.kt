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
    val isLocal: Boolean = getEnvVar("NAIS_CLUSTER_NAME", "localhost") == "localhost",
    /** Entra ID group for Delta maintainers: faggruppe admins, and early access to toggled features. */
    val maintainersGroupId: String = getEnvVar("DELTA_MAINTAINERS_GROUP_ID", ""),
    val webhookBaseUrl: String = getEnvVar("WEBHOOK_BASE_URL", "http://localhost:8080"),
    val webhookClientState: String = getEnvVar(
        "WEBHOOK_CLIENT_STATE",
        if (getEnvVar("NAIS_CLUSTER_NAME", "localhost") == "localhost") "local-dev-secret" else null,
    ),
    val featureRoomBooking: FeatureAccess = FeatureAccess.parse(getEnvVar("FEATURE_ROOM_BOOKING", "off")),
    val featureTeamsMeeting: FeatureAccess = FeatureAccess.parse(getEnvVar("FEATURE_TEAMS_MEETING", "off")),
    val featureSharedCalendar: FeatureAccess = FeatureAccess.parse(getEnvVar("FEATURE_SHARED_CALENDAR", "off")),
) {
    fun isRoomBookingEnabledFor(groups: Collection<String>): Boolean = featureRoomBooking.allows(groups, maintainersGroupId)

    fun isTeamsMeetingEnabledFor(groups: Collection<String>): Boolean = featureTeamsMeeting.allows(groups, maintainersGroupId)

    fun isSharedCalendarEnabledFor(groups: Collection<String>): Boolean = featureSharedCalendar.allows(groups, maintainersGroupId)

    companion object {
        fun getEnvVar(varName: String, defaultValue: String? = null) =
            System.getenv(varName)
                ?: defaultValue ?: throw RuntimeException("Missing required variable [$varName]")

    }
}

/**
 * Rollout state of a feature toggle: [OFF] for nobody, [MAINTAINERS] for members of
 * [Environment.maintainersGroupId] only (prod testing), [ALL] for everyone.
 */
enum class FeatureAccess {
    OFF,
    MAINTAINERS,
    ALL;

    fun allows(groups: Collection<String>, maintainersGroupId: String): Boolean =
        when (this) {
            OFF -> false
            MAINTAINERS -> maintainersGroupId.isNotBlank() && groups.contains(maintainersGroupId)
            ALL -> true
        }

    companion object {
        /** Accepts off/maintainers/all (case-insensitive); "false"/"true" are aliases for off/all. */
        fun parse(raw: String): FeatureAccess =
            when (raw.trim().lowercase()) {
                "", "off", "false" -> OFF
                "maintainers" -> MAINTAINERS
                "all", "true" -> ALL
                else -> throw IllegalArgumentException("Invalid feature toggle value '$raw' (expected off, maintainers or all)")
            }
    }
}
