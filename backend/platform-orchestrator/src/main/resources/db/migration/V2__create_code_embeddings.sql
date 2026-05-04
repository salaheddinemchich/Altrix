-- Enable pgvector extension (requires pgvector installed on the Postgres instance)
CREATE EXTENSION IF NOT EXISTS vector;

-- Stores both source-code chunks and documentation chunks for RAG retrieval.
-- document_type distinguishes SOURCE_CODE (project files) from DOCUMENTATION
-- (Kafka / GCP PubSub reference material fetched via MCP at startup).
CREATE TABLE IF NOT EXISTS code_embeddings (
    id              BIGSERIAL    NOT NULL,
    project_id      VARCHAR(100),                   -- NULL for documentation chunks
    document_type   VARCHAR(30)  NOT NULL,           -- SOURCE_CODE | DOCUMENTATION
    file_path       VARCHAR(1000) NOT NULL,
    chunk_index     INT          NOT NULL DEFAULT 0,
    chunk_text      TEXT         NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,           -- SHA-256 of chunk_text — skip re-embedding if unchanged
    embedding       vector(1536),                    -- text-embedding-3-small / nomic-embed-text (1536-dim)
    source_url      VARCHAR(2000),                   -- populated for DOCUMENTATION chunks
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_code_embeddings PRIMARY KEY (id)
);

-- Fast similarity search — IVFFlat index (exact for small datasets, approximate for large)
CREATE INDEX IF NOT EXISTS idx_embeddings_vector
    ON code_embeddings USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- Lookup by project to scope retrieval to the current migration job
CREATE INDEX IF NOT EXISTS idx_embeddings_project
    ON code_embeddings (project_id, document_type);

-- Deduplication: skip re-embedding a chunk whose content hasn't changed
CREATE UNIQUE INDEX IF NOT EXISTS idx_embeddings_dedup
    ON code_embeddings (project_id, file_path, chunk_index)
    WHERE project_id IS NOT NULL;
