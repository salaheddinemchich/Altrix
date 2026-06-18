-- The RAG index manifest tracked which source files were embedded into the
-- vector store, but nothing ever queried those embeddings back (the Core
-- Migrator's RAG retrieval only ever searches the shared documentation
-- corpus, never the user's own project files) — write-only, UI-only data.
DROP TABLE IF EXISTS rag_index_manifests;
