package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.ProjectContext;
import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.port.out.FileReaderPort;
import com.altrix.orchestrator.domain.port.out.ProjectBlueprintRepository;
import com.altrix.orchestrator.infrastructure.blueprint.*;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase.Mapping;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end assembly test — uses the REAL OpenRewrite parser, graph
 * builder, classifier, stack detector and order resolver; only the file
 * source (MinIO) and persistence are mocked.  Confirms the agent turns a
 * raw project into a coherent {@link ProjectBlueprint}.
 */
class ProjectMapperAgentTest {

    private final FileReaderPort fileReader = mock(FileReaderPort.class);
    private final ProjectBlueprintRepository repository = mock(ProjectBlueprintRepository.class);

    private KafkaMigrationKnowledgeBase kb() {
        return new KafkaMigrationKnowledgeBase(List.of(
                new Mapping("pubsub.publish-single", "producer.send(record)",
                        List.of(), List.of(), List.of(), List.of(), "publish -> send"),
                new Mapping("pubsub.pull", "consumer.poll(Duration)",
                        List.of(), List.of(), List.of(), List.of(), "pull -> poll")),
                List.of(), List.of());
    }

    private ProjectMapperAgent agent() {
        var kb = kb();
        return new ProjectMapperAgent(
                fileReader,
                new OpenRewriteLstParser(),
                new LstSemanticGraphBuilder(),
                new FeatureClassifier(kb),
                new StackDetector(),
                new MigrationOrderResolver(),
                repository,
                // Disabled resolver — tests don't shell Docker; the parser
                // runs intra-project-only, exactly as before this feature.
                new MavenClasspathResolver(false, "maven", System.getProperty("user.home") + "/.m2", 10));
    }

    private Map<String, String> sampleProject() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("pom.xml", """
                <project>
                  <properties><maven.compiler.source>17</maven.compiler.source></properties>
                  <dependencies>
                    <dependency><groupId>jakarta.platform</groupId>
                      <artifactId>jakarta.jakartaee-api</artifactId></dependency>
                  </dependencies>
                </project>""");
        p.put("src/main/java/com/x/PubsubService.java", """
                package com.x;
                public interface PubsubService {
                    void publish(String topic, String value);
                }
                """);
        p.put("src/main/java/com/x/PubsubServiceImpl.java", """
                package com.x;
                public class PubsubServiceImpl implements PubsubService {
                    @Override public void publish(String topic, String value) {
                        pubsub.projects().topics().publish(topic, value);
                    }
                }
                """);
        p.put("src/main/java/com/x/OrderService.java", """
                package com.x;
                public class OrderService {
                    private final PubsubService svc = new PubsubServiceImpl();
                    void create() { svc.publish("t", "v"); }
                }
                """);
        return p;
    }

    private ProjectContext ctx() {
        return ProjectContext.builder()
                .jobId("job-1").projectId("proj-1").storageKey("uploads/p.zip").build();
    }

    @Test
    void assemblesBlueprintFromProject() {
        when(fileReader.readAllFiles("uploads/p.zip")).thenReturn(sampleProject());
        var agent = agent();

        ProjectBlueprint bp = agent.execute(ctx());

        assertThat(bp.projectId()).isEqualTo("proj-1");
        // Stack detected from the pom.
        assertThat(bp.detectedStack().framework()).isEqualTo("Jakarta EE");
        assertThat(bp.detectedStack().buildSystem()).isEqualTo("Maven");
        // Three project classes mapped.
        assertThat(bp.files()).extracting(BlueprintFile::simpleName)
                .contains("PubsubService", "PubsubServiceImpl", "OrderService");
        // Migration order is leaves-first — the interface before its impl.
        assertThat(bp.migrationOrder().indexOf("src/main/java/com/x/PubsubService.java"))
                .isLessThan(bp.migrationOrder().indexOf("src/main/java/com/x/PubsubServiceImpl.java"));
    }

    @Test
    void detectsPublishFeatureAndKafkaTarget() {
        when(fileReader.readAllFiles(any())).thenReturn(sampleProject());

        ProjectBlueprint bp = agent().execute(ctx());

        BlueprintFile impl = bp.fileSlice("src/main/java/com/x/PubsubServiceImpl.java").orElseThrow();
        assertThat(impl.isPassThrough()).isFalse();
        assertThat(impl.features()).extracting("id").contains("pubsub.publish-single");
        assertThat(impl.features().get(0).kafkaTarget()).isEqualTo("producer.send(record)");
        // Relationship captured: impl implements the interface.
        assertThat(impl.relationships().implementsTypes()).contains("com.x.PubsubService");
    }

    @Test
    void rolesAreInferred() {
        when(fileReader.readAllFiles(any())).thenReturn(sampleProject());
        ProjectBlueprint bp = agent().execute(ctx());

        BlueprintFile iface = bp.fileSlice("src/main/java/com/x/PubsubService.java").orElseThrow();
        assertThat(iface.role()).isIn("Messaging Port", "Interface");
    }

    @Test
    void emptyStorageKeyReturnsEmptyBlueprintWithoutReadingFiles() {
        var agent = agent();
        ProjectBlueprint bp = agent.execute(
                ProjectContext.builder().jobId("j").projectId("proj-1").storageKey("").build());
        assertThat(bp.files()).isEmpty();
        verifyNoInteractions(fileReader);
    }

    @Test
    void exposesNameAndOrder() {
        var agent = agent();
        assertThat(agent.getName()).isEqualTo("Project Mapper");
        assertThat(agent.getOrder()).isEqualTo(1);
    }
}
