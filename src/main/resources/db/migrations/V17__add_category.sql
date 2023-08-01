CREATE TABLE category(
    id SERIAL PRIMARY KEY,
    name VARCHAR(25) UNIQUE NOT NULL
);

CREATE TABLE event_has_category(
    event_id UUID REFERENCES event(id),
    category_id INT REFERENCES category(id),
    PRIMARY KEY(event_id, category_id)
);
