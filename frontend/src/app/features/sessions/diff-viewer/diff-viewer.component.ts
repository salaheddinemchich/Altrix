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
import { SessionFileNode } from '../../../core/models/session.model';
import { SessionService } from '../../../core/services/session.service';
import { IconComponent } from '../../../shared/icon/icon.component';

/**
 * Issue #120 — side-by-side diff viewer.
 *
 * <p>Left pane shows the FULL project tree (every file in the source ZIP plus
 * any CREATED file from the migration) so the reviewer sees the architecture
 * exactly like in an IDE.  Each file carries a colour-coded badge:
 * MODIFIED / CREATED / DELETED / UNCHANGED / UNTOUCHED.  Clicking a file
 * loads its before/after into the Monaco diff editor on the right.
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

  readonly sessionId    = signal<string>('');
  readonly nodes        = signal<SessionFileNode[]>([]);
  readonly loadError    = signal<string | null>(null);
  readonly selectedPath = signal<string | null>(null);

  /** Files where status indicates a real change — used for prev/next nav. */
  readonly touchedFiles = computed<SessionFileNode[]>(() =>
    this.nodes().filter(n => n.status === 'MODIFIED'
                          || n.status === 'CREATED'
                          || n.status === 'DELETED'));

  readonly selected = computed<SessionFileNode | null>(() => {
    const path = this.selectedPath();
    return path ? (this.nodes().find(n => n.path === path) ?? null) : null;
  });

  /** Total counts for the header. */
  readonly counts = computed(() => {
    const all = this.nodes();
    const byStatus = (s: string) => all.filter(n => n.status === s).length;
    return {
      total:     all.length,
      modified:  byStatus('MODIFIED'),
      created:   byStatus('CREATED'),
      deleted:   byStatus('DELETED'),
      unchanged: byStatus('UNCHANGED'),
      untouched: byStatus('UNTOUCHED'),
    };
  });

  /** Tree built from the flat path list — VS-Code style nested folders. */
  readonly tree = computed<TreeFolder>(() => buildTree(this.nodes()));

  private monaco?: typeof Monaco;
  private diffEditor?: Monaco.editor.IStandaloneDiffEditor;

  constructor() {
    // Re-render the diff editor whenever the selected file changes.
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
        this.loadTree();
      })
      .catch(err => this.loadError.set('Could not initialise editor: ' + err.message));
  }

  ngOnDestroy(): void { this.diffEditor?.dispose(); }

  // ── Issue #123 — .patch download helpers ───────────────────────────────
  patchUrl(): string { return this.sessApi.patchDownloadUrl(this.sessionId()); }
  patchFilename(): string { return `session-${this.sessionId()}.patch`; }

  // ── Keyboard navigation (Alt+← / Alt+→) cycles through TOUCHED files ────
  @HostListener('window:keydown', ['$event'])
  onKey(ev: KeyboardEvent): void {
    if (!ev.altKey) return;
    if (ev.key === 'ArrowLeft')  { this.previous(); ev.preventDefault(); }
    if (ev.key === 'ArrowRight') { this.next();     ev.preventDefault(); }
  }

  // ── User actions ─────────────────────────────────────────────────────────
  select(path: string): void { this.selectedPath.set(path); }

  previous(): void {
    const touched = this.touchedFiles();
    if (touched.length === 0) return;
    const cur = this.selectedPath();
    const idx = touched.findIndex(n => n.path === cur);
    if (idx > 0) this.selectedPath.set(touched[idx - 1].path);
  }

  next(): void {
    const touched = this.touchedFiles();
    if (touched.length === 0) return;
    const cur = this.selectedPath();
    const idx = touched.findIndex(n => n.path === cur);
    if (idx >= 0 && idx < touched.length - 1) this.selectedPath.set(touched[idx + 1].path);
  }

  statusClass(s: string): string {
    switch (s) {
      case 'MODIFIED':  return 'modified';
      case 'CREATED':   return 'created';
      case 'DELETED':   return 'deleted';
      case 'UNCHANGED': return 'unchanged';
      default:          return 'untouched';
    }
  }

  /** Folder colour-class — picks the most "alarming" change inside the folder. */
  folderClass(folder: TreeFolder): string {
    if (folder.descendantStatuses.has('MODIFIED')) return 'modified';
    if (folder.descendantStatuses.has('CREATED'))  return 'created';
    if (folder.descendantStatuses.has('DELETED'))  return 'deleted';
    return '';
  }

  // ── Internals ────────────────────────────────────────────────────────────
  private loadTree(): void {
    this.sessApi.getFileTree(this.sessionId()).subscribe({
      next: list => {
        this.nodes.set(list ?? []);
        // Default selection: first MODIFIED file, then first CREATED, then first node.
        const first = (list ?? []).find(n => n.status === 'MODIFIED')
                  ?? (list ?? []).find(n => n.status === 'CREATED')
                  ?? (list ?? [])[0];
        if (first) this.selectedPath.set(first.path);
      },
      error: err => this.loadError.set(err?.message ?? 'Could not load file tree'),
    });
  }

  private renderDiff(node: SessionFileNode): void {
    if (!this.monaco || !this.diffEditor) return;

    // UNTOUCHED files were never inspected by the migrator — fetch the source
    // alone (no migrated side); the diff endpoint returns null for both, so
    // we render an empty pane.  For touched files the endpoint returns both.
    this.applyToEditor(node.path, '', '');

    this.sessApi.getFileDiff(this.sessionId(), node.path).subscribe({
      next: diff => {
        if (this.selectedPath() !== node.path) return; // moved on
        this.applyToEditor(node.path,
                           diff.originalContent ?? '',
                           diff.migratedContent ?? '');
      },
      error: () => { /* fall back to the empty placeholder */ },
    });
  }

  private applyToEditor(path: string, before: string, after: string): void {
    if (!this.monaco || !this.diffEditor) return;
    const language = detectLanguage(path);
    const original = this.monaco.editor.createModel(before, language);
    const modified = this.monaco.editor.createModel(after,  language);
    const old = this.diffEditor.getModel();
    this.diffEditor.setModel({ original, modified });
    old?.original.dispose();
    old?.modified.dispose();
  }
}

