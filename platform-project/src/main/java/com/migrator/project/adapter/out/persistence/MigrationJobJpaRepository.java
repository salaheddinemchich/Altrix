package com.migrator.project.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
interface MigrationJobJpaRepository extends JpaRepository<MigrationJobEntity, UUID> {
}
