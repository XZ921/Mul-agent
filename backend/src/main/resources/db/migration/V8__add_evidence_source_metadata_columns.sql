ALTER TABLE evidence_source
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS discovery_method VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_domain VARCHAR(255),
    ADD COLUMN IF NOT EXISTS discovery_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS published_at VARCHAR(30),
    ADD COLUMN IF NOT EXISTS source_score DOUBLE PRECISION;

CREATE INDEX IF NOT EXISTS idx_evidence_source_type ON evidence_source(source_type);
CREATE INDEX IF NOT EXISTS idx_evidence_discovery_method ON evidence_source(discovery_method);
