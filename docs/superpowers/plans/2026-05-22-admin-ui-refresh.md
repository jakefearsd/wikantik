# Admin UI Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat, KG-dominated admin top-nav with a grouped, mode-switching left sidebar, a hybrid (editorial-chrome / tool-density) visual layer, and a sectioned Overview dashboard backed by one aggregation endpoint.

**Architecture:** Frontend React SPA (Vite) under `wikantik-frontend/src/components/admin/`; backend servlet resources in `wikantik-rest` extending `RestServletBase`, registered in `wikantik-war/.../web.xml`, behind `AdminAuthFilter`. Dashboard data comes from one new `GET /admin/overview` endpoint that aggregates existing sources (Micrometer registry via `MeterRegistryHolder`, DB counts via existing services, in-memory `LlmActivityLog`/`IndexStatusSnapshot`) with per-card graceful degradation.

**Tech Stack:** Java 21, Maven, Gson, Micrometer, Servlet API; React 18, React Router, Vitest. Design spec: `docs/superpowers/specs/2026-05-22-admin-ui-refresh-design.md`.

**Phasing:** Phase 1 = navigation shell & IA. Phase 2 = page scaffold + hybrid density. Phase 3 = backend `/admin/overview`. Phase 4 = Overview dashboard frontend. Each phase ships working software.

---

## File Structure

**Phase 1 — Navigation shell & IA**
- Create: `wikantik-frontend/src/components/admin/AdminSidebar.jsx` — grouped, context-swap admin rail.
- Create: `wikantik-frontend/src/components/admin/AdminSidebar.test.jsx`.
- Modify: `wikantik-frontend/src/App.jsx:50-56` — render `<AdminSidebar/>` instead of `<Sidebar/>` when `isAdminRoute`.
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.jsx` — drop top-nav; become the role-gated content shell.
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.test.jsx` (if absent, create).
- Modify: `wikantik-frontend/src/styles/admin.css` — `.admin-sidebar*` styles; admin density base.

**Phase 2 — Page scaffold & hybrid density**
- Create: `wikantik-frontend/src/components/admin/PageHeader.jsx` + `.test.jsx`.
- Create: `wikantik-frontend/src/components/admin/EmptyState.jsx` + `.test.jsx`.
- Modify: each admin page to use `PageHeader` (Users, Content, Security, API Keys, Knowledge, Retrieval, KG Policy).
- Modify: `wikantik-frontend/src/styles/admin.css` — table/form hybrid density, `.page-header*`.

**Phase 3 — Backend aggregation endpoint**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/overview/OverviewSnapshot.java` — record of card sections.
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/overview/OverviewAssembler.java` — runs collectors with per-card try/catch.
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminOverviewResource.java` — `GET /admin/overview`.
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/overview/OverviewAssemblerTest.java`.
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceTest.java`.
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` — `<servlet>` (~line 408) AND `<servlet-mapping>` (~line 613) blocks.
- Create IT: `wikantik-it-tests/.../AdminOverviewIT.java` (mirror an existing admin REST IT).

**Phase 4 — Overview dashboard frontend**
- Create: `wikantik-frontend/src/components/admin/MetricCard.jsx` + `.test.jsx`.
- Create: `wikantik-frontend/src/components/admin/OverviewDashboard.jsx` + `.test.jsx`.
- Modify: `wikantik-frontend/src/api/client.js` — add `admin.getOverview`.
- Modify: `wikantik-frontend/src/main.jsx` — `index` route → `OverviewDashboard`.
- Modify: `wikantik-frontend/src/components/Sidebar.jsx:147` — reader "Admin" section first link → `/admin` (Overview).
- Modify: `wikantik-frontend/src/styles/admin.css` — `.dashboard*`, `.metric-card*`.

---

## PHASE 1 — Navigation shell & IA

### Task 1: AdminSidebar component

**Files:**
- Create: `wikantik-frontend/src/components/admin/AdminSidebar.jsx`
- Test: `wikantik-frontend/src/components/admin/AdminSidebar.test.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
// AdminSidebar.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import AdminSidebar from './AdminSidebar';

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AdminSidebar />
    </MemoryRouter>
  );
}

describe('AdminSidebar', () => {
  it('renders the back-to-wiki link and the Overview entry', () => {
    renderAt('/admin/users');
    expect(screen.getByRole('link', { name: /back to wiki/i })).toHaveAttribute('href', '/wiki/Main');
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('href', '/admin');
  });

  it('renders all four group headings', () => {
    renderAt('/admin/users');
    ['People & Access', 'Content', 'Knowledge & Search'].forEach((g) =>
      expect(screen.getByText(g)).toBeInTheDocument()
    );
  });

  it('marks the active section link', () => {
    renderAt('/admin/security');
    expect(screen.getByRole('link', { name: 'Security' }).className).toMatch(/active/);
    expect(screen.getByRole('link', { name: 'Users' }).className).not.toMatch(/active/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminSidebar.test.jsx`
Expected: FAIL — cannot resolve `./AdminSidebar`.

- [ ] **Step 3: Write the component**

