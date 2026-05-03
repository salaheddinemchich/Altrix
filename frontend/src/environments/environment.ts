/**
 * Development environment.
 *
 * Each backend microservice is reached on its own port during local dev.
 * In production, these will all sit behind the platform-gateway (issue #24).
 */
export const environment = {
  production: false,
  api: {
    project:      'http://localhost:8082',
    job:          'http://localhost:8083',
    orchestrator: 'http://localhost:8084'
  }
};
