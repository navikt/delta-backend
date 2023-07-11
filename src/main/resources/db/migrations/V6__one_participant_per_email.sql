ALTER TABlE participant
    ADD UNIQUE (email, event_id);

CREATE INDEX participant_event_id_email_idx ON participant (event_id, email);