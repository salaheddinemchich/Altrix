-- RAG visibility — persist which source files were indexed for a given
-- session so the JobDetail timeline can show "view indexed files" instead
-- of a silent "DONE".
--
-- One row per session.  jsonb file_paths instead of TEXT[] so we can
-- carry more shape later (e.g. per-file chunk counts) without another
-- migration.

CREATE TABLE rag_index_manifests (
    session_id   UUID         PRIMARY KEY REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    project_id   VARCHAR(36)  NOT NULL,
    file_paths   JSONB        NOT NULL,
    file_count   INTEGER      NOT NULL,
    chunk_count  INTEGER      NOT NULL,
    indexed_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rag_index_manifests_project_id ON rag_index_manifests (project_id);