```jsx
// AdminSidebar.jsx
import { NavLink, Link } from 'react-router-dom';
import '../../styles/admin.css';

// Grouped admin navigation. Rendered by App.jsx in the rail slot in place of the
// reader Sidebar while on /admin/* (the "context swap"). The reader sidebar is
// untouched on wiki routes; "← Back to wiki" is the door out.
const GROUPS = [
  {
    title: 'People & Access',
    links: [
      { to: '/admin/users', label: 'Users' },
      { to: '/admin/security', label: 'Security' },
      { to: '/admin/apikeys', label: 'API Keys' },
    ],
  },
  {
    title: 'Content',
    links: [{ to: '/admin/content', label: 'Content & Index' }],
  },
  {
    title: 'Knowledge & Search',
    links: [
      { to: '/admin/knowledge-graph', label: 'Knowledge Graph' },
      { to: '/admin/kg-policy', label: 'KG Policy' },
      { to: '/admin/retrieval-quality', label: 'Retrieval Quality' },
    ],
  },
];

const linkClass = ({ isActive }) => `admin-sidebar-link${isActive ? ' active' : ''}`;

export default function AdminSidebar() {
  return (
    <aside className="app-sidebar admin-sidebar">
      <Link to="/wiki/Main" className="admin-sidebar-back">← Back to wiki</Link>
      <h1 className="admin-sidebar-title">Administration</h1>
      <nav className="admin-sidebar-nav">
        {/* `end` so /admin matches Overview exactly, not every /admin/* child */}
        <NavLink to="/admin" end className={linkClass}>Overview</NavLink>
        {GROUPS.map((group) => (
          <div className="admin-sidebar-group" key={group.title}>
            <div className="admin-sidebar-group-title">{group.title}</div>
            {group.links.map((l) => (
              <NavLink key={l.to} to={l.to} className={linkClass}>{l.label}</NavLink>
            ))}
          </div>
        ))}
      </nav>
    </aside>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminSidebar.test.jsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminSidebar.jsx wikantik-frontend/src/components/admin/AdminSidebar.test.jsx
git commit -m "feat(admin): grouped context-swap AdminSidebar"
```

### Task 2: Swap reader↔admin rail in App.jsx

**Files:**
- Modify: `wikantik-frontend/src/App.jsx:2-3` (import) and `:50-56` (render)

- [ ] **Step 1: Add the import**

At `App.jsx:3` (after the `Sidebar` import) add:

```jsx
import AdminSidebar from './components/admin/AdminSidebar';
```

- [ ] **Step 2: Conditionally render the rail**

Replace the `<Sidebar .../>` block at `App.jsx:50-56` with:

```jsx
      {isAdminRoute ? (
        <AdminSidebar />
      ) : (
        <Sidebar
          collapsed={sidebarCollapsed}
          onToggle={() => setSidebarCollapsed(c => !c)}
          mobileOpen={mobileOpen}
          onMobileClose={() => setMobileOpen(false)}
          onMobileOpen={() => setMobileOpen(true)}
        />
      )}
```

- [ ] **Step 3: Verify the existing App tests still pass**

Run: `cd wikantik-frontend && npx vitest run src/App.test.jsx`
Expected: PASS (if no `App.test.jsx` exists, skip — verify via the full suite in Task 4's build).

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/App.jsx
git commit -m "feat(admin): swap reader sidebar for AdminSidebar on /admin routes"
```

### Task 3: Reduce AdminLayout to a content shell

**Files:**
- Modify: `wikantik-frontend/src/components/admin/AdminLayout.jsx` (full replace)
- Test: `wikantik-frontend/src/components/admin/AdminLayout.test.jsx` (create)

- [ ] **Step 1: Write the failing test**

```jsx
// AdminLayout.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import AdminLayout from './AdminLayout';

vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => ({ user: { authenticated: true, roles: ['Admin'] }, loading: false }),
}));

