package com.migrator.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderConfigJpaRepository extends JpaRepository<ProviderConfigEntity, String> {}
