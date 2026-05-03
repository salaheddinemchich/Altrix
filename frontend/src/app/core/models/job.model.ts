import { ConfigFormatPreference } from './project.model';

export type JobStatus =
  | 'PENDING'
  | 'ANALYZING'
  | 'MIGRATING'
  | 'DONE'
  | 'FAILED';

export interface Job {
  id: string;
  projectId: string;
  userId: string;
  status: JobStatus;
  configFormatPreference: ConfigFormatPreference | null;
  outputStorageKey: string | null;
  errorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface JobStatusResponse {
  jobId: string;
  status: JobStatus;
}