describe('AdminLayout', () => {
  it('renders the routed child and no longer renders the old top-nav', () => {
    render(
      <MemoryRouter initialEntries={['/admin/users']}>
        <Routes>
          <Route path="/admin" element={<AdminLayout />}>
            <Route path="users" element={<div>USERS CONTENT</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText('USERS CONTENT')).toBeInTheDocument();
    // The old in-content nav link is gone (nav now lives in AdminSidebar).
    expect(screen.queryByRole('link', { name: 'Knowledge Graph' })).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminLayout.test.jsx`
Expected: FAIL — old layout still renders the nav link "Knowledge Graph".

- [ ] **Step 3: Replace AdminLayout**

```jsx
// AdminLayout.jsx
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';

// Content shell for the admin area. Navigation now lives in AdminSidebar (rendered
// by App.jsx in the rail slot); this component only gates on the Admin role and
// renders the routed child.
export default function AdminLayout() {
  const { user, loading } = useAuth();
  if (loading) return null;

  const isAdmin = user?.authenticated && user?.roles?.includes('Admin');
  if (!isAdmin) return <Navigate to="/wiki/Main" replace />;

  return (
    <div className="admin-content">
      <Outlet />
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/AdminLayout.test.jsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/admin/AdminLayout.jsx wikantik-frontend/src/components/admin/AdminLayout.test.jsx
git commit -m "refactor(admin): AdminLayout becomes a content shell, nav moves to sidebar"
```

### Task 4: Admin sidebar + density base styles

**Files:**
- Modify: `wikantik-frontend/src/styles/admin.css` (append a new section)

- [ ] **Step 1: Append styles**

Add to the end of `admin.css`:

```css
/* ── Admin sidebar (context-swap rail) ───────────────────────────── */
.admin-sidebar { padding: var(--space-md); background: var(--bg-sidebar); }
.admin-sidebar-back {
  display: inline-block; color: var(--accent); font-family: var(--font-ui);
  font-size: 0.8125rem; font-weight: 600; text-decoration: none; margin-bottom: var(--space-md);
}
.admin-sidebar-back:hover { color: var(--accent-hover); }
.admin-sidebar-title {
  font-family: var(--font-display); font-size: 1.25rem; font-weight: 600;
  margin: 0 0 var(--space-md);
}
.admin-sidebar-nav { display: flex; flex-direction: column; gap: 2px; }
.admin-sidebar-group { margin-top: var(--space-md); }
.admin-sidebar-group-title {
  font-family: var(--font-ui); font-size: 0.6875rem; letter-spacing: 0.08em;
  text-transform: uppercase; color: var(--text-muted); margin: 0 0 4px var(--space-sm);
}
.admin-sidebar-link {
  display: block; padding: 5px var(--space-sm); border-radius: var(--radius-sm);
  font-family: var(--font-ui); font-size: 0.875rem; color: var(--text); text-decoration: none;
}
.admin-sidebar-link:hover { background: var(--sage-light); }
.admin-sidebar-link.active { background: var(--accent); color: #fff; }

/* ── Admin density layer (tool-grade data regions; chrome stays editorial) ── */
.admin-content { font-family: var(--font-ui); }
```

- [ ] **Step 2: Build the frontend to confirm CSS + components compile**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/ && npm run build`
Expected: tests PASS; Vite build succeeds.

- [ ] **Step 3: Commit**

```bash
git add wikantik-frontend/src/styles/admin.css
git commit -m "style(admin): sidebar styles and admin density base"
```

---

## PHASE 2 — Page scaffold & hybrid density

### Task 5: PageHeader component

**Files:**
- Create: `wikantik-frontend/src/components/admin/PageHeader.jsx` + `.test.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
// PageHeader.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import PageHeader from './PageHeader';

describe('PageHeader', () => {
  it('renders title and description', () => {
    render(<PageHeader title="Users" description="Manage accounts." />);
    expect(screen.getByRole('heading', { name: 'Users' })).toBeInTheDocument();
    expect(screen.getByText('Manage accounts.')).toBeInTheDocument();
  });

  it('renders the actions slot', () => {
    render(<PageHeader title="Users" actions={<button>New</button>} />);
    expect(screen.getByRole('button', { name: 'New' })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/PageHeader.test.jsx`
Expected: FAIL — cannot resolve `./PageHeader`.

- [ ] **Step 3: Write the component**

```jsx
// PageHeader.jsx
// Uniform admin page header: serif title (editorial chrome) + description +
// right-aligned actions slot. Adopted by every admin page so headers stop drifting.
export default function PageHeader({ title, description, actions }) {
  return (
    <header className="page-header">
      <div className="page-header-text">
        <h1 className="page-header-title">{title}</h1>
        {description && <p className="page-header-desc">{description}</p>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </header>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/PageHeader.test.jsx`
Expected: PASS.

- [ ] **Step 5: Add styles to `admin.css`**

```css
/* ── Page header (uniform across admin pages) ────────────────────── */
.page-header {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: var(--space-md); margin-bottom: var(--space-lg);
  padding-bottom: var(--space-md); border-bottom: 1px solid var(--border);
}
.page-header-title {
  font-family: var(--font-display); font-size: 1.375rem; font-weight: 600; margin: 0;
}
.page-header-desc {
  font-family: var(--font-ui); font-size: 0.8125rem; color: var(--text-secondary);
  margin: 4px 0 0;
}
.page-header-actions { display: flex; gap: var(--space-sm); flex-shrink: 0; }
```

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/admin/PageHeader.jsx wikantik-frontend/src/components/admin/PageHeader.test.jsx wikantik-frontend/src/styles/admin.css
git commit -m "feat(admin): shared PageHeader component"
```

### Task 6: EmptyState component

**Files:**
- Create: `wikantik-frontend/src/components/admin/EmptyState.jsx` + `.test.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
// EmptyState.test.jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import EmptyState from './EmptyState';

describe('EmptyState', () => {
  it('renders the message and optional action', () => {
    render(<EmptyState message="No users yet." action={<button>Add</button>} />);
    expect(screen.getByText('No users yet.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/EmptyState.test.jsx`
Expected: FAIL — cannot resolve `./EmptyState`.

- [ ] **Step 3: Write the component**

```jsx
// EmptyState.jsx
// Uniform empty-list state for admin tables/sections.
export default function EmptyState({ message, action }) {
  return (
    <div className="admin-empty-state">
      <p className="admin-empty-message">{message}</p>
      {action && <div className="admin-empty-action">{action}</div>}
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/EmptyState.test.jsx`
Expected: PASS.

- [ ] **Step 5: Add styles to `admin.css` and commit**

```css
/* ── Empty state ─────────────────────────────────────────────────── */
.admin-empty-state {
  text-align: center; padding: var(--space-2xl) var(--space-md); color: var(--text-muted);
}
.admin-empty-message { font-family: var(--font-ui); font-size: 0.9375rem; margin: 0 0 var(--space-md); }
```

```bash
git add wikantik-frontend/src/components/admin/EmptyState.jsx wikantik-frontend/src/components/admin/EmptyState.test.jsx wikantik-frontend/src/styles/admin.css
git commit -m "feat(admin): shared EmptyState component"
```

### Task 7: Adopt PageHeader across admin pages + hybrid table density

**Files (modify, one commit per page is fine):**
- `AdminUsersPage.jsx`, `AdminContentPage.jsx`, `AdminSecurityPage.jsx`, `AdminApiKeysPage.jsx`, `AdminKnowledgePage.jsx`, `AdminRetrievalQualityPage.jsx`, `AdminKgPolicyPage.jsx`
- `wikantik-frontend/src/styles/admin.css`

- [ ] **Step 1: For each page, replace its ad-hoc title block with `PageHeader`**

Pattern (illustrated for `AdminUsersPage.jsx` — apply the equivalent to each page, moving any existing "create"/primary button into the `actions` prop):

```jsx
import PageHeader from './PageHeader';
// ...inside the returned JSX, as the first child of the page wrapper:
<PageHeader
  title="Users"
  description="Manage accounts, roles, and access."
  actions={<button className="btn btn-primary" onClick={() => setModalOpen(true)}>+ New user</button>}
/>
```

Per-page titles/descriptions to use:
- Users — "Manage accounts, roles, and access."
- Content & Index — "Rebuild indexes, refresh content, inspect chunks."
- Security — "Policy grants and groups."
- API Keys — "Issue and revoke programmatic access keys."
- Knowledge Graph — "Curate extracted entities, proposals, and hubs."
- Retrieval Quality — "Nightly retrieval evaluation runs and trends."
- KG Policy — "Cluster inclusion/exclusion policy for entity extraction."

- [ ] **Step 2: Apply hybrid density to the shared table in `admin.css`**

The `.admin-table` already exists at `admin.css:108`; tighten its data density and uppercase the headers. Append override rules (do not delete the originals):

```css
/* ── Hybrid table density (tool-grade rows; titles stay serif) ───── */
.admin-table { font-family: var(--font-ui); font-size: 0.8125rem; }
.admin-table th {
  font-size: 0.6875rem; letter-spacing: 0.04em; text-transform: uppercase;
  color: var(--text-muted); padding: 8px 16px;
}
.admin-table td { padding: 8px 16px; }
```

- [ ] **Step 3: Run the admin component tests + build**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/ && npm run build`
Expected: all admin tests PASS; build succeeds. Fix any test that asserted the old header markup.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/Admin*.jsx wikantik-frontend/src/styles/admin.css
git commit -m "feat(admin): adopt PageHeader on all pages; hybrid table density"
```

---

## PHASE 3 — Backend aggregation endpoint

**Shared mechanism — reading metric values in-process.** The shared Micrometer
registry is reachable via `com.wikantik.observability.MeterRegistryHolder.get()`
(returns a `MeterRegistry`, possibly null in tests). Read a gauge:
`reg.find("wikantik_backpressure.inflight").gauge()` → `.value()`; a counter:
`reg.find("name").counter()` → `.count()`. Always null-check the meter.

### Task 8: OverviewSnapshot record + a metric-read helper

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/overview/OverviewSnapshot.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/overview/MetricReads.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/overview/MetricReadsTest.java`

- [ ] **Step 1: Write the failing test for the helper**

```java
package com.wikantik.rest.overview;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricReadsTest {
    @Test
    void gaugeReturnsValueWhenPresentElseDefault() {
        final MeterRegistry reg = new SimpleMeterRegistry();
        reg.gauge( "test.g", 42.0 );
        assertEquals( 42.0, MetricReads.gauge( reg, "test.g", -1.0 ) );
        assertEquals( -1.0, MetricReads.gauge( reg, "missing", -1.0 ) );
        assertEquals( -1.0, MetricReads.gauge( null, "test.g", -1.0 ) );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=MetricReadsTest`
Expected: FAIL — `MetricReads` does not exist.

- [ ] **Step 3: Write `MetricReads` and `OverviewSnapshot`**

```java
// MetricReads.java
package com.wikantik.rest.overview;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Null-safe reads of current values from the shared Micrometer registry. */
public final class MetricReads {
    private MetricReads() {}

    public static double gauge( final MeterRegistry reg, final String name, final double dflt ) {
        if ( reg == null ) return dflt;
        final Gauge g = reg.find( name ).gauge();
        return g == null ? dflt : g.value();
    }

    public static double counter( final MeterRegistry reg, final String name, final double dflt ) {
        if ( reg == null ) return dflt;
        final Counter c = reg.find( name ).counter();
        return c == null ? dflt : c.count();
    }
}
```

```java
// OverviewSnapshot.java
package com.wikantik.rest.overview;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * The assembled dashboard payload. Each card is a free-form {@link JsonObject}
 * (or null if its collector failed). {@code degraded} names the cards that could
 * not be assembled, mirroring the /for-agent projection's degradation contract.
 */
public record OverviewSnapshot( JsonObject cards, List<String> degraded ) {}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=MetricReadsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/overview/ wikantik-rest/src/test/java/com/wikantik/rest/overview/MetricReadsTest.java
git commit -m "feat(admin-overview): OverviewSnapshot record + MetricReads helper"
```

### Task 9: OverviewAssembler with per-card degradation

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/overview/OverviewAssembler.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/overview/OverviewAssemblerTest.java`

The assembler holds a list of named collectors (`Map<String, Supplier<JsonObject>>`).
For each, it runs the supplier inside try/catch; on success the card JSON is added
under its key; on exception it logs `LOG.warn` with the card name and adds the name
to `degraded`. This is the only place the 14 cards' wiring lives.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.rest.overview;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class OverviewAssemblerTest {
    @Test
    void healthyCollectorsLandUnderTheirKeys() {
        final Map<String, Supplier<JsonObject>> collectors = new LinkedHashMap<>();
        collectors.put( "load", () -> { final JsonObject o = new JsonObject(); o.addProperty( "inflight", 3 ); return o; } );
        final OverviewSnapshot snap = new OverviewAssembler( collectors ).assemble();
        assertTrue( snap.cards().has( "load" ) );
        assertEquals( 3, snap.cards().getAsJsonObject( "load" ).get( "inflight" ).getAsInt() );
        assertTrue( snap.degraded().isEmpty() );
    }

    @Test
    void aThrowingCollectorDegradesOnlyItsOwnCard() {
        final Map<String, Supplier<JsonObject>> collectors = new LinkedHashMap<>();
        collectors.put( "ok", JsonObject::new );
        collectors.put( "boom", () -> { throw new IllegalStateException( "kaboom" ); } );
        final OverviewSnapshot snap = new OverviewAssembler( collectors ).assemble();
        assertTrue( snap.cards().has( "ok" ) );
        assertFalse( snap.cards().has( "boom" ) );
        assertEquals( java.util.List.of( "boom" ), snap.degraded() );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=OverviewAssemblerTest`
Expected: FAIL — `OverviewAssembler` does not exist.

- [ ] **Step 3: Write the assembler**

```java
// OverviewAssembler.java
package com.wikantik.rest.overview;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Runs each card collector with per-card try/catch so one failure can't sink the page. */
public class OverviewAssembler {
    private static final Logger LOG = LogManager.getLogger( OverviewAssembler.class );
    private final Map<String, Supplier<JsonObject>> collectors;

    public OverviewAssembler( final Map<String, Supplier<JsonObject>> collectors ) {
        this.collectors = collectors;
    }

    public OverviewSnapshot assemble() {
        final JsonObject cards = new JsonObject();
        final List<String> degraded = new ArrayList<>();
        for ( final Map.Entry<String, Supplier<JsonObject>> e : collectors.entrySet() ) {
            try {
                cards.add( e.getKey(), e.getValue().get() );
            } catch ( final RuntimeException ex ) {
                LOG.warn( "Overview card '{}' failed to assemble: {}", e.getKey(), ex.toString() );
                degraded.add( e.getKey() );
            }
        }
        return new OverviewSnapshot( cards, degraded );
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=OverviewAssemblerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/overview/OverviewAssembler.java wikantik-rest/src/test/java/com/wikantik/rest/overview/OverviewAssemblerTest.java
git commit -m "feat(admin-overview): OverviewAssembler with per-card degradation"
```

### Task 10: AdminOverviewResource — endpoint + collector wiring

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminOverviewResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

The resource extends `RestServletBase` (mirror `AdminLlmActivityResource`), builds
the collector map, runs the assembler, and writes a `{ "data": { cards…, "degraded": [...] } }`
envelope. Each collector reads its own source.

- [ ] **Step 1: Write the failing test (degradation + envelope shape)**

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AdminOverviewResourceTest {
    @Test
    void writesEnvelopeWithCardsAndDegradedList() throws Exception {
        final var resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );

        // The resource must tolerate a missing registry / services (test env):
        // every collector degrades gracefully rather than throwing out of doGet.
        new AdminOverviewResource().doGetForTesting( Mockito.mock( jakarta.servlet.http.HttpServletRequest.class ), resp );

        final JsonObject env = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertTrue( env.has( "data" ) );
        final JsonObject data = env.getAsJsonObject( "data" );
        assertTrue( data.has( "degraded" ), "envelope must always carry a degraded list" );
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=AdminOverviewResourceTest`
Expected: FAIL — `AdminOverviewResource` does not exist.

- [ ] **Step 3: Write the resource**

Mirror `AdminLlmActivityResource` for the servlet shell (license header, `doGetForTesting` seam, envelope write). Build the collector map and run the assembler:

```java
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.observability.MeterRegistryHolder;
import com.wikantik.rest.overview.MetricReads;
import com.wikantik.rest.overview.OverviewAssembler;
import com.wikantik.rest.overview.OverviewSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code GET /admin/overview} — aggregates existing data (Micrometer registry,
 * DB counts, in-memory snapshots) into one dashboard payload. Read-only; mutates
 * nothing. Auth via the shared {@code AdminAuthFilter}. Each card collector is
 * isolated by {@link OverviewAssembler} so one failure degrades only its card.
 */
public class AdminOverviewResource extends RestServletBase {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminOverviewResource.class );

    void doGetForTesting( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        doGet( req, resp );
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final MeterRegistry reg = MeterRegistryHolder.get();
        final OverviewSnapshot snap = new OverviewAssembler( collectors( reg ) ).assemble();

        final JsonObject data = snap.cards();
        final JsonArray degraded = new JsonArray();
        snap.degraded().forEach( degraded::add );
        data.add( "degraded", degraded );

        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( HttpServletResponse.SC_OK );
        resp.getWriter().write( envelope.toString() );
    }

    /** The 14 cards. Each supplier reads one source; throwing degrades just that card. */
    private Map<String, Supplier<JsonObject>> collectors( final MeterRegistry reg ) {
        final Map<String, Supplier<JsonObject>> c = new LinkedHashMap<>();

        // Exemplar (metric-backed): load / backpressure.
        c.put( "load", () -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "inflight",  (int) MetricReads.gauge( reg, "wikantik_backpressure.inflight", 0 ) );
            o.addProperty( "permitsMax", (int) MetricReads.gauge( reg, "wikantik_backpressure.permits_max", 0 ) );
            o.addProperty( "rejected",  (long) MetricReads.counter( reg, "wikantik_backpressure.rejected_total", 0 ) );
            return o;
        } );

        // Exemplar (in-memory snapshot): LLM activity cache.
        c.put( "llmActivity", () -> {
            final var snap = com.wikantik.llm.activity.LlmActivityLogHolder.get()
                    .snapshot( Integer.MAX_VALUE, null, null );
            final JsonObject o = new JsonObject();
            o.addProperty( "inFlight", snap.inFlight() );
            o.addProperty( "windowMinutes", snap.windowMinutes() );
            o.addProperty( "capacity", snap.maxRecords() );
            o.addProperty( "count", snap.calls().size() );
            o.addProperty( "errors", (int) snap.calls().stream()
                    .filter( v -> "ERROR".equals( String.valueOf( v.status() ) ) ).count() );
            return o;
        } );

        // Remaining 12 cards — same shape, each reading the source named in the
        // design spec's two card tables. Wire each as its own c.put(key, supplier):
        //   health        → HealthResource checks + getVersion()
        //   kgProposals   → KnowledgeGraphService.countProposals(status=pending)
        //   retrieval     → latest retrieval_runs aggregate (nDCG@5)
        //   searchIndex   → IndexStatusSnapshot + gauge wikantik.search.hybrid.vector_index.size
        //   users         → users table count + api_keys active count + locked count
        //   recent        → LlmActivityLog recent entries (top N)
        //   kgSize        → countNodes(), countEdges(), stub + orphan counts
        //   extractor     → counters wikantik_kg_extractor_{requests,triples_emitted,failures}_total + latency
        //   judge         → judge pending depth + counters wikantik.kg_judge.{timeouts,short_circuit_total}
        //   renderCache   → gauges/counters wikantik_cache.{hits,misses,evictions,size}
        //   auth          → counter wikantik.auth.logins + failed + locked count
        //   agentSurface  → wikantik_for_agent_response_bytes + wikantik_hub_summary_synthesis_total
        //                    + wikantik_agent_hints_derivation_failures_total
        // Each supplier MUST tolerate a null registry/service by reading defaults
        // (so the test env degrades, not crashes). Obtain DB-backed services via the
        // same engine accessor pattern other Admin*Resource classes use.
        return c;
    }
}
```

> Implementer note: wire all 14 collectors. Keep each supplier small and
> source-faithful per the spec tables. Where a DB service is unavailable in the
> servlet context, let the supplier throw — the assembler degrades that one card.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=AdminOverviewResourceTest`
Expected: PASS (the envelope always carries `degraded`; collectors needing a live registry/DB degrade in the test env).

- [ ] **Step 5: Register the servlet in web.xml (BOTH blocks)**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, beside the `AdminLlmActivityResource` entries:

`<servlet>` block (near line 406):
```xml
   <servlet>
       <servlet-name>AdminOverviewResource</servlet-name>
       <servlet-class>com.wikantik.rest.AdminOverviewResource</servlet-class>
   </servlet>
```

`<servlet-mapping>` block (near line 612):
```xml
   <servlet-mapping>
       <servlet-name>AdminOverviewResource</servlet-name>
       <url-pattern>/admin/overview</url-pattern>
   </servlet-mapping>
```

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminOverviewResource.java wikantik-rest/src/test/java/com/wikantik/rest/AdminOverviewResourceTest.java wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(admin-overview): GET /admin/overview endpoint + collectors + servlet mapping"
```

### Task 11: Wire-level integration test

**Files:**
- Create IT mirroring an existing admin REST IT (find one: `ls wikantik-it-tests/**/Admin*IT.java`).

- [ ] **Step 1: Write the IT**

Authenticate as admin (reuse the suite's admin login helper / `RestSeedHelper`),
`GET /admin/overview`, assert HTTP 200 and that the JSON has `data.degraded` (array)
and at least the metric-backed `data.load` card. Add a non-admin negative case
asserting 401/403 via `AdminAuthFilter`.

- [ ] **Step 2: Run the IT module sequentially**

Run: `mvn clean install -Pintegration-tests -fae` (NO `-T` — IT ports are fixed).
Expected: the new IT passes; full reactor green.

- [ ] **Step 3: Commit**

```bash
git add wikantik-it-tests/
git commit -m "test(admin-overview): wire-level IT for GET /admin/overview"
```

---

## PHASE 4 — Overview dashboard frontend

### Task 12: MetricCard component

**Files:**
- Create: `wikantik-frontend/src/components/admin/MetricCard.jsx` + `.test.jsx`

- [ ] **Step 1: Write the failing test**

```jsx
// MetricCard.test.jsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import MetricCard from './MetricCard';

describe('MetricCard', () => {
  it('renders label, value and meta', () => {
    render(<MetricCard label="KG proposals" value="17" meta="pending" />);
    expect(screen.getByText('KG proposals')).toBeInTheDocument();
    expect(screen.getByText('17')).toBeInTheDocument();
    expect(screen.getByText('pending')).toBeInTheDocument();
  });

  it('renders an unavailable state when degraded', () => {
    render(<MetricCard label="KG proposals" degraded />);
    expect(screen.getByText(/unavailable/i)).toBeInTheDocument();
  });

  it('links to its section when "to" is provided', () => {
    render(<MemoryRouter><MetricCard label="KG proposals" value="17" to="/admin/knowledge-graph" /></MemoryRouter>);
    expect(screen.getByRole('link')).toHaveAttribute('href', '/admin/knowledge-graph');
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/MetricCard.test.jsx`
Expected: FAIL — cannot resolve `./MetricCard`.

- [ ] **Step 3: Write the component**

```jsx
// MetricCard.jsx
import { Link } from 'react-router-dom';

// One dashboard tile. `degraded` renders a muted unavailable state (the card's
// collector failed server-side). `to` makes the whole card a link into a section.
export default function MetricCard({ label, value, meta, accent, dim, degraded, to, children }) {
  const body = degraded ? (
    <div className="metric-card-unavailable">Unavailable</div>
  ) : (
    <>
      {value != null && <div className={`metric-card-value${accent ? ' accent' : ''}`}>{value}</div>}
      {children}
      {meta && <div className="metric-card-meta">{meta}</div>}
    </>
  );
  const inner = (
    <>
      <div className="metric-card-label">{label}</div>
      {body}
    </>
  );
  const cls = `metric-card${dim ? ' dim' : ''}`;
  return to && !degraded
    ? <Link to={to} className={cls}>{inner}</Link>
    : <div className={cls}>{inner}</div>;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/MetricCard.test.jsx`
Expected: PASS (3 tests).

- [ ] **Step 5: Add styles to `admin.css` and commit**

```css
/* ── Metric cards / dashboard ────────────────────────────────────── */
.metric-card {
  display: block; border: 1px solid var(--border); border-radius: var(--radius-md);
  background: var(--bg-elevated); padding: 13px 15px; text-decoration: none; color: var(--text);
}
.metric-card.dim { background: var(--bg-sidebar); }
a.metric-card:hover { border-color: var(--border-strong); }
.metric-card-label {
  font-family: var(--font-ui); font-size: 0.625rem; letter-spacing: 0.06em;
  text-transform: uppercase; color: var(--text-muted);
}
.metric-card-value { font-family: var(--font-ui); font-size: 1.5rem; font-weight: 700; margin: 3px 0; }
.metric-card-value.accent { color: var(--accent); }
.metric-card-meta { font-family: var(--font-ui); font-size: 0.6875rem; color: var(--text-secondary); }
.metric-card-unavailable { font-family: var(--font-ui); font-size: 0.75rem; color: var(--text-muted); margin: 8px 0; }
```

```bash
git add wikantik-frontend/src/components/admin/MetricCard.jsx wikantik-frontend/src/components/admin/MetricCard.test.jsx wikantik-frontend/src/styles/admin.css
git commit -m "feat(admin): MetricCard dashboard tile"
```

### Task 13: API client method

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (inside the `admin: { … }` block, after `listUsers`)

- [ ] **Step 1: Add the method**

```js
    getOverview: () => request('/admin/overview'),
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-frontend/src/api/client.js
git commit -m "feat(admin): api.admin.getOverview"
```

### Task 14: OverviewDashboard + route flip

**Files:**
- Create: `wikantik-frontend/src/components/admin/OverviewDashboard.jsx` + `.test.jsx`
- Modify: `wikantik-frontend/src/main.jsx:67` — `index` route element.
- Modify: `wikantik-frontend/src/components/Sidebar.jsx:149` — reader "Admin" first link → `/admin`.
- Modify: `wikantik-frontend/src/styles/admin.css`.

- [ ] **Step 1: Write the failing test**

```jsx
// OverviewDashboard.test.jsx
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api } from '../../api/client';
import OverviewDashboard from './OverviewDashboard';

vi.mock('../../api/client', () => ({ api: { admin: { getOverview: vi.fn() } } }));

const payload = {
  load: { inflight: 3, permitsMax: 390, rejected: 0 },
  kgProposals: { pending: 17 },
  degraded: ['retrieval'],
};

beforeEach(() => { api.admin.getOverview.mockResolvedValue(payload); });

describe('OverviewDashboard', () => {
  it('renders the status band cards from the payload', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('17')).toBeInTheDocument());
    expect(screen.getByText('Status & action')).toBeInTheDocument();
    expect(screen.getByText('System metrics')).toBeInTheDocument();
  });

  it('renders a degraded card in its unavailable state', async () => {
    render(<MemoryRouter><OverviewDashboard /></MemoryRouter>);
    await waitFor(() => expect(screen.getAllByText(/unavailable/i).length).toBeGreaterThan(0));
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/OverviewDashboard.test.jsx`
Expected: FAIL — cannot resolve `./OverviewDashboard`.

- [ ] **Step 3: Write the component**

```jsx
// OverviewDashboard.jsx
import { useState, useEffect, useRef } from 'react';
import { api } from '../../api/client';
import PageHeader from './PageHeader';
import MetricCard from './MetricCard';
import '../../styles/admin.css';

const POLL_MS = 20000; // live cards (load, llmActivity) refresh on this cadence

// Each entry: how to render one card from the payload. `dim` marks the diagnostic band.
function statusCards(d) {
  return [
    { key: 'health',      label: 'Health',        render: (c) => <MetricCard label="Health" value={c?.status ?? '—'} meta={c?.version} degraded={!c} /> },
    { key: 'load',        label: 'Load',           render: (c) => <MetricCard label="Load" value={c ? `${c.inflight}/${c.permitsMax}` : null} meta={c ? `${c.rejected} shed` : null} degraded={!c} /> },
    { key: 'kgProposals', label: 'KG proposals',   render: (c) => <MetricCard label="KG proposals" value={c?.pending} meta="pending → review" accent to="/admin/knowledge-graph" degraded={!c} /> },
    { key: 'retrieval',   label: 'Retrieval',      render: (c) => <MetricCard label="Retrieval" value={c?.ndcg5} meta="nDCG@5" to="/admin/retrieval-quality" degraded={!c} /> },
    { key: 'llmActivity', label: 'LLM activity',   render: (c) => <MetricCard label="LLM activity" value={c?.inFlight} meta={c ? `${c.count}/${c.windowMinutes}m · ${c.errors} err` : null} accent degraded={!c} /> },
    { key: 'searchIndex', label: 'Search & index', render: (c) => <MetricCard label="Search & index" value={c?.indexable} meta={c ? `/${c.total} · ${c.embeddingsPct}% emb` : null} to="/admin/content" degraded={!c} /> },
    { key: 'users',       label: 'Users',          render: (c) => <MetricCard label="Users" value={c?.users} meta={c ? `${c.apiKeys} keys · ${c.locked} locked` : null} to="/admin/users" degraded={!c} /> },
    { key: 'recent',      label: 'Recent',         render: (c) => <MetricCard label="Recent" degraded={!c}>{c?.items && <ul className="metric-card-feed">{c.items.map((it, i) => <li key={i}>{it}</li>)}</ul>}</MetricCard> },
  ];
}

function metricCards(d) {
  return [
    { key: 'kgSize',       label: 'Knowledge Graph size', render: (c) => <MetricCard dim label="Knowledge Graph size" value={c?.nodes} meta={c ? `${c.edges} edges · ${c.stubs} stubs · ${c.orphans} orphans` : null} degraded={!c} /> },
    { key: 'extractor',    label: 'Extractor pipeline',   render: (c) => <MetricCard dim label="Extractor pipeline" value={c?.requests} meta={c ? `${c.triples} triples · ${c.failures} fail · p95 ${c.p95}` : null} degraded={!c} /> },
    { key: 'judge',        label: 'KG judge',             render: (c) => <MetricCard dim label="KG judge" value={c?.queued} meta={c ? `${c.timeouts} timeout · ${c.shortCircuit} sc` : null} degraded={!c} /> },
    { key: 'renderCache',  label: 'Render cache',         render: (c) => <MetricCard dim label="Render cache" value={c?.hitRate} meta={c ? `${c.size}/${c.capacity} · ${c.evictions} evict` : null} degraded={!c} /> },
    { key: 'auth',         label: 'Auth activity',        render: (c) => <MetricCard dim label="Auth activity" value={c?.logins} meta={c ? `${c.failed} failed · ${c.locked} locked` : null} degraded={!c} /> },
    { key: 'agentSurface', label: 'Agent surface',        render: (c) => <MetricCard dim label="Agent surface" value={c?.avgBytes} meta={c ? `avg /for-agent · ${c.hintFailures} hint fails` : null} degraded={!c} /> },
  ];
}

export default function OverviewDashboard() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const pollRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const res = await api.admin.getOverview();
        if (!cancelled) { setData(res || {}); setError(null); }
      } catch (e) {
        if (!cancelled) setError(e.message || 'Failed to load overview');
      }
    };
    load();
    pollRef.current = setInterval(load, POLL_MS);
    return () => { cancelled = true; clearInterval(pollRef.current); };
  }, []);

  if (error) return <div className="error-banner">{error}</div>;
  const d = data || {};

  return (
    <div className="dashboard page-enter">
      <PageHeader title="Overview" description="Everything you administer, at a glance." />
      <div className="dashboard-section-title">Status &amp; action</div>
      <div className="dashboard-grid status">
        {statusCards(d).map(({ key, render }) => <div key={key}>{render(d[key])}</div>)}
      </div>
      <div className="dashboard-section-title">System metrics</div>
      <div className="dashboard-grid metrics">
        {metricCards(d).map(({ key, render }) => <div key={key}>{render(d[key])}</div>)}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/OverviewDashboard.test.jsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Flip the index route and reader link**

In `main.jsx`, add the import near the other admin imports and change the index route:

```jsx
import OverviewDashboard from './components/admin/OverviewDashboard';
// ...
              <Route index element={<OverviewDashboard />} />
```

In `Sidebar.jsx:149`, change the first Admin link target from `/admin/users` to `/admin` and its label to `Overview` (keep the rest of the Admin section as-is, or trim it to just "Overview" since the admin rail now carries full nav — leave the others for a familiar entry).

- [ ] **Step 6: Add dashboard grid styles to `admin.css`**

```css
.dashboard-section-title {
  font-family: var(--font-ui); font-size: 0.625rem; letter-spacing: 0.08em;
  text-transform: uppercase; color: var(--text-muted);
  margin: var(--space-lg) 0 var(--space-sm); padding-bottom: 4px; border-bottom: 1px solid var(--border);
}
.dashboard-grid { display: grid; gap: var(--space-sm); }
.dashboard-grid.status  { grid-template-columns: repeat(4, 1fr); }
.dashboard-grid.metrics { grid-template-columns: repeat(3, 1fr); }
.metric-card-feed { list-style: none; margin: 6px 0 0; padding: 0; font-size: 0.6875rem; color: var(--text-secondary); }
@media (max-width: 900px) { .dashboard-grid.status, .dashboard-grid.metrics { grid-template-columns: repeat(2, 1fr); } }
```

- [ ] **Step 7: Build + full frontend test run**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/ && npm run build`
Expected: all admin tests PASS; build succeeds.

- [ ] **Step 8: Commit**

```bash
git add wikantik-frontend/src/components/admin/OverviewDashboard.jsx wikantik-frontend/src/components/admin/OverviewDashboard.test.jsx wikantik-frontend/src/main.jsx wikantik-frontend/src/components/Sidebar.jsx wikantik-frontend/src/styles/admin.css
git commit -m "feat(admin): sectioned Overview dashboard; index route lands here"
```

---

## Final verification (after all phases)

- [ ] **Full reactor with integration tests** (sequential, fail-at-end):

```bash
mvn clean install -Pintegration-tests -fae
```

Expected: BUILD SUCCESS. This catches cross-module breakage that targeted runs miss.

- [ ] **Deploy locally and eyeball the admin area:**

```bash
mvn clean install -Dmaven.test.skip -T 1C && bin/redeploy.sh
# Visit http://localhost:8080/admin — confirm: rail swaps in/out of admin,
# Overview lands with the two bands, degraded cards show "Unavailable" not blank,
# tables read at the new hybrid density, "← Back to wiki" returns to the reader.
```

- [ ] **Dispatch a final whole-implementation code review** (subagent-driven-development final step), then use **superpowers:finishing-a-development-branch**.

## Notes for the implementer

- **web.xml has two blocks** (`<servlet>` and `<servlet-mapping>`) — both need the new entry, in different parts of the file. Missing the mapping silently 404s.
- **Never `git add -A`** — stage the exact files listed per task.
- **Run `mvn test-compile -pl wikantik-rest`** after the resource/record signature work to catch test-source breakage `mvn compile` skips.
- **Collectors must degrade, not crash**: read defaults when a registry/service is absent, or let the supplier throw and rely on the assembler. Never an empty catch — `OverviewAssembler` already logs `LOG.warn`.
- The 12 un-exemplified collectors in Task 10 are the bulk of the backend work; each is small and its source is named in the design spec's card tables (`docs/superpowers/specs/2026-05-22-admin-ui-refresh-design.md`).