// ── Tree types + builder ─────────────────────────────────────────────────────

export interface TreeFile {
  kind: 'file';
  name: string;
  path: string;
  status: string;
}

export interface TreeFolder {
  kind: 'folder';
  name: string;
  /** All statuses present anywhere under this folder — used to pick a colour. */
  descendantStatuses: Set<string>;
  children: TreeFile[];
  folders: TreeFolder[];
}

function buildTree(nodes: SessionFileNode[]): TreeFolder {
  const root: TreeFolder = {
    kind: 'folder',
    name: '',
    descendantStatuses: new Set<string>(),
    children: [],
    folders: [],
  };

  for (const node of nodes) {
    const parts = node.path.split('/').filter(p => p.length > 0);
    if (parts.length === 0) continue;

    let cursor = root;
    for (let i = 0; i < parts.length - 1; i++) {
      const segment = parts[i];
      let next = cursor.folders.find(f => f.name === segment);
      if (!next) {
        next = {
          kind: 'folder',
          name: segment,
          descendantStatuses: new Set<string>(),
          children: [],
          folders: [],
        };
        cursor.folders.push(next);
      }
      next.descendantStatuses.add(node.status);
      cursor = next;
    }
    cursor.children.push({
      kind: 'file',
      name: parts[parts.length - 1],
      path: node.path,
      status: node.status,
    });
    cursor.descendantStatuses.add(node.status);
    root.descendantStatuses.add(node.status);
  }

  sortTree(root);
  return root;
}

function sortTree(folder: TreeFolder): void {
  folder.folders.sort((a, b) => a.name.localeCompare(b.name));
  folder.children.sort((a, b) => a.name.localeCompare(b.name));
  for (const sub of folder.folders) sortTree(sub);
}

// ── helpers ────────────────────────────────────────────────────────────────

function prefersDark(): boolean {
  return typeof window !== 'undefined'
      && !!window.matchMedia
      && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

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
