package no.nav.delta.event

import arrow.core.Either
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.left
import arrow.core.none
import arrow.core.right
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.toList
import no.nav.delta.plugins.toSet

fun DatabaseInterface.addEvent(
    createEvent: CreateEvent,
): Event {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                """
 INSERT INTO event
            (
                        title,
                        description,
                        start_time,
                        end_time,
                        location,
                        public,
                        participant_limit,
                        signup_deadline
            )
            VALUES
            (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?
            )
            returning *;
""")

        preparedStatement.setString(1, createEvent.title)
        preparedStatement.setString(2, createEvent.description)
        preparedStatement.setTimestamp(3, Timestamp.valueOf(createEvent.startTime))
        preparedStatement.setTimestamp(4, Timestamp.valueOf(createEvent.endTime))
        preparedStatement.setString(5, createEvent.location)
        preparedStatement.setBoolean(6, createEvent.public)
        preparedStatement.setInt(7, createEvent.participantLimit)
        preparedStatement.setTimestamp(8, createEvent.signupDeadline?.let { Timestamp.valueOf(it) })

        val result = preparedStatement.executeQuery()
        connection.commit()
        result.next()
        result.toEvent()
    }
}

fun DatabaseInterface.getEvent(id: String): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement("""
SELECT *
FROM   event
WHERE  id = Uuid(?);
""")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun DatabaseInterface.getFullEvent(id: String): Either<EventNotFoundException, FullEvent> {
    // TODO: Do it in one query
    return getEvent(id).flatMap { event ->
        getParticipants(id).flatMap { participants ->
            getHosts(id).flatMap { hosts ->
                getCategories(id).map { categories ->
                    FullEvent(event, participants, hosts, categories)
                }
            }
        }
    }
}


fun DatabaseInterface.getParticipants(
    id: String
): Either<EventNotFoundException, List<Participant>> {
    return connection.use { connection ->
        checkIfEventExists(connection, id).map {
            val preparedStatement =
                connection.prepareStatement(
                    """
SELECT email,
       name
FROM   participant
WHERE  event_id = Uuid(?)
       AND type = 'PARTICIPANT';
""")
            preparedStatement.setString(1, id)
            val result = preparedStatement.executeQuery()
            result.toList { Participant(email = getString(1), name = getString(2)) }
        }
    }
}

fun DatabaseInterface.getHosts(id: String): Either<EventNotFoundException, List<Participant>> {
    return connection.use { connection ->
        checkIfEventExists(connection, id).map {
            val preparedStatement =
                connection.prepareStatement(
                    """
SELECT email,
       name
FROM   participant
WHERE  event_id = Uuid(?)
       AND type = 'HOST';
""")
            preparedStatement.setString(1, id)
            val result = preparedStatement.executeQuery()
            result.toList { Participant(email = getString(1), name = getString(2)) }
        }
    }
}

