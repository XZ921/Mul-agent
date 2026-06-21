-- 为 extractor 产出的任务现场快照补齐显式边界字段，避免默认落入 DOMAIN 记忆语义。
ALTER TABLE competitor_knowledge
    ADD COLUMN IF NOT EXISTS snapshot_scope VARCHAR(40) NOT NULL DEFAULT 'TASK',
    ADD COLUMN IF NOT EXISTS producer_node_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS plan_version_id BIGINT,
    ADD COLUMN IF NOT EXISTS branch_key VARCHAR(120);

CREATE INDEX IF NOT EXISTS idx_knowledge_task_scope
    ON competitor_knowledge(task_id, snapshot_scope);
