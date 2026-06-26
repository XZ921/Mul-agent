ALTER TABLE report ADD COLUMN writer_evidence_state VARCHAR(40);
ALTER TABLE report ADD COLUMN citation_gap_severity VARCHAR(40);
ALTER TABLE report ADD COLUMN missing_citation_sections TEXT;
ALTER TABLE report ADD COLUMN section_citation_gaps TEXT;
ALTER TABLE report ADD COLUMN writer_issue_flags TEXT;
ALTER TABLE report ADD COLUMN writer_source_urls TEXT;
