package com.altrix.job.adapter.out.persistence;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.common.domain.enums.JobStatus;
import com.altrix.job.domain.model.MigrationJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationJobMapperTest {

    MigrationJobMapper mapper = new MigrationJobMapper();

    @Test
    void toDomain_mapsAllFields() {
        Instant now = Instant.now();
        MigrationJobJpaEntity entity = MigrationJobJpaEntity.builder()
                .id("job-1")
                .projectId("proj-1")
                .userId("user-1")
                .projectStorageKey("uploads/proj.zip")
                .status(JobStatus.ANALYZING)
                .configFormatPreference(ConfigFormatPreference.KEEP_ORIGINAL)
                .outputStorageKey("migrated/out.zip")
                .errorMessage(null)
                .createdAt(now)
                .updatedAt(now)
                .completedAt(null)
                .build();

        MigrationJob domain = mapper.toDomain(entity);

        assertThat(domain.getId()).isEqualTo("job-1");
        assertThat(domain.getProjectId()).isEqualTo("proj-1");
        assertThat(domain.getUserId()).isEqualTo("user-1");
        assertThat(domain.getProjectStorageKey()).isEqualTo("uploads/proj.zip");
        assertThat(domain.getStatus()).isEqualTo(JobStatus.ANALYZING);
        assertThat(domain.getConfigFormatPreference()).isEqualTo(ConfigFormatPreference.KEEP_ORIGINAL);
        assertThat(domain.getOutputStorageKey()).isEqualTo("migrated/out.zip");
        assertThat(domain.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void toJpaEntity_mapsAllFields() {
        MigrationJob domain = MigrationJob.create("proj-1", "user-1", "uploads/proj.zip", ConfigFormatPreference.FORCE_YAML);

        MigrationJobJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(domain.getId());
        assertThat(entity.getProjectId()).isEqualTo("proj-1");
        assertThat(entity.getUserId()).isEqualTo("user-1");
        assertThat(entity.getProjectStorageKey()).isEqualTo("uploads/proj.zip");
        assertThat(entity.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(entity.getConfigFormatPreference()).isEqualTo(ConfigFormatPreference.FORCE_YAML);
    }

    @Test
    void roundTrip_preservesAllFields() {
        MigrationJob original = MigrationJob.create("proj-1", "user-1", "key", null);
        MigrationJobJpaEntity entity = mapper.toJpaEntity(original);
        MigrationJob restored = mapper.toDomain(entity);

        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getProjectId()).isEqualTo(original.getProjectId());
        assertThat(restored.getUserId()).isEqualTo(original.getUserId());
        assertThat(restored.getConfigFormatPreference()).isEqualTo(original.getConfigFormatPreference());
    }
}
