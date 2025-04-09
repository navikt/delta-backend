ALTER TABLE event_has_category
    DROP CONSTRAINT event_has_category_category_id_fkey,
    ADD CONSTRAINT event_has_category_category_id_fkey FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE CASCADE;

ALTER TABLE event_has_category
    DROP CONSTRAINT event_has_category_event_id_fkey,
    ADD CONSTRAINT event_has_category_event_id_fkey FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE CASCADE;
