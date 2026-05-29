-- ==============================================================================
-- V3: 为 task_node 表添加重试相关字段
-- 说明：满足 plan 4.5 对 retryable、maxRetries 的要求
-- ==============================================================================

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS retryable BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS max_retries INT NOT NULL DEFAULT 3;
