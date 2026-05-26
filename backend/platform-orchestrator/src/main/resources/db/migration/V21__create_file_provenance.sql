-- #1 — per-file RAG provenance.  For each migration session, store the
-- map of source-file path → list of documentation chunks the migrator
-- retrieved as context when migrating that file.
--
-- One row per session.  per_file is a JSON object keyed by file path,
-- whose values are arrays of {logicalPath, sourceUrl, snippet} records.
-- JSONB on purpose so the shape can evolve (e.g. relevance score, line
-- references) without another migration.

CREATE TABLE file_provenance (
    session_id   UUID         PRIMARY KEY REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    per_file     JSONB        NOT NULL,
    generated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
