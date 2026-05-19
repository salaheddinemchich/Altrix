package com.altrix.project.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ProjectJpaEntity}.
 *
 * <p>This interface is in the adapter layer. The domain never imports it.
 * The domain only knows {@link com.altrix.project.domain.port.out.ProjectRepositoryPort}.
 */
@Repository
interface ProjectJpaRepository extends JpaRepository<ProjectJpaEntity, String> {

    List<ProjectJpaEntity> findAllByUserIdOrderByCreatedAtDesc(String userId);

    /** #90 — most-recent project ingested from a given Git URL. */
    Optional<ProjectJpaEntity> findFirstByRepoUrlOrderByCreatedAtDesc(String repoUrl);
}
