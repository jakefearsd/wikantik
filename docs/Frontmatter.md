# Frontmatter — Structured Article Metadata

Every Wikantik page opens with a YAML **frontmatter** block: a fenced header of
`key: value` metadata above the Markdown body.

```markdown
---
title: Hybrid Retrieval
type: article
cluster: retrieval
tags: [search, embeddings, bm25]
summary: How Wikantik fuses BM25 and dense vectors with a Knowledge-Graph rerank.
status: active
date: 2026-06-10
---

# Hybrid Retrieval
…the article body…
```

In most wikis frontmatter is a loose bag of tags. In Wikantik it is the
**structured metadata layer that many subsystems read from one place** — full-text
search, the topical hierarchy, the RDF ontology, SEO/structured data, and
agent-grade retrieval all derive from the same block. Author the metadata once and
it powers all of them, kept consistent by a server-authoritative schema, a
structured editor, and live validation.

This guide is the complete reference: every supported key, what it does, the
editor and validation model, and how each field ties into the rest of the
platform.

> Two related guides go deeper on the downstream systems:
> [OntologyManagement.md](OntologyManagement.md) (the RDF/SKOS model frontmatter
> projects into) and [KgInclusionPolicy.md](KgInclusionPolicy.md) (which pages'
> entities reach the Knowledge Graph).

---

## Why structured frontmatter is a strength for article metadata

- **One source, many consumers.** A single `cluster:` value places the article in
  the topical hierarchy, becomes a SKOS concept in the ontology, drives faceted
  search, *and* governs Knowledge-Graph inclusion. You don't maintain the same
  fact in five systems.
- **Server-authoritative schema.** The fields, their widgets, and their canonical
  values are defined once on the server (`GET /api/frontmatter-schema`) and the
  editor renders from it — so the form, the validator, and the API never drift
  apart.
- **Validate-as-you-type, converge over time.** Genuinely corrupting problems are
  blocked; stylistic drift is flagged with a one-click fix but never blocks a save,
  so a large existing corpus normalizes gradually instead of breaking on edit.
- **Humans and agents author the same way.** The MCP write tools hit the exact
  same validator and receive the same field-addressed, suggestion-bearing
  messages, so AI edits stay schema-conformant.
- **Rename-stable identity.** `canonical_id` is a permanent ULID, so an article's
  ontology IRI, agent projection, and structural-spine identity survive renames.

---

## The field reference

The structured editor renders these fields in order from the default schema
(`com.wikantik.api.frontmatter.schema.FrontmatterSchema`). Keys not in the schema
are preserved verbatim (editable via the Raw-YAML tab).

### Core / identity

| Key | Type | What it is |
|---|---|---|
| `canonical_id` | read-only ULID | Rename-stable identifier. Becomes the ontology IRI `/id/page/{canonical_id}`, the `for-agent` projection key, and the structural-spine identity. System-managed — assigned on save, never hand-edited. |
| `title` | text | Human display title. |
| `type` | enum (open) | One of `article`, `hub`, `reference`, `runbook`, `design`. Selects the ontology content class **and** the SEO schema.org `@type`; `runbook` additionally unlocks the runbook block and the agent runbook surface. Off-list values are tolerated with a warning. |
| `status` | enum (open) | `draft`, `active`, `archived`. Off-list values warn but save. |
| `summary` | text (50–160) | One-sentence abstract. Indexed for search, used as the SEO meta description, and carried into the agent projection and RDF. Outside 50–160 characters warns. |
| `date` | date | Authored / last-substantive-update date (ISO `YYYY-MM-DD`). |
| `author` | text | Author name. |

### Topical / navigation

These place the article in the hierarchy and feed search and the Page Graph
structural spine.

