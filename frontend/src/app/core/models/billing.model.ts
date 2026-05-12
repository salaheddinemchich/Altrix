export interface AiCallUsageSummary {
  agentName: string;
  providerName: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCostUsd: number;
  callCount: number;
}

export interface TokenUsageSummary {
  providerId: string;
  tier: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  callCount: number;
  estimatedCostUsd: number;
}
