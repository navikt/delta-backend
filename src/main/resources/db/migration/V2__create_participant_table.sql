CREATE TABLE participant (
    id BIGSERIAL PRIMARY KEY ,
    event_id BIGINT REFERENCES event(id) NOT NULL ,
    email TEXT NOT NULL ,
    otp UUID NOT NULL DEFAULT gen_random_uuid()
)