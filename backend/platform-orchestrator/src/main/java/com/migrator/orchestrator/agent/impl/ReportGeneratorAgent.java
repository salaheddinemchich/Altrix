package com.migrator.orchestrator.agent.impl;

import com.migrator.common.domain.model.MigrationReport;
import com.migrator.common.domain.model.WorkflowOutcome;
import com.migrator.common.domain.port.MigrationAgent;
import com.migrator.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Agent 5 — Report Generator.
 *
 * <p>Identity-pass stub: produces a minimal {@link MigrationReport} that
 * echoes the workflow's plan summary. The real report generator
 * (Markdown/HTML/PDF artifacts, signed download URLs, persistence) lands
 * in issue #29.
 */
@Slf4j
@Component("reportGeneratorAgent")
public class ReportGeneratorAgent implements MigrationAgent<WorkflowOutcome, MigrationReport> {

    @Override public String getName() { return "Report Generator"; }

    @Override public int getOrder() { return 5; }

    @Override
    public MigrationReport execute(WorkflowOutcome input) {
        if (input == null) {
            throw new AgentFailureException(getName(), "input WorkflowOutcome was null");
        }
        log.info("[{}] (stub) generating report for project '{}'", getName(), input.projectId());

        String body = """
                # Migration Report (stub)
                Project: %s
                Plan: %s
                Validation: %s
                """.formatted(
                input.projectId(),
                input.plan().summary(),
                input.validation().passed() ? "passed" : "failed");

        return new MigrationReport(input.projectId(), body, Instant.now());
    }
}
