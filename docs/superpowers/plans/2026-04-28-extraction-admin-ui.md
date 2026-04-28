# Extraction Admin UI Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends with a commit.

**Goal:** Add an "Extraction" tab to `/admin/knowledge` that drives the existing `/admin/knowledge/extract-mentions` REST endpoint with a status tracker, force-reextract toggle, and cancel button.

**Architecture:** New React component `ExtractionTab.jsx` mirroring `IndexStatusTab.jsx`'s polling/error idiom. Three thin client methods in `api.knowledge`. One backend additive: surface the configured extractor backend in the status payload so the UI can label the run.

**Tech Stack:** React (Vite + Vitest), Java servlet (`AdminExtractionResource`), Mockito + JUnit 5 for the backend test extension.

**Spec:** `docs/superpowers/specs/2026-04-28-extraction-admin-ui-design.md`

---

### Task 1: Backend — surface `extractorBackend` in the status payload

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminExtractionResource.java`
- Modify: `wikantik-rest/src/test/java/com/wikantik/rest/AdminExtractionResourceTest.java`

- [ ] **Step 1: Add a failing test asserting `extractorBackend` is present in the GET payload.**

Insert into `AdminExtractionResourceTest`:

```java
@Test
void doGet_includesExtractorBackendFromConfig() throws Exception {
    engine.getWikiProperties().setProperty( "wikantik.knowledge.extractor.backend", "claude" );
    final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
    Mockito.when( indexer.status() ).thenReturn( idleStatus() );
    installIndexer( indexer );

    final StringWriter sw = new StringWriter();
    final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
    servlet.doGet( request, response );

    final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
    assertEquals( "claude", body.get( "extractorBackend" ).getAsString() );
}

