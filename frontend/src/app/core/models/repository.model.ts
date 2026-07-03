export interface GitHubRepo {
  id: number;
  name: string;
  fullName: string;
  defaultBranch: string;
  privateRepo: boolean;
  description: string | null;
  htmlUrl: string;
  language: string | null;
}

export type ConfigFormatPreference = 'KEEP_ORIGINAL' | 'YAML' | 'PROPERTIES';

export type JakartaMessagingTarget = 'NATIVE_KAFKA_CLIENTS' | 'SPRING_KAFKA_HYBRID';

export interface IngestProjectRequest {
  repoFullName: string;
  defaultBranch: string;
  configFormatPreference?: ConfigFormatPreference;
  jakartaMessagingTarget?: JakartaMessagingTarget;
}
