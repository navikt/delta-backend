package no.nav.delta.webhook

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphNotificationPayload(
    val value: List<GraphNotification>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphNotification(
    val clientState: String?,
    val resource: String,
    val changeType: String,
    val subscriptionId: String?,
)

data class StoredSubscription(
    val id: Long,
    val subscriptionId: String,
    val resource: String,
    val expirationTime: LocalDateTime,
    val clientState: String,
)