@Test
void doGet_extractorBackendDefaultsToDisabledWhenUnset() throws Exception {
    engine.getWikiProperties().remove( "wikantik.knowledge.extractor.backend" );
    final BootstrapEntityExtractionIndexer indexer = Mockito.mock( BootstrapEntityExtractionIndexer.class );
    Mockito.when( indexer.status() ).thenReturn( idleStatus() );
    installIndexer( indexer );

    final StringWriter sw = new StringWriter();
    final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/admin/knowledge/extract-mentions" );
    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
    servlet.doGet( request, response );

    final JsonObject body = gson.fromJson( sw.toString(), JsonObject.class );
    assertEquals( "disabled", body.get( "extractorBackend" ).getAsString() );
}
```

- [ ] **Step 2: Run the new tests; expect FAIL (`extractorBackend` field absent).**

```bash
mvn test -pl wikantik-rest -Dtest='AdminExtractionResourceTest#doGet_includesExtractorBackendFromConfig+doGet_extractorBackendDefaultsToDisabledWhenUnset' -q
```

- [ ] **Step 3: Implement — read the property in the servlet and pass to `statusToMap`.**

Edit `AdminExtractionResource.java`:

1. Change the three `statusToMap( indexer.status() )` call sites in `doGet`, `doPost`, `doDelete` to `statusToMap( indexer.status(), extractorBackend() )`.
2. Add a private helper:

```java
private String extractorBackend() {
    final String v = getEngine().getWikiProperties().getProperty( "wikantik.knowledge.extractor.backend" );
    return ( v == null || v.isBlank() ) ? "disabled" : v.trim().toLowerCase( java.util.Locale.ROOT );
}
```

3. Update `statusToMap` signature and add the field at the end:

```java
private static Map< String, Object > statusToMap( final BootstrapEntityExtractionIndexer.Status s,
                                                  final String extractorBackend ) {
    final Map< String, Object > m = new LinkedHashMap<>();
    // … existing entries unchanged …
    if ( s.lastError() != null ) m.put( "lastError", s.lastError() );
    m.put( "extractorBackend", extractorBackend );
    return m;
}
```

4. Update the `body.put( "message", ... )` clause inside `doPost`'s 409 branch to also start from the extended map (already does via `new LinkedHashMap<>( statusToMap(...) )`, just pass `extractorBackend()` through).

- [ ] **Step 4: Run both new tests + the existing class to ensure nothing else regresses.**

```bash
mvn test -pl wikantik-rest -Dtest=AdminExtractionResourceTest -q
```

Expected: all tests pass.

- [ ] **Step 5: Commit.**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminExtractionResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminExtractionResourceTest.java
git commit -m "$(cat <<'EOF'
admin(extract): surface configured extractor backend in status payload

Lets the upcoming admin UI label which LLM backend (claude/ollama/disabled)
the in-flight or last batch ran against — useful when operators flip the
property between runs to compare extractor outputs.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Frontend — add three client methods to `api.knowledge`

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Add the three methods at the end of the existing `knowledge:` namespace, before the namespace's closing brace.**

Locate the `knowledge: {` block (currently starts around line 498) and insert before its closing brace, after `bulkDeleteDismissedHubDiscoveryProposals(...)`:

```javascript
    // Entity extraction (LLM-based proposal regeneration)
    getExtractionStatus: () =>
      request('/admin/knowledge/extract-mentions'),

    startExtraction: (force = false) =>
      request(`/admin/knowledge/extract-mentions${force ? '?force=true' : ''}`,
              { method: 'POST' }),

    cancelExtraction: () =>
      request('/admin/knowledge/extract-mentions', { method: 'DELETE' }),
```

- [ ] **Step 2: Quick smoke — start the Vite dev server-build to confirm the file still parses.**

```bash
cd /home/jakefear/source/jspwiki/wikantik-frontend && npx vite build --logLevel error >/dev/null && cd -
```

Expected: build succeeds with no syntax error. (Component-level tests covered in Task 4.)

- [ ] **Step 3: Commit.**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "$(cat <<'EOF'
admin(extract): add knowledge.{get,start,cancel}Extraction REST clients

Three thin wrappers over /admin/knowledge/extract-mentions [GET|POST|DELETE]
that the upcoming Extraction tab will drive.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Frontend — `ExtractionTab.jsx` component

**Files:**
- Create: `wikantik-frontend/src/components/admin/ExtractionTab.jsx`

This task creates the component with no test coverage yet — Task 4 adds Vitest cases against it.

- [ ] **Step 1: Write `ExtractionTab.jsx` based on the design + `IndexStatusTab.jsx` idiom.**

```jsx
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api/client';
import ConfirmDialog from './ConfirmDialog';

const FAST_POLL_MS = 2000;
const SLOW_POLL_MS = 10000;

export default function ExtractionTab() {
  const [status, setStatus] = useState(null);
  const [disabled, setDisabled] = useState(false);
  const [error, setError] = useState(null);
  const [confirmKind, setConfirmKind] = useState(null); // 'start' | 'force' | 'cancel' | null
  const [force, setForce] = useState(false);
  const [cancelRequested, setCancelRequested] = useState(false);
  const pollRef = useRef(null);
  const stateRef = useRef('IDLE');

  const fetchStatus = async () => {
    try {
      const s = await api.knowledge.getExtractionStatus();
      setStatus(s);
      setDisabled(false);
      stateRef.current = s?.state || 'IDLE';
      if ((s?.state || 'IDLE') !== 'RUNNING') setCancelRequested(false);
    } catch (e) {
      if (e.status === 503) {
        setDisabled(true);
        stateRef.current = 'IDLE';
      } else {
        setError(e.message || 'Failed to fetch status');
      }
    }
  };

  useEffect(() => {
    let cancelled = false;
    fetchStatus();
    const tick = async () => {
      if (cancelled) return;
      await fetchStatus();
      if (cancelled) return;
      const active = stateRef.current === 'RUNNING';
      clearInterval(pollRef.current);
      pollRef.current = setInterval(tick, active ? FAST_POLL_MS : SLOW_POLL_MS);
    };
    pollRef.current = setInterval(tick, FAST_POLL_MS);
    return () => { cancelled = true; if (pollRef.current) clearInterval(pollRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const doStart = async () => {
    setConfirmKind(null);
    setError(null);
    try {
      const next = await api.knowledge.startExtraction(force);
      if (next) { setStatus(next); stateRef.current = next.state || 'RUNNING'; }
    } catch (e) {
      if (e.status === 409) {
        setError('An extraction run is already in progress.');
        if (e.body) setStatus(e.body);
      } else if (e.status === 503) {
        setDisabled(true);
      } else {
        setError(e.message || 'Failed to start extraction.');
      }
    }
  };

  const doCancel = async () => {
    setConfirmKind(null);
    setError(null);
    try {
      const next = await api.knowledge.cancelExtraction();
      if (next) setStatus(next);
      setCancelRequested(true);
    } catch (e) {
      if (e.status === 409) {
        setError('No extraction run is currently in progress.');
      } else {
        setError(e.message || 'Failed to cancel extraction.');
      }
    }
  };

  if (disabled) {
    return (
      <div className="admin-message warning" role="status">
        Entity extraction is not configured (check <code>wikantik.knowledge.extractor.backend</code>).
      </div>
    );
  }

  if (!status) return <div className="admin-loading">Loading extraction status…</div>;

  const state = status.state || 'IDLE';
  const isRunning = state === 'RUNNING';
  const elapsedSec = status.elapsedMs ? Math.round(status.elapsedMs / 1000) : 0;
  const pagesPct = status.totalPages > 0
    ? Math.round((status.processedPages / status.totalPages) * 100) : 0;
  const chunksPct = status.totalChunks > 0
    ? Math.round((status.processedChunks / status.totalChunks) * 100) : 0;

  return (
    <div className="extraction-tab">
      <div className="admin-section-header">
        <h3>Entity Extraction</h3>
      </div>
      <p className="admin-section-help">
        Runs the LLM-based entity extractor across every chunk in the corpus,
        writing entity mentions and filing knowledge-graph proposals. This is
        the upstream of the <em>Proposals</em> tab. Long-running — typical
        runs take minutes per hundred pages depending on backend.
      </p>

      <div className="stats-grid" data-testid="extraction-header">
        <StatCard label="State" value={state} subtitle={`backend: ${status.extractorBackend || 'unknown'}`} />
        <StatCard label="Concurrency" value={status.concurrency ?? 0} />
        <StatCard label="Elapsed" value={`${elapsedSec}s`}
                  subtitle={status.startedAt ? `started ${status.startedAt}` : '—'} />
        {status.finishedAt && (
          <StatCard label="Finished" value={status.finishedAt} />
        )}
      </div>

      {state !== 'IDLE' && (
        <div className="extraction-progress" data-testid="extraction-progress">
          <ProgressBar
            label={`Pages — ${status.processedPages}/${status.totalPages} (${pagesPct}%)`}
            value={pagesPct}
            failed={status.failedPages}
          />
          <ProgressBar
            label={`Chunks — ${status.processedChunks}/${status.totalChunks} (${chunksPct}%)`}
            value={chunksPct}
            failed={status.failedChunks}
          />
          <div className="extraction-counters">
            <span>mentions written: <strong>{status.mentionsWritten ?? 0}</strong></span>
            <span>proposals filed: <strong>{status.proposalsFiled ?? 0}</strong></span>
            <span>excluded skipped: <strong>{status.excludedSkipped ?? 0}</strong></span>
          </div>
          {cancelRequested && isRunning && (
            <div className="admin-message info" role="status">
              Cancellation requested — the in-flight page will finish, then the run stops.
            </div>
          )}
        </div>
      )}

      <div className="admin-actions-row">
        <button
          className="btn btn-primary btn-danger"
          onClick={() => setConfirmKind(force ? 'force' : 'start')}
          disabled={isRunning}
        >
          {isRunning ? `Running (${state})` : 'Extract Mentions'}
        </button>
        <label style={{ marginLeft: 'var(--space-md)' }}>
          <input
            type="checkbox"
            checked={force}
            onChange={(e) => setForce(e.target.checked)}
            disabled={isRunning}
          />
          {' '}Force re-extract
        </label>
        {isRunning && (
          <button
            className="btn btn-secondary"
            style={{ marginLeft: 'var(--space-md)' }}
            onClick={() => setConfirmKind('cancel')}
          >
            Cancel
          </button>
        )}
      </div>

      {status.lastError && (
        <details className="errors-panel" style={{ marginTop: 'var(--space-md)' }}>
          <summary>Last error</summary>
          <pre>{status.lastError}</pre>
        </details>
      )}

      {error && (
        <div className="admin-message error" role="alert" style={{ marginTop: 'var(--space-md)' }}>
          {error}
        </div>
      )}

      {confirmKind === 'start' && (
        <ConfirmDialog
          message="Run entity extraction over every page? This calls the LLM extractor and may take a long time."
          onConfirm={doStart}
          onCancel={() => setConfirmKind(null)}
        />
      )}
      {confirmKind === 'force' && (
        <ConfirmDialog
          message="Force re-extract every page, including ones already processed? This will re-run the LLM extractor over the entire corpus."
          onConfirm={doStart}
          onCancel={() => setConfirmKind(null)}
        />
      )}
      {confirmKind === 'cancel' && (
        <ConfirmDialog
          message="Cancel the in-flight extraction run? The current page will finish; remaining pages will be skipped."
          onConfirm={doCancel}
          onCancel={() => setConfirmKind(null)}
        />
      )}
    </div>
  );
}

function StatCard({ label, value, subtitle }) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {subtitle && <div className="stat-subtitle">{subtitle}</div>}
    </div>
  );
}

function ProgressBar({ label, value, failed }) {
  return (
    <div className="progress-row">
      <div className="progress-label">{label}</div>
      <div className="progress-track">
        <div className="progress-fill" style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
      </div>
      {failed > 0 && (
        <div className="progress-failed" style={{ color: 'var(--color-danger, #c00)' }}>
          {failed} failed
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Verify the file parses by building the frontend.**

```bash
cd /home/jakefear/source/jspwiki/wikantik-frontend && npx vite build --logLevel error >/dev/null && cd -
```

Expected: build succeeds.

- [ ] **Step 3: Commit.**

```bash
git add wikantik-frontend/src/components/admin/ExtractionTab.jsx
git commit -m "$(cat <<'EOF'
admin(extract): ExtractionTab UI — status tracker + start/force/cancel

Mirrors IndexStatusTab's polling cadence (2s while RUNNING, 10s otherwise),
disabled-fallback on 503, and ConfirmDialog usage. Cancel is cooperative
(per BootstrapEntityExtractionIndexer.cancel) — UI surfaces the request as
a status hint until state next leaves RUNNING.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Frontend — Vitest coverage for `ExtractionTab`

**Files:**
- Create: `wikantik-frontend/src/components/admin/ExtractionTab.test.jsx`

- [ ] **Step 1: Write the failing test file.**

```jsx
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ExtractionTab from './ExtractionTab';
import { api } from '../../api/client';

const idle = {
  state: 'IDLE', totalPages: 0, processedPages: 0, failedPages: 0,
  totalChunks: 0, processedChunks: 0, failedChunks: 0,
  mentionsWritten: 0, proposalsFiled: 0, elapsedMs: 0,
  forceOverwrite: false, concurrency: 4, startedAt: null, finishedAt: null,
  excludedSkipped: 0, lastError: null, extractorBackend: 'claude',
};

const running = {
  ...idle, state: 'RUNNING',
  totalPages: 100, processedPages: 25, failedPages: 1,
  totalChunks: 800, processedChunks: 200, failedChunks: 3,
  mentionsWritten: 42, proposalsFiled: 18, elapsedMs: 12_000,
  startedAt: '2026-04-28T10:00:00Z',
};

const errored = {
  ...idle, state: 'ERROR',
  lastError: 'Anthropic API timed out after 60s',
  finishedAt: '2026-04-28T11:00:00Z',
  totalPages: 100, processedPages: 30, failedPages: 5,
};

describe('ExtractionTab', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('IDLE: shows Extract Mentions enabled and no Cancel button', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    render(<ExtractionTab />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Extract Mentions/i })).toBeEnabled());
    expect(screen.queryByRole('button', { name: /^Cancel$/ })).toBeNull();
  });

  it('RUNNING: shows progress bars, counter row, disabled trigger, visible Cancel', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(running);
    render(<ExtractionTab />);
    await waitFor(() =>
      expect(screen.getByTestId('extraction-progress')).toBeInTheDocument());
    expect(screen.getByText(/25\/100/)).toBeInTheDocument();
    expect(screen.getByText(/200\/800/)).toBeInTheDocument();
    expect(screen.getByText(/mentions written/i).querySelector('strong'))
      .toHaveTextContent('42');
    expect(screen.getByRole('button', { name: /Running/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Cancel$/ })).toBeInTheDocument();
  });

  it('ERROR: surfaces lastError in the details panel', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(errored);
    render(<ExtractionTab />);
    await waitFor(() =>
      expect(screen.getByText(/Anthropic API timed out/i)).toBeInTheDocument());
  });

  it('503 from API renders the disabled fallback', async () => {
    const err = Object.assign(new Error('disabled'), { status: 503, body: {} });
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockRejectedValue(err);
    render(<ExtractionTab />);
    await waitFor(() =>
      expect(screen.getByText(/extraction is not configured/i)).toBeInTheDocument());
  });

  it('clicking Extract Mentions opens confirm and POSTs without force by default', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    const start = vi.spyOn(api.knowledge, 'startExtraction').mockResolvedValue(running);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(screen.getByRole('button', { name: /Extract Mentions/i }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/may take a long time/i)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await waitFor(() => expect(start).toHaveBeenCalledWith(false));
  });

  it('Force re-extract checkbox changes the confirm copy and POSTs force=true', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    const start = vi.spyOn(api.knowledge, 'startExtraction').mockResolvedValue(running);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(screen.getByRole('checkbox', { name: /Force re-extract/i }));
    fireEvent.click(screen.getByRole('button', { name: /Extract Mentions/i }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/already processed/i)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await waitFor(() => expect(start).toHaveBeenCalledWith(true));
  });

  it('Cancel button confirms then sends DELETE; cancellation hint appears', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(running);
    const cancel = vi.spyOn(api.knowledge, 'cancelExtraction').mockResolvedValue(running);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /^Cancel$/ }));
    fireEvent.click(screen.getByRole('button', { name: /^Cancel$/ }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /Continue/i }));
    await waitFor(() => expect(cancel).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByText(/Cancellation requested/i)).toBeInTheDocument());
  });

  it('409 on POST surfaces "already in progress" error', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue(idle);
    const err = Object.assign(new Error('Conflict'),
      { status: 409, body: { ...running, message: 'in progress' } });
    vi.spyOn(api.knowledge, 'startExtraction').mockRejectedValue(err);

    render(<ExtractionTab />);
    await waitFor(() => screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(screen.getByRole('button', { name: /Extract Mentions/i }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /Continue/i }));
    await waitFor(() =>
      expect(screen.getByText(/already in progress/i)).toBeInTheDocument());
  });

  it('COMPLETED with processedPages < totalPages (post-cancel shape) renders cleanly', async () => {
    vi.spyOn(api.knowledge, 'getExtractionStatus').mockResolvedValue({
      ...running, state: 'COMPLETED', processedPages: 30, totalPages: 100,
      finishedAt: '2026-04-28T10:30:00Z',
    });
    render(<ExtractionTab />);
    await waitFor(() => expect(screen.getByText(/30\/100/)).toBeInTheDocument());
    // Trigger button is enabled again at COMPLETED.
    expect(screen.getByRole('button', { name: /Extract Mentions/i })).toBeEnabled();
    expect(screen.queryByRole('button', { name: /^Cancel$/ })).toBeNull();
  });
});
```

- [ ] **Step 2: Run the new test file. Expect FAIL or PASS depending on the component's match — fix any wiring discrepancies in `ExtractionTab.jsx` until all tests pass.**

```bash
cd /home/jakefear/source/jspwiki/wikantik-frontend && npx vitest run src/components/admin/ExtractionTab.test.jsx --reporter=verbose && cd -
```

Expected: 9 tests pass.

- [ ] **Step 3: Commit.**

```bash
git add wikantik-frontend/src/components/admin/ExtractionTab.test.jsx
git commit -m "$(cat <<'EOF'
admin(extract): Vitest coverage for ExtractionTab

