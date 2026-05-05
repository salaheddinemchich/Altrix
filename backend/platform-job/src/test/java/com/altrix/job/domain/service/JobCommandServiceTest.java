package com.altrix.job.domain.service;

import com.altrix.common.domain.enums.JobStatus;
import com.altrix.common.exception.JobNotFoundException;
import com.altrix.job.domain.model.MigrationJob;
import com.altrix.job.domain.port.out.JobCachePort;
import com.altrix.job.domain.port.out.JobEventPublisherPort;
import com.altrix.job.domain.port.out.JobRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobCommandServiceTest {

    @Mock JobRepositoryPort    jobRepository;
    @Mock JobCachePort         jobCachePort;
    @Mock JobEventPublisherPort jobEventPublisher;

    JobCommandService commandService;
    JobQueryService   queryService;

    @BeforeEach
    void setUp() {
        commandService = new JobCommandService(jobRepository, jobCachePort, jobEventPublisher);
        queryService   = new JobQueryService(jobRepository, jobCachePort);
    }

    @Test
    void createJob_savesAndPublishesAndCaches() {
        MigrationJob saved = MigrationJob.create("proj-1", "user-1", "key", null, null);
        when(jobRepository.save(any())).thenReturn(saved);

        MigrationJob result = commandService.createJob("proj-1", "user-1", "key", null, null);

        assertThat(result.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(jobRepository).save(any());
        verify(jobCachePort).putStatus(any(), eq("PENDING"));
        verify(jobEventPublisher).publishJobCreated(any());
    }

    @Test
    void markAnalyzing_transitionsStatus() {
        MigrationJob pending  = MigrationJob.create("proj-1", "user-1", "key", null, null);
        MigrationJob analyzing = pending.startAnalyzing();
        when(jobRepository.findById("job-id")).thenReturn(Optional.of(pending));
        when(jobRepository.save(any())).thenReturn(analyzing);

        MigrationJob result = commandService.markAnalyzing("job-id");

        assertThat(result.getStatus()).isEqualTo(JobStatus.ANALYZING);
        verify(jobCachePort).putStatus(any(), eq("ANALYZING"));
    }

    @Test
    void markDone_publishesCompletedEvent() {
        MigrationJob migrating = MigrationJob.create("proj-1", "user-1", "key", null, null)
                .startAnalyzing().startMigrating();
        MigrationJob done      = migrating.complete("output.zip");
        when(jobRepository.findById("job-id")).thenReturn(Optional.of(migrating));
        when(jobRepository.save(any())).thenReturn(done);

        commandService.markDone("job-id", "output.zip");

        verify(jobEventPublisher).publishJobCompleted(any());
        verify(jobCachePort).putStatus(any(), eq("DONE"));
    }

    @Test
    void markFailed_setsErrorAndPublishes() {
        MigrationJob analyzing = MigrationJob.create("proj-1", "user-1", "key", null, null).startAnalyzing();
        MigrationJob failed    = analyzing.fail("AI timeout");
        when(jobRepository.findById("job-id")).thenReturn(Optional.of(analyzing));
        when(jobRepository.save(any())).thenReturn(failed);

        commandService.markFailed("job-id", "AI timeout");

        verify(jobEventPublisher).publishJobCompleted(any());
        verify(jobCachePort).putStatus(any(), eq("FAILED"));
    }

    @Test
    void markMigrating_transitionsStatus() {
        MigrationJob analyzing = MigrationJob.create("proj-1", "user-1", "key", null, null).startAnalyzing();
        MigrationJob migrating = analyzing.startMigrating();
        when(jobRepository.findById("job-id")).thenReturn(Optional.of(analyzing));
        when(jobRepository.save(any())).thenReturn(migrating);

        MigrationJob result = commandService.markMigrating("job-id");

        assertThat(result.getStatus()).isEqualTo(JobStatus.MIGRATING);
        verify(jobCachePort).putStatus(any(), eq("MIGRATING"));
    }

    @Test
    void loadOrThrow_throwsJobNotFoundException_whenNotFound() {
        when(jobRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.markAnalyzing("unknown"))
                .isInstanceOf(JobNotFoundException.class)
                .hasMessageContaining("unknown");
    }
}
