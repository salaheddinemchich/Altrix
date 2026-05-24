-- #163 — full-text search over migration reports.
--
-- A GENERATED tsvector column keeps the index in sync with content
-- automatically (no triggers, no application-level updates).  GIN
-- gives near-instant lookups even at millions of rows.
--
-- 'english' is the dictionary; multi-language support would graduate to
-- a per-row config column later.  For now reports are English
-- (migration narrative + code snippets read fine through the English
-- stemmer).

ALTER TABLE migration_reports
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(content, ''))) STORED;

CREATE INDEX idx_migration_reports_content_tsv
    ON migration_reports
    USING GIN (content_tsv);