Covers IDLE/RUNNING/ERROR/COMPLETED states, the disabled fallback on 503,
the Force-re-extract toggle, the cancel-confirm flow, and 409-on-POST.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Frontend — wire `ExtractionTab` into `AdminKnowledgePage`

**Files:**
- Modify: `wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx`

- [ ] **Step 1: Edit the imports and `TABS` array — insert at index 1, render at the bottom.**

Add the import near the existing tab imports:

```jsx
import ExtractionTab from './ExtractionTab';
```

Insert into `TABS` between the `proposals` entry and the `node-explorer` entry:

```jsx
  { id: 'extraction', label: 'Extraction',
    description: 'Run the LLM-based entity extractor across every page to regenerate knowledge-graph proposals. Watch progress, cancel in flight, or force re-extraction of pages already processed.' },
```

Add the conditional render alongside the other `activeTab === ...` lines:

```jsx
      {activeTab === 'extraction' && <ExtractionTab />}
```

- [ ] **Step 2: Run the existing admin-page test to make sure nothing regressed.**

```bash
cd /home/jakefear/source/jspwiki/wikantik-frontend && npx vitest run src/components/admin/ --reporter=dot && cd -
```

Expected: all existing admin tests pass; ExtractionTab tests pass.

- [ ] **Step 3: Commit.**

