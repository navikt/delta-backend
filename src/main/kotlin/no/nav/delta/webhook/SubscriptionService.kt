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

    // Only meaningful on the leader pod; true once a healthy subscription is confirmed.
    @Volatile private var subscriptionHealthy = false

    // True if this pod won the advisory lock and is responsible for subscription management.
    @Volatile private var isLeader = false

    /**
     * Returns false only if this pod is the leader and no healthy subscription exists.
     * Non-leader pods always report healthy — subscription management is not their concern.
     */
    fun isHealthy(): Boolean = !isLeader || subscriptionHealthy

    fun initialize() {
        // Acquire a dedicated long-lived connection for the advisory lock.
        // The lock is session-scoped: it is held as long as this connection stays open,
        // and released automatically if the pod dies (connection drops).
        val lockConn = database.connection
        isLeader = tryAdvisoryLock(lockConn)

        if (isLeader) {
            logger.info("Acquired subscription leader lock — this pod manages Graph subscriptions")
            manageSubscriptions()
            startRenewalThread(lockConn)
        } else {
            logger.info("Another pod holds the subscription leader lock — starting lock-acquisition thread")
            startLockAcquisitionThread()
        }
    }

    private fun manageSubscriptions() {
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
                    subscriptionHealthy = true
                }
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
                val subscriptionId = subscription.id ?: return
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

    private fun startLockAcquisitionThread() {
        val thread = Thread {
            while (true) {
                try {
                    Thread.sleep(RENEWAL_CHECK_INTERVAL_MS)
                    val lockConn = database.connection
                    if (tryAdvisoryLock(lockConn)) {
                        isLeader = true
                        logger.info("Acquired subscription leader lock — taking over subscription management")
                        manageSubscriptions()
                        startRenewalThread(lockConn)
                        break
                    } else {
                        lockConn.close()
                        logger.debug("Leader lock still held by another pod, will retry in ${RENEWAL_CHECK_INTERVAL_MS / 3600000}h")
                    }
                } catch (e: InterruptedException) {
                    logger.info("Lock acquisition thread interrupted, stopping")
                    break
                } catch (e: Exception) {
                    logger.error("Error in lock acquisition thread: ${e.message}", e)
                }
            }
        }
        thread.isDaemon = true
        thread.name = "graph-subscription-lock-acquisition"
        thread.start()
    }

    private fun startRenewalThread(lockConn: java.sql.Connection) {
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
                    logger.info("Subscription renewal thread interrupted, releasing leader lock")
                    lockConn.close()
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

