import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { Subscription } from 'rxjs';
import {
  DEFAULT_PIPELINE,
  PipelineNode,
  PipelineNodeStatus,
  ProgressEvent,
} from '../../core/models/pipeline.model';
import { PipelineService } from '../../core/services/pipeline.service';
import { SessionService } from '../../core/services/session.service';
import { FileProvenance, MigrationPlan, MigrationReport, SandboxLog } from '../../core/models/session.model';
import { IconComponent } from '../icon/icon.component';
import { PlanPreviewComponent } from '../plan-preview/plan-preview.component';
import { catchError, of } from 'rxjs';

/**
 * Issue #114 — vertical session timeline.
 *
 * <p>Sibling visualisation to the horizontal {@code PipelineGraphComponent}:
 * the graph gives a fast at-a-glance view, this timeline gives the textual
 * detail (latest message + elapsed time per step).  Both share
 * {@link PipelineService} so a single WebSocket connection drives them
 * together.
 */
@Component({
  selector: 'app-session-timeline',
  standalone: true,
  imports: [DatePipe, IconComponent, PlanPreviewComponent],
  templateUrl: './session-timeline.component.html',
  styleUrl: './session-timeline.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionTimelineComponent implements OnDestroy {
  private readonly pipelineApi = inject(PipelineService);
  private readonly sessionApi  = inject(SessionService);

  readonly jobId = input.required<string>();

  /**
   * Latest known session/job status — drives a backfill so a timeline opened
   * AFTER the job already ran shows the historical progress, not all-PENDING.
   * The WebSocket only emits live events; without this input a DONE job would
   * look frozen forever.
   */
  readonly currentStatus = input<string | null>(null);

  /**
   * Session id — when present, the Validate and Migrate steps render
   * expandable panels (sandbox logs, per-file doc provenance) that
   * lazy-load via SessionService.  Without it, those toggles are hidden.
   * Optional so the timeline stays reusable in places where the session
   * id isn't readily available.
   */
  readonly sessionId = input<string | null>(null);

  /**
   * The migration plan awaiting review — when present and {@code
   * currentStatus() === 'AWAITING_APPROVAL'}, the Plan step renders it
   * inline (plus Approve/Reject when {@code canApprove()}) so a reviewer
   * sees and acts on the plan right where it was produced, instead of in a
   * separate generic session card.
   */
  readonly plan = input<MigrationPlan | null>(null);
  /**
   * Gates the Approve/Reject buttons + plan editing.  Matches the Sessions
   * list page's prior behaviour: visible to any authenticated user (no
   * admin check) since reaching this page already requires authGuard.
   */
  readonly canApprove = input<boolean>(false);
  /** True while a plan edit save is in flight — passed straight to app-plan-preview. */
  readonly savingPlan = input<boolean>(false);

  readonly planSaved = output<MigrationPlan>();
  readonly approveClicked = output<void>();
  readonly rejectClicked = output<void>();

  // ── Per-file RAG provenance lazy-load state (#1) ─────────────────────────
  /** Whether the per-file provenance panel is expanded on the Migrate step. */
  readonly fileProvenanceExpanded = signal<boolean>(false);
  readonly fileProvenance = signal<FileProvenance | null>(null);
  readonly fileProvenanceLoading = signal<boolean>(false);
  readonly fileProvenanceMissing = signal<boolean>(false);
  /** Which file's docs are currently shown in the right pane.  null = none. */
  readonly selectedProvenanceFile = signal<string | null>(null);

  /** Sorted list of files that have provenance entries — drives the left list. */
  readonly provenanceFiles = computed<string[]>(() => {
    const p = this.fileProvenance();
    if (!p) return [];
    return Object.keys(p.perFile).sort();
  });

  /** Docs the AI used for the currently-selected file. */
  readonly selectedProvenanceDocs = computed(() => {
    const p = this.fileProvenance();
    const f = this.selectedProvenanceFile();
    if (!p || !f) return [];
    return p.perFile[f] ?? [];
  });

  // ── Final report lazy-load state ─────────────────────────────────────────
  readonly reportExpanded = signal<boolean>(false);
  readonly report = signal<MigrationReport | null>(null);
  readonly reportLoading = signal<boolean>(false);
  readonly reportMissing = signal<boolean>(false);

  /**
   * Rendered HTML for the report's Markdown content (see
   * renderReportMarkdown() below).  Bound via plain [innerHTML] — NOT
   * DomSanitizer.bypassSecurityTrustHtml — so Angular's built-in sanitizer
   * stays in the loop as a safety net: file paths and messages embedded in
   * the report originate from the user's uploaded project, so they're
   * untrusted input even though renderReportMarkdown() HTML-escapes them
   * before reintroducing any markup.
   */
  readonly reportHtml = computed<string>(() => {
    const r = this.report();
    return r ? renderReportMarkdown(r.content) : '';
  });

  /**
   * Toggles the "view full report" panel on the Report step.  Fetches once
   * on first open (the report is append-only / final once DONE, so there's
   * nothing to live-refresh here unlike the sandbox logs).
   */
  toggleReport(): void {
    const wasOpen = this.reportExpanded();
    this.reportExpanded.set(!wasOpen);
    if (wasOpen) return;
    if (this.report() !== null || this.reportMissing()) return;
    const id = this.sessionId();
    if (!id) return;

    this.reportLoading.set(true);
    this.sessionApi.getReport(id)
      .pipe(catchError(() => of<MigrationReport | null>(null)))
      .subscribe(r => {
        this.reportLoading.set(false);
        if (r) this.report.set(r);
        else   this.reportMissing.set(true);
      });
  }

  reportStatusClass(status: string): string {
    switch (status) {
      case 'SUCCESS': return 'success';
      case 'PARTIAL': return 'warning';
      case 'FAILED':  return 'danger';
      default:        return '';
    }
  }

  /**
   * Exports the report as a PDF via the browser's native print pipeline:
   * opens the rendered report in a new tab with print-only styling, then
   * invokes window.print() so the user picks "Save as PDF" in the print
   * dialog.  No client-side PDF library needed — every modern browser's
   * print dialog already does this conversion, and it's the only way to
   * produce a PDF from the client without asking the browser to silently
   * write a file (which print-dialog-less approaches can't do safely).
   */
  downloadReportPdf(): void {
    const r = this.report();
    if (!r) return;
    const win = window.open('', '_blank');
    if (!win) return;
    win.document.write(buildReportPrintDocument(r.projectId, renderReportMarkdown(r.content)));
    win.document.close();
    win.focus();
    // A short delay lets the new document finish layout before the print
    // dialog opens — calling print() synchronously right after write()
    // sometimes renders a blank page in Chromium.
    setTimeout(() => win.print(), 300);
  }

  // ── Sandbox logs lazy-load state (#106) ──────────────────────────────────
  readonly sandboxLogsExpanded = signal<boolean>(false);
  readonly sandboxLogs = signal<SandboxLog[]>([]);
  readonly sandboxLogsLoading = signal<boolean>(false);
  readonly sandboxLogsMissing = signal<boolean>(false);
  /** Which runner's log the viewer currently shows.  null = no selection. */
  readonly selectedRunnerId = signal<string | null>(null);

  readonly selectedSandboxLog = computed<SandboxLog | null>(() => {
    const id = this.selectedRunnerId();
    return id ? this.sandboxLogs().find(l => l.runnerId === id) ?? null : null;
  });

  // #108 — log filter/search.  Pure client-side filtering because the log
  // already lives in memory and the volume is bounded by the Docker runner's
  // capture (which caps at the container's stdout).
  //
  // Search supports real-world operators:
  //   foo bar      → AND   (line must contain BOTH "foo" and "bar")
  //   "foo bar"    → exact phrase match
  //   -foo         → exclude lines containing "foo"
  // All case-insensitive.  Severity chips (ERROR/WARN/INFO) compose with the
  // text query, and clickable keyword chips (auto-extracted from the log)
  // toggle terms into the query.
  readonly logFilterQuery = signal<string>('');
  /** Severity filter — 'all' lets everything through; others keep matching lines only. */
  readonly logFilterLevel = signal<'all' | 'error' | 'warn' | 'info'>('all');

  /** Parsed query — recomputed whenever the raw query changes. */
  private readonly parsedQuery = computed<ParsedLogQuery>(() => parseLogQuery(this.logFilterQuery()));

  /** Filtered lines (array form — used by the highlighter + count). */
  readonly filteredLogLines = computed<string[]>(() => {
    const log = this.selectedSandboxLog();
    if (!log || !log.content) return [];
    const q = this.parsedQuery();
    const level = this.logFilterLevel();
    if (q.empty && level === 'all') return log.content.split('\n');

    return log.content.split('\n').filter(line => {
      if (!matchesLevel(line, level)) return false;
      return matchesQuery(line.toLowerCase(), q);
    });
  });

  /** Plain-text view (kept for the line-count + any non-HTML consumer). */
  readonly filteredSandboxLog = computed<string>(() => this.filteredLogLines().join('\n'));

  /**
   * HTML view with matched terms wrapped in &lt;mark&gt;.  Bound via
   * [innerHTML]; every line is HTML-escaped first so log content can never
   * inject markup, then only our own &lt;mark&gt; tags are added back.
   */
  readonly filteredSandboxLogHtml = computed<string>(() => {
    const q = this.parsedQuery();
    const terms = [...q.includes, ...q.phrases]; // don't highlight exclusions
    return this.filteredLogLines()
      .map(line => highlightLine(line, terms))
      .join('\n');
  });

  /** Convenience for the template — total lines after filter, for the count badge. */
  readonly filteredLineCount = computed<number>(() => this.filteredLogLines().length);

  /**
   * Auto-extracted clickable keywords from the selected log.  Picks the most
   * frequent "interesting" tokens — class names, file names, qualified
   * packages — that a developer would actually want to filter on.  Capped so
   * the chip row stays compact.
   */
  readonly logKeywords = computed<string[]>(() => {
    const log = this.selectedSandboxLog();
    if (!log || !log.content) return [];
    return extractKeywords(log.content, 12);
  });

  setLogFilterLevel(level: 'all' | 'error' | 'warn' | 'info'): void {
    this.logFilterLevel.set(level);
  }

  setLogFilterQuery(query: string): void {
    this.logFilterQuery.set(query);
  }

  /** Toggles a keyword in/out of the query — click to add, click again to remove. */
  toggleKeyword(kw: string): void {
    const current = this.logFilterQuery().trim();
    const tokens = current.length ? current.split(/\s+/) : [];
    const idx = tokens.findIndex(t => t.toLowerCase() === kw.toLowerCase());
    if (idx >= 0) {
      tokens.splice(idx, 1);
    } else {
      tokens.push(kw);
    }
    this.logFilterQuery.set(tokens.join(' '));
  }

  /** True when a keyword chip is currently part of the active query. */
  isKeywordActive(kw: string): boolean {
    const tokens = this.logFilterQuery().toLowerCase().split(/\s+/);
    return tokens.includes(kw.toLowerCase());
  }

  /** Clears both filters in one click. */
  clearLogFilter(): void {
    this.logFilterQuery.set('');
    this.logFilterLevel.set('all');
  }

  readonly steps = signal<TimelineStep[]>(initialSteps());
  readonly now   = signal<number>(Date.now());

  /** Re-emitted every second so elapsed times tick while a step is ACTIVE. */
  private readonly tickHandle: ReturnType<typeof setInterval>;
  /** Tick counter for throttling the live sandbox-log refresh. */
  private logRefreshTick = 0;
  /** Tick counter for throttling the live file-provenance refresh. */
  private provenanceRefreshTick = 0;

  readonly hasActive = computed(() =>
    this.steps().some(s => s.status === 'ACTIVE')
  );

  // ── Issue #115 — overall progress + ETA ─────────────────────────────────

  /**
   * Done steps count fully; an ACTIVE step counts as half-done so the bar
   * advances during a long agent run instead of jumping in 20% chunks.
   */
  readonly percentComplete = computed(() => {
    const list = this.steps();
    if (list.length === 0) return 0;
    const score = list.reduce((sum, s) => {
      if (s.status === 'DONE')   return sum + 1;
      if (s.status === 'ACTIVE') return sum + 0.5;
      return sum;
    }, 0);
    return Math.round((score / list.length) * 100);
  });

  /**
   * Heuristic ETA derived from observed runtimes on this run:
   *   avg(elapsed of DONE steps) × remaining (PENDING + ACTIVE) steps.
   * Returns null when no step has completed yet (no signal to project from).
   */
  readonly etaSeconds = computed<number | null>(() => {
    const list = this.steps();
    const now  = this.now();
    const completed = list.filter(s => s.status === 'DONE' && s.startedAt && s.endedAt);
    if (completed.length === 0) return null;

    const avgMs = completed.reduce((sum, s) => sum + (s.endedAt! - s.startedAt!), 0) / completed.length;
    const remaining = list.filter(s => s.status === 'PENDING' || s.status === 'ACTIVE').length;
    if (remaining === 0) return 0;

    // Subtract elapsed-so-far on any ACTIVE step from the projection so the
    // ETA shrinks while we watch it.
    const activeElapsed = list
      .filter(s => s.status === 'ACTIVE' && s.startedAt)
      .reduce((sum, s) => sum + (now - s.startedAt!), 0);

    const projectedMs = Math.max(0, avgMs * remaining - activeElapsed);
    return Math.round(projectedMs / 1000);
  });

  etaLabel(): string | null {
    const s = this.etaSeconds();
    if (s === null) return null;
    if (s === 0)    return 'almost done';
    return '~' + formatDuration(s * 1000) + ' remaining';
  }

  private sub: Subscription | null = null;

  constructor() {
    this.tickHandle = setInterval(() => {
      // Only repaint while a step is running — saves a render per second
      // once everything is DONE/ERROR.
      if (this.hasActive()) this.now.set(Date.now());
      // Live-tail the sandbox logs while validation is still in progress
      // so the user sees Maven download + Spring Boot startup output
      // appearing in real time instead of an empty box for 8 minutes.
      // Throttled to every 3 ticks (~3s) to keep network chatter low.
      this.maybeRefreshSandboxLogs();
      // Same pattern for the per-file RAG provenance — the migrator now
      // persists per-file as it goes, so the panel streams in instead
      // of dropping all at once at the end of Migrate.
      this.maybeRefreshFileProvenance();
    }, 1000);

    // Angular 18 forbids writing to signals from an effect by default
    // (NG0600).  We intentionally seed `steps` here from jobId + currentStatus
    // before live WS events arrive — without allowSignalWrites the effect
    // throws and the backfill silently doesn't run.
    effect(() => {
      const id = this.jobId();
      const status = this.currentStatus();
      this.sub?.unsubscribe();
      // Reset + backfill atomically so an in-flight WebSocket apply() can't
      // race the reset and leave the timeline in an inconsistent state.
      this.steps.set(backfillSteps(initialSteps(), status));
      if (!id) return;
      this.sub = this.pipelineApi.watch(id).subscribe(evt => this.apply(evt));
    }, { allowSignalWrites: true });
  }


  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    clearInterval(this.tickHandle);
  }

  // ── View helpers ─────────────────────────────────────────────────────────
  statusIcon(s: PipelineNodeStatus): string {
    switch (s) {
      case 'DONE':   return 'check';
      case 'ERROR':  return 'alert';
      case 'ACTIVE': return 'refresh';
      default:       return 'clock';
    }
  }

  elapsedLabel(step: TimelineStep): string {
    if (!step.startedAt) return '';
    const end = step.endedAt ?? this.now();
    const ms  = Math.max(0, end - step.startedAt);
    return formatDuration(ms);
  }

  /**
   * Toggles the "view sandbox logs" panel on the Validate step.  Same
   * lazy-load pattern as the RAG manifest — fetch on first open, then
   * just flip visibility.  Auto-selects the first log so the viewer
   * has something to show without an extra click.
   */
  toggleSandboxLogs(): void {
    const wasOpen = this.sandboxLogsExpanded();
    this.sandboxLogsExpanded.set(!wasOpen);
    if (wasOpen) return;
    if (this.sandboxLogs().length > 0 || this.sandboxLogsMissing()) return; // already loaded
    const id = this.sessionId();
    if (!id) return;

    this.sandboxLogsLoading.set(true);
    this.sessionApi.getSandboxLogs(id)
      .pipe(catchError(() => of<SandboxLog[]>([])))
      .subscribe(logs => {
        this.sandboxLogsLoading.set(false);
        if (logs && logs.length > 0) {
          this.sandboxLogs.set(logs);
          this.selectedRunnerId.set(logs[0].runnerId);
        } else {
          this.sandboxLogsMissing.set(true);
        }
      });
  }

  /** Switch which runner's log is shown in the viewer. */
  selectRunner(runnerId: string): void {
    this.selectedRunnerId.set(runnerId);
  }

  /**
   * Called every tick.  Refetches the sandbox-logs endpoint when ALL of:
   *  - the panel is currently expanded;
   *  - at least one step is still ACTIVE (typically Validate);
   *  - the throttle window (3s) has elapsed.
   * Without these gates we'd hammer the API once a second for every open
   * timeline in the app.  Once validation finishes, refresh stops on its
   * own because hasActive() becomes false.
   */
  private maybeRefreshSandboxLogs(): void {
    if (!this.sandboxLogsExpanded()) return;
    if (!this.hasActive()) return;
    this.logRefreshTick = (this.logRefreshTick + 1) % 3;
    if (this.logRefreshTick !== 0) return;
    const id = this.sessionId();
    if (!id) return;

    this.sessionApi.getSandboxLogs(id)
      .pipe(catchError(() => of<SandboxLog[]>([])))
      .subscribe(logs => {
        if (!logs || logs.length === 0) return;
        // Only replace the in-memory list when the content actually changed
        // — saves an OnPush re-render on every tick when nothing's new.
        const current = this.sandboxLogs();
        const same = current.length === logs.length
                  && current.every((c, i) => c.runnerId === logs[i].runnerId
                                          && c.content.length === logs[i].content.length);
        if (same) return;
        this.sandboxLogs.set(logs);
        // Preserve the user's tab selection if it's still present.
        if (this.selectedRunnerId() == null
            || !logs.some(l => l.runnerId === this.selectedRunnerId())) {
          this.selectedRunnerId.set(logs[0].runnerId);
        }
      });
  }

  /**
   * Toggles the "based on which docs" panel (#1) on the Migrate step.
   * Lazy-fetches once, then just flips visibility on subsequent toggles.
   * Auto-selects the first file in the trace so the right pane has
   * something to render without an extra click.
   */
  toggleFileProvenance(): void {
    const wasOpen = this.fileProvenanceExpanded();
    this.fileProvenanceExpanded.set(!wasOpen);
    if (wasOpen) return;
    if (this.fileProvenance() !== null || this.fileProvenanceMissing()) return;
    const id = this.sessionId();
    if (!id) return;

    this.fileProvenanceLoading.set(true);
    this.sessionApi.getFileProvenance(id)
      .pipe(catchError(() => of<FileProvenance | null>(null)))
      .subscribe(p => {
        this.fileProvenanceLoading.set(false);
        if (p && p.perFile && Object.keys(p.perFile).length > 0) {
          this.fileProvenance.set(p);
          this.selectedProvenanceFile.set(Object.keys(p.perFile).sort()[0]);
        } else if (!this.hasActive()) {
          // Permanently empty only when nothing is still running — otherwise
          // the live-tail will populate it as files are migrated.
          this.fileProvenanceMissing.set(true);
        }
      });
  }

  /** Picks which file's docs the right pane should show. */
  selectProvenanceFile(path: string): void {
    this.selectedProvenanceFile.set(path);
  }

  /**
   * Tick-driven auto-refresh for the provenance panel.  Mirrors the
   * sandbox-log live-tail: only fires when the panel is open AND a step
   * is still active, throttled to ~3 s.  The backend writes provenance
   * incrementally per migrated file, so each fetch gradually fills the
   * left-hand list in real time.
   */
  private maybeRefreshFileProvenance(): void {
    if (!this.fileProvenanceExpanded()) return;
    if (!this.hasActive()) return;
    this.provenanceRefreshTick = (this.provenanceRefreshTick + 1) % 3;
    if (this.provenanceRefreshTick !== 0) return;
    const id = this.sessionId();
    if (!id) return;

    this.sessionApi.getFileProvenance(id)
      .pipe(catchError(() => of<FileProvenance | null>(null)))
      .subscribe(p => {
        if (!p || !p.perFile) return;
        const incomingKeys = Object.keys(p.perFile);
        if (incomingKeys.length === 0) return;
        // Skip re-render when the set of files hasn't grown — the
        // typical "still-running" tick.
        const current = this.fileProvenance();
        if (current && Object.keys(current.perFile).length === incomingKeys.length) return;
        this.fileProvenance.set(p);
        // Auto-select the first file if the user hasn't picked one yet.
        if (this.selectedProvenanceFile() == null && incomingKeys.length > 0) {
          this.selectedProvenanceFile.set(incomingKeys.sort()[0]);
        }
        // First time we see provenance: clear the "missing" flag we may
        // have set when the panel was opened before the migrator had
        // produced anything.
        if (this.fileProvenanceMissing()) this.fileProvenanceMissing.set(false);
      });
  }

  // ── Event application ────────────────────────────────────────────────────
  private apply(evt: ProgressEvent): void {
    const status = (evt.status ?? '').toUpperCase();
    const agent  = evt.agentName;
    const epoch  = parseTimestamp(evt.timestamp);

    this.steps.update(list => {
      const idx = list.findIndex(s => s.agents.includes(agent));
      if (idx < 0) return list;

      const next = [...list];
      const mapped = mapStatus(status);

      next[idx] = {
        ...next[idx],
        status:    mapped,
        message:   evt.message ?? next[idx].message,
        startedAt: next[idx].startedAt ?? epoch,
        endedAt:   (mapped === 'DONE' || mapped === 'ERROR') ? epoch : null,
        lastUpdate: epoch,
      };

      // Once a step reaches DONE / ACTIVE, any earlier still-PENDING step
      // must have completed in the meantime (the orchestrator skipped its
      // emission or it happened off-stream) — backfill them as DONE.
      if (mapped === 'DONE' || mapped === 'ACTIVE') {
        for (let i = 0; i < idx; i++) {
          if (next[i].status === 'PENDING') {
            next[i] = { ...next[i], status: 'DONE', endedAt: epoch };
          }
        }
      }
      return next;
    });
  }
}

