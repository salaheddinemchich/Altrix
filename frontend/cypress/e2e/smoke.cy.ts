/// <reference types="cypress" />

/**
 * Smoke spec (#112) — confirms the SPA shell loads and the unauthenticated
 * landing screen renders.  Per-feature specs (jobs list, approval flow,
 * diff viewer) live in their own files alongside this one.
 *
 * The orchestrator REST endpoints are stubbed via cy.intercept so this
 * suite runs without a live backend; integration-flavoured specs will
 * remove the stubs and require a running `docker-compose up` + services.
 */
describe('Altrix shell — smoke', () => {
  beforeEach(() => {
    cy.intercept('GET', '/api/v1/auth/me', { fixture: 'user.json' }).as('me');
    cy.intercept('GET', '/api/v1/sessions*', {
      body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    }).as('sessions');
    cy.intercept('GET', '/api/v1/jobs*', { body: { content: [], totalElements: 0 } }).as('jobs');
  });

  it('renders the login screen on an unauthenticated visit', () => {
    cy.visit('/');
    // Either the OAuth provider buttons or the brand mark should appear —
    // the exact selector is deliberately broad so a future redesign
    // doesn't fail the smoke unless the page itself stops rendering.
    cy.get('body').should('be.visible');
    cy.contains(/altrix/i).should('exist');
  });

  it('reaches the dashboard once a JWT is in sessionStorage', () => {
    cy.login();
    cy.visit('/');
    // Wait for the auth XHR; the shell flips to the authenticated tree
    // immediately after.
    cy.wait('@me');
    cy.get('body').should('be.visible');
  });
});