| Key | Type | What it is |
|---|---|---|
| `cluster` | text (kebab slug) | The article's primary topical cluster. Drives hub membership, faceted navigation, the SKOS topic hierarchy, and the cluster-primary Knowledge-Graph inclusion default. Sub-clusters use `parent/sub` (→ `skos:broader`). Non-kebab values warn. |
| `tags` | list | Free tags. Each becomes a `skos:Concept` the page links via `dct:subject`, and a Lucene facet. Non-kebab tags warn. |
| `hubs` | list | Hub pages this article belongs to (structural-spine hub membership). |
| `related` | list of page names | Curated "see also" links surfaced in the reader and the structural spine. |

> **Removed — `relations:`.** The typed `relations:` frontmatter field (and its
> page-relation vocabulary) was **removed 2026-05-02**. Page Graph edges are now
> strictly real wikilinks; curated typed edges between concepts live in the
> **Knowledge Graph** as admin-approved edges, not in frontmatter. See
> [StructuralSpineDesign.md](wikantik-pages/StructuralSpineDesign.md).

### Knowledge Graph

| Key | Type | What it is |
|---|---|---|
| `kg_include` | tristate (`true` / `false` / inherit) | Per-page override of the cluster-primary, **default-exclude** KG inclusion policy. Set `true` to opt an article's extracted entities into the Knowledge Graph. See [KgInclusionPolicy.md](KgInclusionPolicy.md). |

### Agent-grade content & verification

