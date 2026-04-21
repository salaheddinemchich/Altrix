package com.migrator.common.domain.enums;

public enum JobStatus {
    PENDING,
    INITIALIZING,
    EXTRACTING,
    TRANSFORMING,
    LOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}