fun DatabaseInterface.getEvents(
    categories: List<Int> = listOf(),
    onlyFuture: Boolean = false,
    onlyPast: Boolean = false,
    onlyPublic: Boolean = false,
    byHost: Option<String> = none(),
    joinedBy: Option<String> = none(),
): List<Event> {
    return connection.use { connection ->
        val clauses = mutableListOf("TRUE")
        val values = mutableListOf<PreparedStatement.(Int) -> Unit>()

        if (onlyFuture) clauses.add("start_time > NOW()")
        if (onlyPast) clauses.add("end_time < NOW()")
        if (onlyPublic) clauses.add("public = TRUE")

        categories.forEach { categoryId ->
            clauses.add("id IN (SELECT event_id FROM event_has_category WHERE category_id=?)")
            values.add { setInt(it, categoryId) }
        }
        byHost.onSome { host ->
            clauses.add(
                "id IN (SELECT event_id FROM participant WHERE email = ? AND type = 'HOST')")
            values.add { setString(it, host) }
        }
        joinedBy.onSome { joinedBy ->
            clauses.add(
                "id IN (SELECT event_id FROM participant WHERE email = ? AND type = 'PARTICIPANT')")
            values.add { setString(it, joinedBy) }
        }

        val preparedStatement =
            connection.prepareStatement(
                "SELECT * FROM event WHERE ${clauses.joinToString(" AND ")} ORDER BY start_time;")
        values.forEachIndexed { index, setSomething -> preparedStatement.setSomething(index + 1) }

        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.registerForEvent(
    eventId: String,
    email: String,
    name: String,
    type: ParticipantType = ParticipantType.PARTICIPANT,
): Either<RegisterForEventError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId)
            .flatMap { checkIfParticipantIsRegistered(connection, eventId, email) }
            .flatMap { checkIfEventIsFull(connection, eventId) }
            .flatMap { checkIfDeadlineIsPassed(connection, eventId) }
            .map {
                val preparedStatement =
                    connection.prepareStatement(
                        """
INSERT INTO participant
            (event_id,
             email,
             name,
             type)
VALUES      (Uuid(?),
             ?,
             ?,
             ?::participant_type);
""")
                preparedStatement.setString(1, eventId)
                preparedStatement.setString(2, email)
                preparedStatement.setString(3, name)
                preparedStatement.setString(4, type.name)
                println(type.name)
                println(preparedStatement)

                preparedStatement.executeUpdate()
                connection.commit()
            }
    }
}

fun DatabaseInterface.changeParticipant(
    eventId: String,
    changeParticipant: ChangeParticipant,
): Either<ChangeParticipantError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId)
            .flatMap { checkIfEventWillHaveNoHosts(connection, eventId, changeParticipant) }
            .map {
                val preparedStatement =
                    connection.prepareStatement(
                        """
UPDATE participant
SET    type = ?::participant_type
WHERE  event_id = Uuid(?)
       AND email = ?;
""")
                preparedStatement.setString(1, changeParticipant.type.name)
                preparedStatement.setString(2, eventId)
                preparedStatement.setString(3, changeParticipant.email)

                preparedStatement.executeUpdate()
                connection.commit()
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
                    """
DELETE FROM participant
WHERE  event_id = Uuid(?)
       AND email = ?;
""")
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val rowsAffected = preparedStatement.executeUpdate()
            connection.commit()
            if (rowsAffected == 0) EmailNotFoundException.left() else Unit.right()
        }
    }
}

fun DatabaseInterface.deleteEvent(id: String): Either<EventNotFoundException, Unit> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement("""
DELETE FROM event
WHERE  id = Uuid(?);
""")
        preparedStatement.setString(1, id)

        val result = preparedStatement.executeUpdate()
        connection.commit()

        if (result == 0) EventNotFoundException.left() else Unit.right()
    }
}

