package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface FileProvenanceJpaRepository extends JpaRepository<FileProvenanceJpaEntity, UUID> {
}
