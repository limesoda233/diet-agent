-- Apply once before DIET_MEAL_PLANNING_ENABLED=true. No model calls inside transactions.
CREATE TABLE IF NOT EXISTS meal_plan (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 session_id VARCHAR(80) NOT NULL,
 trace_id VARCHAR(80) NOT NULL,
 current_version INT NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(user_id,trace_id)
);
CREATE TABLE IF NOT EXISTS meal_plan_feedback (
 plan_id VARCHAR(36) NOT NULL,
 plan_version INT NOT NULL,
 task_id VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 session_id VARCHAR(80) NOT NULL,
 trace_id VARCHAR(80) NOT NULL,
 item_id BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL,
 reason VARCHAR(512),
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(plan_id,plan_version,task_id,user_id)
);
CREATE TABLE IF NOT EXISTS meal_plan_version (
 plan_id VARCHAR(36) NOT NULL,
 version INT NOT NULL,
 status VARCHAR(32) NOT NULL,
 context_json LONGTEXT NOT NULL,
 spec_json LONGTEXT NOT NULL,
 candidates_json LONGTEXT NOT NULL,
 result_json LONGTEXT NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY(plan_id,version)
);
