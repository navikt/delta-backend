CREATE TYPE participant_type AS ENUM ('HOST', 'PARTICIPANT');

ALTER TABLE participant
    ADD COLUMN type participant_type NOT NULL DEFAULT 'PARTICIPANT';

CREATE OR REPLACE FUNCTION tmp_email_to_name(TEXT) RETURNS TEXT AS $$
DECLARE
    email ALIAS FOR $1;
    name_arr TEXT[];
    name TEXT;
BEGIN
    name_arr = STRING_TO_ARRAY(SPLIT_PART(email, '@', 1), '.'); -- [victor, sebastian, i, k, bugge]
    FOR I IN 1..ARRAY_LENGTH(name_arr, 1) LOOP
            name_arr[I] = INITCAP(name_arr[I]);
        END LOOP; -- [Victor, Sebastian, I, K, Bugge]
    name = name_arr[ARRAY_LENGTH(name_arr, 1)] || ','; -- "Bugge,"
    FOR I IN 1..ARRAY_LENGTH(name_arr, 1) - 1 LOOP
            name = name || ' ' || name_arr[I];
        END LOOP; -- "Bugge, Victor Sebastian I K"
    RETURN name;
END;
$$ LANGUAGE plpgsql VOLATILE;

DELETE FROM participant WHERE participant IN (SELECT participant FROM event JOIN event ON participant.event_id = event.id WHERE event.owner = participant.email);
INSERT INTO participant (event_id, name, type, email) SELECT id, tmp_email_to_name(owner), 'HOST', owner FROM event;

ALTER TABLE event DROP COLUMN owner;
DROP FUNCTION tmp_email_to_name(TEXT);