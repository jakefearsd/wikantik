# Drift Sweep Progress Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a live, determinate progress bar while a drift sweep runs — truthful for both manual and scheduled (post-rebuild) sweeps.

**Architecture:** `DriftSweepService` keeps ephemeral volatile progress counters (phase, pagesScanned, totalPages) updated inside `runSweep`; a new `GET /admin/drift/status` returns them; `AdminDriftPage` polls status every 1 s while sweeping and renders a div-based progress bar, also detecting an in-flight sweep on mount.

**Tech Stack:** Java 21, JUnit 5 + Mockito, Gson, React + vitest.

**Spec:** `docs/superpowers/specs/2026-06-09-drift-sweep-progress-design.md`.

**Context for implementers (read once):**
- This extends the shipped drift dashboard. `DriftSweepService` (wikantik-main, `com.wikantik.drift`) currently has: an `AtomicBoolean running`; `runSweep(String)` that calls `sweepFrontmatter(counts)` → `sweepShacl(counts)` → `repository.insertSweep(...)` inside a try, with `running.set(false)` in `finally`; `sweepFrontmatter` and `currentPageList` both drive a private `forEachParsedPage(String context, PageVisitor visitor)` which calls `pageManager.getAllPages()` then loops `getPureText` + `visitor.visit`. Records `SweepOutcome`, `DriftCount`, `PageViolation` already exist.
- `AdminDriftResource` (wikantik-rest) `doGet` resolves `service()` (503 if null) then dispatches on `extractPathParam(request)` to summary/trend/pages; `doPost` handles sweep. It has `NULL_SAFE_GSON` (serializeNulls) and a private `sendJsonWithStatus(response, status, payload)`. `/admin/drift/*` is already mapped in web.xml and gated by AdminAuthFilter — **no web.xml change in this feature.**
- `AdminDriftPage.jsx` (wikantik-frontend) currently: mount effect `Promise.all([getDriftSummary(), getDriftTrend(30)])`; `runNow()` POSTs then polls `getDriftSummary` every 2 s (`POLL_INTERVAL_MS=2000`, `MAX_POLLS=60`) until `sweptAt` moves past the pre-trigger value. `mounted` ref + `pollTimer` ref guard async. Its test file mocks `api.admin.{getDriftSummary,getDriftTrend,getDriftPages,runDriftSweep}` and sets summary+trend defaults in `beforeEach`.
- No determinate progress primitive exists in `src/components/ui/` (Spinner is indeterminate). `admin.css` has no `progress`/`bar` classes — add them.
- House rules: TDD (red before green); never `git add -A` (stage by name); commit trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; no empty catch blocks (LOG.warn with context); never run ITs with `-T`. After changing wikantik-main, `mvn install -pl wikantik-main -DskipTests -q` before testing wikantik-rest. Single test: `mvn test -pl <module> -Dtest=Class`. Vitest single file: `npx vitest run <path>` (known concurrency flakes — re-run a failing file alone before chasing).
- Wire contract is camelCase: `running, phase, pagesScanned, totalPages`.

---

