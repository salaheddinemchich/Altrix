package com.altrix.orchestrator.infra.ai.provider;

/**
 * Distinguishes analysis-grade calls (cheap, fast) from migration-grade calls
 * (powerful, higher quality). The router picks the appropriate model per tier.
 */
public enum ProviderTier {
    /**
     * Lightweight analysis: ContextAnalyzer, MigrationPlanner. Small, fast model.
     */
    ANALYSIS,
    /**
     * Code rewriting: CoreMigrator, SandboxValidator. Powerful, quality-first model.
     */
    MIGRATION
}
