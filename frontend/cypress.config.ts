import { defineConfig } from 'cypress';

/**
 * Cypress E2E configuration (#112).
 *
 * Runs against the local `ng serve` dev server.  Spec files live in
 * `cypress/e2e/*.cy.ts`.  Custom commands (e.g. cy.login()) are in
 * `cypress/support/commands.ts`.
 */
export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:4200',
    supportFile: 'cypress/support/e2e.ts',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    fixturesFolder: 'cypress/fixtures',
    screenshotsFolder: 'cypress/screenshots',
    videosFolder: 'cypress/videos',
    video: false,
    viewportWidth: 1280,
    viewportHeight: 800,
    defaultCommandTimeout: 6000,
    retries: { runMode: 1, openMode: 0 },
  },
});
