package com.altrix.job.adapter.out.persistence;

import com.altrix.job.adapter.out.persistence.spec.JobSpecification;
import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.JobFilter;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JobPersistenceAdapter implements JobRepositoryPort {

    private final MigrationJobJpaRepository jpaRepository;
    private final MigrationJobMapper mapper;

    @Override
    public MigrationJob save(MigrationJob job) {
        return mapper.toDomain(jpaRepository.save(mapper.toJpaEntity(job)));
    }

    @Override
    public Optional<MigrationJob> findById(String jobId) {
        return jpaRepository.findById(jobId).map(mapper::toDomain);
    }

    @Override
    public List<MigrationJob> findAll(JobFilter filter) {
        return jpaRepository.findAll(JobSpecification.from(filter))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(String jobId) {
        jpaRepository.deleteById(jobId);
    }
}
