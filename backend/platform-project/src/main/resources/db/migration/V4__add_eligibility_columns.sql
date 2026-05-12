ALTER TABLE projects
    ADD COLUMN eligible_for_migration BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN detected_technologies  TEXT;
