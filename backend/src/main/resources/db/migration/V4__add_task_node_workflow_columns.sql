-- ==============================================================================
-- V4: add workflow metadata columns to task_node
-- ==============================================================================

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS node_config TEXT;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
