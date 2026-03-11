package no.nav.delta.webhook

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger(LeaderElection::class.java)
private val mapper = jacksonObjectMapper()

class LeaderElection {
    private val electorUrl = System.getenv("ELECTOR_GET_URL") ?: ""
    private val hostname = try {
        InetAddress.getLocalHost().hostName
    } catch (e: Exception) {
        logger.warn("Failed to get hostname, defaulting to 'unknown'", e)
        "unknown"
    }

    private val cachedLeaderStatus = AtomicBoolean(false)
    private val checksStarted = AtomicBoolean(false)

    fun startLeaderElectionChecks(scope: CoroutineScope) {
        if (electorUrl.isEmpty()) {
            logger.info("ELECTOR_GET_URL not set — assuming single instance, acting as leader")
            cachedLeaderStatus.set(true)
            return
        }

        if (checksStarted.getAndSet(true)) return

        scope.launch {
            logger.info("Starting Kubernetes leader election checks every 10s (hostname: $hostname)")
            while (isActive) {
                checkLeaderStatus()
                delay(10.seconds)
            }
        }
    }

    private fun checkLeaderStatus() {
        try {
            val text = URI(electorUrl).toURL().readText()
            val leaderName = mapper.readValue<Map<String, String>>(text)["name"] ?: return
            val isLeader = hostname == leaderName
            val wasLeader = cachedLeaderStatus.getAndSet(isLeader)

            when {
                isLeader && !wasLeader -> logger.info("Leadership acquired: $hostname is now the leader")
                !isLeader && wasLeader -> logger.info("Leadership lost: $hostname is no longer the leader (leader: $leaderName)")
                isLeader -> logger.debug("$hostname is the leader")
                else -> logger.debug("$hostname is not the leader (leader: $leaderName)")
            }
        } catch (e: Exception) {
            logger.warn("Failed to check leader election status: ${e.message}")
            cachedLeaderStatus.set(false)
        }
    }

    fun isLeader(): Boolean = cachedLeaderStatus.get()
}
