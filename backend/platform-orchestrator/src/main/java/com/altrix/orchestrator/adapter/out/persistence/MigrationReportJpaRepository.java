package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface MigrationReportJpaRepository extends JpaRepository<MigrationReportJpaEntity, UUID> {
}
