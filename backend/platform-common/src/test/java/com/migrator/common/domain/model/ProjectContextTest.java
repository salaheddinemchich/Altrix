package com.migrator.common.domain.model;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectContextTest {

    @Test
    void builder_createsContextWithAllFields() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("job-1")
                .projectId("proj-1")
                .storageKey("uploads/proj-1.zip")
                .configFormatPreference(ConfigFormatPreference.KEEP_ORIGINAL)
                .build();

        assertThat(ctx.jobId()).isEqualTo("job-1");
        assertThat(ctx.projectId()).isEqualTo("proj-1");
        assertThat(ctx.storageKey()).isEqualTo("uploads/proj-1.zip");
    }

    @Test
    void nullLists_areReplacedWithEmptyLists() {
        ProjectContext ctx = ProjectContext.builder()
                .jobId("job-1")
                .projectId("proj-1")
                .build();

        assertThat(ctx.pubSubTopics()).isEmpty();
        assertThat(ctx.pubSubSubscriptions()).isEmpty();
        assertThat(ctx.listenerClasses()).isEmpty();
        assertThat(ctx.publisherClasses()).isEmpty();
        assertThat(ctx.migratedFiles()).isEmpty();
    }

    @Test
    void withPubSubTopics_returnsNewImmutableInstance() {
        ProjectContext original = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        ProjectContext enriched = original.withPubSubTopics(List.of("orders.created"));

        assertThat(enriched.pubSubTopics()).containsExactly("orders.created");
        assertThat(original.pubSubTopics()).isEmpty(); // original unchanged
    }

    @Test
    void withMigratedFiles_returnsNewImmutableInstance() {
        ProjectContext original = ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").build();

        MigratedFile file = MigratedFile.builder()
                .originalPath("MyListener.java")
                .newPath("MyListener.java")
                .content("package com.example;")
                .changeType(com.migrator.common.domain.enums.FileChangeType.MODIFIED)
                .diffSummary("Migrated PubSub to Kafka")
                .build();

        ProjectContext enriched = original.withMigratedFiles(List.of(file));

        assertThat(enriched.migratedFiles()).hasSize(1);
        assertThat(original.migratedFiles()).isEmpty();
    }
}
