package com.migrator.project.adapter.out.persistence;

import com.migrator.common.domain.model.MigrationJobRecord;
import com.migrator.project.domain.port.out.MigrationJobRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MigrationJobPersistenceAdapter implements MigrationJobRepositoryPort {

    private final MigrationJobJpaRepository repository;

    @Override
    public MigrationJobRecord save(MigrationJobRecord job) {
        MigrationJobEntity entity = MigrationJobEntity.builder()
                .id(job.getJobId())
                .name(job.getName())
                .sourceTopic(job.getSourceTopic())
                .targetTopic(job.getTargetTopic())
                .status(job.getStatus())
                .build();

        MigrationJobEntity saved = repository.save(entity);
        return mapToDomain(saved);
    }

    @Override
    public Optional<MigrationJobRecord> findById(UUID jobId) {
        return repository.findById(jobId).map(this::mapToDomain);
    }

    @Override
    public List<MigrationJobRecord> findAll() {
        return repository.findAll().stream()
                .map(this::mapToDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(UUID jobId) {
        repository.deleteById(jobId);
    }

    private MigrationJobRecord mapToDomain(MigrationJobEntity entity) {
        return MigrationJobRecord.builder()
                .jobId(entity.getId())
                .name(entity.getName())
                .sourceTopic(entity.getSourceTopic())
                .targetTopic(entity.getTargetTopic())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
