/**
 * Production environment.
 *
 * In production all backend services sit behind a single gateway, so the API
 * base URLs collapse to one origin.
 */
export const environment = {
  production: true,
  api: {
    project:      '/api',
    job:          '/api',
    orchestrator: '/api'
  }
};
