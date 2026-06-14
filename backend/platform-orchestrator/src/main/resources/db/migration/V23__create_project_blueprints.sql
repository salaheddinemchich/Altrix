--
-- ProjectBlueprint storage.
--
-- One row per workflow session.  Holds the full project map produced by
-- ProjectMapperAgent (the combined Index + Analyse phase): detected stack,
-- the LST-derived semantic graph, per-file features + Kafka migration
-- targets, doc references, and the topologically-sorted migration order.
--
-- Stored as a single JSONB column so the structure can evolve without
-- schema migrations every time we add a field to BlueprintFile or
-- SemanticGraph.  Slices are read on demand via JSONB path operators.
--
-- This table supersedes rag_index_manifests (V19) and file_provenance
-- (V21) once the parallel-run cutover completes.  Until then all three
-- tables coexist.
--

CREATE TABLE project_blueprints (
    session_id    UUID PRIMARY KEY REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    project_id    VARCHAR(36)  NOT NULL,
    blueprint     JSONB        NOT NULL,
    generated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    schema_version SMALLINT    NOT NULL DEFAULT 1
);

CREATE INDEX idx_blueprints_project ON project_blueprints (project_id);
CREATE INDEX idx_blueprints_generated_at ON project_blueprints (generated_at DESC);

COMMENT ON TABLE  project_blueprints IS
    'Per-session ProjectBlueprint — semantic project map produced by ProjectMapperAgent (combined Index + Analyse phase).';
COMMENT ON COLUMN project_blueprints.blueprint IS
    'JSONB serialisation of the ProjectBlueprint aggregate.  Includes detectedStack, detectedIntegrations, files[], semanticGraph (nodes + edges), migrationOrder, riskNotes.';
COMMENT ON COLUMN project_blueprints.schema_version IS
    'Blueprint JSON schema version — bump when the aggregate shape changes incompatibly.';