fun DatabaseInterface.updateEvent(
    newEvent: Event,
): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                """
UPDATE event
SET    title=?,
       description=?,
       start_time=?,
       end_time=?,
       location=?,
       public=?,
       participant_limit=?,
       signup_deadline=?
WHERE  id=Uuid(?) returning *;
""")
        preparedStatement.setString(1, newEvent.title)
        preparedStatement.setString(2, newEvent.description)
        preparedStatement.setTimestamp(3, Timestamp.valueOf(newEvent.startTime))
        preparedStatement.setTimestamp(4, Timestamp.valueOf(newEvent.endTime))
        preparedStatement.setString(5, newEvent.location)
        preparedStatement.setBoolean(6, newEvent.public)
        preparedStatement.setInt(7, newEvent.participantLimit)
        preparedStatement.setTimestamp(8, newEvent.signupDeadline?.let { Timestamp.valueOf(it) })
        preparedStatement.setString(9, newEvent.id.toString())

        val result = preparedStatement.executeQuery()
        connection.commit()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun DatabaseInterface.setCategories(
    eventId: String,
    categories: List<Int>
): Either<EventNotFoundException, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId).map {
            val categoriesSet = categories.toSet()

            val categoriesToRemove =
                if (categoriesSet.isEmpty()) {
                    connection
                        .prepareStatement(
                            """
SELECT id
FROM   category
       JOIN event_has_category ehc
         ON category.id = ehc.category_id
WHERE  event_id = Uuid(?);
""")
                        .use {
                            it.setString(1, eventId)
                            val result = it.executeQuery()
                            result.toSet { getInt(1) }
                        }
                } else {
                    connection
                        .prepareStatement(
                            """
SELECT id
FROM   category
JOIN   event_has_category ehc
ON     category.id = ehc.category_id
WHERE  event_id = Uuid(?)
AND    category_id NOT IN (${categoriesSet.joinToString(",") { "?" }}); 
""")
                        .use {
                            it.setString(1, eventId)
                            categoriesSet.forEachIndexed { index, category ->
                                it.setInt(index + 2, category)
                            }
                            val result = it.executeQuery()
                            result.toSet { getInt(1) }
                        }
                }
            val fakeCategories =
                if (categoriesSet.isEmpty()) emptySet()
                else
                    connection
                        .prepareStatement(
                            """
SELECT *
FROM   (VALUES ${categoriesSet.joinToString(",") { " (?)" }}) AS t1 (id)
WHERE  id NOT IN (SELECT id
                  FROM   category);
""")
                        .use {
                            categoriesSet.forEachIndexed { index, category ->
                                it.setInt(index + 1, category)
                            }
                            val result = it.executeQuery()
                            result.toSet { getInt(1) }
                        }

            val currentCategories =
                connection
                    .prepareStatement(
                        """
SELECT category_id
FROM   event_has_category
WHERE  event_id = Uuid(?);
""")
                    .use {
                        it.setString(1, eventId)
                        val result = it.executeQuery()
                        result.toSet { getInt(1) }
                    }

            val categoriesToAdd =
                categoriesSet
                    .filter { !categoriesToRemove.contains(it) }
                    .filter { !fakeCategories.contains(it) }
                    .filter { !currentCategories.contains(it) }

            if (categoriesToRemove.isNotEmpty()) {
                val preparedStatement =
                    connection.prepareStatement(
                        """
DELETE FROM event_has_category
WHERE event_id = Uuid(?)
  AND category_id IN (${categoriesToRemove.joinToString(",") { "?" }});
""")
                preparedStatement.setString(1, eventId)
                categoriesToRemove.forEachIndexed { index, category ->
                    preparedStatement.setInt(index + 2, category)
                }
                preparedStatement.executeUpdate()
            }

            if (categoriesToAdd.isNotEmpty()) {
                val preparedStatement =
                    connection.prepareStatement(
                        """
INSERT INTO event_has_category
            (event_id,
             category_id)
VALUES      ${categoriesToAdd.joinToString(",") { "(Uuid(?), ?)" }};
""")
                categoriesToAdd.forEachIndexed { index, category ->
                    preparedStatement.setString(index * 2 + 1, eventId)
                    preparedStatement.setInt(index * 2 + 2, category)
                }
                preparedStatement.executeUpdate()
            }

            connection.commit()
        }
    }
}

fun DatabaseInterface.getCategories(): List<Category> {
    return connection.use { connection ->
        val preparedStatement = connection.prepareStatement(""" 
SELECT *
FROM   category;
""")
        val result = preparedStatement.executeQuery()
        result.toList { toCategory() }
    }
}

fun DatabaseInterface.getCategories(id: String): Either<EventNotFoundException, List<Category>> {
    return connection.use { connection ->
        checkIfEventExists(connection, id).map {
            val preparedStatement =
                connection.prepareStatement(
                    """
SELECT *
FROM   category
       JOIN event_has_category
         ON category.id = event_has_category.category_id
WHERE  event_id = Uuid(?); 
""")
            preparedStatement.setString(1, id)
            val result = preparedStatement.executeQuery()
            result.toList { toCategory() }
        }
    }
}

