-- ==============================================================================
-- V12: create task plan table and dynamic graph version columns
-- 说明：
-- 1. 为任务图增加正式的 TaskPlan 版本对象，承载“这一次任务图长什么样”的快照；
-- 2. 为 analysis_task / task_node / task_workflow_event 补齐 planVersionId、branchKey 等追溯字段；
-- 3. 这样动态补图、回流和局部恢复就不再只能靠 nodeName 猜历史。
-- ==============================================================================

CREATE TABLE IF NOT EXISTS task_plan (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    plan_version INT NOT NULL,
    parent_plan_id BIGINT,
    branch_key VARCHAR(120) NOT NULL,
    trigger_node_name VARCHAR(80),
    plan_type VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    plan_snapshot TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_task_plan_task_id
    ON task_plan(task_id);

CREATE INDEX IF NOT EXISTS idx_task_plan_parent_plan_id
    ON task_plan(parent_plan_id);

ALTER TABLE analysis_task
    ADD COLUMN IF NOT EXISTS current_plan_version_id BIGINT;

ALTER TABLE analysis_task
    ADD COLUMN IF NOT EXISTS current_plan_version INT;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS plan_version_id BIGINT;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS branch_key VARCHAR(120);

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS dynamic_node BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS origin_node_name VARCHAR(80);

ALTER TABLE task_workflow_event
    ADD COLUMN IF NOT EXISTS plan_version_id BIGINT;

ALTER TABLE task_workflow_event
    ADD COLUMN IF NOT EXISTS branch_key VARCHAR(120);
