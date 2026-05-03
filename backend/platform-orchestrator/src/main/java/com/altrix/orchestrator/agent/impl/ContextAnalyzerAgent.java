package com.altrix.orchestrator.agent.impl;

import com.altrix.common.domain.model.AnalysisReport;
import com.altrix.common.domain.model.ProjectContext;
import com.altrix.common.domain.port.MigrationAgent;
import com.altrix.common.exception.AgentFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 1 — Context Analyzer.
 *
 * <p>Reads {@link ProjectContext} and produces a typed {@link AnalysisReport}
 * summarising the components and integrations the planner needs.
 *
 * <p>Real AI-driven analysis (against arbitrary stacks, not just Spring/PubSub)
 * is delivered when the orchestrator pipeline is rewired in issue #35 — at
 * which point this agent absorbs the logic currently in
 * {@code adapter.out.agent.ArchitectureAnalyzerAgent}.
 */
@Slf4j
@Component("contextAnalyzerAgent")
public class ContextAnalyzerAgent implements MigrationAgent<ProjectContext, AnalysisReport> {

    @Override public String getName() { return "Context Analyzer"; }

    @Override public int getOrder() { return 1; }

    @Override
    public AnalysisReport execute(ProjectContext input) {
        if (input == null) {
            throw new AgentFailureException(getName(), "input ProjectContext was null");
        }
        log.info("[{}] analysing project '{}'", getName(), input.projectId());

        List<String> components   = collectComponents(input);
        List<String> integrations = collectIntegrations(input);
        String summary = "Analyzed project '%s' — %d components, %d integrations"
                .formatted(input.projectId(), components.size(), integrations.size());

        return new AnalysisReport(input.projectId(), components, integrations, summary);
    }

    private List<String> collectComponents(ProjectContext c) {
        List<String> out = new ArrayList<>();
        out.addAll(c.listenerClasses());
        out.addAll(c.publisherClasses());
        return out;
    }

    private List<String> collectIntegrations(ProjectContext c) {
        List<String> out = new ArrayList<>();
        out.addAll(c.pubSubTopics());
        out.addAll(c.pubSubSubscriptions());
        return out;
    }
}