fun DatabaseInterface.createCategory(
    category: CreateCategory
): Either<CreateCategoryError, Category> {
    val name = category.name.lowercase()
    return connection.use { connection ->
        checkIfCategoryNameIsTooLong(name).flatMap {
            checkIfCategoryExists(connection, name).map {
                val preparedStatement =
                    connection.prepareStatement(
                        """
INSERT INTO category
            (
                        name
            )
            VALUES
            (
                        ?
            )
            returning *;
""")
                preparedStatement.setString(1, name)
                val result = preparedStatement.executeQuery()
                connection.commit()
                result.next()
                result.toCategory()
            }
        }
    }
}

fun ResultSet.toEvent(): Event {
    return Event(
        id = UUID.fromString(getString("id")),
        title = getString("title"),
        description = getString("description"),
        startTime = getTimestamp("start_time").toLocalDateTime(),
        endTime = getTimestamp("end_time").toLocalDateTime(),
        location = getString("location"),
        public = getBoolean("public"),
        participantLimit = getInt("participant_limit"),
        signupDeadline = getTimestamp("signup_deadline")?.toLocalDateTime(),
    )
}

fun ResultSet.toCategory(): Category {
    return Category(
        id = getInt("id"),
        name = getString("name"),
    )
}

fun checkIfEventExists(
    connection: Connection,
    eventId: String
): Either<EventNotFoundException, Unit> {
    return connection.prepareStatement("""
SELECT *
FROM   event
WHERE  id = Uuid(?);
""").use {
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
        .prepareStatement(
            """
SELECT *
FROM   participant
WHERE  event_id = Uuid(?)
       AND email = ?;
""")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val result = preparedStatement.executeQuery()
            if (result.next()) ParticipantAlreadyRegisteredException.left() else Unit.right()
        }
}

fun checkIfEventIsFull(
    connection: Connection,
    eventId: String,
): Either<EventFullException, Unit> {
    return connection
        .prepareStatement(
            """
SELECT e.participant_limit,
       Count(p.event_id) AS participants_count
FROM   event e
       LEFT JOIN participant p
              ON p.event_id = e.id
WHERE  e.id = Uuid(?)
GROUP  BY e.participant_limit
HAVING Count(p.event_id) >= e.participant_limit
       AND e.participant_limit > 0;
""")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)

            val result = preparedStatement.executeQuery()
            if (result.next()) EventFullException.left() else Unit.right()
        }
}

fun checkIfDeadlineIsPassed(
    connection: Connection,
    eventId: String,
): Either<DeadlinePassedException, Unit> {
    return connection
        .prepareStatement(
            """
SELECT signup_deadline
FROM event
WHERE id = Uuid(?)
  AND signup_deadline <= NOW();
""") // if signup_deadline is null, it will be false, so no need to check for null
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)

            val result = preparedStatement.executeQuery()
            if (result.next()) DeadlinePassedException.left() else Unit.right()
        }
}

fun checkIfEventWillHaveNoHosts(
    connection: Connection,
    eventId: String,
    changeParticipant: ChangeParticipant,
): Either<EventWillHaveNoHostsException, Unit> {
    return if (changeParticipant.type == ParticipantType.HOST) {
        Unit.right()
    } else {
        connection
            .prepareStatement(
                """
SELECT *
FROM   participant p
WHERE  p.event_id = Uuid(?)
       AND p.type = 'HOST'
       AND p.email <> ?;
""")
            .use { preparedStatement ->
                preparedStatement.setString(1, eventId)
                preparedStatement.setString(2, changeParticipant.email)

                val result = preparedStatement.executeQuery()
                if (result.next()) Unit.right() else EventWillHaveNoHostsException.left()
            }
    }
}

fun checkIfCategoryExists(
    connection: Connection,
    name: String,
): Either<CategoryAlreadyExistsException, Unit> {
    return connection.prepareStatement("""
SELECT *
FROM   category
WHERE  NAME = ?;
""").use {
        preparedStatement ->
        preparedStatement.setString(1, name)

        val result = preparedStatement.executeQuery()
        if (result.next()) CategoryAlreadyExistsException.left() else Unit.right()
    }
}

fun checkIfCategoryNameIsTooLong(name: String): Either<CategoryNameTooLongException, Unit> {
    return if (name.length > 25) CategoryNameTooLongException.left() else Unit.right()
}
