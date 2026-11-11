-- Fresh experience database only. Do not run the local development dump here.
-- Apply this file, migrations/007_wechat_access.sql, then public_meal_seed.sql.
CREATE TABLE IF NOT EXISTS diet_sessions (
 id VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, phase VARCHAR(64) NOT NULL,
 slots JSON NOT NULL, last_recommendations JSON NOT NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 INDEX idx_session_user(user_id,updated_at)
);
CREATE TABLE IF NOT EXISTS diet_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(64) NOT NULL,
 role VARCHAR(32) NOT NULL, content TEXT NOT NULL, intent VARCHAR(64),
 agent_trace_id VARCHAR(128), created_at DATETIME NOT NULL,
 INDEX idx_message_session(session_id,created_at)
);
CREATE TABLE IF NOT EXISTS diet_request_trace (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, trace_id VARCHAR(128) NOT NULL UNIQUE,
 session_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 event_count INT NOT NULL DEFAULT 0, duration_ms BIGINT, error_message TEXT,
 trace_json JSON NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 expected_intent VARCHAR(64), expected_slots JSON, expected_clarify_action VARCHAR(32),
 labeled_by BIGINT, labeled_at DATETIME, label_note VARCHAR(512),
 INDEX idx_trace_user(user_id,created_at), INDEX idx_trace_expiry(user_id,status)
);
CREATE TABLE IF NOT EXISTS diet_slot_option (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, slot_name VARCHAR(64) NOT NULL,
 option_value VARCHAR(64) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
 enabled TINYINT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL, UNIQUE(slot_name,option_value)
);
CREATE TABLE IF NOT EXISTS meal_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(16) NOT NULL,
 owner_user_id BIGINT, name VARCHAR(128) NOT NULL, meal_time JSON NOT NULL,
 mood JSON NOT NULL, scene JSON NOT NULL, health_goal JSON NOT NULL,
 cuisine JSON NOT NULL, taste JSON NOT NULL, convenience JSON NOT NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 INDEX idx_personal(owner_user_id,source_type), INDEX idx_public(source_type)
);
CREATE TABLE IF NOT EXISTS meal_knowledge (
 meal_id BIGINT PRIMARY KEY, ingredients JSON NOT NULL, description TEXT NOT NULL,
 preparation TEXT NOT NULL, source_type VARCHAR(32) NOT NULL,
 source_reference VARCHAR(1000) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY (meal_id) REFERENCES meal_item(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS recommend_feedback (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 session_id VARCHAR(64) NOT NULL, item_id BIGINT, action VARCHAR(32) NOT NULL,
 rating INT, reason VARCHAR(512), created_at DATETIME NOT NULL,
 INDEX idx_feedback_user(user_id,created_at)
);
