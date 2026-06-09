-- ==============================================================================
-- V22: create recovery checkpoint table
-- 说明：
-- 1. 为任务回放 / 恢复平台建立正式恢复点表，承载“可从哪里恢复”的稳定对象；
-- 2. 显式关联 task_id、plan_version_id 与 source_urls，满足回放追溯与计划版本切换要求；
-- 3. 更复杂的恢复窗口、释放规则和人工介入语义将在后续子任务继续补齐。
-- ==============================================================================

CREATE TABLE IF NOT EXISTS recovery_checkpoint (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    plan_version_id BIGINT NOT NULL,
    checkpoint_key VARCHAR(120) NOT NULL,
    checkpoint_type VARCHAR(40) NOT NULL,
    node_name VARCHAR(80),
    branch_key VARCHAR(120),
    summary VARCHAR(500) NOT NULL,
    payload_snapshot TEXT,
    source_urls TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_recovery_checkpoint_task_key
    ON recovery_checkpoint(task_id, checkpoint_key);

CREATE INDEX IF NOT EXISTS idx_recovery_checkpoint_task_id
    ON recovery_checkpoint(task_id);

CREATE INDEX IF NOT EXISTS idx_recovery_checkpoint_plan_version_id
    ON recovery_checkpoint(plan_version_id);