// ── helpers ────────────────────────────────────────────────────────────────

interface TimelineStep extends PipelineNode {
  startedAt: number | null;
  endedAt:   number | null;
  lastUpdate?: number;
}

// ── log search (#108 enhancement) ────────────────────────────────────────────

/** Parsed shape of a log search query. All terms are pre-lowercased. */
interface ParsedLogQuery {
  /** Plain terms — a line must contain ALL of them (AND). */
  includes: string[];
  /** "quoted phrases" — exact substring match, also ANDed. */
  phrases: string[];
  /** -terms — a line must contain NONE of them. */
  excludes: string[];
  /** True when there's nothing to filter on. */
  empty: boolean;
}

/**
 * Parses a raw search string into include / phrase / exclude terms.
 *   foo bar     → includes ["foo","bar"]   (AND)
 *   "foo bar"   → phrases  ["foo bar"]
 *   -foo        → excludes ["foo"]
 * Quotes win over the leading '-', so `-"a b"` excludes the phrase "a b".
 */
function parseLogQuery(raw: string): ParsedLogQuery {
  const includes: string[] = [];
  const phrases: string[] = [];
  const excludes: string[] = [];
  // Tokenise: either a "quoted phrase" (optionally negated) or a bare token.
  const re = /(-?)"([^"]+)"|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(raw)) !== null) {
    if (m[2] !== undefined) {
      // quoted phrase
      const phrase = m[2].toLowerCase();
      if (m[1] === '-') excludes.push(phrase);
      else phrases.push(phrase);
    } else {
      let tok = m[3];
      if (tok.startsWith('-') && tok.length > 1) {
        excludes.push(tok.slice(1).toLowerCase());
      } else if (tok.length > 0) {
        includes.push(tok.toLowerCase());
      }
    }
  }
  const empty = includes.length === 0 && phrases.length === 0 && excludes.length === 0;
  return { includes, phrases, excludes, empty };
}