| Key | Type | What it is |
|---|---|---|
| `audience` | enum | `humans`, `agents`, or `both`. |
| `verified_at` | datetime | When the page was last verified. |
| `verified_by` | text | Who/what verified it. |
| `confidence` | read-only | Derived trust signal, surfaced in the agent projection. |
| `agent_hints` | read-only | Derived `prefer_tools` / `prefer_pages` hints (and a `summary_synthesized` flag for hub overlays); computed by `AgentHintsDeriver` and carried in `get_page_for_agent`. |
| `runbook` | block | A procedural block, valid only on `type: runbook` pages — see [The runbook block](#the-runbook-block-type-runbook). |

### SEO / structured data

| Key | Type | What it is |
|---|---|---|
| `image` | text (path/URL) | Per-page `og:image` / `twitter:image`. Falls back to a bundled default when unset. Read by `SemanticHeadRenderer`. |

`type`, `summary`, and `canonical_id` also feed SEO automatically (see
[ties into other features](#how-frontmatter-ties-into-the-rest-of-the-platform))
— you don't author the structured-data output directly.

### Derived-page provenance

| Key | Type | What it is |
|---|---|---|
| `derived_from` | read-only | Presence marks the page as **derived** — its body is machine-owned and regenerated (extraction/reflow or connector sync), not hand-authored. Value is the retained source attachment filename. |
| `derived_connector` | read-only | The id of the [connector](Connectors.md) that owns this page, when the page came from an external-source sync rather than a manual document upload. |
| `derived_source_url` | read-only | A human-clickable URL back to the external origin, when the source URI isn't already one. |
| `derived_orphaned` | read-only | Stamped `true` when the owning connector is deleted without cascading page delete — the page is kept but its source is "no longer syncing". |

These four fields are **machine-stamped, never hand-edited**. They're written
by the ingestion/sync pipeline (`DerivedPageSinkAdapter`,
`DerivedPageIngestionService`) on create/reflow/sync and read by the reader UI
to render the provenance banner and the ↯ derived badge in lists and search
results. Editing them by hand has no lasting effect — the next reflow or sync
overwrites them from the actual source. See
[Connectors.md](Connectors.md#reader-facing-provenance) for the full
provenance model and the six connector types that produce derived pages.

---

## The structured editor

When you edit a page, a **Frontmatter** form sits beside the Markdown body (the
body stays in CodeMirror; the form owns the metadata object).

- **Schema-driven.** Every field is rendered from `GET /api/frontmatter-schema`
  with the right control: enum dropdowns/comboboxes, tag chips, a related-pages
  picker, a date picker, the runbook sub-form, etc. Read-only fields
  (`canonical_id`, `confidence`, `agent_hints`) are shown but not editable.
- **Live validation.** As you type, the editor debounces and revalidates against
  `POST /api/frontmatter/validate` (the *same* rules the save enforces). A summary
  strip shows **`N errors · M warnings`** with click-to-jump, and each issue
  renders inline on its field.
- **Save gating.** **Save is disabled while any ERROR-severity issue exists**;
  advisory warnings never block. So you can't accidentally ship malformed
  frontmatter, but a legitimate stylistic warning won't stop a real edit.
- **One-click suggestions.** Warnings that carry a fix (e.g. a non-canonical
  `status`) offer a "use this" button.
- **Raw-YAML break-glass.** A Form ⇄ Raw YAML toggle lets you edit the YAML
  directly (and is the place to edit non-schema keys); it re-validates on blur via
  the same dry-run endpoint.

---

## Validation: what blocks vs. what advises

Frontmatter validation has two severities, enforced on every save path
(`PUT /api/pages`, the dry-run endpoint, and the MCP write tools), so every
surface applies one rule set.

**Errors — block the save (HTTP 422), shown inline on the field:**

- Malformed YAML (`yaml.parse`).
- Any **runbook block** violation (see below) — field-addressed and structured.

**Warnings — advisory (HTTP 200), with a suggestion, never blocking:**

| Code | Trigger |
|---|---|
| `summary.length` | `summary` outside 50–160 characters |
| `cluster.slug.malformed` | `cluster` isn't a kebab slug |
| `tags.kebab` | a tag isn't kebab-case |
| `type.noncanonical` | `type` outside the canonical set |
| `status.noncanonical` | `status` outside the canonical set |
| `date.date.malformed` | `date` not ISO-parseable |

The philosophy is **convergence under shared control**: a large corpus carries
legitimate drift (non-kebab clusters, list-valued audiences, odd dates), so
field-value checks warn rather than break existing pages. When a warning's
corpus-wide count reaches zero (tracked on the
[drift dashboard](OntologyManagement.md#measuring-drift-the-burn-down-dashboard)),
it can be ratcheted to a hard error via
`wikantik.frontmatter.enum.nonCanonical.severity` (default `warning`; see
below).

---

## Save-time enforcement

The validate/save model above is implemented by `SchemaValidationPageFilter`,
a preSave page filter that runs on every save path. A small property family
controls it:

| Property | Default | What it controls |
|---|---|---|
| `wikantik.frontmatter.enforcement.enabled` | `true` | Master gate. When `true`, ERROR-severity violations (malformed YAML, runbook block violations) reject the save with **422**. Set `false` only while migrating a dirty corpus onto the validator for the first time — every save skips schema enforcement entirely while it's off. |
| `wikantik.frontmatter.enum.nonCanonical.severity` | `warning` | Severity for the `type.noncanonical` / `status.noncanonical` checks above. Set to `error` once the [drift dashboard](OntologyManagement.md#measuring-drift-the-burn-down-dashboard) shows zero remaining occurrences, to make the canonical `type`/`status` sets mandatory. |
| `wikantik.frontmatter.trustedAuthors` | *(empty)* | Comma-separated login names exempt from the `verified_by` advisory check. Empty — the default — trusts every author; set it to restrict the exemption to a specific list of logins. |

Separately, pages saved with **no frontmatter block at all** can be
auto-scaffolded rather than left bare, via `FrontmatterDefaultsFilter`:

| Property | Default | What it controls |
|---|---|---|
| `wikantik.frontmatter.autoDefaults` | `false` | When `true`, a page saved without any frontmatter gets one generated (`title`, `type`, `tags`, `summary`, `auto-generated: true`). System pages are left untouched regardless of this flag. |
| `wikantik.frontmatter.defaultTags` | `3` | Number of tags the auto-generated block extracts. Only meaningful when `autoDefaults` is enabled. |

### Auditing a corpus before ratcheting severity to `error`

Turning on enforcement (or ratcheting `nonCanonical.severity` to `error`) on a
wiki that predates the validator can turn existing dirty pages into save
failures. `GET /admin/frontmatter-issues` is the migration tool for exactly
this: an admin-only scan that finds pages whose YAML fails **strict parsing**
(not the field-value warnings above — those live on the drift dashboard) and
reports each with the SnakeYAML message and a best-effort line/column. Fix
each page (editor, or MCP `update_page` — which also auto-normalizes, so a
fix can be as simple as re-saving with quoted values), re-run the audit, and
once the list is empty the validator is fully consistent with the corpus.
It's a synchronous O(N) page-read scan — a migration tool, not something to
poll from a dashboard.

---

## The runbook block (`type: runbook`)

Pages typed `runbook` carry a structured `runbook:` block describing a procedure.
It has its own schema (`FrontmatterRunbookValidator`); violations are
**ERROR-severity** and block the save with a structured 422 keyed to the exact
sub-field (`runbook.<subfield>`).

```yaml
type: runbook
runbook:
  when_to_use:                 # list, ≥ 1 entry
    - When the IT suite fails and you can't tell why
  inputs:                      # list (optional)
    - The failing module + the surefire error
  steps:                       # list, ≥ 2 entries
    - Run without -T (shared IT ports collide under parallel)
    - Re-run only the failing module
  pitfalls:                    # list, ≥ 1 — use "(none known)" if truly none
    - Running -T with the IT profile guarantees port conflicts
  related_tools:               # list (optional)
    - /admin/page-graph/conflicts
    - kg-policy
  references:                  # list (optional)
    - BuildingAndDeployingLocally
```

Rules:

| Sub-field | Rule |
|---|---|
| `when_to_use` | list, at least 1 non-blank entry |
| `inputs` | list (no minimum) |
| `steps` | list, at least 2 entries |
| `pitfalls` | list, at least 1 — use `"(none known)"` rather than omitting |
| `related_tools` | each entry must be a path under `/api`, `/admin`, `/knowledge-mcp`, `/wikantik-admin-mcp`, or `/tools` **or** a bare `snake_case`/`kebab-case` tool name (e.g. `kg-policy`) |
| `references` | each entry must resolve to a live `canonical_id` or an existing page title |

Enforcement is on by default; the master flag is
`wikantik.runbook.enforcement.enabled`. The runbook block powers the agent runbook
surface and the `get_page_for_agent` projection.

---

## How frontmatter ties into the rest of the platform

This is the payoff — the same block feeds every layer:

- **Search & navigation.** `tags`, `cluster`, `summary`, and `type` are indexed in
  Lucene for full-text and **faceted** search and for the structural-spine
  navigation tools (`list_clusters`, `list_tags`, `list_pages_by_filter`).
- **Page Graph / structural spine.** `cluster`, `hubs`, `related`, and
  `canonical_id` build the machine-queryable structural index mirrored at
  `/api/structure/*` and the `knowledge-mcp` navigation tools.
- **Ontology (RDF/OWL).** `type` → the `wk:` content class; `cluster` and each
  `tag` → `skos:Concept`s linked via `dct:subject` (`parent/sub` → `skos:broader`);
  `canonical_id` → the dereferenceable IRI `/id/page/{canonical_id}`. All
  queryable over SPARQL. See [OntologyManagement.md](OntologyManagement.md).
- **Knowledge Graph.** `kg_include` overrides the cluster-primary inclusion policy
  that decides whether a page's extracted entities populate the KG.
- **SEO / structured data.** `type` re-sources the schema.org `@type`
  (`hub`→`CollectionPage`, `article`→`Article`, `runbook`→`HowTo`,
  `design`→`TechArticle`, else `Article` — upgrade-only); `summary` becomes the
  meta description; `image` becomes `og:image`/`twitter:image`; `canonical_id`
  becomes a `sameAs` to the ontology IRI. Because the `@type` is re-sourced from
  the ontology, the public structured data and the internal model can't silently
  drift.
- **Agent-grade retrieval.** `type: runbook` + the runbook block, `audience`,
  `verified_at`/`verified_by`, derived `confidence` and `agent_hints` all feed the
  token-budgeted `GET /api/pages/for-agent/{canonical_id}` projection and its
  `get_page_for_agent` MCP tool. See
  [AgentGradeContentDesign.md](wikantik-pages/AgentGradeContentDesign.md).

---

## Editing programmatically (REST + MCP)

Frontmatter isn't only an editor concern — the same schema and validator back the
API and the agent tools:

- `GET /api/frontmatter-schema` — the field inventory the editor renders from.
- `POST /api/frontmatter/validate` — dry-run validation of a YAML block or a
  metadata object; returns `{ metadata, violations }`.
- `PUT /api/pages/{name}` — a save returns **422 with field-addressed violations**
  on an error, or **200 with a `warnings` array** otherwise.
- **Admin MCP** `update_page` / `write_pages` run the identical validator and
  return the same structured, suggestion-bearing violations, so AI agents
  self-correct to the schema.

---

## Measuring frontmatter drift across the corpus

Advisory warnings only matter if you can see them in aggregate. The **drift
dashboard** (`/admin/drift`) runs the schema validator over every page (plus the
SHACL conformance check over the ontology) and reports one count per
`(family, code, severity)` with deltas, trend sparklines, and a per-code page list
linked to the editor. It's the burn-down view for normalizing the corpus — and the
evidence for ratcheting a warning to an error once its count hits zero. See
[OntologyManagement.md § Measuring drift](OntologyManagement.md#measuring-drift-the-burn-down-dashboard).

---

## Quick reference

| Purpose | Endpoint / tool |
|---|---|
| Field schema (what the editor renders) | `GET /api/frontmatter-schema` |
| Dry-run validation | `POST /api/frontmatter/validate` |
| Save (422 errors / 200 + warnings) | `PUT /api/pages/{name}` |
| Corpus-wide drift burn-down | `/admin/drift/*` |
| Agent writes (same validator) | admin MCP `update_page`, `write_pages` |
| Migration audit (strict-YAML parse failures) | `GET /admin/frontmatter-issues` |

**Where the code lives**

- Schema + field specs: `wikantik-api`
  (`com.wikantik.api.frontmatter.schema.FrontmatterSchema`)
- Validators: `wikantik-main`
  (`com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator`,
  `com.wikantik.knowledge.agent.FrontmatterRunbookValidator`)
- Save-time enforcement: `wikantik-main`
  (`com.wikantik.frontmatter.schema.SchemaValidationPageFilter`,
  `com.wikantik.knowledge.FrontmatterDefaultsFilter`)
- Editor: `wikantik-frontend`
  (`src/components/frontmatter/`, `src/hooks/useFrontmatterValidation.js`)
- REST: `wikantik-rest` (`FrontmatterSchemaResource`, `FrontmatterValidateResource`,
  `PageResource`)

**Related docs**

- [OntologyManagement.md](OntologyManagement.md) — the RDF/SKOS model frontmatter projects into, and curation
- [KgInclusionPolicy.md](KgInclusionPolicy.md) — `kg_include` and the cluster-primary policy
- [Connectors.md](Connectors.md) — the `derived_*` fields and the six connector types that stamp them
- [AgentGradeContentDesign.md](wikantik-pages/AgentGradeContentDesign.md) — runbooks, verification, the for-agent projection
- [StructuralSpineDesign.md](wikantik-pages/StructuralSpineDesign.md) — `cluster`/`hubs`/`canonical_id` and the structural index
- [SeoAndCrawling.md](SeoAndCrawling.md) — how `type`/`summary`/`image` become structured data
