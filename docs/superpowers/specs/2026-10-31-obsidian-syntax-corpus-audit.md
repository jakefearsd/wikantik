# Obsidian syntax corpus audit

Read-only audit of existing wiki content against the Obsidian-compatibility syntax shipped on `main`
(unreleased). Nothing in prod or `docs/wikantik-pages/` was edited; fixes are listed only, for the user to
apply at deploy time.

## Phase 1 — block markers

**Date:** 2026-10-31. **Feature rules audited:** a line ending in whitespace + `^[A-Za-z0-9-]+` (end of a
paragraph or list item, including a marker alone on the last line of a multi-line paragraph), or a paragraph
that is only `^[A-Za-z0-9-]+`, is read as a block id and removed from the rendered page. Never recognised in
code spans, fenced or indented code, inline or display math, or heading lines.

### Method

- Rules source: the real parser, not a hand-written regex. `wikantik-frontend/src/utils/blockIds.js`
  (`blocksFromMarkdown` for blocks with a winning id, `blockMarkers` for every marker including orphans and
  losing duplicates). That module is the JS mirror of the server block model and is parity-tested against the
  server on the shared 42-case fixture.
- Script: a throwaway Node ESM script (kept in the session scratchpad, not in the repo) loaded the module via
  Vite `ssrLoadModule`, ran it over each page body and reported every block with a non-null id plus every
  orphan / losing-duplicate marker, with page, line, marker and line text. CRLF bodies (prod pages) were
  handled by the parser.
- Sanity check: a 1-page fixture with a CRLF paragraph marker, a list-item marker, a standalone marker, a
  last-line-of-multiline-paragraph marker and a non-marker `x ^2 here` produced exactly the expected 4 hits
  (the `^2` mid-line text was correctly ignored).
- Cross-check: an independent `grep -aE` for `[[:space:]]\^[A-Za-z0-9-]+[[:space:]]*\r?$` and for a bare
  `^id` line over both corpora also found nothing.
- Prod corpus (authoritative): live knowledge MCP, read tools only (`list_pages`, `list_pages_by_filter`,
  `read_pages`, 20 pages per call, 74 calls). No write or admin tool was used.
  - The listing reported `totalMatched` 1465 (1464 on one call; the corpus was being edited while paging).
  - 1463 distinct page names were enumerated across the paged listings and all 1463 were read successfully:
    1460 were scanned by the script; 3 (`WritingANewMcpTool`, `ZeroTrustArchitecture`, `ZigLanguage`) were
    returned inline in the final call and were read by eye (no `^` marker text in any of them).
  - Gap to report: 1 to 2 pages of the reported total are not accounted for. The offset-200 page returned 199
    rows while the total moved 1465 to 1464, so one row at a page boundary may have been skipped, or a page
    was created and deleted mid-run. The union of names was cross-checked against the first 1000-row
    `list_pages_by_filter` result (no name missing) and against recently modified pages. No page that was
    listed failed to read.
- Repo corpus: 1197 files, `docs/wikantik-pages/*.md`, scanned by the script (0 read errors). Differences
  from prod: the checkout has 17 names prod lacks (e.g. 9 generated `+Hub` files) and prod has pages the repo
  lacks; four repo-only names (`AuditLogDesign`, `CurationTest`, `ScimProvisioningDesign`,
  `WikantikConfigurationReference`) return `not_found` on prod.

### Hits

| Corpus | Page | Line | Marker | Snippet | Classification | Fix |
|---|---|---|---|---|---|---|
| prod | (none) | | | | | |
| repo | (none) | | | | | |

Counts: prod 0 block-id hits, 0 orphan markers, 0 losing duplicates; repo 0 and 0 and 0.

### Near-misses reviewed (not hits, nothing visible would be lost)

A looser search for any ` ^word` token, anywhere in a line, found only these; none sits at the end of a block
or alone, and all are inside code spans, a YAML title, or fall outside the marker rules:

| Corpus | Page | Line | Text | Why it is safe |
|---|---|---|---|---|
| prod | `ObsidianBlockReferencesDesign` | 7 | title: `... ^id Anchors, Block Links ...` | frontmatter, mid-line |
| prod | `ObsidianBlockReferencesDesign` | 42 | `` ` ^id` `` and `` `^id` `` | inside code spans |
| prod | `WikantikFrontendArchitecture` | 40, 41 | `` `react-router-dom ^7.18` ``, `` `@vitejs/plugin-react ^6` `` | inside code spans (version carets); would only matter if one ended a paragraph unfenced |
| repo | `EditPageHelp` | 41 | `` `The launch date is fixed. ^launch` `` | inside a code span (deliberate documentation) |

### Summary

0 intended and 0 unintended hits across 1460 scanned prod pages (plus 3 read by eye; 1463 of the reported
1464 to 1465) and 1197 repo pages: no existing page loses visible text to block-marker stripping, so no
escape (`\^`) fixes are needed before deploy. The only residual risk is the 1 to 2 prod pages not
enumerated and any page created or edited after 2026-10-31; re-run the scan against the live index at
deploy time if a stricter guarantee is wanted.

## Phase 2 — `==highlight==` and `%% comments %%` (2026-11-01)

The detectors from OBR §5 match a `==…==` span (non-space at both inner edges, not part of `===`), and any `%%`
outside frontmatter, fenced code, inline code, and block or inline math. They ran over a byte-exact copy of the
production page store (copied read-only out of the running container, prod at 2.5.5) and over the repo corpora.

| Corpus | Pages | `==…==` hits | `%%` hits | Action |
|---|---|---|---|---|
| production | 1466 | 0 | 0 real (1 false positive, see below) | none |
| `docs/wikantik-pages/` | 1197 | 0 | 0 | none |
| `wikantik-wikipages/` | 38 | 0 | 0 | none |

The one detector hit is `RenderedFencesDesign` line 118, `` `%%{ … }%%` `` (a Mermaid init directive). It is already
inside an inline code span, so it stays literal under Phase 2. No page needed escaping.
