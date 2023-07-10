DELETE FROM participant;
ALTER TABLE participant
    DROP constraint participant_pkey CASCADE,
    ADD PRIMARY KEY (otp),
    DROP COLUMN id,
    DROP COLUMN event_id;

ALTER TABLE event
    ADD COLUMN uuid_id uuid NOT NULL DEFAULT gen_random_uuid(),
    DROP CONSTRAINT event_pkey CASCADE,
    DROP COLUMN id,
    ADD PRIMARY KEY (uuid_id);

ALTER TABLE event
    RENAME COLUMN uuid_id TO id;

ALTER TABLE participant
    ADD COLUMN event_id uuid REFERENCES event(id),
    ALTER COLUMN event_id SET NOT NULL;
