package no.nav.delta.webhook

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.sql.Connection
import java.sql.Timestamp
import java.time.LocalDateTime
import no.nav.delta.plugins.DatabaseInterface

// Fixed advisory lock key for subscription leader election across pods
private const val SUBSCRIPTION_ADVISORY_LOCK_KEY = 8675309L

/**
 * Attempts to acquire a PostgreSQL session-level advisory lock.
 * Returns true if the lock was acquired (this pod becomes the leader), false if another pod holds it.
 * The lock is held for the lifetime of [conn] — caller is responsible for keeping the connection open.
 */
fun tryAdvisoryLock(conn: Connection): Boolean {
    conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { stmt ->
        stmt.setLong(1, SUBSCRIPTION_ADVISORY_LOCK_KEY)
        stmt.executeQuery().use { rs ->
            rs.next()
            return rs.getBoolean(1)
        }
    }
}

fun DatabaseInterface.saveSubscription(
    subscriptionId: String,
    resource: String,
    expirationTime: LocalDateTime,
    clientState: String,
) {
    connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO graph_subscription (subscription_id, resource, expiration_time, client_state)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (resource) DO UPDATE
                SET subscription_id = EXCLUDED.subscription_id,
                    client_state    = EXCLUDED.client_state,
                    expiration_time = EXCLUDED.expiration_time
            """
        ).use { stmt ->
            stmt.setString(1, subscriptionId)
            stmt.setString(2, resource)
            stmt.setTimestamp(3, Timestamp.valueOf(expirationTime))
            stmt.setString(4, clientState)
            stmt.executeUpdate()
        }
        conn.commit()
    }
}

fun DatabaseInterface.getSubscriptions(): List<StoredSubscription> {
    return connection.use { conn ->
        conn.prepareStatement(
            "SELECT id, subscription_id, resource, expiration_time, client_state FROM graph_subscription"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<StoredSubscription>()
                while (rs.next()) {
                    results.add(
                        StoredSubscription(
                            id = rs.getLong("id"),
                            subscriptionId = rs.getString("subscription_id"),
                            resource = rs.getString("resource"),
                            expirationTime = rs.getTimestamp("expiration_time").toLocalDateTime(),
                            clientState = rs.getString("client_state"),
                        )
                    )
                }
                results
            }
        }
    }
}

fun DatabaseInterface.updateSubscriptionExpiry(
    subscriptionId: String,
    newExpiry: LocalDateTime,
): Either<Throwable, Unit> {
    return try {
        connection.use { conn ->
            val updated = conn.prepareStatement(
                "UPDATE graph_subscription SET expiration_time = ? WHERE subscription_id = ?"
            ).use { stmt ->
                stmt.setTimestamp(1, Timestamp.valueOf(newExpiry))
                stmt.setString(2, subscriptionId)
                stmt.executeUpdate()
            }
            conn.commit()
            if (updated == 0) {
                return RuntimeException("No subscription row found for id=$subscriptionId — row may have been deleted").left()
            }
        }
        Unit.right()
    } catch (e: Exception) {
        e.left()
    }
}

fun DatabaseInterface.deleteSubscription(subscriptionId: String) {
    connection.use { conn ->
        conn.prepareStatement(
            "DELETE FROM graph_subscription WHERE subscription_id = ?"
        ).use { stmt ->
            stmt.setString(1, subscriptionId)
            stmt.executeUpdate()
        }
        conn.commit()
    }
}

fun DatabaseInterface.getParticipantByCalendarEventId(calendarEventId: String): ParticipantRef? {
    return connection.use { conn ->
        conn.prepareStatement(
            "SELECT email, event_id FROM participant WHERE calendar_event_id = ?"
        ).use { stmt ->
            stmt.setString(1, calendarEventId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    ParticipantRef(
                        email = rs.getString("email"),
                        eventId = rs.getString("event_id"),
                    )
                } else {
                    null
                }
            }
        }
    }
}

data class ParticipantRef(val email: String, val eventId: String)
