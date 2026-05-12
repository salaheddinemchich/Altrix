export type PipelineNodeStatus = 'PENDING' | 'ACTIVE' | 'DONE' | 'ERROR';

export interface ProgressEvent {
  jobId: string;
  agentName: string;
  status: string;
  message?: string;
  timestamp: string;
}

export interface PipelineNode {
  id: string;
  label: string;
  description: string;
  status: PipelineNodeStatus;
  agents: string[];
  message?: string;
  updatedAt?: string;
}

export const DEFAULT_PIPELINE: PipelineNode[] = [
  { id: 'analyze',  label: 'Analyse',  description: 'Scanning project & detecting services',
    status: 'PENDING', agents: ['contextAnalyzerAgent', 'ArchitectureAnalyzerAgent'] },
  { id: 'plan',     label: 'Plan',     description: 'Generating migration plan',
    status: 'PENDING', agents: ['migrationPlannerAgent', 'MigrationPlannerAgent'] },
  { id: 'migrate',  label: 'Migrate',  description: 'Rewriting Pub/Sub → Kafka',
    status: 'PENDING', agents: ['typedCoreMigratorAgent', 'CoreMigratorAgent'] },
  { id: 'validate', label: 'Validate', description: 'Sandbox build & test',
    status: 'PENDING', agents: ['sandboxValidatorAgent'] },
  { id: 'report',   label: 'Report',   description: 'Final migration report',
    status: 'PENDING', agents: ['reportGeneratorAgent'] },
];
