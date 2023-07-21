ALTER TABLE event
    ADD COLUMN signup_deadline TIMESTAMP;

UPDATE event
    SET signup_deadline = start_time;

ALTER TABLE event
    ALTER COLUMN signup_deadline SET NOT NULL;