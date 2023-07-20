package no.nav.delta.event

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.toList

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    createEvent: CreateEvent,
): Event {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                """
INSERT INTO event(owner, title, description, start_time, end_time, location, public)
    VALUES (?, ?, ?, ?, ?, ?, ?)
RETURNING *;
""",
            )

        preparedStatement.setString(1, ownerEmail)
        preparedStatement.setString(2, createEvent.title)
        preparedStatement.setString(3, createEvent.description)
        preparedStatement.setTimestamp(4, Timestamp.from(createEvent.startTime.toInstant()))
        preparedStatement.setTimestamp(5, Timestamp.from(createEvent.startTime.toInstant()))
        preparedStatement.setString(6, createEvent.location)
        preparedStatement.setBoolean(7, createEvent.public)

        val result = preparedStatement.executeQuery()
        connection.commit()
        result.next()
        result.toEvent()
    }
}

fun DatabaseInterface.getEvent(id: String): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?)")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun DatabaseInterface.getParticipants(
    id: String
): Either<EventNotFoundException, List<Participant>> {
    return connection.use { connection ->
        checkIfEventExists(connection, id).flatMap {
            val preparedStatement =
                connection.prepareStatement("SELECT email FROM participant WHERE event_id=uuid(?);")
            preparedStatement.setString(1, id)
            val result = preparedStatement.executeQuery()
            result.toList { Participant(email = getString(1)) }.right()
        }
    }
}

fun DatabaseInterface.getEvents(
    onlyFuture: Boolean = false,
    onlyPublic: Boolean = false
): List<Event> {
    return connection.use { connection ->
        val clauses = mutableListOf("TRUE")
        if (onlyFuture) clauses.add("end_time > NOW()")
        if (onlyPublic) clauses.add("public = TRUE")

        val preparedStatement =
            connection.prepareStatement(
                "SELECT * FROM event WHERE ${clauses.joinToString(" AND ")} ORDER BY start_time;")
        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.getEventsByOwner(ownerEmail: String): List<Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement("SELECT * FROM event WHERE owner=? ORDER BY start_time;")
        preparedStatement.setString(1, ownerEmail)
        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.registerForEvent(
    eventId: String,
    email: String
): Either<RegisterForEventError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId)
            .flatMap { checkIfParticipantIsRegistered(connection, eventId, email) }
            .flatMap {
                val preparedStatement =
                    connection.prepareStatement(
                        "INSERT INTO participant(event_id, email) VALUES (uuid(?), ?);",
                    )
                preparedStatement.setString(1, eventId)
                preparedStatement.setString(2, email)

                preparedStatement.executeUpdate()
                connection.commit()
                Unit.right()
            }
    }
}

fun DatabaseInterface.unregisterFromEvent(
    eventId: String,
    email: String
): Either<UnregisterFromEventError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId).flatMap {
            val preparedStatement =
                connection.prepareStatement(
                    "DELETE FROM participant WHERE event_id=uuid(?) AND email=?;",
                )
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val rowsAffected = preparedStatement.executeUpdate()
            connection.commit()
            if (rowsAffected == 0) EmailNotFoundException.left() else Unit.right()
        }
    }
}

fun DatabaseInterface.getJoinedEvents(email: String): List<Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "SELECT * FROM event JOIN participant p on event.id = p.event_id WHERE email=? ORDER BY start_time;")
        preparedStatement.setString(1, email)

        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.deleteEvent(id: String): Either<EventNotFoundException, Unit> {
    return connection.use { connection ->
        val preparedStatement = connection.prepareStatement("DELETE FROM event WHERE id=uuid(?);")
        preparedStatement.setString(1, id)

        val result = preparedStatement.executeUpdate()
        connection.commit()

        if (result == 0) EventNotFoundException.left() else Unit.right()
    }
}

fun DatabaseInterface.updateEvent(newEvent: Event): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                """
UPDATE event 
    SET title=?, description=?, start_time=?, end_time=?, location=?, public=?
    WHERE id=uuid(?) RETURNING *;
""")
        preparedStatement.setString(1, newEvent.title)
        preparedStatement.setString(2, newEvent.description)
        preparedStatement.setTimestamp(3, Timestamp.from(newEvent.startTime.toInstant()))
        preparedStatement.setTimestamp(4, Timestamp.from(newEvent.endTime.toInstant()))
        preparedStatement.setString(5, newEvent.location)
        preparedStatement.setBoolean(6, newEvent.public)
        preparedStatement.setString(7, newEvent.id.toString())

        val result = preparedStatement.executeQuery()
        connection.commit()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun ResultSet.toEvent(): Event {
    return Event(
        id = UUID.fromString(getString("id")),
        ownerEmail = getString("owner"),
        title = getString("title"),
        description = getString("description"),
        startTime = getTimestamp("start_time"),
        endTime = getTimestamp("end_time"),
        location = getString("location"),
        public = getBoolean("public"),
    )
}

fun checkIfEventExists(
    connection: Connection,
    eventId: String
): Either<EventNotFoundException, Unit> {
    return connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?);").use {
        preparedStatement ->
        preparedStatement.setString(1, eventId)

        val result = preparedStatement.executeQuery()
        if (!result.next()) EventNotFoundException.left() else Either.Right(Unit)
    }
}

fun checkIfParticipantIsRegistered(
    connection: Connection,
    eventId: String,
    email: String
): Either<ParticipantAlreadyRegisteredException, Unit> {
    return connection
        .prepareStatement("SELECT * FROM participant WHERE event_id=uuid(?) AND email=?;")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val result = preparedStatement.executeQuery()
            if (result.next()) ParticipantAlreadyRegisteredException.left() else Unit.right()
        }
}