/** AND over includes+phrases, NOT over excludes.  `lineLower` is pre-lowercased. */
function matchesQuery(lineLower: string, q: ParsedLogQuery): boolean {
  for (const inc of q.includes) if (!lineLower.includes(inc)) return false;
  for (const ph of q.phrases)  if (!lineLower.includes(ph)) return false;
  for (const exc of q.excludes) if (lineLower.includes(exc)) return false;
  return true;
}

/** Severity-chip predicate, mirrors the Maven/Surefire line shapes. */
function matchesLevel(line: string, level: 'all' | 'error' | 'warn' | 'info'): boolean {
  if (level === 'all') return true;
  const u = line.toUpperCase();
  switch (level) {
    case 'error': return u.includes('[ERROR]') || u.includes('ERROR ') || u.includes(' ERROR');
    case 'warn':  return u.includes('[WARN')   || u.includes('WARNING') || u.includes(' WARN');
    case 'info':  return u.includes('[INFO]')  || u.includes(' INFO');
    default:      return true;
  }
}

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
};
function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
}
function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * HTML-escapes a log line, then wraps any occurrence of the search terms in
 * &lt;mark&gt;.  Safe to bind via [innerHTML]: the line is fully escaped
 * first, so only our own &lt;mark&gt; tags are ever live markup.
 */