### Task 1: DriftSweepService — SweepProgress record + volatile counters

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/drift/DriftSweepService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/drift/DriftSweepServiceTest.java` (extend)

- [ ] **Step 1: Write the failing tests** (append to `DriftSweepServiceTest`; the file already has a `service(pm, repo, shacl)` helper, a `pm(...)` helper, and a latch-based `concurrentSweepIsRefused` test — reuse those idioms):

```java
    @Test
    void progressIsIdleBeforeAnySweep() {
        final DriftSweepService svc = service( mock( PageManager.class ),
                mock( DriftSnapshotRepository.class ), null );
        final DriftSweepService.SweepProgress p = svc.progress();
        assertFalse( p.running() );
        assertNull( p.phase() );
        assertEquals( 0, p.pagesScanned() );
        assertEquals( 0, p.totalPages() );
    }

    @Test
    void progressReportsTotalAndPhaseMidSweep() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final PageManager pm = Mockito.mock( PageManager.class );
        final Page p1 = page( "P1" );
        final Page p2 = page( "P2" );
        when( pm.getPureText( p1 ) ).thenAnswer( inv -> {
            entered.countDown();
            release.await();
            return "---\ntype: article\nstatus: active\n---\n\nbody";
        } );
        when( pm.getPureText( p2 ) ).thenReturn( "---\ntype: article\nstatus: active\n---\n\nbody" );
        Mockito.doReturn( List.of( p1, p2 ) ).when( pm ).getAllPages();
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService svc = service( pm, repo, null );
        final Thread t = new Thread( () -> svc.runSweep( "manual" ) );
        t.start();
        assertTrue( entered.await( 5, TimeUnit.SECONDS ) );

        final DriftSweepService.SweepProgress mid = svc.progress();
        assertTrue( mid.running() );
        assertEquals( "frontmatter", mid.phase() );
        assertEquals( 2, mid.totalPages() );   // enumeration done before the per-page loop

        release.countDown();
        t.join();

        final DriftSweepService.SweepProgress after = svc.progress();
        assertFalse( after.running() );
        assertNull( after.phase() );
        assertEquals( 0, after.totalPages() );
        assertEquals( 0, after.pagesScanned() );
    }

    @Test
    void progressResetsAfterRepositoryFailure() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenThrow( new IllegalStateException( "db gone" ) );

        final DriftSweepService svc = service( pm, repo, null );
        assertThrows( IllegalStateException.class, () -> svc.runSweep( "manual" ) );

        final DriftSweepService.SweepProgress p = svc.progress();
        assertFalse( p.running() );
        assertNull( p.phase() );
        assertEquals( 0, p.totalPages() );
    }
```

Add imports if missing: `java.util.concurrent.CountDownLatch`, `java.util.concurrent.TimeUnit` (the concurrency test already uses these — likely present).

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-main -Dtest=DriftSweepServiceTest -q`
Expected: COMPILATION ERROR — `SweepProgress` / `progress()` don't exist.

- [ ] **Step 3: Implement**

Add the record (next to `SweepOutcome`, ~line 64):

```java
    /** Live, ephemeral progress of an in-flight sweep. Idle: (false, null, 0, 0). */
    public record SweepProgress( boolean running, String phase, int pagesScanned, int totalPages ) {}
```

Add volatile fields (next to `running`, ~line 74):

```java
    private volatile String progressPhase;
    private volatile int progressScanned;
    private volatile int progressTotal;
```

Add the accessor (next to `isRunning()`):

```java
    /** Snapshot of the current sweep's progress; idle values when no sweep is running. */
    public SweepProgress progress() {
        return new SweepProgress( running.get(), progressPhase, progressScanned, progressTotal );
    }
```

In `runSweep`, set the phase markers. Replace the body between the `try {` and the `insertSweep` call so it reads:

```java
        try {
            final long startedAt = System.currentTimeMillis();
            final java.time.Instant sweepStart = Instant.ofEpochMilli( startedAt );
            this.progressPhase = "frontmatter";
            this.progressScanned = 0;
            this.progressTotal = 0;
            final Map< CountKey, Integer > counts = new LinkedHashMap<>();
            final int pagesScanned = sweepFrontmatter( counts );
            this.progressPhase = "shacl";
            final boolean shaclChecked = sweepShacl( counts );
            this.progressPhase = "persisting";

            final long durationMs = System.currentTimeMillis() - startedAt;
            final List< DriftCount > rows = counts.entrySet().stream()
```

(everything from `final long durationMs` onward is unchanged.)

In the `finally`, reset progress alongside the running flag:

```java
        } finally {
            this.progressPhase = null;
            this.progressScanned = 0;
            this.progressTotal = 0;
            running.set( false );
        }
```

Thread the total + per-page count through `forEachParsedPage`. Change its signature to take an `IntConsumer` invoked once with the enumerated page count:

```java
    private void forEachParsedPage( final String context,
                                    final java.util.function.IntConsumer onEnumerated,
                                    final PageVisitor visitor ) {
        final Collection< Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "{}: page enumeration failed: {}", context, e.getMessage(), e );
            throw new IllegalStateException( context + ": page enumeration failed", e );
        }
        onEnumerated.accept( pages.size() );
        for ( final Page page : pages ) {
```

(the loop body is unchanged.)

Update `sweepFrontmatter` to set the total and mirror the scanned counter into the volatile field:

