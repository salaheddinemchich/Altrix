export type ProviderCostTier = 'FREE' | 'PAID';

export interface ProviderConfig {
  providerId: string;
  costTier: ProviderCostTier;
  effectiveEnabled: boolean;
  hasCustomApiKey: boolean;
  effectiveModelAnalysis: string;
  effectiveModelMigration: string;
  updatedAt: string | null;
}

export interface SaveProviderConfig {
  enabled: boolean | null;
  apiKey: string | null;
  baseUrl: string | null;
  modelAnalysis: string | null;
  modelMigration: string | null;
}
