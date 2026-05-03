package com.altrix.job.adapter.out.persistence.spec;

import com.altrix.common.domain.enums.JobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobSpecificationTest {

    @Test
    void from_withNullFilter_doesNotThrow() {
        JobFilter filter = JobFilter.builder().build();
        // Just check it constructs without NPE — actual query tested via integration test
        assertThat(JobSpecification.from(filter)).isNotNull();
    }

    @Test
    void jobFilter_builder_setsAllFields() {
        JobFilter filter = JobFilter.builder()
                .userId("user-1")
                .projectId("proj-1")
                .status(JobStatus.DONE)
                .build();

        assertThat(filter.userId()).isEqualTo("user-1");
        assertThat(filter.projectId()).isEqualTo("proj-1");
        assertThat(filter.status()).isEqualTo(JobStatus.DONE);
    }
}
