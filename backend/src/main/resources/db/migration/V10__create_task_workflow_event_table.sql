-- ==============================================================================
-- V10: create task workflow event outbox table
-- ==============================================================================

CREATE TABLE task_workflow_event (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    task_id BIGINT NOT NULL,
    node_name VARCHAR(80),
    event_type VARCHAR(50) NOT NULL,
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    topic VARCHAR(200) NOT NULL,
    tag VARCHAR(100) NOT NULL,
    payload TEXT,
    source_urls TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 6,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    consumed_at TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_workflow_event_task_id ON task_workflow_event(task_id);
CREATE INDEX idx_task_workflow_event_event_id ON task_workflow_event(event_id);
CREATE INDEX idx_task_workflow_event_status ON task_workflow_event(delivery_status);
