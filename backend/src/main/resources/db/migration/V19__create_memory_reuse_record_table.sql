-- Task 5.4.a：先把记忆层级边界与复用留痕对象正式落库。
-- 本迁移只解决“载体是谁、留痕表是什么、sourceUrls 如何保留”三个问题，
-- 不提前引入 5.4.b/5.4.c 的融合、写回、失效等运行策略。

ALTER TABLE memory_snapshot
    ADD COLUMN IF NOT EXISTS memory_layer VARCHAR(40) NOT NULL DEFAULT 'SHORT_TERM';

UPDATE memory_snapshot
SET memory_layer = 'SHORT_TERM'
WHERE memory_layer IS NULL
   OR memory_layer = '';

ALTER TABLE competitor_knowledge
    ADD COLUMN IF NOT EXISTS memory_layer VARCHAR(40) NOT NULL DEFAULT 'DOMAIN';

UPDATE competitor_knowledge
SET memory_layer = 'DOMAIN'
WHERE memory_layer IS NULL
   OR memory_layer = '';

CREATE TABLE IF NOT EXISTS memory_reuse_record (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    consumer_node_name VARCHAR(100) NOT NULL,
    source_memory_layer VARCHAR(40) NOT NULL,
    source_object_type VARCHAR(60) NOT NULL,
    source_record_id BIGINT NOT NULL,
    source_task_id BIGINT,
    source_summary TEXT,
    source_urls TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_memory_reuse_record_task_id
    ON memory_reuse_record(task_id);

CREATE INDEX IF NOT EXISTS idx_memory_reuse_record_source_task_id
    ON memory_reuse_record(source_task_id);

CREATE INDEX IF NOT EXISTS idx_memory_reuse_record_source_memory_layer
    ON memory_reuse_record(source_memory_layer);
