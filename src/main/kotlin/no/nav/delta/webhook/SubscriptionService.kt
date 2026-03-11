package no.nav.delta.webhook

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.plugins.DatabaseInterface
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.webhook.SubscriptionService")
private val OSLO: ZoneId = ZoneId.of("Europe/Oslo")

private const val SUBSCRIPTION_LIFETIME_HOURS = 71L // Outlook event subscriptions support up to 10,080 min (7 days)
private const val RENEWAL_THRESHOLD_HOURS = 24L
private const val RENEWAL_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 hours

class SubscriptionService(
    private val cloudClient: CloudClient,
    private val database: DatabaseInterface,
    private val env: Environment,
) {
    private val notificationUrl get() = "${env.webhookBaseUrl}/webhook/calendar"
    private val subscriptionResource get() = "users/${env.deltaEmailAddress}/events"

    fun initialize() {
        val existing = database.getSubscriptions()
        if (existing.isEmpty()) {
            logger.info("No existing Graph subscriptions found, creating new one")
            createSubscription()
        } else {
            existing.forEach { sub ->
                val hoursUntilExpiry = java.time.Duration.between(
                    LocalDateTime.now(OSLO), sub.expirationTime
                ).toHours()
                if (hoursUntilExpiry <= RENEWAL_THRESHOLD_HOURS) {
                    logger.info("Subscription ${sub.subscriptionId} expires in ${hoursUntilExpiry}h, renewing")
                    renewSubscription(sub)
                } else {
                    logger.info("Subscription ${sub.subscriptionId} is valid for ${hoursUntilExpiry}h, no action needed")
                }
            }
        }
        startRenewalThread()
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
            },
            ifRight = { subscription ->
                val subscriptionId = subscription.id ?: return
                val expirationTime = subscription.expirationDateTime?.toLocalDateTimeOslo()
                    ?: expiry.toLocalDateTimeOslo()
                database.saveSubscription(
                    subscriptionId = subscriptionId,
                    resource = subscriptionResource,
                    expirationTime = expirationTime,
                    clientState = env.webhookClientState,
                )
                logger.info("Created Graph subscription $subscriptionId, expires at $expirationTime")
            }
        )
    }

    private fun renewSubscription(sub: StoredSubscription) {
        val newExpiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(SUBSCRIPTION_LIFETIME_HOURS)
        cloudClient.renewSubscription(sub.subscriptionId, newExpiry).fold(
            ifLeft = { err ->
                logger.error("Failed to renew subscription ${sub.subscriptionId}: ${err.message}", err)
                // If renewal fails, try creating a fresh subscription
                database.deleteSubscription(sub.subscriptionId)
                createSubscription()
            },
            ifRight = {
                val newLocalExpiry = newExpiry.toLocalDateTimeOslo()
                database.updateSubscriptionExpiry(sub.subscriptionId, newLocalExpiry)
                logger.info("Renewed subscription ${sub.subscriptionId}, new expiry: $newLocalExpiry")
            }
        )
    }

    private fun startRenewalThread() {
        val thread = Thread {
            while (true) {
                try {
                    Thread.sleep(RENEWAL_CHECK_INTERVAL_MS)
                    logger.info("Running scheduled subscription renewal check")
                    val subs = database.getSubscriptions()
                    if (subs.isEmpty()) {
                        logger.info("No subscriptions found during renewal check, creating new one")
                        createSubscription()
                    } else {
                        subs.forEach { sub ->
                            val hoursUntilExpiry = java.time.Duration.between(
                                LocalDateTime.now(OSLO), sub.expirationTime
                            ).toHours()
                            if (hoursUntilExpiry <= RENEWAL_THRESHOLD_HOURS) {
                                renewSubscription(sub)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    logger.info("Subscription renewal thread interrupted, stopping")
                    break
                } catch (e: Exception) {
                    logger.error("Error in subscription renewal thread: ${e.message}", e)
                }
            }
        }
        thread.isDaemon = true
        thread.name = "graph-subscription-renewal"
        thread.start()
        logger.info("Started Graph subscription renewal daemon thread")
    }
}

private fun OffsetDateTime.toLocalDateTimeOslo(): LocalDateTime =
    this.atZoneSameInstant(OSLO).toLocalDateTime()