```bash
git add wikantik-frontend/src/components/admin/AdminKnowledgePage.jsx
git commit -m "$(cat <<'EOF'
admin(extract): wire ExtractionTab into AdminKnowledgePage

Inserts the new tab at index 1 (right after Proposals) so the upstream of
the proposal queue is visually adjacent to the queue itself.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Final verification — full build

- [ ] **Step 1: Build the WAR end-to-end (compiles backend + runs the React build via npm).**

```bash
mvn clean install -T 1C -DskipITs -q
```

Expected: `BUILD SUCCESS`. Any failure here means a Task 1–5 step regressed something — fix in place, then re-run.

- [ ] **Step 2: Smoke-deploy and click through (manual; the model can run the deploy + describe what to verify).**

```bash
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null || true
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

Verify in browser: `/admin/knowledge` → `Extraction` tab loads, status panel renders, force checkbox toggles, confirm dialog appears, cancel button hidden when IDLE.

- [ ] **Step 3: No commit needed for the verification step (already covered by per-task commits).**

---

## Self-Review Notes

- All spec sections are covered: Task 1 = backend `extractorBackend` field; Task 2 = three client methods; Task 3 = component; Task 4 = unit tests; Task 5 = tab wiring + index 1 placement; Task 6 = full build gate.
- No placeholders. Every code block is the actual code to write.
- Type/method names cross-checked: `getExtractionStatus / startExtraction / cancelExtraction` consistent across Tasks 2/3/4. State enum (`IDLE | RUNNING | COMPLETED | ERROR`) consistent with `BootstrapEntityExtractionIndexer.State`. `extractorBackend` field consistent across Tasks 1/3/4.
- Cancel semantics consistent with the actual Java behavior (cooperative flag, no terminal `CANCELLED` state).
