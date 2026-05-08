# AdminTable + bulk-action pattern (V1)

**Status:** ready
**Estimated effort:** 1.5–2 days

## Goal

Ship a reusable `AdminTable` component that codifies the multi-select + sticky-action-bar + confirm-and-apply pattern, plus three reference adoptions (Users, API Keys, KG ProposalReviewQueue) that demonstrate three distinct action shapes.

V1 is **synchronous server-side bulk endpoints** with **pagination-scoped selection**. The backend response envelope is shaped so V2 can upgrade to an async job model without breaking the UI.

## Non-goals (defer to V2)

- Async job model with polling.
- Selection survival across reload / route change (URL-encoded selection).
- Type-to-confirm friction scaling for very large operations (V1: single confirm modal).
- Undo affordance on toasts.
- ⌘⇧A select-all-pages (V1: ⌘A selects current page only).

## Scope

### A. Foundation — `AdminTable` + primitives

New module: `wikantik-frontend/src/components/admin/table/`

```
AdminTable.jsx        // top-level component, opinionated common case
SelectionBar.jsx      // sticky toolbar shown when N selected
BulkActionMenu.jsx    // action buttons / overflow menu
ConfirmBulkModal.jsx  // generic confirm dialog for destructive bulk ops
useTableSelection.js  // hook: { selected, toggle, toggleAll, isSelected,
                      //         isAllSelected, isIndeterminate, clear,
                      //         shiftClickRange }
index.js              // public exports
AdminTable.test.jsx
useTableSelection.test.js
```

#### `AdminTable` props

```ts
{
  rows: T[],
  getRowKey: (row: T) => string,
  columns: Array<{
    id: string,
    label: string,
    render?: (row: T) => ReactNode,
    sortable?: boolean,
    width?: string | number,
    align?: 'left' | 'right' | 'center',
  }>,

  // Selection / bulk
  selectable?: boolean,                     // default false
  bulkActions?: BulkAction<T>[],            // surfaces SelectionBar when N > 0
  onBulkAction?: (action: BulkAction<T>, selectedRows: T[], reason?: string)
    => Promise<BulkResult>,

  // Search / sort
  searchable?: boolean | {
    placeholder?: string,
    filterFn?: (row: T, query: string) => boolean,  // default substring across rendered text
  },
  initialSort?: { columnId: string, direction: 'asc' | 'desc' },

  // Loading / empty
  loading?: boolean,
  loadingLabel?: string,                    // default 'Loading…'
  emptyState?: ReactNode,                   // default helpful copy via emptyMessage
  emptyMessage?: string,                    // shorthand for empty state body

  // Density
  density?: 'compact' | 'comfortable',      // default 'comfortable'

  // Row interactions
  rowAction?: (row: T) => Array<{ id, label, variant?, onClick }>,
  onRowClick?: (row: T) => void,
}
```

#### `BulkAction<T>` shape

```ts
type BulkAction<T> = {
  id: string,
  label: string,
  variant?: 'default' | 'primary' | 'danger',  // styles the button + confirm intent
  // Confirm modal config. `true` uses a generic "Are you sure?" dialog.
  confirm?: boolean | {
    title: string,
    body: (selected: T[]) => ReactNode,
    confirmLabel?: string,
  },
  // Optional free-form reason input collected before dispatch (e.g. reject reason).
  reason?: {
    label: string,
    placeholder?: string,
    required?: boolean,
  },
  // Optional disable predicate; returning a string shows it as a tooltip.
  disabled?: (selected: T[]) => false | string,
}
```

#### `BulkResult` (response envelope; mirrors server contract)

```ts
type BulkResult = {
  succeeded: string[],                            // row keys
  failed: Array<{ id: string, error: string }>,
  // V1 always 'completed'. V2 can return 'pending' + jobId for async.
  status: 'completed' | 'pending',
  jobId?: string,
  message?: string,
}
```

### B. Behaviour invariants

- **Indeterminate header checkbox** when 0 < selected < visible.
- **Shift-click range** between two row checkboxes.
- **⌘A / Ctrl-A** when the table has focus → select all *visible* rows.
- **Esc** clears selection.
- Selection bar is **sticky** at the top of the table viewport once any rows are selected; transparent until that point.
- Selection bar shows: `(N selected) | <action buttons> | Clear`.
- **More ▾ overflow** when the bar would have >4 actions.
- **Confirm modal** body shows up to 5 sample rows (`bob, alice, charlie + 12 more`) so the operator sees what they're about to mutate.
- **Per-row status icon** during dispatch: pending → spinner, done → checkmark, failed → red dot with hover-tooltip showing `error`.
- **Toast on completion** with `N succeeded, M failed`. If M > 0, toast has a `Retry failed` button that re-dispatches just the failed IDs.
- **Selection survives sort + filter**, lost on pagination change (per V1 scope) and on dispatch success.
- Selection bar's `Clear` button or Esc clears selection without firing actions.

### C. CSS / visual

Extend `wikantik-frontend/src/styles/admin.css`:

- `.admin-table--compact` row height 32px, `.admin-table--comfortable` 44px (default).
- `.admin-selection-bar` — sticky positioned, subtle elevation, primary background.
- `.admin-selection-bar--floating` — shown when scrolled past the natural bar position.
- `.admin-row-status-icon` — 16px, three states.
- `.admin-checkbox-indeterminate` — distinct from checked (dash icon).
- New variables for selection-bar background and border accents.

### D. Backend — three new bulk endpoints (sync, same envelope)

Following the existing admin REST conventions (`POST` + JSON body + admin-gated):

