# Obsidian-Compatible Content Export — Design

**Status:** approved design, not yet implemented (2026-09-29)
**Scope:** v1 — one-shot, user-scoped zip export whose output is a ready-to-open Obsidian vault.
**Explicitly deferred:** incremental refresh CLI (the manifest is designed for it), MCP export
tool, page-history export, Obsidian → wiki import, live sync plugin.

## 1. Problem

Readers cannot take wiki content offline. The only raw-content surfaces are per-page
(`/wiki/{slug}?format=md`), the `/api/changes` feed, per-page attachments, and whole-ontology
RDF dumps. Even a hand-assembled folder of raw `.md` files is not usable in Obsidian: wiki links
are `[Text](PageName)` (Obsidian needs `[[PageName]]` or `PageName.md`), anchors are slugs
(Obsidian links by heading text), attachments are `Page/file.ext`, and plugin markup
(`[{…}]()`) is meaningless outside the wiki.

Goal: an authenticated user selects *how much* to pull down, sees the size before downloading,
and receives a zip that unzips into a working Obsidian vault — links resolve, the graph view is
meaningful, images render, math renders — and that carries enough provenance to support a
future incremental refresh.

## 2. Decisions (settled during brainstorming)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Delivery model | One-shot zip now; manifest shipped from day one so an incremental refresh can follow |
| D2 | Scope selection | Clusters + frontmatter filters, with optional outbound-link hop expansion (0–2). No search-query export in v1 |
| D3 | Out-of-scope links | Per-export setting `unresolved=keep\|url`, default `keep` (unresolved `[[Page]]`) |
| D4 | Who may export | New wiki permission `export`, granted to `Authenticated` by default, revocable via `policy_grants` |
| D5 | Architecture | Synchronous streaming `ZipOutputStream` + a cheap preview endpoint; no job state |
| D6 | Code placement | Selection + conversion in `wikantik-main` `com.wikantik.export.*`, no servlet deps (reusable by a future CLI/MCP tool); thin REST resource in `wikantik-rest` |

## 3. Permission

- `WikiPermission` gains `EXPORT_ACTION = "export"`, an `EXPORT_MASK` bit, and a public
  `EXPORT` constant; `createMask` recognises the action.
- Update every place that enumerates wiki actions: `PolicyRoleTable` `wikiPerms`,
  `PolicyGrantFormModal.jsx` (`wiki:` action list), and the file-based fallback
  `WEB-INF/wikantik.policy` (Authenticated gets `export`).
- **Migration `V060__wiki_export_permission.sql`** (next free number at implementation time),
  modelled on V043: append `export` to `('role','Authenticated','wiki','*')` **only when its
  `actions` still equals V003's default `'createPages,createGroups'`**. A customised row is
  left untouched. Idempotent: a re-run finds the row no longer equal to the default and does
  nothing. Treated as a structural grant seed (V003/V043 precedent), not a data backfill.
- Revocation = editing the grant in `/admin/security`; no code change.

## 4. Selection — `ExportSelection` / `ExportSelectionResolver`

`ExportSelection` record: `clusters[]`, `includeSubClusters` (default true), `tags[]`, `type`,
`status`, `hops` (0–2, default 0), `unresolved` (`KEEP` | `URL`, default `KEEP`).

Resolution, in order:

1. **Seed set.** Pages matching any listed cluster (segment-aware via `ClusterPath`; sub-clusters
   included when `includeSubClusters`), intersected with `tags`/`type`/`status`. Implemented on
   `StructuralIndexService.listPagesByFilter` (paging through its 1000-row limit). No filters =
   every page.
2. **Hop expansion.** BFS over **outbound** links only (`ReferenceManager.findRefersTo`) up to
   `hops`. Inbound expansion is excluded — it converges on the whole corpus.
3. **ACL pass.** Drop every page the caller cannot `view`. A dropped page is indistinguishable
   from a nonexistent one, including as a link target (it is treated as out-of-scope).
4. **Cap.** If the result exceeds `wikantik.export.maxPages` (default 2000), refuse with 413 and
   the true count. Never silently truncate.

Attachments included = the attachments of included pages that are actually referenced from
their bodies.

## 5. Conversion — `ObsidianPageConverter`

Parse each body with the wiki's Flexmark parser; rewrite **only the source spans** of nodes that
need it and copy every other byte verbatim. (Regex rewriting corrupts code blocks; full AST
re-serialisation reformats the page.)

