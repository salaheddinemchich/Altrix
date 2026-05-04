package com.altrix.common.domain.enums;

public enum DocumentType {
    /** A chunk from the project's source files being migrated. */
    SOURCE_CODE,
    /** A chunk from framework/platform reference documentation (Kafka, GCP PubSub, etc.). */
    DOCUMENTATION
}
