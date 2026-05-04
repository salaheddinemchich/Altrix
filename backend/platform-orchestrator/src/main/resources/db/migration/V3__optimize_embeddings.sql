-- Increase statistics for high-cardinality and enum-like columns so the
-- query planner makes better row-count estimates on embedding searches.
ALTER TABLE code_embeddings ALTER COLUMN document_type SET STATISTICS 200;
ALTER TABLE code_embeddings ALTER COLUMN project_id    SET STATISTICS 200;

-- BRIN index on created_at — very small, near-zero write overhead.
-- Ideal for append-only tables where rows arrive in timestamp order.
CREATE INDEX IF NOT EXISTS idx_embeddings_created_brin
    ON code_embeddings USING brin (created_at);

-- Partial index: covers the documentationExists(logicalPath) check used at startup.
-- Scans only DOCUMENTATION rows — far smaller than a full table scan.
CREATE INDEX IF NOT EXISTS idx_embeddings_docs_path
    ON code_embeddings (file_path)
    WHERE document_type = 'DOCUMENTATION';

ANALYZE code_embeddings;

COMMENT ON INDEX idx_embeddings_created_brin IS 'BRIN — cheap range scans on append-only created_at';
COMMENT ON INDEX idx_embeddings_docs_path    IS 'Covers documentationExists(logicalPath) lookup';
