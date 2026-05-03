export type BuildSystem = 'GRADLE' | 'MAVEN' | 'NPM' | 'UNKNOWN';
export type ConfigFormat = 'YAML' | 'PROPERTIES' | 'JSON' | 'UNKNOWN';
export type DetectedFramework = 'SPRING_BOOT' | 'NONE' | 'UNKNOWN';
export type ProjectStatus = 'REGISTERED' | 'PROCESSING' | 'READY' | 'FAILED';
export type ConfigFormatPreference = 'KEEP_ORIGINAL' | 'YAML' | 'PROPERTIES';

export interface Project {
  id: string;
  name: string;
  status: ProjectStatus;
  buildSystem: BuildSystem;
  configFormat: ConfigFormat;
  framework: DetectedFramework;
  createdAt: string;
}