```java
    private int sweepFrontmatter( final Map< CountKey, Integer > counts ) {
        final int[] scanned = { 0 };
        forEachParsedPage( "drift sweep", total -> this.progressTotal = total,
                ( name, parsedOrNull, parseError ) -> {
            scanned[ 0 ]++;
            this.progressScanned = scanned[ 0 ];
            if ( parseError != null ) {
                bump( counts, "frontmatter", "yaml.parse", "ERROR" );
                return;
            }
            if ( parsedOrNull == null ) {
                return; // no frontmatter block — nothing to validate, but the page was scanned
            }
            for ( final FieldViolation v : validator.validate( parsedOrNull.metadata(), ctx ) ) {
                bump( counts, "frontmatter", v.code(), v.severity().name() );
            }
        } );
        return scanned[ 0 ];
    }
```

Update `currentPageList`'s `forEachParsedPage` call to pass a no-op total consumer (drill-down doesn't drive progress):

```java
        forEachParsedPage( "drift drill-down", total -> { }, ( name, parsedOrNull, parseError ) -> {
```

(the visitor body is unchanged.)

Add the import `import java.util.function.IntConsumer;` if you prefer the short form (or keep the fully-qualified `java.util.function.IntConsumer` inline as written above — either is fine; match the file's existing style, which fully-qualifies one-off types).

- [ ] **Step 4: Run to verify pass**

Run: `mvn test -pl wikantik-main -Dtest=DriftSweepServiceTest -q`
Expected: PASS (existing tests + 3 new = 12).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/drift/DriftSweepService.java \
        wikantik-main/src/test/java/com/wikantik/drift/DriftSweepServiceTest.java
git commit -m "feat(drift): SweepProgress record + volatile progress counters in the sweep

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: AdminDriftResource — GET /admin/drift/status

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminDriftResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminDriftResourceTest.java` (extend)

First: `mvn install -pl wikantik-main -DskipTests -q` (pick up Task 1's `progress()`).

- [ ] **Step 1: Write the failing tests** (append to `AdminDriftResourceTest`, reusing its `Stub` servlet + mocked `service`/`req`/`resp`/`body` idioms):

```java
    @Test
    void statusReportsRunningProgress() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( service.progress() ).thenReturn(
                new DriftSweepService.SweepProgress( true, "frontmatter", 84, 312 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertTrue( out.get( "running" ).getAsBoolean() );
        assertEquals( "frontmatter", out.get( "phase" ).getAsString() );
        assertEquals( 84, out.get( "pagesScanned" ).getAsInt() );
        assertEquals( 312, out.get( "totalPages" ).getAsInt() );
    }

    @Test
    void statusReportsIdleWithNullPhase() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/status" );
        when( service.progress() ).thenReturn(
                new DriftSweepService.SweepProgress( false, null, 0, 0 ) );

        servlet.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject out = json();
        assertFalse( out.get( "running" ).getAsBoolean() );
        assertTrue( out.get( "phase" ).isJsonNull() );
        assertEquals( 0, out.get( "totalPages" ).getAsInt() );
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-rest -Dtest=AdminDriftResourceTest -q`
Expected: FAIL — `/status` falls through to `sendNotFound` (404), so `setStatus(200)` is never called / `service.progress()` is unstubbed-null.

- [ ] **Step 3: Implement**

In `doGet`, add a `status` branch before the `else`:

```java
        } else if ( "pages".equals( action ) ) {
            handlePages( request, response, service );
        } else if ( "status".equals( action ) ) {
            handleStatus( service, response );
        } else {
            sendNotFound( response, "Unknown drift endpoint: " + action );
        }
```

Add the handler (next to `handlePages`):

```java
    private void handleStatus( final DriftSweepService service, final HttpServletResponse response )
            throws IOException {
        final DriftSweepService.SweepProgress p = service.progress();
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "running", p.running() );
        out.put( "phase", p.phase() );
        out.put( "pagesScanned", p.pagesScanned() );
        out.put( "totalPages", p.totalPages() );
        sendJsonWithStatus( response, 200, out );
    }
```

(`sendJsonWithStatus` already uses `NULL_SAFE_GSON`, so `phase: null` serializes literally. The `service == null` → 503 guard at the top of `doGet` already covers `/status`.) Update the class javadoc endpoint list to add the `GET /admin/drift/status` line.

- [ ] **Step 4: Run to verify pass**

```bash
mvn test -pl wikantik-rest -Dtest=AdminDriftResourceTest -q
```
Expected: PASS (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminDriftResource.java \
        wikantik-rest/src/test/java/com/wikantik/rest/AdminDriftResourceTest.java
git commit -m "feat(drift): GET /admin/drift/status — live sweep progress

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Frontend — status client method, progress bar, race-safe poll, mount check

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`
- Modify: `wikantik-frontend/src/components/admin/AdminDriftPage.jsx`
- Modify: `wikantik-frontend/src/styles/admin.css`
- Test: `wikantik-frontend/src/components/admin/AdminDriftPage.test.jsx`

