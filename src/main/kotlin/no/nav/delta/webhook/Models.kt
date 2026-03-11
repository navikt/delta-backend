package no.nav.delta.webhook

import java.time.LocalDateTime

data class GraphNotificationPayload(
    val value: List<GraphNotification>
)

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
