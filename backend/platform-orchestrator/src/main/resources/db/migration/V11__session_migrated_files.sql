-- #122: Store migrated files per session for the diff endpoint (#119)
ALTER TABLE workflow_sessions
    ADD COLUMN IF NOT EXISTS migrated_files JSONB;
