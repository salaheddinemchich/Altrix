import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  ViewChild,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import loader from '@monaco-editor/loader';
import type * as Monaco from 'monaco-editor';
import { MigratedFile } from '../../../core/models/session.model';
import { SessionService } from '../../../core/services/session.service';
import { IconComponent } from '../../../shared/icon/icon.component';

/**
 * Issue #120 — side-by-side diff viewer using Monaco Editor.
 *
 * <p>The "after" pane is populated from the migrated content the backend
 * already returns via {@code GET /api/v1/sessions/{id}/files}. The "before"
 * pane is empty until the original-content endpoint lands with issue #119;
 * for {@code CREATED} files an empty original is in fact the correct UX.
 *
 * <p>Monaco itself (~2 MB) is loaded lazily from the npm package via
 * {@code @monaco-editor/loader} on the first render, so the route bundle
 * stays small.
 */
@Component({
  selector: 'app-diff-viewer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './diff-viewer.component.html',
  styleUrl: './diff-viewer.component.scss',
})
export class DiffViewerComponent implements AfterViewInit, OnDestroy {
  private readonly route   = inject(ActivatedRoute);
  private readonly sessApi = inject(SessionService);

  @ViewChild('editorHost', { static: true })
  private editorHost!: ElementRef<HTMLDivElement>;

  readonly sessionId   = signal<string>('');
  readonly files       = signal<MigratedFile[]>([]);
  readonly loadError   = signal<string | null>(null);
  readonly selectedIdx = signal<number>(0);

  readonly selected = computed<MigratedFile | null>(() => {
    const list = this.files();
    const idx  = this.selectedIdx();
    return list.length > 0 && idx >= 0 && idx < list.length ? list[idx] : null;
  });

  private monaco?: typeof Monaco;
  private diffEditor?: Monaco.editor.IStandaloneDiffEditor;

  constructor() {
    // Re-render the diff editor whenever the selected file (or the file list)
    // changes — works regardless of whether the change comes from a click,
    // a keyboard shortcut, or an HTTP response landing.
    effect(() => {
      const f = this.selected();
      if (f && this.monaco && this.diffEditor) this.renderDiff(f);
    });
  }

  ngAfterViewInit(): void {
    const sid = this.route.snapshot.paramMap.get('id') ?? '';
    this.sessionId.set(sid);
    if (!sid) {
      this.loadError.set('Missing session id in route');
      return;
    }

    // Load Monaco once, then load the file list — order matters: the editor
    // host element must already exist when we call create().
    loader
      .init()
      .then(monaco => {
        this.monaco = monaco;
        this.diffEditor = monaco.editor.createDiffEditor(this.editorHost.nativeElement, {
          readOnly: true,
          renderSideBySide: true,
          automaticLayout: true,
          theme: prefersDark() ? 'vs-dark' : 'vs',
          renderOverviewRuler: false,
          scrollBeyondLastLine: false,
        });
        this.loadFiles();
      })
      .catch(err => this.loadError.set('Could not initialise editor: ' + err.message));
  }

  ngOnDestroy(): void {
    this.diffEditor?.dispose();
  }

  // ── Keyboard navigation (Alt+← / Alt+→) ─────────────────────────────────
  @HostListener('window:keydown', ['$event'])
  onKey(ev: KeyboardEvent): void {
    if (!ev.altKey) return;
    if (ev.key === 'ArrowLeft')  { this.previous(); ev.preventDefault(); }
    if (ev.key === 'ArrowRight') { this.next();     ev.preventDefault(); }
  }

  // ── User actions ─────────────────────────────────────────────────────────
  select(idx: number): void { this.selectedIdx.set(idx); }

  previous(): void {
    const idx = this.selectedIdx();
    if (idx > 0) this.selectedIdx.set(idx - 1);
  }

  next(): void {
    const idx = this.selectedIdx();
    if (idx < this.files().length - 1) this.selectedIdx.set(idx + 1);
  }

  changeTypeClass(t: string): string {
    switch (t) {
      case 'CREATED':  return 'created';
      case 'DELETED':  return 'deleted';
      case 'MODIFIED': return 'modified';
      default:         return '';
    }
  }

  // ── Internals ────────────────────────────────────────────────────────────
  private loadFiles(): void {
    this.sessApi.getFiles(this.sessionId()).subscribe({
      next: list => {
        this.files.set(list ?? []);
        this.selectedIdx.set(0);
      },
      error: err => this.loadError.set(err?.message ?? 'Could not load files'),
    });
  }

  private renderDiff(f: MigratedFile): void {
    if (!this.monaco || !this.diffEditor) return;

    const language = detectLanguage(f.newPath || f.originalPath);

    // For DELETED files the "before" is the (unknown) original content and
    // the "after" is empty — flip the panes accordingly so users see what is
    // being removed rather than blank.
    const beforeContent = f.changeType === 'DELETED' ? f.content : '';
    const afterContent  = f.changeType === 'DELETED' ? ''        : f.content;

    const original = this.monaco.editor.createModel(beforeContent, language);
    const modified = this.monaco.editor.createModel(afterContent,  language);

    // Dispose the previous models to avoid a memory leak between file switches.
    const old = this.diffEditor.getModel();
    this.diffEditor.setModel({ original, modified });
    old?.original.dispose();
    old?.modified.dispose();
  }
}

// ── helpers ────────────────────────────────────────────────────────────────

function prefersDark(): boolean {
  // Honour the OS scheme on first render; fine to ignore mid-session changes
  // since Monaco can be themed dynamically if we ever want to wire it up.
  return typeof window !== 'undefined'
      && !!window.matchMedia
      && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Maps a path's extension to a Monaco language id; defaults to plaintext. */
function detectLanguage(path: string): string {
  const dot = path.lastIndexOf('.');
  if (dot < 0) return 'plaintext';
  const ext = path.substring(dot + 1).toLowerCase();
  switch (ext) {
    case 'java':       return 'java';
    case 'kt':
    case 'kts':        return 'kotlin';
    case 'ts':         return 'typescript';
    case 'js':
    case 'mjs':
    case 'cjs':        return 'javascript';
    case 'xml':
    case 'pom':        return 'xml';
    case 'yml':
    case 'yaml':       return 'yaml';
    case 'json':       return 'json';
    case 'properties': return 'ini';
    case 'sql':        return 'sql';
    case 'sh':         return 'shell';
    case 'md':         return 'markdown';
    case 'html':       return 'html';
    case 'css':        return 'css';
    case 'scss':       return 'scss';
    default:           return 'plaintext';
  }
}
