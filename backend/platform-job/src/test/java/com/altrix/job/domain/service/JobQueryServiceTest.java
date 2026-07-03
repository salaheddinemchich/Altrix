package com.altrix.job.domain.service;

import com.altrix.common.exception.JobNotFoundException;
import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.in.JobFilter;
import com.altrix.job.domain.port.out.JobCachePort;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobQueryServiceTest {

    @Mock JobRepositoryPort jobRepository;
    @Mock JobCachePort  jobCachePort;

    JobQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new JobQueryService(jobRepository, jobCachePort);
    }

    @Test
    void findById_returnsJob_whenExists() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "key", null, null, null);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        MigrationJob result = queryService.findById(job.getId());

        assertThat(result.getId()).isEqualTo(job.getId());
    }

    @Test
    void findById_throws_whenNotFound() {
        when(jobRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.findById("missing"))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void getStatus_returnsCachedValue_withoutHittingDb() {
        when(jobCachePort.getStatus("job-1")).thenReturn(Optional.of("ANALYZING"));

        String status = queryService.getStatus("job-1");

        assertThat(status).isEqualTo("ANALYZING");
        verifyNoInteractions(jobRepository);
    }

    @Test
    void getStatus_fallsBackToDb_onCacheMiss() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "key", null, null, null).startAnalyzing();
        when(jobCachePort.getStatus(job.getId())).thenReturn(Optional.empty());
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        String status = queryService.getStatus(job.getId());

        assertThat(status).isEqualTo("ANALYZING");
        verify(jobCachePort).putStatus(job.getId(), "ANALYZING");
    }

    @Test
    void findAll_delegatesToRepository() {
        MigrationJob job = MigrationJob.create("proj-1", "user-1", "key", null, null, null);
        JobFilter filter = JobFilter.builder().userId("user-1").build();
        when(jobRepository.findAll(filter)).thenReturn(List.of(job));

        List<MigrationJob> results = queryService.findAll(filter);

        assertThat(results).hasSize(1);
    }
}
