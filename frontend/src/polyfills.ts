/**
 * Browser polyfills.
 *
 * <p>`sockjs-client` (used by the live-pipeline WebSocket bridge) is published
 * as a CommonJS module that references Node's `global` object. Browsers have
 * `window` instead — without this shim the JobDetail page crashes with
 * "ReferenceError: global is not defined" before any Angular code runs.
 */
(window as any).global = window;

import 'zone.js';
