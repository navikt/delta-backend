ALTER TABLE participant
    DROP CONSTRAINT participant_event_id_fkey,
    ADD CONSTRAINT participant_event_id_fkey FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE CASCADE;