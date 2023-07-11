package no.nav.delta.event

import no.nav.delta.plugins.DatabaseInterface
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    title: String,
    description: String,
    startTime: Timestamp,
    endTime: Timestamp,
    location: String?,
): Event {
    lateinit var out: Event
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
"""
INSERT INTO event(owner, title, description, start_time, end_time, location)
    VALUES (?, ?, ?, ?, ?, ?)
RETURNING *;
""",
            )

        preparedStatement.setString(1, ownerEmail)
        preparedStatement.setString(2, title)
        preparedStatement.setString(3, description)
        preparedStatement.setTimestamp(4, startTime)
        preparedStatement.setTimestamp(5, endTime)
        preparedStatement.setString(6, location)

        preparedStatement.executeQuery().use {
            it.next()
            out = resultSetToEvent(it)
        }
        connection.commit()
    }

    return out
}

fun DatabaseInterface.getEvent(id: String): Event? {
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?)")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) return null
        return resultSetToEvent(result)
    }
}

fun DatabaseInterface.getFutureEvents(): List<Event> {
    val events = ArrayList<Event>()
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE end_time > now();")
        val result = preparedStatement.executeQuery()
        while (result.next()) {
            val event = resultSetToEvent(result)
            events.add(event)
        }
    }
    return events
}

fun DatabaseInterface.getEventsByOwner(ownerEmail: String): List<Event> {
    val events = ArrayList<Event>()
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE owner=?")
        preparedStatement.setString(1, ownerEmail)
        val result = preparedStatement.executeQuery()
        while (result.next()) {
            val event = resultSetToEvent(result)
            events.add(event)
        }
    }
    return events
}

fun DatabaseInterface.registerForEvent(eventId: String, email: String): UUID? {
    var otp: String? = null
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "INSERT INTO participant(event_id, email) VALUES (uuid(?), ?) RETURNING otp;",
            )
        preparedStatement.setString(1, eventId)
        preparedStatement.setString(2, email)

        lateinit var result: ResultSet
        try {
            result = preparedStatement.executeQuery()
        } catch (e: SQLException) {
            return null
        }

        if (result.next()) {
            otp = result.getString("otp")
        }
        connection.commit()
    }
    otp ?: return null
    return UUID.fromString(otp)
}

fun DatabaseInterface.unregisterFromEvent(eventId: String, otp: String): Boolean {
    var rowsAffected: Int
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "DELETE FROM participant WHERE event_id=uuid(?) AND otp=uuid(?);",
            )
        preparedStatement.setString(1, eventId)
        preparedStatement.setString(2, otp)

        rowsAffected = preparedStatement.executeUpdate()
        connection.commit()
    }
    return rowsAffected > 0
}

fun resultSetToEvent(resultSet: ResultSet): Event {
    return Event(
        id = UUID.fromString(resultSet.getString("id")),
        ownerEmail = resultSet.getString("owner"),
        title = resultSet.getString("title"),
        description = resultSet.getString("description"),
        startTime = resultSet.getTimestamp("start_time"),
        endTime = resultSet.getTimestamp("end_time"),
        location = resultSet.getString("location"),
    )
}
