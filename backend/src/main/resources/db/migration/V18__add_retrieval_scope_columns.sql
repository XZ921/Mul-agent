ALTER TABLE retrieval_chunk
    ALTER COLUMN task_id DROP NOT NULL;

ALTER TABLE retrieval_index
    ALTER COLUMN task_id DROP NOT NULL;

ALTER TABLE retrieval_chunk
    ADD COLUMN IF NOT EXISTS retrieval_scope VARCHAR(40) NOT NULL DEFAULT 'TASK',
    ADD COLUMN IF NOT EXISTS scope_ref_key VARCHAR(160) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS knowledge_domain_key VARCHAR(120);

ALTER TABLE retrieval_index
    ADD COLUMN IF NOT EXISTS retrieval_scope VARCHAR(40) NOT NULL DEFAULT 'TASK',
    ADD COLUMN IF NOT EXISTS scope_ref_key VARCHAR(160) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS knowledge_domain_key VARCHAR(120);

UPDATE retrieval_chunk
SET retrieval_scope = COALESCE(NULLIF(retrieval_scope, ''), 'TASK'),
    scope_ref_key = CASE
        WHEN task_id IS NOT NULL THEN CAST(task_id AS VARCHAR(160))
        ELSE 'ORGANIZATION'
    END
WHERE retrieval_scope IS NULL
   OR retrieval_scope = ''
   OR scope_ref_key IS NULL
   OR scope_ref_key = '';

UPDATE retrieval_index
SET retrieval_scope = COALESCE(NULLIF(retrieval_scope, ''), 'TASK'),
    scope_ref_key = CASE
        WHEN task_id IS NOT NULL THEN CAST(task_id AS VARCHAR(160))
        ELSE 'ORGANIZATION'
    END
WHERE retrieval_scope IS NULL
   OR retrieval_scope = ''
   OR scope_ref_key IS NULL
   OR scope_ref_key = '';

CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_scope_ref_key
    ON retrieval_chunk(scope_ref_key);

CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_knowledge_domain_key
    ON retrieval_chunk(knowledge_domain_key);

CREATE INDEX IF NOT EXISTS idx_retrieval_index_scope_ref_key
    ON retrieval_index(scope_ref_key);

CREATE INDEX IF NOT EXISTS idx_retrieval_index_knowledge_domain_key
    ON retrieval_index(knowledge_domain_key);