| Wiki construct | Output |
|---|---|
| `[Text](Page)`, Page in export | `[[Page\|Text]]`; `[[Page]]` when text equals the page name |
| `[Text](Page#slug)` | `[[Page#Heading Text\|Text]]` — slug resolved against the target's headings using the wiki's own slug function; unmatched → `[[Page\|Text]]` |
| Link to a page not in the export | `keep`: `[[Page\|Text]]` · `url`: `[Text](<baseURL>/wiki/Page)` |
| `[Text](Page/file.ext)` attachment | image → `![[file.ext]]`; other → `[[file.ext\|Text]]`; file written to `_attachments/Page/` |
| `[claim](cite://Target/heading "span")` | `[[Target#Heading\|claim]]` + footnote carrying the cited span |
| `[{ALLOW …}]`, `[{SET …}]` | removed |
| `[{TableOfContents}]` | removed (Obsidian's outline pane replaces it) |
| `[{InsertPage page=X}]` | `![[X]]` (Obsidian embed) |
| any other plugin | `> [!note] Wiki plugin omitted: <Name>` + link to the live page |
| external links, `$…$`/`$$…$$` math, code, tables | unchanged |

Obsidian resolves attachment embeds by filename vault-wide; if two included pages carry
attachments with the same filename, the embed is written path-qualified
(`![[_attachments/Page/file.ext]]`).

**Frontmatter:** preserved verbatim, plus:
- `wikantik_url` — live page URL
- `wikantik_version` — exported page version
- `aliases` — page title, when it differs from the filename
- `related` — rewritten to `["[[Page]]", …]` so relations appear in Obsidian's graph

Stripping `[{ALLOW}]` means an exported file no longer carries its ACL. The exporter could
already view it, so nothing is disclosed that they could not read; the readme states it.

## 6. Archive layout and manifest — `ObsidianVaultWriter`

```
wikantik-export-<yyyyMMdd-HHmm>.zip
├── Wikantik Export.md          readme: selection, timestamp, source, stripped constructs, ACL note, warnings
├── <cluster>/<Page>.md         primary cluster folder; sub-cluster → nested folder
├── _unclustered/<Page>.md
├── _attachments/<Page>/<file>
└── .wikantik/manifest.json
```

- **Placement:** primary cluster only (`PageDescriptor.cluster()`); multi-membership pages are
  not duplicated — Obsidian resolves `[[Page]]` vault-wide.
- **Filenames:** page name + `.md`, with `: * ? " < > | \` replaced. Case-insensitive
  collisions get a `~2` suffix and an `aliases` entry for the original name.
- **No `.obsidian/` directory** — unzipping into an existing vault must never clobber settings.
- **`manifest.json`:** `formatVersion`, `serverBaseUrl`, `exportedAt`, the full `selection`
  (reproducible), `pages[]` of `{name, canonicalId, version, path, sha256}`, `attachments[]` of
  `{page, name, path, sha256}`, `warnings[]`. `canonicalId` lets the future refresh distinguish a
  rename from a delete; `sha256` lets it detect local edits.

## 7. REST surface — `ExportResource` (wikantik-rest)

Both endpoints share one selection parser and require the `export` wiki permission
(anonymous → 401, denied → 403, via `RestServletBase.sendError`, never `response.sendError`).

- `GET /api/export/preview?<selection>` → JSON
  `{pages, attachments, estimatedBytes, unresolvedLinks, cap, sample:[first 50 names]}`.
  Estimate = sum of body sizes + attachment sizes (no conversion run).
- `GET /api/export?<selection>` → `application/zip`,
  `Content-Disposition: attachment; filename="wikantik-export-<ts>.zip"`, streamed.

Parameters: `cluster` (repeatable), `subclusters=true|false`, `tag` (repeatable), `type`,
`status`, `hops`, `unresolved=keep|url`. Invalid values → 400 with the offending parameter.

Registration: `web.xml` servlet + mapping for `/api/export` and `/api/export/*` (update the
`/api/*` servlet count in CLAUDE.md).

## 8. Failure handling

- **Before the first byte:** parse, resolve, ACL, cap. All errors here are normal JSON 400/401/
  403/413.
- **After the first byte, nothing aborts the archive.** Page conversion failure → raw markdown
  written instead; missing/unreadable attachment → skipped. Each is logged at WARN with page
  context and recorded in `manifest.warnings[]` and the readme.
- **Client disconnect** → `IOException` on write, logged at DEBUG, request ends. No server-side
  state exists to clean up.

## 9. UI — `ExportDialog` (modal, no new route)

- Entry points: user menu "Export to Obsidian…" (logged-in users) and an "Export this cluster"
  button on hub pages (pre-fills that cluster).
- Controls: cluster picker + sub-cluster toggle, tag picker, type/status selects, hops 0/1/2,
  unresolved-links radio. Reuse `src/components/ui/` (`Combobox`, `TagInput`).
- Debounced call to `/api/export/preview` on every change; shows
  "N pages · M attachments · ~X MB · K unresolved links" plus the sample. Download disabled on an
  empty result or over-cap, with the reason shown.
- A 403 from preview renders "Export is disabled for your account." (the SPA has no wiki-permission
  feed; no new plumbing is added for one).
- Download is a plain `<a href>` to `/api/export?…` — the browser's download manager handles
  progress; nothing is buffered in JS.

## 10. Configuration

Declared in `ini/wikantik.properties` first (enforced by `ConfigSurfaceDriftTest`), then
regenerate the config reference (`bin/config-reference.sh --write`):

- `wikantik.export.maxPages` — Type: int, default `2000`.

## 11. Testing (TDD — each test demonstrated failing first)

- **`ObsidianPageConverterTest`** — one case per §5 row; links inside fenced/inline code
  untouched; unmatched anchor fallback; untouched bytes identical; math untouched; frontmatter
  additions; `related` rewrite.
- **`ExportSelectionResolverTest`** — filter composition; sub-cluster inclusion via
  `ClusterPath` (and no false `startsWith` match); hop bound; ACL drop (a restricted page never
  appears, not even as a resolved link target); cap → 413 with true count.
- **`ObsidianVaultWriterTest`** — layout; filename sanitisation and case-collision suffix +
  alias; manifest hashes match written bytes; a converter failure yields a complete zip with a
  warning.
- **Permission** — `WikiPermission` mask/implication tests; migration test against
  `PostgresTestDb`: default row gains `export`, customised row untouched, re-run is a no-op.
- **`ExportResourceTest`** — 401/403/400/413, content type, filename, preview JSON shape.
- **Frontend (vitest)** — dialog preview refresh, disabled states, 403 message, hub pre-fill.
- **REST IT** — export a seeded cluster, unzip, assert a `[[wikilink]]` resolves to a file in the
  archive and the manifest matches.
