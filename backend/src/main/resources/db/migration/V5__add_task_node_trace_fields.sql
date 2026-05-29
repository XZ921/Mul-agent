-- ==============================================================================
-- V5: add traceability fields for task_node
-- ==============================================================================

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS node_notes TEXT;

ALTER TABLE task_node
    ADD COLUMN IF NOT EXISTS allow_failed_dependency BOOLEAN NOT NULL DEFAULT FALSE;
