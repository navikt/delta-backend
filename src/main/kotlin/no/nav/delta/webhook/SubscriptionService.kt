package no.nav.delta.webhook

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.plugins.DatabaseInterface
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.webhook.SubscriptionService")
private val OSLO: ZoneId = ZoneId.of("Europe/Oslo")

private const val SUBSCRIPTION_LIFETIME_HOURS = 71L // Outlook event subscriptions support up to 10,080 min (7 days)
private const val RENEWAL_THRESHOLD_HOURS = 24L

class SubscriptionService(
    private val cloudClient: CloudClient,
    private val database: DatabaseInterface,
    private val env: Environment,
    private val leaderElection: LeaderElection,
) {
    private val notificationUrl get() = "${env.webhookBaseUrl}/webhook/calendar"
    private val subscriptionResource get() = "users/${env.deltaEmailAddress}/events"

    @Volatile private var subscriptionHealthy = false
    @Volatile private var lastRenewalCheck: Instant = Instant.EPOCH

    /**
     * Returns false only if this pod is the leader and no healthy subscription exists.
     * Non-leader pods always report healthy — subscription management is not their concern.
     */
    fun isHealthy(): Boolean = !leaderElection.isLeader() || subscriptionHealthy

    fun initialize(scope: CoroutineScope) {
        leaderElection.startLeaderElectionChecks(scope)

        scope.launch {
            // Give leader election a moment to resolve before acting
            delay(15.seconds)
            if (leaderElection.isLeader()) {
                logger.info("This pod is the leader — managing Graph subscriptions")
                manageSubscriptions()
            }
            startRenewalLoop(scope)
        }
    }

    private fun startRenewalLoop(scope: CoroutineScope) {
        scope.launch {
            var wasLeader = leaderElection.isLeader()
            while (isActive) {
                delay(10.seconds)
                val isLeaderNow = leaderElection.isLeader()

                if (isLeaderNow && !wasLeader) {
                    // Just gained leadership — run full management immediately
                    logger.info("Leadership acquired mid-lifecycle — running subscription management")
                    manageSubscriptions()
                } else if (isLeaderNow) {
                    // Already the leader — check renewal every 12h
                    val hoursSinceLastCheck = java.time.Duration.between(lastRenewalCheck, java.time.Instant.now()).toHours()
                    if (hoursSinceLastCheck >= 12) {
                        lastRenewalCheck = java.time.Instant.now()
                        logger.info("Running scheduled subscription renewal check")
                        val subs = database.getSubscriptions()
                        if (subs.isEmpty()) {
                            logger.info("No subscriptions found during renewal check, creating new one")
                            createSubscription()
                        } else {
                            val sub = subs.maxByOrNull { it.expirationTime }!!
                            val hoursUntilExpiry = java.time.Duration.between(
                                LocalDateTime.now(OSLO), sub.expirationTime
                            ).toHours()
                            if (hoursUntilExpiry <= RENEWAL_THRESHOLD_HOURS) {
                                renewSubscription(sub)
                            }
                        }
                    }
                }

                wasLeader = isLeaderNow
            }
        }
    }

    private fun manageSubscriptions() {
        val existing = database.getSubscriptions()
        if (existing.isEmpty()) {
            logger.info("No existing Graph subscriptions found, creating new one")
            createSubscription()
        } else {
            // Enforce single-subscription invariant: keep the newest, delete any extras
            val sorted = existing.sortedByDescending { it.expirationTime }
            sorted.drop(1).forEach { stale ->
                logger.warn("Deleting surplus subscription ${stale.subscriptionId} to enforce single-subscription invariant")
                database.deleteSubscription(stale.subscriptionId)
            }
            val sub = sorted.first()
            val hoursUntilExpiry = java.time.Duration.between(
                LocalDateTime.now(OSLO), sub.expirationTime
            ).toHours()
            if (hoursUntilExpiry <= RENEWAL_THRESHOLD_HOURS) {
                logger.info("Subscription ${sub.subscriptionId} expires in ${hoursUntilExpiry}h, renewing")
                renewSubscription(sub)
            } else {
                logger.info("Subscription ${sub.subscriptionId} is valid for ${hoursUntilExpiry}h, no action needed")
                subscriptionHealthy = true
            }
        }
    }

    private fun createSubscription() {
        val expiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(SUBSCRIPTION_LIFETIME_HOURS)
        cloudClient.createSubscription(
            notificationUrl = notificationUrl,
            resource = subscriptionResource,
            clientState = env.webhookClientState,
            expirationDateTime = expiry,
        ).fold(
            ifLeft = { err ->
                logger.error("Failed to create Graph subscription: ${err.message}", err)
                subscriptionHealthy = false
            },
            ifRight = { subscription ->
                val subscriptionId = subscription.id ?: run {
                    logger.error("MS Graph returned a subscription without an id — cannot store it")
                    subscriptionHealthy = false
                    return
                }
                val expirationTime = subscription.expirationDateTime?.toLocalDateTimeOslo()
                    ?: expiry.toLocalDateTimeOslo()
                database.saveSubscription(
                    subscriptionId = subscriptionId,
                    resource = subscriptionResource,
                    expirationTime = expirationTime,
                    clientState = env.webhookClientState,
                )
                subscriptionHealthy = true
                logger.info("Created Graph subscription $subscriptionId, expires at $expirationTime")
            }
        )
    }

    private fun renewSubscription(sub: StoredSubscription) {
        val newExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(SUBSCRIPTION_LIFETIME_HOURS)
        cloudClient.renewSubscription(sub.subscriptionId, newExpiry).fold(
            ifLeft = { err ->
                logger.error("Failed to renew subscription ${sub.subscriptionId}: ${err.message}", err)
                // Renewal failed — delete stale record and try creating a fresh subscription
                database.deleteSubscription(sub.subscriptionId)
                createSubscription()
            },
            ifRight = {
                val newLocalExpiry = newExpiry.toLocalDateTimeOslo()
                database.updateSubscriptionExpiry(sub.subscriptionId, newLocalExpiry)
                subscriptionHealthy = true
                logger.info("Renewed subscription ${sub.subscriptionId}, new expiry: $newLocalExpiry")
            }
        )
    }
}

private fun OffsetDateTime.toLocalDateTimeOslo(): LocalDateTime =
    this.atZoneSameInstant(OSLO).toLocalDateTime()


