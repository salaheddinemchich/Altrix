package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Tunables for the post-migration "apply to repository" workflow.
 * Nothing in here is hard-coded — every default is a {@code @DefaultValue}
 * so the app boots without config but every value can be overridden via
 * YAML / env var.
 *
 * <pre>
 * migration:
 *   apply:
 *     # Prefix the system suggests when the user accepts the auto-generated
 *     # branch name.  The session id is appended.  Never used unless the
 *     # user accepts the suggestion.
 *     branch-name-prefix: altrix/migration-
 *     # Default commit message template — {sessionId}, {strategy}, {date}
 *     # placeholders supported.
 *     commit-message-template: "chore(altrix): apply migration result {sessionId}"
 *     # Default PR title.  Same placeholders as commit-message-template.
 *     pr-title-template: "Altrix migration — {sessionId}"
 *     # Default PR body.  Markdown.
 *     pr-body-template: |
 *       Automated migration produced by Altrix.
 *
 *       Session: {sessionId}
 *     # Branch-naming regex.  Default mirrors `git check-ref-format` reasonably.
 *     branch-name-pattern: ^[A-Za-z0-9._/-]{1,128}$
 *     # Lifetime of the server-issued confirmation token.  After this the
 *     # user must re-confirm.
 *     confirmation-token-ttl: PT15M
 * </pre>
 */
@ConfigurationProperties(prefix = "migration.apply")
public record MigrationApplyConfig(
        @DefaultValue("altrix/migration-")
        String branchNamePrefix,

        @DefaultValue("chore(altrix): apply migration result {sessionId}")
        String commitMessageTemplate,

        @DefaultValue("Altrix migration — {sessionId}")
        String prTitleTemplate,

        @DefaultValue("Automated migration produced by Altrix.\n\nSession: {sessionId}\n")
        String prBodyTemplate,

        @DefaultValue("^[A-Za-z0-9._/-]{1,128}$")
        String branchNamePattern,

        @DefaultValue("PT15M")
        Duration confirmationTokenTtl
) {

    public MigrationApplyConfig {
        if (branchNamePrefix      == null) branchNamePrefix      = "altrix/migration-";
        if (commitMessageTemplate == null) commitMessageTemplate = "chore(altrix): apply migration result {sessionId}";
        if (prTitleTemplate       == null) prTitleTemplate       = "Altrix migration — {sessionId}";
        if (prBodyTemplate        == null) prBodyTemplate        = "Automated migration produced by Altrix.\n\nSession: {sessionId}\n";
        if (branchNamePattern     == null) branchNamePattern     = "^[A-Za-z0-9._/-]{1,128}$";
        if (confirmationTokenTtl  == null) confirmationTokenTtl  = Duration.ofMinutes(15);
    }

    /** Pre-compiled view of the branch-name regex — created on first call. */
    public Pattern compiledBranchNamePattern() {
        return Pattern.compile(branchNamePattern);
    }

    public boolean isValidBranchName(String name) {
        return name != null && !name.isBlank() && compiledBranchNamePattern().matcher(name).matches();
    }

    /** Renders a template, replacing {@code {sessionId}}. */
    public String renderTemplate(String template, String sessionId) {
        if (template == null) return "";
        return template.replace("{sessionId}", sessionId == null ? "" : sessionId);
    }
}
