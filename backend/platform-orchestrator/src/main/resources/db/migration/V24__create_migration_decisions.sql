--
-- MigrationDecisionRegistry storage (SemanticValidator Capability 8).
--
-- One row per workflow session.  Holds the append-only list of migration
-- decisions (renamed classes, replaced types, dependency substitutions,
-- API replacements) made while migrating the project — so every file is
-- migrated consistently with the ones before it.
--
-- Stored as a single JSONB array so decisions can grow without schema
-- migrations.  Read by the migrator's per-file prompt builder and by the
-- SemanticValidator's cross-file consistency checks.
--

CREATE TABLE migration_decisions (
    session_id  UUID PRIMARY KEY REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    decisions   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  migration_decisions IS
    'Per-session append-only MigrationDecisionRegistry — enforces cross-file migration consistency (SemanticValidator Capability 8).';
COMMENT ON COLUMN migration_decisions.decisions IS
    'JSONB array of MigrationDecision records: {kind, from, to, scope, rationale, decidedAt}.';
