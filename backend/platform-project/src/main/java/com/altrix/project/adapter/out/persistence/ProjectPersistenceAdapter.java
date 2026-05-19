package com.altrix.project.adapter.out.persistence;

import com.altrix.project.domain.model.Project;
import com.altrix.project.domain.port.out.ProjectRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Secondary adapter — implements the {@link ProjectRepository} domain port
 * using Spring Data JPA + PostgreSQL.
 *
 * <p>The domain service depends only on {@link ProjectRepository}.
 * It has no knowledge that this adapter exists or that JPA is being used.
 * Swapping to a different database means writing a new adapter — zero
 * changes to the domain.
 */
@Component
@RequiredArgsConstructor
public class ProjectPersistenceAdapter implements ProjectRepositoryPort {

    private final ProjectJpaRepository jpaRepository;
    private final ProjectMapper mapper;

    @Override
    public Project save(Project project) {
        ProjectJpaEntity entity  = mapper.toJpaEntity(project);
        ProjectJpaEntity saved   = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Project> findById(String projectId) {
        return jpaRepository.findById(projectId)
                .map(mapper::toDomain);
    }

    @Override
    public List<Project> findAllByUserId(String userId) {
        return jpaRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(String projectId) {
        jpaRepository.deleteById(projectId);
    }

    @Override
    public Optional<Project> findLatestByRepoUrl(String repoUrl) {
        return jpaRepository.findFirstByRepoUrlOrderByCreatedAtDesc(repoUrl)
                .map(mapper::toDomain);
    }
}
