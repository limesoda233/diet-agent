CREATE TABLE IF NOT EXISTS meal_knowledge (
    meal_id BIGINT PRIMARY KEY,
    ingredients JSON NOT NULL,
    description TEXT NOT NULL,
    preparation TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_reference VARCHAR(1000) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS meal_index_task (
    meal_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS meal_index_progress (
    collection_name VARCHAR(128) NOT NULL,
    meal_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    applied_revision BIGINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(120) NULL,
    PRIMARY KEY(collection_name,meal_id)
);