function highlightLine(line: string, terms: string[]): string {
  const escaped = escapeHtml(line);
  const real = terms.map(t => t.trim()).filter(t => t.length > 0);
  if (real.length === 0) return escaped;
  // One alternation pass so overlapping terms don't double-wrap.
  const pattern = real.map(escapeRegExp).join('|');
  try {
    return escaped.replace(new RegExp(pattern, 'gi'), m => `<mark>${m}</mark>`);
  } catch {
    return escaped; // pathological regex — fail open to plain escaped text
  }
}

/**
 * Pulls the most useful filterable keywords out of a log: file names,
 * CamelCase class names, and dotted package/qualified names that appear at
 * least twice.  Ranked by frequency, capped at `limit`.
 */
function extractKeywords(content: string, limit: number): string[] {
  const counts = new Map<string, number>();
  // Candidate tokens: identifiers/paths of 4+ chars.
  const re = /[A-Za-z_][A-Za-z0-9_.]{3,}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(content)) !== null) {
    const tok = m[0];
    const interesting =
      tok.endsWith('.java') ||                 // file names
      /^[a-z]+(\.[a-z0-9]+){2,}/.test(tok) ||  // qualified package (a.b.c…)
      /[a-z][A-Z]/.test(tok);                  // CamelCase identifier
    if (!interesting) continue;
    // Skip noise: pure log boilerplate.
    if (/^(org\.apache\.maven|INFO|ERROR|WARNING)/i.test(tok)) continue;
    counts.set(tok, (counts.get(tok) ?? 0) + 1);
  }
  return [...counts.entries()]
    .filter(([, n]) => n >= 2)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, limit)
    .map(([tok]) => tok);
}

