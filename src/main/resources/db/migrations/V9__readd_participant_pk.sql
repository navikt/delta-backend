ALTER TABLE participant
    DROP CONSTRAINT participant_email_event_id_key CASCADE;

DROP INDEX participant_event_id_email_idx;

ALTER TABLE participant
    ADD PRIMARY KEY (email, event_id);
CREATE INDEX participant_event_id_idx ON participant (event_id);