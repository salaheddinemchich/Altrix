package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.MigrationArtifact;
import com.altrix.common.domain.model.ValidationReport;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import com.altrix.orchestrator.domain.model.sandbox.SandboxFinding;
import com.altrix.orchestrator.domain.port.out.SandboxRunnerPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 4 — Sandbox Validator.
 *
 * <p>Composes every available {@link SandboxRunnerPort} and produces a
 * single {@link ValidationReport}.  Today the only runner is the static
 * checker ({@code StaticSandboxRunner}); #16/#17 will add a Docker
 * compile-and-test runner, #97 will add Checkstyle + SpotBugs, etc.
 * New runners declare themselves {@code @Component} and the agent picks
 * them up via Spring auto-wiring — no edits here.
 *
 * <p>Findings with {@link SandboxFinding.Severity#ERROR} fail validation.
 * Warnings + info are reported but don't gate.
 *
 * <p>A runner that throws is logged and treated as if it returned no
 * findings — one bad runner can never take down the whole pipeline.
 */
@Slf4j
@Component("sandboxValidatorAgent")
public class SandboxValidatorAgent implements MigrationAgent<MigrationArtifact, ValidationReport> {

    private final List<SandboxRunnerPort> runners;

    public SandboxValidatorAgent(List<SandboxRunnerPort> runners) {
        // Sort once at construction.  Spring guarantees the list is fully
        // populated before the agent is used, and the comparator is stable.
        this.runners = runners.stream()
                .sorted(Comparator.comparingInt(SandboxRunnerPort::order))
                .toList();
        log.info("[Sandbox Validator] composed {} runner(s): {}",
                this.runners.size(),
                this.runners.stream().map(SandboxRunnerPort::id).toList());
    }

    @Override
    public String getName() {
        return "Sandbox Validator";
    }

    @Override
    public int getOrder() {
        return 4;
    }

    @Override
    public ValidationReport execute(MigrationArtifact input) {
        if (input == null) throw new AgentFailureException(getName(), "input MigrationArtifact was null");
        log.info("[{}] validating {} file(s) for project '{}'",
                getName(), input.files().size(), input.projectId());

        List<SandboxFinding> findings = new ArrayList<>();
        for (SandboxRunnerPort runner : runners) {
            if (!runner.isAvailable()) {
                log.debug("[{}] runner '{}' skipped (unavailable)", getName(), runner.id());
                continue;
            }
            try {
                List<SandboxFinding> runnerFindings = runner.run(input);
                if (runnerFindings != null && !runnerFindings.isEmpty()) {
                    findings.addAll(runnerFindings);
                    log.debug("[{}] runner '{}' produced {} finding(s)",
                            getName(), runner.id(), runnerFindings.size());
                }
            } catch (Exception e) {
                // Defensive — a misbehaving runner must not block validation.
                log.error("[{}] runner '{}' threw — skipped: {}",
                        getName(), runner.id(), e.getMessage());
            }
        }

        // Map findings to the legacy ValidationReport.failures string list so
        // downstream consumers (frontend, report agent, retry context #98)
        // don't have to change yet.  Only ERRORs gate; warnings + info are
        // surfaced inline but don't fail the report.
        List<String> failures = findings.stream()
                .filter(f -> f.severity() == SandboxFinding.Severity.ERROR)
                .map(SandboxFinding::toFailureLine)
                .toList();

        boolean passed = failures.isEmpty();
        long warningCount = findings.stream()
                .filter(f -> f.severity() == SandboxFinding.Severity.WARNING).count();

        String summary;
        if (passed) {
            summary = warningCount == 0
                    ? "Validated %d file(s) — all static checks passed".formatted(input.files().size())
                    : "Validated %d file(s) — passed with %d warning(s)".formatted(input.files().size(), warningCount);
        } else {
            summary = "Validated %d file(s) — %d issue(s) found".formatted(input.files().size(), failures.size());
        }

        log.info("[{}] validation {}: {}", getName(), passed ? "PASSED" : "FAILED", summary);
        return new ValidationReport(input.projectId(), passed, failures, summary);
    }
}