1. **`POST /admin/users/bulk-action`**
   ```json
   { "action": "lock" | "unlock" | "delete" | "add-to-group",
     "ids": ["bob", "alice"],
     "group": "editors"          // only for add-to-group
   }
   ```
   Response:
   ```json
   { "succeeded": ["bob"],
     "failed": [{"id": "alice", "error": "...."}],
     "status": "completed",
     "message": "1 of 2 users locked" }
   ```

2. **`POST /admin/api-keys/bulk-action`**
   ```json
   { "action": "revoke", "ids": ["key-1", "key-2"] }
   ```
   Same response envelope.

3. **`POST /admin/kg/proposals/bulk-action`**
   ```json
   { "action": "approve" | "reject" | "judge",
     "ids": ["proposal-1", "proposal-2"],
     "reason": "duplicate"       // for reject
   }
   ```
   Same envelope.

Each bulk endpoint:
- Loops over `ids` server-side, applies the per-item operation.
- Returns success/failure per id (does **not** abort on first failure).
- Emits a single audit log entry per bulk call: `INFO bulk action={action} resource={users|api-keys|kg-proposals} actor={principal} attempted=N succeeded=M failed=K`.
- Reuses existing per-item permission checks per id (so a partial failure due to ACL is reflected in `failed`, not 403 on the whole call).

### E. API client additions

`wikantik-frontend/src/api/client.js`:

```js
api.admin.bulkUserAction = (action, ids, opts = {}) =>
  request('/admin/users/bulk-action', { method: 'POST', body: JSON.stringify({ action, ids, ...opts }) });

api.admin.bulkApiKeyAction = (action, ids) =>
  request('/admin/api-keys/bulk-action', { method: 'POST', body: JSON.stringify({ action, ids }) });

api.knowledge.bulkProposalAction = (action, ids, opts = {}) =>
  request('/admin/kg/proposals/bulk-action', { method: 'POST', body: JSON.stringify({ action, ids, ...opts }) });
```

### F. Reference adoptions

**1. `AdminUsersPage`** — Currently has per-row Edit/Lock/Delete. Add:
- Selection column on left.
- `bulkActions`:
  - `Lock` (variant: default, confirm: 'Lock {N} users?')
  - `Unlock` (variant: default, confirm: 'Unlock {N} users?')
  - `Delete` (variant: danger, confirm with sample list)
  - `Add to group…` (no confirm — opens existing group-picker modal, dispatches with `group` param)

**2. `AdminApiKeysPage`** — Currently has per-row Revoke. Add:
- Selection column.
- `bulkActions`:
  - `Revoke` (variant: danger, confirm with sample list showing key labels)

**3. `ProposalReviewQueue`** — Currently has per-row Approve / Reject / JudgeNow. Add:
- Selection column.
- `bulkActions`:
  - `Approve` (variant: primary)
  - `Reject` (variant: danger, reason: required)
  - `Judge` (variant: default, no confirm — runs the LLM judge on selected proposals)

Per-row buttons stay on each row for single-item ergonomics.

### G. Tests

- **Unit:** `useTableSelection` covers toggle / toggleAll / shift-range / indeterminate / clear.
- **Component:** `AdminTable` renders rows, surfaces selection bar at N>0, fires `onBulkAction` with selected rows + chosen action + reason.
- **Server:** one IT per bulk endpoint asserting partial-success semantics (mix of valid + invalid ids returns 200 with split arrays).
- **E2E unit on each adoption:** the existing test files (`AdminApiKeysPage.test.jsx`, `AdminUsersPage` will get one) gain a `bulk revoke / bulk lock / bulk approve` test.

## Deliverables checklist

- [ ] `AdminTable` + primitives + tests, public exports via `components/admin/table/index.js`
- [ ] CSS additions in `admin.css`
- [ ] 3 backend bulk endpoints (registered in `wikantik-rest`)
- [ ] 3 admin REST resource classes (or extensions of existing) wiring the endpoints
- [ ] API client wrappers (3)
- [ ] `AdminUsersPage` adopts `AdminTable` for the user list with bulk actions
- [ ] `AdminApiKeysPage` adopts for the key list with bulk-revoke
- [ ] `ProposalReviewQueue` adopts for the proposal list with bulk approve/reject/judge
- [ ] Unit tests for hook + component
- [ ] Tests for the 3 bulk endpoints (Mockito unit + a Cargo IT for at least one)
- [ ] Updated existing adoption tests
- [ ] Full IT reactor green
- [ ] One commit, pushed to origin/main

## Constraints

- Per CLAUDE.md: never `git add -A`; never silent catches.
- Per `feedback_full_it_after_targeted_fix.md`: full IT before commit.
- Per `feedback_mcp_write_surface_pairing.md`: bulk endpoints are write surface — pair Mockito unit + Cargo IT per endpoint.
- Per `reference_docker_cleanup.md`: clear stale pgvector containers before IT.
- Per `feedback_subagent_worktree_cleanup.md`: clean up worktrees after.
- Worktree it-db.properties workaround: `cp /home/jakefear/source/jspwiki/wikantik-it-tests/it-db.properties wikantik-it-tests/it-db.properties` before IT.
- **Do not push if IT fails.** The boot-ordering canary `WikiEngineTest#engineBoot_renderingPipelineConvertsMarkdownToHtml` must stay green.

## Done when

- The 3 reference screens have working bulk actions visible in the local Tomcat after redeploy.
- Each bulk endpoint returns the standard envelope on a happy path AND on a mixed-id path.
- Existing per-row actions on the 3 screens continue to work unchanged.
- Full IT reactor green.
