package com.altrix.orchestrator.adapter.out.sandbox;

import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding.Severity;
import com.altrix.orchestrator.infrastructure.config.SandboxDockerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of the Surefire-output parser inside DockerTestRunner.
 * Daemon interaction is exercised by the integration tests (#102, gated
 * on host Docker).
 */
class DockerTestRunnerTest {

    @Test
    void idAndOrder_areStable() {
        DockerTestRunner r = new DockerTestRunner(cfg());
        assertThat(r.id()).isEqualTo("docker-test");
        // After compile (10); a broken compile poisons the test signal.
        assertThat(r.order()).isEqualTo(11);
    }

    @Test
    void allTestsPassed_emitsInfoFindingWithCounts() {
        DockerTestRunner r = new DockerTestRunner(cfg());
        String logs = """
                [INFO] -------------------------------------------------------
                [INFO]  T E S T S
                [INFO] -------------------------------------------------------
                Tests run: 42, Failures: 0, Errors: 0, Skipped: 1
                [INFO] BUILD SUCCESS
                """;
        List<SandboxFinding> findings = r.interpret(0, logs);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(findings.get(0).message())
                .contains("Sandbox tests passed")
                .contains("42 run")
                .contains("1 skipped");
    }

    @Test
    void noTests_emitsInfoMessage() {
        DockerTestRunner r = new DockerTestRunner(cfg());
        String logs = "[INFO] No tests to run.\nTests run: 0, Failures: 0, Errors: 0, Skipped: 0\n";
        List<SandboxFinding> findings = r.interpret(0, logs);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("No tests in project");
    }

    @Test
    void failuresReported_emitsPerTestFindings() {
        DockerTestRunner r = new DockerTestRunner(cfg());
        String logs = """
                [INFO] -------------------------------------------------------
                [INFO]  T E S T S
                [INFO] -------------------------------------------------------
                Tests run: 5, Failures: 2, Errors: 0, Skipped: 0

                Failed tests:
                  com.example.OrderServiceTest.createOrder_publishesEvent: expected <2> but was <1>
                  com.example.OrderServiceTest.cancelOrder_removesIt: NullPointerException

                [ERROR] BUILD FAILURE
                """;
        List<SandboxFinding> findings = r.interpret(1, logs);
        assertThat(findings).hasSize(2);
        assertThat(findings).allMatch(f -> f.severity() == Severity.ERROR);
        assertThat(findings).allMatch(f -> f.runnerId().equals("docker-test"));
        assertThat(findings).anyMatch(f -> f.message().contains("createOrder_publishesEvent"));
        assertThat(findings).anyMatch(f -> f.message().contains("cancelOrder_removesIt"));
    }

    @Test
    void countsButNoParseableFailures_emitsSummaryError() {
        DockerTestRunner r = new DockerTestRunner(cfg());
        // Counts say failures present but no per-test block parses — fall
        // back to a summary finding.
        String logs = "Tests run: 10, Failures: 3, Errors: 1, Skipped: 0\n[ERROR] BUILD FAILURE\n";
        List<SandboxFinding> findings = r.interpret(1, logs);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(findings.get(0).message())
                .contains("3 failure")
                .contains("1 error")
                .contains("10 test");
    }

    @Test
    void unknownFailure_emitsLogTail() {
        DockerTestRunner r = new DockerTestRunner(cfg());
        String logs = "[ERROR] surefire plugin not configured\n[ERROR] BUILD FAILURE\n";
        List<SandboxFinding> findings = r.interpret(1, logs);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message())
                .contains("Sandbox tests exited 1")
                .contains("BUILD FAILURE");
    }

    private static SandboxDockerConfig cfg() {
        return new SandboxDockerConfig(true, "maven:3.9-eclipse-temurin-21-alpine",
                Duration.ofMinutes(5), 1024, "2.0", "bridge", "altrix-sandbox-",
                new SandboxDockerConfig.Reaper(true, Duration.ofMinutes(10), Duration.ofMinutes(30)));
    }
}
