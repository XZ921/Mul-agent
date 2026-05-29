ALTER TABLE competitor_knowledge
    ADD COLUMN IF NOT EXISTS source_urls TEXT;

ALTER TABLE competitor_knowledge
    ADD COLUMN IF NOT EXISTS evidence_coverage TEXT;

UPDATE competitor_knowledge
SET source_urls = '[]'
WHERE source_urls IS NULL;

UPDATE competitor_knowledge
SET evidence_coverage = '{}'
WHERE evidence_coverage IS NULL;
