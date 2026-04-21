package com.migrator.project.adapter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link ProjectJpaEntity}.
 *
 * <p>This interface is in the adapter layer. The domain never imports it.
 * The domain only knows {@link com.migrator.project.domain.port.out.ProjectRepository}.
 */
@Repository
interface ProjectJpaRepository extends JpaRepository<ProjectJpaEntity, String> {

    List<ProjectJpaEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);
}
