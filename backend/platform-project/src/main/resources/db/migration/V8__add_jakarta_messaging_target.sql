-- User's explicit choice of Jakarta EE messaging output for the migration —
-- NATIVE_KAFKA_CLIENTS (default) or SPRING_KAFKA_HYBRID. Only meaningful once
-- detection confirms the source is Jakarta EE; ignored otherwise. Never
-- inferred from source — set only by the user at upload/ingestion time.

ALTER TABLE projects
    ADD COLUMN jakarta_messaging_target VARCHAR(24) NOT NULL DEFAULT 'NATIVE_KAFKA_CLIENTS';

COMMENT ON COLUMN projects.jakarta_messaging_target IS 'User choice: NATIVE_KAFKA_CLIENTS | SPRING_KAFKA_HYBRID';