// ── Report Markdown rendering ────────────────────────────────────────────

/**
 * Minimal Markdown → HTML renderer scoped to exactly what
 * MigrationReportBuilder (backend) emits: `#`/`##`/`###` headers, GFM
 * tables (header row + `|---|---|` separator), `-`/`1.` lists, `**bold**`,
 * `_italic_`, `` `code` ``.  Not a general-purpose Markdown parser — a full
 * library is unnecessary since the input shape is fully known and
 * self-generated.
 *
 * <p>Every text-bearing line passes through {@link inline} which
 * HTML-escapes FIRST, then reintroduces markup — so file paths /
 * messages embedded in the report (sourced from the user's uploaded
 * project, hence untrusted) can never inject live HTML even though the
 * caller binds the result via plain [innerHTML].
 */
function renderReportMarkdown(md: string): string {
  const lines = md.split('\n');
  const out: string[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    const header = /^(#{1,4})\s+(.*)$/.exec(line);
    if (header) {
      const level = header[1].length;
      out.push(`<h${level}>${inline(header[2])}</h${level}>`);
      i++;
      continue;
    }

    if (line.trim().startsWith('|') && /^\s*\|[\s:-]+\|/.test(lines[i + 1] ?? '')) {
      const headCells = splitTableRow(line);
      i += 2;
      const bodyRows: string[][] = [];
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        bodyRows.push(splitTableRow(lines[i]));
        i++;
      }
      const thead = headCells.map(c => `<th>${inline(c)}</th>`).join('');
      const tbody = bodyRows
        .map(row => `<tr>${row.map(c => `<td>${inline(c)}</td>`).join('')}</tr>`)
        .join('');
      out.push(`<table class="report-table"><thead><tr>${thead}</tr></thead><tbody>${tbody}</tbody></table>`);
      continue;
    }

    if (/^\s*-\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*-\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*-\s+/, ''));
        i++;
      }
      out.push(`<ul>${items.map(it => `<li>${inline(it)}</li>`).join('')}</ul>`);
      continue;
    }

    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+\.\s+/, ''));
        i++;
      }
      out.push(`<ol>${items.map(it => `<li>${inline(it)}</li>`).join('')}</ol>`);
      continue;
    }

    if (/^\s*-{3,}\s*$/.test(line)) {
      out.push('<hr/>');
      i++;
      continue;
    }

    if (line.trim() === '') {
      i++;
      continue;
    }

    out.push(`<p>${inline(line)}</p>`);
    i++;
  }

  return out.join('\n');
}

