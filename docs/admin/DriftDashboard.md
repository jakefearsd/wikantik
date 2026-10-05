# Drift Dashboard

This page is for curators and administrators who keep the wiki's metadata healthy. It
explains the **Drift** screen at `/admin/drift`, what each row means, and what to do
about it, and it covers two related checks that have no dashboard panel:
structural conflicts and stale citations. Drift is metadata that has wandered away from
the schema: it never breaks a page, but it weakens search, the Knowledge Graph and the
ontology, so the dashboard tracks the count over time as a burn-down.

## Read the dashboard

The screen (`AdminDriftPage.jsx`, backed by `AdminDriftResource`) shows the latest
sweep and compares it to the one before.

| Element | Meaning |
|---|---|
| Last sweep, Triggered by, Pages scanned, Duration | when the latest sweep ran, whether it was `scheduled` or `manual`, and how much it covered |
| **SHACL not checked** badge | the ontology subsystem was off or failed during the sweep, so the `shacl` rows are missing, not zero |
| Table rows | one row per `(family, code, severity)`: **Family**, **Code**, **Severity**, **Count** |
| **Δ** | change in count since the previous sweep (`+n` or `-n`); an em dash means the code did not exist in the previous sweep |
| **Trend** | sparkline of that code's count across the sweeps of the last 30 days |
| **Pages** (Show/Hide) | expands a live list of the offending pages for that code |
| **Run sweep now** | starts a sweep in the background; the screen shows progress until it finishes |

The page list under **Show** is recomputed when you open it, never read from the
snapshot, so it shows what is wrong now. If you already fixed the pages it says so
(`No pages currently affected`). Each entry links to the page's editor and shows the
field, the message and, where one exists, a suggested replacement.

### How sweeps run

A sweep validates the frontmatter of every page and, when the ontology is enabled,
checks the materialised graph against the SHACL shapes. Only one sweep runs at a time;
a second request returns HTTP 409 and the screen shows "A sweep is already running."
Sweeps run in two ways:

- **Scheduled**: after each ontology rebuild completes (by default every
  `wikantik.ontology.rebuild.interval.hours`, 24).
- **Manual**: **Run sweep now**, or `POST /admin/drift/sweep` (returns 202).

You can also poll the REST endpoints directly:

```bash
curl -u admin:... http://localhost:8080/admin/drift/summary
curl -u admin:... 'http://localhost:8080/admin/drift/trend?days=30'
curl -u admin:... 'http://localhost:8080/admin/drift/pages?family=frontmatter&code=tags.kebab'
curl -u admin:... http://localhost:8080/admin/drift/status
```

`days` is clamped to 1-365.

## Fix each kind of drift

### Family `frontmatter`

These come from `SchemaDrivenFrontmatterValidator`, the same validator that runs on every
save, so a code you see here is also what an editor sees in the frontmatter editor. Fix
the page and the count drops at the next sweep. Most are warnings, so pages still save.
The `Severity` column tells you which.

| Code | Severity | What it means | What to do |
|---|---|---|---|
| `yaml.parse` | ERROR | the page's frontmatter block is not valid YAML | open the page, fix the YAML (quote values that contain a colon); `GET /admin/frontmatter-issues` lists these with line and column |
| `type.noncanonical`, `status.noncanonical` | WARNING (ERROR if `wikantik.frontmatter.enum.nonCanonical.severity=error`) | value is outside the canonical set (`type`: article, hub, reference, runbook, design; `status`: draft, active, archived) | change to a canonical value; the message names a suggested one when it knows it |
| `audience.enum.invalid` | ERROR | `audience` holds a value other than `humans`, `agents` or `both` | correct the value or list |
| `summary.length` | WARNING | `summary` is outside 50-160 characters | rewrite it to fit |
| `aliases.length`, `aliases.list`, `aliases.blank` | WARNING | an alias is too long (over 100), the field is not a list, or an entry is blank | clean up the alias list |
| `cluster.slug.malformed` | WARNING | `cluster` is not lowercase kebab-case | use the suggested slug, or rename the cluster everywhere with `rename_cluster` |
| `cluster.undeclared` | WARNING | the page names a cluster that no hub page declares | create the hub (`type: hub` plus `cluster: <path>`) or move the page to a declared cluster |
| `cluster.hub.multivalued` | ERROR | a hub page lists more than one cluster; a hub declares exactly one | reduce the hub's `cluster` to a single value |
| `tags.kebab` | WARNING | a tag is not lowercase kebab-case | rewrite the tag |
| `related.unresolved` | WARNING | an entry in `related` does not match an existing page | fix the name or remove the entry |
| `date.date.malformed`, `verified_at.date.malformed` | WARNING | `date` is not an ISO date, or `verified_at` is not an ISO timestamp | rewrite as `YYYY-MM-DD` or ISO-8601 |
| `verified_by.untrusted` | WARNING | `verified_by` is not on the trusted-authors list, so confidence may be reduced | verify the page as a trusted author, or add the author to the registry |
| `runbook.*` (`missing_block`, `malformed_block`, `when_to_use_empty`, `steps_too_few`, `pitfalls_empty`, `related_tool_invalid`, `reference_unresolvable`) | ERROR | a `type: runbook` page whose `runbook:` block fails the schema | complete the `runbook:` block |

