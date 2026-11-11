-- Apply once to diet_db before enabling user memory. MySQL 8 / H2 MySQL-mode.
CREATE TABLE IF NOT EXISTS user_memory_profile (
 user_id BIGINT PRIMARY KEY,
 collection_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 epoch BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS user_memory (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 memory_type VARCHAR(32) NOT NULL,
 memory_value VARCHAR(128) NOT NULL,
 stance VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 source_type VARCHAR(16) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 source_message_id BIGINT,
 last_job_id BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 expires_at BIGINT,
 updated_at BIGINT NOT NULL,
 UNIQUE(user_id,memory_type,memory_value)
);
CREATE TABLE IF NOT EXISTS user_memory_job (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 session_id VARCHAR(80) NOT NULL,
 message_id BIGINT NOT NULL,
 trace_id VARCHAR(80) NOT NULL,
 epoch BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36),
 available_at BIGINT NOT NULL,
 lease_until BIGINT,
 error_type VARCHAR(128),
 created_at BIGINT NOT NULL,
 UNIQUE(message_id)
);
CREATE TABLE IF NOT EXISTS user_memory_evidence (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 job_id BIGINT NOT NULL,
 session_id VARCHAR(80) NOT NULL,
 memory_type VARCHAR(32) NOT NULL,
 memory_value VARCHAR(128) NOT NULL,
 stance VARCHAR(16) NOT NULL,
 scope VARCHAR(16) NOT NULL,
 evidence VARCHAR(2000) NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(job_id,memory_type,memory_value,stance)
);
CREATE TABLE IF NOT EXISTS user_memory_event (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 memory_id VARCHAR(36) NOT NULL,
 action VARCHAR(16) NOT NULL,
 trace_id VARCHAR(80),
 created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS user_memory_exposure (
 user_id BIGINT NOT NULL,
 session_id VARCHAR(80) NOT NULL,
 trace_id VARCHAR(80) NOT NULL,
 item_id BIGINT NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,trace_id,item_id)
);
CREATE TABLE IF NOT EXISTS user_memory_feedback (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 session_id VARCHAR(80) NOT NULL,
 trace_id VARCHAR(80) NOT NULL,
 item_id BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL,
 reason VARCHAR(512),
 created_at BIGINT NOT NULL,
 UNIQUE(user_id,trace_id,item_id)
);