- [ ] **Step 1: API client method.** In `src/api/client.js`, inside `api.admin`, after `runDriftSweep`:

```js
    getDriftStatus: () => request('/admin/drift/status'),
```

- [ ] **Step 2: Update the existing test mock so current tests keep passing.** In `AdminDriftPage.test.jsx`, add `getDriftStatus: vi.fn(),` to the `vi.mock` `admin` object, and in `beforeEach` add an idle default:

```js
  api.admin.getDriftStatus.mockResolvedValue({ running: false, phase: null, pagesScanned: 0, totalPages: 0 });
```

Run `npx vitest run src/components/admin/AdminDriftPage.test.jsx` — existing tests should still pass (the new mount status fetch returns idle). The run-now test will be rewritten in Step 6.

- [ ] **Step 3: Write the new failing tests** (append to `AdminDriftPage.test.jsx`):

```js
  it('shows a determinate progress bar with phase label while sweeping', async () => {
    vi.useFakeTimers();
    try {
      api.admin.getDriftStatus
        .mockResolvedValueOnce({ running: false, phase: null, pagesScanned: 0, totalPages: 0 }) // mount check
        .mockResolvedValueOnce({ running: true, phase: 'frontmatter', pagesScanned: 84, totalPages: 312 }) // first poll
        .mockResolvedValue({ running: false, phase: null, pagesScanned: 0, totalPages: 0 }); // then done
      api.admin.runDriftSweep.mockResolvedValue({ state: 'RUNNING' });
      // after completion, summary's sweptAt has advanced
      api.admin.getDriftSummary
        .mockResolvedValueOnce(SUMMARY)                                   // mount
        .mockResolvedValue({ ...SUMMARY, sweptAt: '2026-06-10T06:00:00Z' }); // completion check

      render(<AdminDriftPage />);
      await act(async () => {}); // flush mount
      fireEvent.click(screen.getByTestId('drift-run-now'));
      await act(async () => {}); // flush trigger + immediate first poll

      expect(screen.getByTestId('drift-progress')).toBeInTheDocument();
      expect(screen.getByTestId('drift-progress-label'))
        .toHaveTextContent('84 / 312 pages — validating frontmatter');

      await act(async () => { await vi.advanceTimersByTimeAsync(1000); }); // next poll → done
      await act(async () => {});
      expect(screen.queryByTestId('drift-progress')).not.toBeInTheDocument();
      expect(screen.getByTestId('drift-run-now')).toBeEnabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('detects an in-flight sweep on mount and shows the bar', async () => {
    api.admin.getDriftStatus.mockResolvedValue(
      { running: true, phase: 'frontmatter', pagesScanned: 10, totalPages: 50 });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-progress')).toBeInTheDocument());
    expect(screen.getByTestId('drift-run-now')).toBeDisabled();
    expect(screen.getByTestId('drift-progress-label'))
      .toHaveTextContent('10 / 50 pages — validating frontmatter');
  });

  it('renders a full bar with a phase label for the shacl phase', async () => {
    api.admin.getDriftStatus.mockResolvedValue(
      { running: true, phase: 'shacl', pagesScanned: 50, totalPages: 50 });
    render(<AdminDriftPage />);
    await waitFor(() => expect(screen.getByTestId('drift-progress-label'))
      .toHaveTextContent('checking SHACL conformance'));
  });
```

- [ ] **Step 4: Run to verify failure**