Frontmatter fields themselves are described in [Frontmatter.md](../user/Frontmatter.md).

### Family `shacl`

These rows come from checking the ontology graph against its SHACL shapes. The **Code**
is the property path of the violated shape (for example a `wk:` predicate), and the
severity is always `ERROR`. A violation that is not tied to a property path is counted
under `node-scoped`. Expand a row to see the focus node and the message; the usual fix
is in the Knowledge Graph (correct or remove the offending edge in
**Admin → Knowledge Graph**) or in the page that produced it. The write-time gate
refuses new non-conformant edges for the predicates that have shapes, so this family mostly
measures edges that predate it or that no shape guards at write time. The same
list is at `GET /admin/ontology/violations`; see
[OntologyManagement.md](OntologyManagement.md).

## Structural conflicts

Structural conflicts are defects in the Page Graph's structural index: missing
identifiers and a malformed cluster taxonomy. They are **not** shown on the Drift
screen. List them with:

```bash
curl -u admin:... http://localhost:8080/admin/page-graph/conflicts
curl -u admin:... 'http://localhost:8080/admin/page-graph/conflicts?kind=HEADLESS_CLUSTER'
```

Each entry has `slug`, `canonical_id` (when present), `kind` and `detail`. The
`kind` filter is case-insensitive. The endpoint returns 503 when the structural index
is unavailable. The seven kinds (`StructuralConflict.Kind`):

| Kind | Meaning | What to do |
|---|---|---|
| `MISSING_CANONICAL_ID` | the page has no `canonical_id` and was indexed under a synthesised id | save the page again; the save filter assigns a `canonical_id` automatically |
| `RELATION_ISSUE` | the page declares a relation the validator rejected | correct the relation in the page's frontmatter |
| `DUPLICATE_CLUSTER_DECLARATION` | two or more hubs declare the same cluster; reported once per losing hub (the lowest slug wins) | keep one hub per cluster and change or retire the others |
| `HEADLESS_CLUSTER` | a cluster has member pages but no hub declares it (subject is the cluster path) | create a `type: hub` page with that `cluster:` value |
| `UNDECLARED_CLUSTER` | a sub-cluster (`parent/child`) names a parent that no hub declares | declare the parent with a hub page |
| `CLUSTERLESS_HUB` | a `type: hub` page carries no `cluster:`, so it declares nothing | add `cluster: <path>` to the hub |
| `MULTI_CLUSTER_HUB` | a hub names more than one cluster; only the first is treated as declared | leave a single cluster on the hub |

Duplicate declarations are refused at save time only when
`wikantik.cluster_declaration.enforcement.enabled` is `true`; it defaults to `false`,
so duplicates can still exist. Enable it only after you confirm none do.

## Stale citations

A page can ground a claim in another page's section with inline `cite://` markup. At
save time those citations are parsed into the `citations` table, pinned to the target's
version and hashed by span, and graded as the target changes. Staleness is a patient
curation task, never a save-time error, and a changed target version alone does not make a
citation stale. Citation tracking is on by
default (`wikantik.citations.enabled`) and needs a datasource. The dashboard has no
citation panel; use the endpoint, or the `list_stale_citations` tool on `/knowledge-mcp`
(see [McpAgents.md](McpAgents.md)):

```bash
curl -u admin:... http://localhost:8080/admin/drift/citations
curl -u admin:... 'http://localhost:8080/admin/drift/citations?page=SomePage'
```

Without `page` you get the corpus-wide counts. With `page` you also get that page's
**outbound** citations (claims it makes) and its **inbound** citations that are no
longer `current` (other pages relying on it). Each entry has the source and target
canonical ids, target heading path, span text, claim text, status and pinned version.

| Status | Meaning | What to do |
|---|---|---|
| `current` | the pinned span is still present in the target's cited section | nothing |
| `stale` | span drift: the pinned span no longer appears in that section (the text changed, moved, or the heading was renamed) | re-read the target; if the claim still holds, edit the citation's quoted span or repoint it, which creates a new citation pinned to the target's current version; if not, correct the claim |
| `target_missing` | the target page was truly deleted (a rename does not count) | point the citation at the new location, or remove the claim |

When citations are disabled the endpoint answers 200 with zero counts and empty lists.
Citations are a third edge type, separate from Page Graph wikilinks and Knowledge Graph
entities; the design is in
[the citation-edges design](../superpowers/specs/2026-06-14-phase-3-citation-edges-design.md).

## Where to go next

- [AdminPanel.md](AdminPanel.md): the other admin screens.
- [Frontmatter.md](../user/Frontmatter.md): the fields that drift codes refer to.
- [OntologyManagement.md](OntologyManagement.md): rebuilding the ontology and reading SHACL results.
- [the drift-dashboard design](../superpowers/specs/2026-06-09-drift-dashboard-design.md): why the dashboard works this way.
