CREATE TABLE event (
    id BIGSERIAL PRIMARY KEY ,
    title TEXT NOT NULL ,
    description TEXT DEFAULT '' NOT NULL ,
    start_time TIMESTAMP NOT NULL ,
    end_time TIMESTAMP NOT NULL 
)