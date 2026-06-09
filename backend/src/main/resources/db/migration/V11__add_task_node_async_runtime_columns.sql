-- ==============================================================================
-- V11: add async runtime state machine columns and persistence tables
-- 说明：
-- 1. 为 task_node 补齐 Phase 4.2 所需的失败分类、重试时间窗、事件追踪和乐观锁字段；
-- 2. 新增节点执行尝试表，沉淀每一次真实执行尝试；
-- 3. 新增工作流死信表，保留进入人工处理 / DLQ 的最小审计信息。
-- ==============================================================================

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS failure_category VARCHAR(50);

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS last_event_id VARCHAR(64);

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS task_node_execution_attempt (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    node_id BIGINT NOT NULL,
    node_name VARCHAR(80) NOT NULL,
    attempt_no INT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    result_status VARCHAR(30) NOT NULL,
    failure_category VARCHAR(50),
    error_summary TEXT,
    source_event_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_node_attempt_task_id
    ON task_node_execution_attempt(task_id);

CREATE INDEX IF NOT EXISTS idx_node_attempt_node_id
    ON task_node_execution_attempt(node_id);

CREATE INDEX IF NOT EXISTS idx_node_attempt_idempotency_key
    ON task_node_execution_attempt(idempotency_key);

CREATE TABLE IF NOT EXISTS workflow_dead_letter_record (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    node_id BIGINT,
    node_name VARCHAR(80),
    source_event_id VARCHAR(64),
    failure_category VARCHAR(50) NOT NULL,
    latest_error_summary TEXT,
    retry_history TEXT,
    original_payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_workflow_dlq_task_id
    ON workflow_dead_letter_record(task_id);

CREATE INDEX IF NOT EXISTS idx_workflow_dlq_node_id
    ON workflow_dead_letter_record(node_id);

CREATE INDEX IF NOT EXISTS idx_workflow_dlq_event_id
    ON workflow_dead_letter_record(source_event_id);
