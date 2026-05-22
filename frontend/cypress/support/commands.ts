/// <reference types="cypress" />

import 'cypress';

/**
 * Custom Cypress commands (#112).
 *
 * `cy.login()` seeds the JWT into sessionStorage under the same key the
 * AuthService uses (`altrix_access_token`), then visits the dashboard.
 * Lets specs skip the GitHub OAuth dance — auth is exercised by the
 * dedicated auth specs only.
 */

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable<Subject = any> {
      /**
       * Seeds a fake JWT in sessionStorage so the AuthInterceptor passes
       * the X-User-Id header on subsequent XHRs.  Loads the token from
       * cypress/fixtures/user.json.
       */
      login(): Chainable<void>;
    }
  }
}

Cypress.Commands.add('login', () => {
  cy.fixture('user.json').then((user: { accessToken: string }) => {
    cy.window().then(win => {
      win.sessionStorage.setItem('altrix_access_token', user.accessToken);
    });
  });
});

export {};
