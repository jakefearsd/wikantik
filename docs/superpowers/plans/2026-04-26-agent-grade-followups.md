# Agent-Grade Content — Post-Phase-6 Follow-Ups

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close out the two open engineering follow-ups from the Agent-Grade Content design — the React admin dashboard at `/admin/retrieval-quality` (Phase 5b) and the structural-index incremental-update bug surfaced during Phases 3, 4, and 5 smoke-testing.

**Scope-check note:** These are two independent subsystems (frontend SPA vs. backend event wiring). They share only their context — both are "what's left over after Agent-Grade Content shipped". Both can be executed in either order; pick whichever has the cheaper unblock first.

**Out-of-original-scope items** that have since closed themselves and need no work:

- `${guice.version}` pom.xml break — resolved by the parallel session on 2026-04-25.
- `AdminExtractionResourceTest` constructor-arity break — resolved by the parallel session at 23:54:58 on 2026-04-25 (file timestamps confirmed; test passes 11/11).

---

## Project A — Phase 5b React admin dashboard at `/admin/retrieval-quality`

**Goal:** Surface the `RetrievalQualityRunner` data already in `retrieval_runs` to a React page under `/admin`, mirroring the patterns established by `AdminApiKeysPage`, `AdminContentPage`, and `AdminVerificationResource`'s consumers.

**Architecture:**
- The JSON endpoint `/admin/retrieval-quality` (REST, both GET + POST `/run`) already exists from Phase 5 (commits `da6c139b2..1786f181d`). No backend change.
- Add `AdminRetrievalQualityPage.jsx` under `wikantik-frontend/src/components/admin/`. Pattern: exactly as `AdminApiKeysPage.jsx` — `useState` + `useEffect` + an `api.admin.*` call, table render, action buttons. No new dependencies.
- Extend `wikantik-frontend/src/api/client.js` with `api.admin.listRetrievalRuns()` + `api.admin.runRetrievalNow(querySetId, mode)`.
- Add a `<NavLink to="/admin/retrieval-quality">Retrieval</NavLink>` to `AdminLayout.jsx` alongside the existing six nav links.
- Add a route in whichever file owns the admin route table (likely `App.jsx` or a `Routes.jsx` — the discovery is task A1).
- Sparkline component: keep it dependency-free for the first cut. A small `<Sparkline>` SVG component built inline (no `recharts` / `victory` etc. unless the project already uses one). Each metric (nDCG@5, nDCG@10, Recall@20, MRR) gets a row showing the latest value + a 30-day mini-trend.

**Tech stack delta:** None. React + Vite already ship with the WAR per CLAUDE.md.

### File structure

**New files:**
```
wikantik-frontend/src/components/admin/
    AdminRetrievalQualityPage.jsx           — top-level page component
    Sparkline.jsx                           — dependency-free SVG sparkline
    AdminRetrievalQualityPage.test.jsx      — Vitest component tests (mirrors AdminApiKeysPage.test.jsx pattern)
```

**Modified files:**
```
wikantik-frontend/src/api/client.js         — add api.admin.listRetrievalRuns() + .runRetrievalNow()
wikantik-frontend/src/components/admin/AdminLayout.jsx
                                            — add Retrieval nav link
<route file>                                — add /admin/retrieval-quality route (file TBD in A1)
```

### Task A1 — Discover the admin route table

**Files:**
- Read: `wikantik-frontend/src/App.jsx`, `wikantik-frontend/src/Routes.jsx`, `wikantik-frontend/src/main.jsx`

- [ ] **Step 1: Find where existing admin routes are wired**

Run: `grep -rn "admin/security\|admin/knowledge\|admin/apikeys" wikantik-frontend/src --include="*.jsx" --include="*.js"`

Expected: identifies the file(s) with `<Route path="/admin/...">` declarations. Note the file path; it's the one to modify in Task A6.

No commit for this task — discovery only. Document the answer in your scratch notes.

### Task A2 — `api.admin.listRetrievalRuns()` + `api.admin.runRetrievalNow()`

**Files:**
- Modify: `wikantik-frontend/src/api/client.js`

- [ ] **Step 1: Read the existing `api.admin.*` shape for naming convention**

Run: `grep -n 'admin\.\|api\.admin' wikantik-frontend/src/api/client.js | head -20`

Identify the verb naming style (`listX` / `getX` / `createX`) and the axios-or-fetch wrapper used.

