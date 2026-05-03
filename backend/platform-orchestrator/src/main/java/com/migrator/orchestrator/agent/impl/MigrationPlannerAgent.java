package com.migrator.orchestrator.agent.impl;

import com.migrator.common.domain.model.AnalysisReport;
import com.migrator.common.domain.model.MigrationPlan;
import com.migrator.common.domain.port.MigrationAgent;
import com.migrator.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 2 — Migration Planner.
 *
 * <p>Identity-pass stub: ships an empty {@link MigrationPlan} that carries
 * the analysis summary forward. The real planner (full schema, AI-driven
 * step generation, risk scoring, effort estimation) lands in issue #5.
 */
@Slf4j
@Component("migrationPlannerAgent")
public class MigrationPlannerAgent implements MigrationAgent<AnalysisReport, MigrationPlan> {

    @Override public String getName() { return "Migration Planner"; }

    @Override public int getOrder() { return 2; }

    @Override
    public MigrationPlan execute(AnalysisReport input) {
        if (input == null) {
            throw new AgentFailureException(getName(), "input AnalysisReport was null");
        }
        log.info("[{}] (stub) producing empty plan for project '{}'", getName(), input.projectId());

        String summary = input.summary().isBlank()
                ? "Plan stub — no steps generated"
                : "Plan stub based on: " + input.summary();

        return new MigrationPlan(input.projectId(), List.of(), summary);
    }
}