function splitTableRow(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim());
}

/** HTML-escapes first, then reintroduces `**bold**`, `_italic_`, `` `code` `` as real markup. */
function inline(text: string): string {
  let t = escapeHtml(text);
  t = t.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/`([^`]+?)`/g, '<code>$1</code>');
  t = t.replace(/_(.+?)_/g, '<em>$1</em>');
  return t;
}

/** Self-contained HTML document (own <style>, no Angular styles) used for the PDF print window. */
function buildReportPrintDocument(projectId: string, bodyHtml: string): string {
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Migration Report — ${escapeHtml(projectId)}</title>
<style>
  body { font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif; color: #1a1a1a; max-width: 860px; margin: 32px auto; padding: 0 24px; }
  h1 { font-size: 22px; border-bottom: 2px solid #1a1a1a; padding-bottom: 8px; }
  h2 { font-size: 16px; margin-top: 28px; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
  h3, h4 { font-size: 13px; margin-top: 18px; }
  p { font-size: 12.5px; line-height: 1.6; }
  table { width: 100%; border-collapse: collapse; margin: 10px 0 16px; font-size: 12px; }
  th, td { border: 1px solid #ccc; padding: 6px 8px; text-align: left; vertical-align: top; }
  th { background: #f3f3f3; }
  code { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; font-family: ui-monospace, monospace; font-size: 11.5px; }
  ul, ol { font-size: 12.5px; line-height: 1.6; padding-left: 22px; }
  hr { border: none; border-top: 1px solid #ddd; margin: 20px 0; }
  em { color: #555; }
  @media print {
    body { margin: 0; padding: 16px; }
    h2 { page-break-after: avoid; }
    table, tr { page-break-inside: avoid; }
  }
</style>
</head>
<body>
${bodyHtml}
</body>
</html>`;
}

