package no.nav.delta.webhook

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger(LeaderElection::class.java)

class LeaderElection {
    private val electorUrl = System.getenv("ELECTOR_GET_URL") ?: ""
    private val hostname = try {
        InetAddress.getLocalHost().hostName
    } catch (e: Exception) {
        logger.warn("Failed to get hostname, defaulting to 'unknown'", e)
        "unknown"
    }

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 5_000 // 5s timeout — elector is in-cluster, should be fast
        }
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

    private suspend fun checkLeaderStatus() {
        try {
            val text = httpClient.get(electorUrl).bodyAsText()
            // Elector returns {"name": "pod-hostname"}
            val leaderName = text.substringAfter("\"name\":\"").substringBefore("\"").trim()
            if (leaderName.isEmpty()) {
                logger.warn("Elector returned unexpected response: $text")
                return // Keep last known status — don't flip on parse failure
            }
            val isLeader = hostname == leaderName
            val wasLeader = cachedLeaderStatus.getAndSet(isLeader)

            when {
                isLeader && !wasLeader -> logger.info("Leadership acquired: $hostname is now the leader")
                !isLeader && wasLeader -> logger.info("Leadership lost: $hostname is no longer the leader (leader: $leaderName)")
                isLeader -> logger.debug("$hostname is the leader")
                else -> logger.debug("$hostname is not the leader (leader: $leaderName)")
            }
        } catch (e: Exception) {
            // Keep last known status on transient errors — a single network blip should not
            // cause the current leader to step down and leave all pods without a leader.
            logger.warn("Failed to check leader election status (keeping last known=${cachedLeaderStatus.get()}): ${e.message}")
        }
    }

    fun isLeader(): Boolean = cachedLeaderStatus.get()
}
