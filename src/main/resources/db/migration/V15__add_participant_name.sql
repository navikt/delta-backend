ALTER TABLE participant
    ADD COLUMN name TEXT;

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

UPDATE participant
    SET name = tmp_email_to_name(email);

DROP FUNCTION tmp_email_to_name(TEXT);

ALTER TABLE participant
    ALTER COLUMN name SET NOT NULL;