function initialSteps(): TimelineStep[] {
  return DEFAULT_PIPELINE.map(node => ({
    ...node,
    startedAt: null,
    endedAt:   null,
  }));
}

/**
 * Pure backfill: given a fresh step list and a coarse session/job status,
 * returns the step list with stages marked DONE / ACTIVE / ERROR so the
 * timeline reflects the current pipeline position even before any live
 * WebSocket event arrives.
 *
 * Steps: [0] Analyse  [1] Plan  [2] Migrate  [3] Validate  [4] Report
 *
 *   PENDING                              none
 *   ANALYZING                            Analyse ACTIVE
 *   CONTEXT_ANALYSED                     Analyse DONE, Plan ACTIVE
 *   PLAN_READY / AWAITING_APPROVAL       Analyse + Plan DONE (gated)
 *   MIGRATING                            +Migrate ACTIVE
 *   VALIDATING                           +Validate ACTIVE
 *   DONE / COMPLETED                     all DONE
 *   FAILED                               first non-DONE → ERROR
 *   PAUSED                               keep existing state
 */
function backfillSteps(base: TimelineStep[], status: string | null | undefined): TimelineStep[] {
  if (!status) return base;
  const s = status.toUpperCase();
  const epoch = Date.now();

  if (s === 'PAUSED') return base;

  if (s === 'FAILED' || s === 'ERROR') {
    const next = [...base];
    let errIdx = next.findIndex(n => n.status !== 'DONE');
    if (errIdx === -1) errIdx = 0;
    next[errIdx] = { ...next[errIdx], status: 'ERROR', endedAt: epoch };
    return next;
  }

  let lastDone = -1;
  let active = -1;
  switch (s) {
    case 'PENDING':           lastDone = -1; active = -1; break;
    case 'ANALYZING':         lastDone = -1; active = 0;  break;
    case 'CONTEXT_ANALYSED':  lastDone = 0;  active = 1;  break;
    case 'PLAN_READY':
    case 'AWAITING_APPROVAL': lastDone = 1;  active = -1; break;
    case 'MIGRATING':         lastDone = 1;  active = 2;  break;
    case 'VALIDATING':        lastDone = 2;  active = 3;  break;
    case 'DONE':
    case 'COMPLETED':         lastDone = 4;  active = -1; break;
    default: return base;
  }

  return base.map((step, i) => {
    if (i <= lastDone) {
      return { ...step, status: 'DONE' as PipelineNodeStatus,
               startedAt: step.startedAt ?? epoch,
               endedAt:   step.endedAt   ?? epoch };
    }
    if (i === active) {
      return { ...step, status: 'ACTIVE' as PipelineNodeStatus,
               startedAt: step.startedAt ?? epoch };
    }
    return step;
  });
}

function mapStatus(s: string): PipelineNodeStatus {
  switch (s) {
    case 'COMPLETED':
    case 'DONE':
    case 'SUCCESS':  return 'DONE';
    case 'FAILED':
    case 'ERROR':    return 'ERROR';
    case 'STARTED':
    case 'RUNNING':
    case 'ACTIVE':   return 'ACTIVE';
    default:         return 'PENDING';
  }
}

function parseTimestamp(ts: string | undefined): number {
  if (!ts) return Date.now();
  const parsed = Date.parse(ts);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}

/** "12s", "1m 04s", "2h 13m" — kept short so it fits in the timeline column. */
function formatDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  if (totalSec < 60)   return `${totalSec}s`;
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  if (min < 60)        return `${min}m ${sec.toString().padStart(2, '0')}s`;
  const hr = Math.floor(min / 60);
  return `${hr}h ${(min % 60).toString().padStart(2, '0')}m`;
}
