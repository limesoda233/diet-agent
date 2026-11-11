-- Apply after diet_db.sql or experience_schema.sql. Authenticated IDs start above local test users.
CREATE TABLE IF NOT EXISTS diet_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
) AUTO_INCREMENT=1000000;

CREATE TABLE IF NOT EXISTS user_identity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(24) NOT NULL,
    app_id VARCHAR(64) NOT NULL,
    provider_subject VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    last_login_at DATETIME NOT NULL,
    UNIQUE (provider, app_id, provider_subject),
    UNIQUE (user_id, provider, app_id),
    FOREIGN KEY (user_id) REFERENCES diet_user(id)
);

CREATE TABLE IF NOT EXISTS user_session (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash BINARY(32) NOT NULL UNIQUE,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    created_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES diet_user(id)
);

CREATE TABLE IF NOT EXISTS user_invitation (
    id CHAR(36) PRIMARY KEY,
    code_hash BINARY(32) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    target_role VARCHAR(16) NOT NULL,
    expires_at DATETIME NOT NULL,
    claimed_by BIGINT,
    claimed_at DATETIME,
    created_by BIGINT,
    created_at DATETIME NOT NULL,
    FOREIGN KEY (claimed_by) REFERENCES diet_user(id),
    FOREIGN KEY (created_by) REFERENCES diet_user(id)
);

CREATE TABLE IF NOT EXISTS diagnostic_consent (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash BINARY(32) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME NOT NULL,
    consumed_trace_id VARCHAR(64),
    created_at DATETIME NOT NULL,
    consumed_at DATETIME,
    FOREIGN KEY (user_id) REFERENCES diet_user(id)
);

ALTER TABLE diet_request_trace ADD COLUMN capture_mode VARCHAR(24);
ALTER TABLE diet_request_trace ADD COLUMN expires_at DATETIME;
ALTER TABLE diet_request_trace ADD COLUMN diagnostic_consent_id CHAR(36);
