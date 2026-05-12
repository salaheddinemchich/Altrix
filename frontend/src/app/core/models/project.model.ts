export type BuildSystem = 'GRADLE_KOTLIN' | 'GRADLE_GROOVY' | 'MAVEN' | 'NPM' | 'UNKNOWN';
export type ConfigFormat = 'YAML' | 'PROPERTIES' | 'JSON' | 'UNKNOWN';
export type DetectedFramework = 'SPRING_BOOT' | 'SPRING_FRAMEWORK' | 'JAKARTA_EE' | 'JAVA_EE' | 'NONE' | 'UNKNOWN';
export type ProjectStatus = 'PENDING' | 'REGISTERED' | 'PROCESSING' | 'READY' | 'FAILED' | 'ERROR';
export type ConfigFormatPreference = 'KEEP_ORIGINAL' | 'YAML' | 'PROPERTIES';

export interface Project {
  id: string;
  name: string;
  status: ProjectStatus;
  buildSystem: BuildSystem | null;
  configFormat: ConfigFormat | null;
  framework: DetectedFramework | null;
  eligibleForMigration: boolean;
  detectedTechnologies: string[];
  createdAt: string;
}