- [ ] **Step 2: Add the two methods**

In `wikantik-frontend/src/api/client.js`, in whichever object exposes the admin API:

```js
listRetrievalRuns: async ({ querySetId, mode, limit = 30 } = {}) => {
  const params = new URLSearchParams();
  if ( querySetId ) params.set( 'query_set_id', querySetId );
  if ( mode )       params.set( 'mode', mode );
  if ( limit )      params.set( 'limit', String( limit ) );
  const qs = params.toString();
  return fetchJson( `/admin/retrieval-quality${qs ? '?' + qs : ''}` );
},

runRetrievalNow: async ( querySetId, mode ) => {
  return fetchJson( `/admin/retrieval-quality/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query_set_id: querySetId, mode }),
  } );
},
```

Adapt to match the file's existing wrapper (`fetchJson`, `axios`, etc.).

- [ ] **Step 3: Compile-check**

Run: `npm --prefix wikantik-frontend run typecheck` (if the project uses TypeScript) OR `npm --prefix wikantik-frontend run lint` (if ESLint is configured) OR `npm --prefix wikantik-frontend run build`.

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "$(cat <<'EOF'
feat(frontend,api): admin client for retrieval-quality runs

Adds api.admin.listRetrievalRuns and api.admin.runRetrievalNow over
the GET / POST /admin/retrieval-quality endpoints shipped in AG-Phase 5.
No backend change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task A3 — Dependency-free `Sparkline` SVG component

**Files:**
- Create: `wikantik-frontend/src/components/admin/Sparkline.jsx`

- [ ] **Step 1: Write the component**

```jsx
import React from 'react';

/**
 * Dependency-free SVG sparkline. `values` is an array of numbers;
 * the component renders a polyline scaled to the given width × height.
 * Null/undefined values are dropped (no gaps drawn).
 */
