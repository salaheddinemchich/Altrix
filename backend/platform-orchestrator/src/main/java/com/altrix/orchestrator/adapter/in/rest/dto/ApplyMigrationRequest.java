package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Final-gate body of {@code POST /sessions/{id}/apply}.  The frontend
 * MUST send back the {@code confirmationToken} it received from
 * {@code /branch-strategy} and tick the {@code userApproved} flag.
 * Both checks are also enforced server-side by
 * {@link com.altrix.orchestrator.domain.service.MigrationApplyService}.
 */
public record ApplyMigrationRequest(
        @NotNull BranchStrategy strategy,
        @Size(max = 255) String branchName,
        @Size(max = 1024) String commitMessage,
        @Size(max = 255)  String prTitle,
        @Size(max = 4096) String prBody,
        @NotNull UUID confirmationToken,
        @AssertTrue(message = "You must approve to continue.") boolean userApproved
) {}
