-- Apply to the experience database before deploying the dietary onboarding release.
CREATE TABLE IF NOT EXISTS user_dietary_profile (
    user_id BIGINT NOT NULL PRIMARY KEY,
    allergens JSON NOT NULL,
    avoided_foods JSON NOT NULL,
    disliked_foods JSON NOT NULL,
    spice_preference VARCHAR(16) NOT NULL DEFAULT 'UNSET',
    spice_note VARCHAR(128) NOT NULL DEFAULT '',
    onboarding_status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_dietary_profile_user FOREIGN KEY (user_id) REFERENCES diet_user(id) ON DELETE CASCADE
);

INSERT INTO diet_slot_option(slot_name,option_value,sort_order,enabled,created_at,updated_at)
VALUES ('taste','不辣',10,1,NOW(),NOW())
ON DUPLICATE KEY UPDATE enabled=1;

CREATE TABLE IF NOT EXISTS personal_meal_name_guard (
    user_id BIGINT NOT NULL,
    normalized_name VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (user_id, normalized_name),
    CONSTRAINT fk_meal_name_guard_user FOREIGN KEY (user_id) REFERENCES diet_user(id) ON DELETE CASCADE
);