export default function Sparkline({ values, width = 120, height = 24, stroke = 'currentColor' }) {
  const cleaned = (values || []).filter(v => typeof v === 'number' && Number.isFinite(v));
  if (cleaned.length < 2) {
    return <svg width={width} height={height} aria-hidden="true" />;
  }
  const min = Math.min(...cleaned);
  const max = Math.max(...cleaned);
  const range = max - min || 1;
  const stepX = width / (cleaned.length - 1);
  const points = cleaned.map((v, i) => {
    const x = i * stepX;
    const y = height - ((v - min) / range) * height;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(' ');
  return (
    <svg width={width} height={height} role="img" aria-label="trend sparkline">
      <polyline fill="none" stroke={stroke} strokeWidth="1.5" points={points} />
    </svg>
  );
}
```

- [ ] **Step 2: Add a tiny smoke test**

Create `wikantik-frontend/src/components/admin/Sparkline.test.jsx` (Vitest pattern — mirror `AdminApiKeysPage.test.jsx`):

```jsx
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import Sparkline from './Sparkline';

describe('Sparkline', () => {
  it('renders an svg with a polyline when given >=2 numeric values', () => {
    const { container } = render(<Sparkline values={[0.6, 0.7, 0.8]} />);
    const polyline = container.querySelector('polyline');
    expect(polyline).not.toBeNull();
    expect(polyline.getAttribute('points')).toMatch(/^[\d.,\s]+$/);
  });

  it('renders an empty svg when fewer than 2 valid values', () => {
    const { container } = render(<Sparkline values={[0.6]} />);
    expect(container.querySelector('polyline')).toBeNull();
  });
});
```

- [ ] **Step 3: Run the test**

Run: `npm --prefix wikantik-frontend test -- Sparkline`
Expected: 2 tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/Sparkline.jsx \
        wikantik-frontend/src/components/admin/Sparkline.test.jsx
git commit -m "$(cat <<'EOF'
feat(frontend,admin): dependency-free Sparkline SVG component

Used by AdminRetrievalQualityPage to show 30-day trend per metric.
Pure SVG, no recharts/victory dependency.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task A4 — `AdminRetrievalQualityPage.jsx`

**Files:**
- Create: `wikantik-frontend/src/components/admin/AdminRetrievalQualityPage.jsx`
- Create: `wikantik-frontend/src/components/admin/AdminRetrievalQualityPage.test.jsx`

- [ ] **Step 1: Read the AdminApiKeysPage skeleton for the page-component pattern**

Run: `head -100 wikantik-frontend/src/components/admin/AdminApiKeysPage.jsx`

Note: useState + useEffect + try/catch around the api call + loading/error rendering. Mirror this.

- [ ] **Step 2: Write the page component**

```jsx
import { useState, useEffect, useMemo } from 'react';
import { api } from '../../api/client';
import Sparkline from './Sparkline';
import '../../styles/admin.css';

const MODES = ['bm25', 'hybrid', 'hybrid_graph'];
const METRICS = ['ndcg_at_5', 'ndcg_at_10', 'recall_at_20', 'mrr'];

export default function AdminRetrievalQualityPage() {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filterSet, setFilterSet] = useState('');
  const [filterMode, setFilterMode] = useState('');
  const [running, setRunning] = useState(false);

  const loadRuns = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.admin.listRetrievalRuns({
        querySetId: filterSet || undefined,
        mode:       filterMode || undefined,
        limit:      30,
      });
      setRuns(data.runs || []);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadRuns(); }, [filterSet, filterMode]);

  const runNow = async (querySetId, mode) => {
    setRunning(true);
    try {
      await api.admin.runRetrievalNow(querySetId, mode);
      await loadRuns();
    } catch (err) {
      setError(err.message);
    } finally {
      setRunning(false);
    }
  };

  // Bucket runs by (querySetId, mode) so each cell shows a sparkline of recent values.
  const cells = useMemo(() => {
    const out = {};
    for (const r of runs) {
      const k = `${r.query_set_id}|${r.mode}`;
      if (!out[k]) out[k] = { querySetId: r.query_set_id, mode: r.mode, latest: r, history: [] };
      out[k].history.push(r);
    }
    return Object.values(out);
  }, [runs]);

  if (loading) return <div className="admin-page"><p>Loading retrieval-quality runs…</p></div>;
  if (error)   return <div className="admin-page"><p className="error">Error: {error}</p></div>;

  return (
    <div className="admin-page">
      <h2>Retrieval Quality</h2>
      <div className="admin-toolbar">
        <label>Query set: <input type="text" value={filterSet} onChange={e => setFilterSet(e.target.value)} placeholder="(any)" /></label>
        <label>Mode:
          <select value={filterMode} onChange={e => setFilterMode(e.target.value)}>
            <option value="">(any)</option>
            {MODES.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
        </label>
      </div>

      <table className="admin-table">
        <thead>
          <tr>
            <th>Query set</th><th>Mode</th>
            {METRICS.map(m => <th key={m}>{m}</th>)}
            <th>Run</th>
          </tr>
        </thead>
        <tbody>
          {cells.length === 0 && <tr><td colSpan={2 + METRICS.length + 1}><em>No runs yet.</em></td></tr>}
          {cells.map(cell => (
            <tr key={`${cell.querySetId}-${cell.mode}`}>
              <td>{cell.querySetId}</td>
              <td>{cell.mode}</td>
              {METRICS.map(metric => {
                const series = cell.history.map(r => r[metric]).filter(v => typeof v === 'number');
                const latest = cell.latest[metric];
                return (
                  <td key={metric}>
                    <div>{typeof latest === 'number' ? latest.toFixed(3) : '—'}</div>
                    <Sparkline values={series.slice().reverse()} />
                  </td>
                );
              })}
              <td>
                <button disabled={running} onClick={() => runNow(cell.querySetId, cell.mode)}>
                  {running ? '…' : 'Run now'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 3: Add a smoke test**

Create `wikantik-frontend/src/components/admin/AdminRetrievalQualityPage.test.jsx`:

```jsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AdminRetrievalQualityPage from './AdminRetrievalQualityPage';

vi.mock('../../api/client', () => ({
  api: {
    admin: {
      listRetrievalRuns: vi.fn().mockResolvedValue({
        runs: [
          { query_set_id: 'core-agent-queries', mode: 'hybrid',
            ndcg_at_5: 0.42, ndcg_at_10: 0.55, recall_at_20: 0.71, mrr: 0.38 },
          { query_set_id: 'core-agent-queries', mode: 'hybrid',
            ndcg_at_5: 0.45, ndcg_at_10: 0.58, recall_at_20: 0.74, mrr: 0.40 },
        ],
      }),
      runRetrievalNow: vi.fn(),
    },
  },
}));

describe('AdminRetrievalQualityPage', () => {
  it('renders one row per (set, mode) bucket with the latest value + sparkline', async () => {
    render(<AdminRetrievalQualityPage />);
    await waitFor(() => screen.getByText('core-agent-queries'));
    expect(screen.getByText('hybrid')).toBeInTheDocument();
    expect(screen.getByText('0.420')).toBeInTheDocument(); // latest first row
    expect(screen.getByRole('button', { name: /Run now/ })).toBeInTheDocument();
  });
});
```

- [ ] **Step 4: Run the tests**

Run: `npm --prefix wikantik-frontend test -- AdminRetrievalQualityPage`
Expected: 1 test, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminRetrievalQualityPage.jsx \
        wikantik-frontend/src/components/admin/AdminRetrievalQualityPage.test.jsx
git commit -m "$(cat <<'EOF'
feat(frontend,admin): AdminRetrievalQualityPage (Phase 5b)

React page surfacing /admin/retrieval-quality. Buckets runs by
(query_set, mode), shows latest value + 30-day Sparkline per metric,
plus a Run now button. Vitest smoke covers the happy path.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task A5 — Wire route + nav link

**Files:**
- Modify: the route table file discovered in A1
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.jsx`

- [ ] **Step 1: Add the route**

In whichever file owns admin routes, add a sibling to the existing `/admin/security` / `/admin/apikeys` routes:

```jsx
import AdminRetrievalQualityPage from './components/admin/AdminRetrievalQualityPage';
// …inside the route table:
<Route path="/admin/retrieval-quality" element={<AdminRetrievalQualityPage />} />
```

- [ ] **Step 2: Add the nav link**

In `wikantik-frontend/src/components/admin/AdminLayout.jsx`, after the existing `<NavLink to="/admin/apikeys">`:

```jsx
<NavLink to="/admin/retrieval-quality" className={({ isActive }) => `admin-nav-link ${isActive ? 'active' : ''}`}>
  Retrieval
</NavLink>
```

- [ ] **Step 3: Build the frontend bundle**

Run: `npm --prefix wikantik-frontend run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminLayout.jsx <route-file>
git commit -m "$(cat <<'EOF'
feat(frontend,admin): wire AdminRetrievalQualityPage route + nav link

Surfaces the new page under /admin/retrieval-quality alongside the
existing six admin pages.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task A6 — Manual smoke against deployed WAR

- [ ] **Step 1: Rebuild + redeploy**

```bash
mvn install -DskipITs -pl wikantik-war -am -q
tomcat/tomcat-11/bin/shutdown.sh || true
sleep 3
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/health/structural-index | grep -q '^200$'; do sleep 2; done
echo "ready"
```

- [ ] **Step 2: Hit the admin page**

In a browser (logged in as admin or `testbot`), visit `http://localhost:8080/admin/retrieval-quality`.

Expected: page renders with whatever runs are in `retrieval_runs`. If the table is empty, click `Run now` for `core-agent-queries` × `hybrid`; refresh; the row should appear.

No commit for this task — verification only.

---

## Project B — Structural-index incremental-update fix

**Goal:** REST-saved pages should appear on `/api/structure/sitemap` (and via `/api/pages/by-id/{canonical_id}`, and on `/for-agent` projection) without a Tomcat restart. Currently they don't — the issue surfaced repeatedly during AG-Phase 3 / 4 / 5 smoke tests and was deferred to its own follow-up.

**Architecture / suspected root cause:**

The structural index registers a listener on `PageManager`:
```java
WikiEventManager.addWikiEventListener( pageManager, this );
```

But `PageEventFilter.fireEvent` fires events with the filter itself as the source:
```java
WikiEventManager.fireEvent( this, event );   // 'this' is the PageEventFilter, not pageManager
```

`WikiEventManager` routes events to listeners by source identity. Listeners attached to `pageManager` never receive events fired *from* `PageEventFilter`. This explains why the bootstrap rebuild (which scans the filesystem at startup) sees new pages but the incremental listener never fires for REST saves.

The fix has two reasonable shapes:

1. **Re-register on the right source.** Have `StructuralIndexEventListener` register on the `PageEventFilter` (or `FilterManager`, depending on which actually owns the source identity) instead of on `pageManager`.
2. **Bridge the event chain.** Have `PageEventFilter.postSave` re-fire the event on `pageManager` after firing on `this`, so listeners on either source get it.

Option 1 is the lower-blast-radius fix (no behaviour change for existing listeners on pageManager — which currently get *nothing* either, so the bridge would also surface other latent listeners). Option 2 is a wider repair that may help other subsystems but warrants its own audit.

This plan executes Option 1 and leaves Option 2 as a deferred audit item.

### File structure

**Modified files:**
```
wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralIndexEventListener.java
                                          — register on the right source
wikantik-main/src/test/java/com/wikantik/knowledge/structure/
    StructuralIndexEventListenerTest.java — assertion: a fired POST_SAVE reaches onPageSaved
```

### Task B1 — Reproduce + identify the actual event source

**Files:**
- Read: `wikantik-main/src/main/java/com/wikantik/filters/PageEventFilter.java` (lines around `fireEvent`)
- Read: `wikantik-main/src/main/java/com/wikantik/filters/DefaultFilterManager.java`

- [ ] **Step 1: Confirm the source mismatch**

Run: `grep -n "WikiEventManager\.\(fireEvent\|addWikiEventListener\)" wikantik-main/src/main/java -r`

For every `fireEvent(X, ...)` and every `addWikiEventListener(X, ...)`, write down `X`. The ones that share `X` form a working source/listener pair; the ones that don't are dead listeners.

**Expected finding** (per the investigation in this plan's preamble): `PageEventFilter.fireEvent` fires with `this` (a PageEventFilter instance) as source, but `StructuralIndexEventListener` registers on `pageManager` — they don't pair. Confirm.

- [ ] **Step 2: Determine how to obtain the live source instance**

The structural index needs a reference to the *running* `PageEventFilter` instance to register on it. Two paths:
- Look up via the engine's `FilterManager`: `engine.getManager(FilterManager.class).getPageFilter(PageEventFilter.class)` (if such an accessor exists; check the interface).
- If no direct accessor, register on the FilterManager itself (events bubble up from the filters it owns? — verify in code).

Document the chosen path.

No commit for this task — investigation only.

### Task B2 — Failing test that locks the regression

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/structure/StructuralIndexEventListenerTest.java`

- [ ] **Step 1: Add a failing test**

If `StructuralIndexEventListenerTest` doesn't already exist, mirror the test pattern from `RecentChangesAdapterTest`:

```java
@Test
void post_save_event_reaches_on_page_saved() {
    // Arrange: a real WikiEventManager + the listener registered on the chosen source.
    final DefaultStructuralIndexService service = mock( DefaultStructuralIndexService.class );
    final StructuralIndexEventListener listener = new StructuralIndexEventListener( service );
    listener.register( /* the source the listener should bind to per Task B1's finding */ );

    // Act: fire a POST_SAVE event from the same source the production code fires it from.
    WikiEventManager.fireEvent( /* the production source */, new WikiPageEvent(
            /* source */, WikiPageEvent.POST_SAVE, "TestPage" ) );

    // Assert: onPageSaved was called.
    verify( service, timeout( 1000 ) ).onPageSaved( "TestPage" );
}
```

- [ ] **Step 2: Run the test to confirm it fails today**

Run: `mvn test -pl wikantik-main -Dtest=StructuralIndexEventListenerTest#post_save_event_reaches_on_page_saved -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — `onPageSaved` was never called (the regression).

### Task B3 — Fix `StructuralIndexEventListener.register` to bind to the right source

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralIndexEventListener.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — pass the right source to `.register(...)`

- [ ] **Step 1: Update `register()` signature + body to take the production event source**

Change `register( final PageManager pageManager )` to take whichever object Task B1 identified as the actual event source.

```java
public void register( final Object eventSource ) {
    WikiEventManager.addWikiEventListener( eventSource, this );
    LOG.info( "Structural index event listener registered on {}", eventSource.getClass().getSimpleName() );
}
```

- [ ] **Step 2: Update WikiEngine wiring**

In `wikantik-main/src/main/java/com/wikantik/WikiEngine.java`, find the existing line:
```java
new StructuralIndexEventListener( structuralIndex )
    .register( getManager( PageManager.class ) );
```

Change it to register on the source identified in Task B1.

- [ ] **Step 3: Run the regression test — should now pass**

Run: `mvn test -pl wikantik-main -Dtest=StructuralIndexEventListenerTest#post_save_event_reaches_on_page_saved -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS.

- [ ] **Step 4: Run the existing structural-spine tests to confirm no break**

Run: `mvn test -pl wikantik-main -Dtest='StructuralIndexEventListenerTest,DefaultStructuralIndexServiceTest,StructuralSpinePageFilterTest' -am -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/structure/StructuralIndexEventListener.java \
        wikantik-main/src/main/java/com/wikantik/WikiEngine.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/structure/StructuralIndexEventListenerTest.java
git commit -m "$(cat <<'EOF'
fix(knowledge,structure): register StructuralIndexEventListener on the right event source

PageEventFilter fires WikiPageEvent.POST_SAVE with itself as the
event source, not with PageManager. StructuralIndexEventListener was
registered on PageManager, so it never got the events — REST-saved
pages didn't show up in /api/structure/sitemap or /for-agent until
Tomcat restart. Fixed by registering on the event-firing source
identified by the existing tests.

Surfaced repeatedly during AG-Phase 3, 4, and 5 smoke-testing;
deferred until now per the post-Phase-6 follow-up plan.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task B4 — Manual smoke

- [ ] **Step 1: Rebuild + redeploy**

```bash
mvn install -DskipITs -pl wikantik-war -am -q
tomcat/tomcat-11/bin/shutdown.sh || true
sleep 3
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
until curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/health/structural-index | grep -q '^200$'; do sleep 2; done
```

- [ ] **Step 2: Save a page through REST and confirm it shows up immediately**

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
JSON_PAYLOAD=$(jq -n --arg c '---
type: article
title: B4 Smoke Page
---
body content' '{content: $c, markupSyntax: "markdown"}')
curl -u "${login}:${password}" -X PUT -H "Content-Type: application/json" \
     -d "$JSON_PAYLOAD" http://localhost:8080/api/pages/B4SmokePage
sleep 2
echo "--- should appear in sitemap WITHOUT a restart ---"
curl -s http://localhost:8080/api/structure/sitemap | jq '.data.pages[] | select(.slug == "B4SmokePage")'
```

Expected: the page appears in the sitemap output. If absent, the fix didn't land — investigate.

- [ ] **Step 3: Cleanup**

```bash
curl -u "${login}:${password}" -X DELETE http://localhost:8080/api/pages/B4SmokePage
```

No commit for this task.

---

## Self-review checklist

1. **Spec coverage.**
   - Phase 5b's six tasks (A1–A6) cover discovery → API client → component → page → wiring → smoke. ✅
   - Structural-index bug's four tasks (B1–B4) cover investigation → failing test → fix → smoke. ✅
2. **No placeholders.** Code blocks contain working code; commands are executable; expected outputs are concrete. ✅ (One placeholder *is* present and intentional: Task B2's test mocks the source object that Task B1 must identify; the placeholder is named and traced explicitly.)
3. **Type consistency.** Frontend tasks reference `api.admin.listRetrievalRuns`, `api.admin.runRetrievalNow`, `Sparkline`, `AdminRetrievalQualityPage` — all consistent. ✅
4. **Build commands.** Each task has explicit `npm` or `mvn` commands. ✅
5. **Commit isolation.** Each task is one commit, specific files, no `git add -A`. ✅
6. **CLAUDE.md guardrails.** TDD (failing test first in B2); commits go to `main`; per-module test commands; final smoke against deployed WAR. ✅

---

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| `PageEventFilter` source-identity audit (Option 2 from B's preamble) | Wider blast radius; warrants its own design pass to identify all latent listeners |
| Threshold calibration for `wikantik_retrieval_ndcg_at_5` smoke gate | Operational, not engineering — wait for ~2 weeks of nightly baseline data |
| Recharts/victory chart library upgrade for the admin dashboard | Sparkline is dependency-free for the first cut; richer charts are a follow-up if real demand emerges |
| `core-agent-queries` query-set expansion past 16 entries | Editorial work, not engineering |
| Trusted-authors registry growth | Editorial / single-developer constraint; covered in `AgentCookbook.md`'s new "Editorial / ongoing" section |

---

## Verification (after Tasks A6 + B4)

- `mvn clean install -T 1C -DskipITs` green across all 26 modules.
- `npm --prefix wikantik-frontend test` green.
- `npm --prefix wikantik-frontend run build` green.
- Manual smoke: `/admin/retrieval-quality` page renders + "Run now" works + new metrics appear after a run.
- Manual smoke: a REST-saved page appears on `/api/structure/sitemap` without Tomcat restart.

---

## Next phases

After this plan ships, the engineering surface for the Agent-Grade Content design is fully closed. Remaining items are operational (threshold calibration after baseline data) or editorial (cookbook expansion, trusted-author registry growth) and do not require further engineering passes.
