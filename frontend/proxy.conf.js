/**
 * Angular dev-server proxy (#110).
 *
 * Forwards backend traffic to the right service so the dev server can use
 * relative URLs (`/api/v1/jobs`, `/ws`, …) without CORS.  Mirrors the URL
 * shape production will take once platform-gateway (#24) is live; the
 * gateway will collapse the three explicit routes below into a single
 * upstream, but the frontend code does not need to change.
 *
 * To opt in from frontend code, set every `environment.api.*` URL to an
 * empty string — the existing absolute URLs continue to work without the
 * proxy.  This file is wired via angular.json -> serve.options.proxyConfig.
 */
module.exports = [
  // platform-project (port 8082) — projects + webhooks
  {
    context: ['/api/v1/projects', '/api/v1/webhooks'],
    target: 'http://localhost:8082',
    secure: false,
    changeOrigin: true,
    logLevel: 'warn',
  },

  // platform-job (port 8083) — migration jobs
  {
    context: ['/api/v1/jobs'],
    target: 'http://localhost:8083',
    secure: false,
    changeOrigin: true,
    logLevel: 'warn',
  },

  // platform-orchestrator (port 8084) — auth, sessions, AI, billing,
  // repositories, GitHub ingestion, WebSocket
  {
    context: [
      '/api/v1/auth',
      '/api/v1/sessions',
      '/api/v1/billing',
      '/api/v1/repositories',
      '/api/v1/projects/from-github',
      '/api/ai',
      '/login',
      '/oauth2',
    ],
    target: 'http://localhost:8084',
    secure: false,
    changeOrigin: true,
    logLevel: 'warn',
  },

  // STOMP / SockJS WebSocket — ws: true keeps the upgrade handshake intact
  {
    context: ['/ws'],
    target: 'http://localhost:8084',
    secure: false,
    changeOrigin: true,
    ws: true,
    logLevel: 'warn',
  },
];
