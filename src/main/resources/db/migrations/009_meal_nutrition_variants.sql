-- Apply once with a schema-capable account before deploying nutrition variants.
CREATE TABLE IF NOT EXISTS meal_nutrition_variant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_id BIGINT NOT NULL,
    variant_key VARCHAR(64) NOT NULL,
    label VARCHAR(64) NOT NULL,
    template_code VARCHAR(32) NOT NULL,
    selections JSON NOT NULL,
    estimate_json JSON NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_meal_variant(meal_id,variant_key),
    CONSTRAINT fk_nutrition_variant_meal FOREIGN KEY (meal_id) REFERENCES meal_item(id) ON DELETE CASCADE
);

-- Two common takeaway templates. These are categories, not a specific shop's measured product.
INSERT INTO meal_item(source_type,owner_user_id,name,meal_time,mood,scene,health_goal,cuisine,taste,convenience,created_at,updated_at)
SELECT 'PUBLIC',NULL,'麻辣烫',JSON_ARRAY('午餐','晚餐'),JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY('火锅','小吃'),JSON_ARRAY('麻辣'),JSON_ARRAY('外带方便'),NOW(),NOW()
WHERE NOT EXISTS(SELECT 1 FROM meal_item WHERE source_type='PUBLIC' AND owner_user_id IS NULL AND name='麻辣烫');
INSERT INTO meal_item(source_type,owner_user_id,name,meal_time,mood,scene,health_goal,cuisine,taste,convenience,created_at,updated_at)
SELECT 'PUBLIC',NULL,'炸串',JSON_ARRAY('午餐','晚餐'),JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY('小吃'),JSON_ARRAY('咸鲜'),JSON_ARRAY('外带方便'),NOW(),NOW()
WHERE NOT EXISTS(SELECT 1 FROM meal_item WHERE source_type='PUBLIC' AND owner_user_id IS NULL AND name='炸串');
