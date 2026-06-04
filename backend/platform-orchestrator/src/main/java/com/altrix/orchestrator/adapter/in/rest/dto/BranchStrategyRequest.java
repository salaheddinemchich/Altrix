package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /sessions/{id}/branch-strategy}.  Branch name is
 * optional — the server uses {@code MigrationApplyConfig.branchNamePrefix}
 * + the session id when blank.  Commit message and PR fields are also
 * optional; the server renders defaults from configurable templates.
 */
public record BranchStrategyRequest(
        @NotNull BranchStrategy strategy,
        @Size(max = 255) String branchName,
        @Size(max = 1024) String commitMessage,
        @Size(max = 255)  String prTitle,
        @Size(max = 4096) String prBody
) {}