Run: `npx vitest run src/components/admin/AdminDriftPage.test.jsx`
Expected: the 3 new tests FAIL (no `drift-progress` element; mount doesn't enter sweeping).

- [ ] **Step 5: Implement the progress bar component.** Add to `AdminDriftPage.jsx` (a sibling function like `DriftRowGroup`):

```jsx
const PHASE_LABELS = {
  frontmatter: 'validating frontmatter',
  shacl: 'checking SHACL conformance',
  persisting: 'saving snapshot',
};

function DriftProgressBar({ progress }) {
  const phase = progress?.phase;
  const label = PHASE_LABELS[phase] || 'starting…';
  const total = progress?.totalPages || 0;
  const scanned = progress?.pagesScanned || 0;
  const perPage = phase === 'frontmatter' && total > 0;
  const pct = perPage ? Math.min(100, Math.round((scanned / total) * 100)) : phase ? 100 : 8;
  const text = perPage ? `${scanned} / ${total} pages — ${label}` : label;
  return (
    <div className="drift-progress" data-testid="drift-progress">
      <div
        className="drift-progress-track"
        role="progressbar"
        aria-label={text}
        aria-valuemin={0}
        aria-valuemax={perPage ? total : undefined}
        aria-valuenow={perPage ? scanned : undefined}
      >
        <div className="drift-progress-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="drift-progress-label" data-testid="drift-progress-label">{text}</div>
    </div>
  );
}
```

- [ ] **Step 6: Refactor `runNow` + add the mount status check + render the bar.** Replace the constants, state, mount effect, and `runNow` in `AdminDriftPage`:

Constants (replace the existing two):

```jsx
const STATUS_POLL_INTERVAL_MS = 1000;
const MAX_POLLS = 120; // 1s × 120 ≈ 2 minutes
const COLUMNS = 7;
```

Add progress state (next to the other `useState` calls):

```jsx
  const [progress, setProgress] = useState(null);
```

Add a stable status-polling driver (place above the mount effect; `before` is the pre-sweep `sweptAt`):

```jsx
  // Poll /status every second while a sweep runs; when it stops, confirm a NEW
  // sweep landed (sweptAt advanced) before declaring done — triggerAsync returns
  // 202 before the worker flips `running`, and a fast sweep can finish before the
  // first poll, so `running=false` alone is not a completion signal.
  const startStatusPolling = useCallback((before) => {
    let tries = 0;
    const poll = async () => {
      tries += 1;
      try {
        const st = await api.admin.getDriftStatus();
        if (!mounted.current) return;
        if (st?.running) {
          setProgress(st);
        } else {
          const s = await api.admin.getDriftSummary();
          if (!mounted.current) return;
          if (s?.sweptAt && s.sweptAt !== before) {
            const t = await api.admin.getDriftTrend(30);
            if (!mounted.current) return;
            setSummary(s);
            setTrend(t);
            setPagesByKey({});
            setExpandedKey(null);
            setProgress(null);
            setSweeping(false);
            return;
          }
          // running=false but no new sweep yet → startup window; keep polling.
        }
      } catch {
        // Transient status/summary poll error — ignore and retry next tick.
        if (!mounted.current) return;
      }
      if (tries >= MAX_POLLS) {
        setActionError('Sweep did not complete within 2 minutes — reload to check its status.');
        setProgress(null);
        setSweeping(false);
        return;
      }
      pollTimer.current = setTimeout(poll, STATUS_POLL_INTERVAL_MS);
    };
    poll();
  }, []);
```

Replace the mount effect to also fetch status and resume a running sweep:

```jsx
  useEffect(() => {
    let cancelled = false;
    Promise.all([api.admin.getDriftSummary(), api.admin.getDriftTrend(30), api.admin.getDriftStatus()])
      .then(([s, t, st]) => {
        if (cancelled) return;
        setSummary(s);
        setTrend(t);
        if (st?.running) {
          setSweeping(true);
          setProgress(st);
          startStatusPolling(s?.sweptAt ?? null);
        }
      })
      .catch(err => { if (!cancelled) setError(err.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [startStatusPolling]);
```

(Remove the now-unused `loadAll` useCallback if nothing else references it.)

Replace `runNow`:

```jsx
  const runNow = async () => {
    setSweeping(true);
    setActionError(null);
    setProgress({ running: true, phase: 'frontmatter', pagesScanned: 0, totalPages: 0 });
    const before = summary?.sweptAt ?? null;
    try {
      await api.admin.runDriftSweep();
    } catch (err) {
      if (mounted.current) {
        setActionError(err.status === 409 ? 'A sweep is already running.' : err.message);
        setProgress(null);
        setSweeping(false);
      }
      return;
    }
    startStatusPolling(before);
  };
```

Render the bar — add it right after the `{actionError && ...}` line, before the `{!hasSweep ? ...}` block, so it shows even during the very first sweep (no summary yet):

```jsx
      {sweeping && progress && <DriftProgressBar progress={progress} />}
```

- [ ] **Step 7: CSS.** Append to `wikantik-frontend/src/styles/admin.css` (check the file for existing custom-property names like `--admin-accent`/`--admin-border`; if they exist use them, otherwise the literal fallbacks below are fine):

```css
.drift-progress {
  margin: 0.75rem 0;
}
.drift-progress-track {
  width: 100%;
  height: 0.5rem;
  background: var(--admin-border, #e2e8f0);
  border-radius: 0.25rem;
  overflow: hidden;
}
.drift-progress-fill {
  height: 100%;
  background: var(--admin-accent, #3b82f6);
  transition: width 0.3s ease;
}
.drift-progress-label {
  margin-top: 0.25rem;
  font-size: 0.85rem;
  color: var(--admin-muted, #64748b);
}
```

- [ ] **Step 8: Run to verify pass**

```bash
npx vitest run src/components/admin/AdminDriftPage.test.jsx
```
Expected: all green (existing + 3 new). If the fake-timer test hangs, mirror the prior task's resolution: avoid RTL `waitFor` under fake timers; drive with `await act(async () => { await vi.advanceTimersByTimeAsync(...) })` and assert synchronously. If a flake appears, re-run the file alone.

- [ ] **Step 9: Full frontend suite**

```bash
npx vitest run
```
Expected: all green.

- [ ] **Step 10: Commit**

```bash
git add wikantik-frontend/src/api/client.js \
        wikantik-frontend/src/components/admin/AdminDriftPage.jsx \
        wikantik-frontend/src/styles/admin.css \
        wikantik-frontend/src/components/admin/AdminDriftPage.test.jsx
git commit -m "feat(drift): progress bar — getDriftStatus client, determinate bar, race-safe poll, mount resume

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Docs + final verification

**Files:**
- Modify: `docs/OntologyManagement.md` (one line in the "Measuring drift" section)

- [ ] **Step 1: Doc touch.** In `docs/OntologyManagement.md`, in the "Measuring drift" section's endpoint list, append `GET /admin/drift/status` so the surface list is complete:

Change the endpoints line to include status, e.g.:
`Endpoints: GET /admin/drift/summary, GET /admin/drift/trend?days=N, GET /admin/drift/pages?family=F&code=C, GET /admin/drift/status, POST /admin/drift/sweep.`

- [ ] **Step 2: Commit the doc**

```bash
git add docs/OntologyManagement.md
git commit -m "docs: note /admin/drift/status in the drift section

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 3: Full unit reactor**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS. (Known parallel-flaky `DefaultPageManagerTest` / wikantik-main provider tests pass in isolation — re-run alone before chasing.)

- [ ] **Step 4: Frontend suite**

Run: `cd wikantik-frontend && npx vitest run`
Expected: all green.

- [ ] **Step 5: Full IT reactor — sequential, detached** (house rule; the status endpoint is additive, `AdminDriftIT` already exercises the sweep):

```bash
nohup mvn clean install -Pintegration-tests -fae > /tmp/drift-progress-it.log 2>&1 &
# poll /tmp/drift-progress-it.log for "^[INFO] BUILD (SUCCESS|FAILURE)"
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Done.** Do not push — the user decides when to push.

---

## Self-review notes (already applied)

- Type/name consistency across tasks: `SweepProgress(running, phase, pagesScanned, totalPages)`, `progress()`, `/admin/drift/status`, client `getDriftStatus`, testids `drift-progress` / `drift-progress-label` — all consistent Task 1→3.
- Race-safety: `running=false` is never treated as "done" on its own — completion requires `sweptAt` to advance past the captured pre-trigger value (Task 3 `startStatusPolling`), covering both the 202-before-thread-start window and a sweep that finishes before the first poll.
- Reset-on-failure: progress fields reset in the same `finally` that releases the single-flight flag (Task 1), so no failure path leaves a stale "running" status — pinned by `progressResetsAfterRepositoryFailure`.
- Existing tests preserved: Task 3 Step 2 adds the `getDriftStatus` idle mock to `beforeEach` before any behavior change, so the current green tests stay green.
- No web.xml / SpaRoutingFilter change — `/admin/drift/*` and the `/admin/drift` SPA route already exist.
