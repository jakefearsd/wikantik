# Live Values — design

Status: draft for owner approval (2026-11-24).

## Intent

Meta pages — `Main`, `Sitemap`, hub pages, platform overviews — state facts about the wiki itself
("1,100+ articles", "15 clusters", "16 knowledge tools"). Those facts drift as the corpus grows, so the
pages need hand edits that nobody remembers to make (today `Sitemap.md` contradicts itself: 1,171 vs
1,193 pages; `Main`'s summary still says 1,100+). The goal is that an author writes a **token** once and
every reader, crawler, feed and agent sees the **current value**, so these pages stop needing edits.

## Decisions the owner made

- **Tokens work in the page body *and* in frontmatter**, including `summary` (which drives the meta
  description, OG/Twitter, JSON-LD, Atom `<summary>` and every agent descriptor).
- **Read-time substitution (Approach 1).** The stored page keeps the token; every output surface
  substitutes the current value when it serves. Rejected: materialising values into the page by a
  server job (Approach 2) and a hybrid (Approach 3) — see *Approaches*.
- Lists (live cluster-member listings replacing `HubSetPlugin`) are **out of this design** — a follow-up.

## Goals

1. One token syntax, with optional `round` and `else` arguments, usable in the body and in frontmatter `summary`.
2. Every output surface (SSR, REST, feeds, sitemap, MCP/tools, briefings, bundles, retrieval, ontology)
   shows the substituted value; none shows a literal token.
3. Every write and editing surface sees and preserves the raw token.
4. Rounded values, so prose and caches stay stable between meaningful changes.
5. `Main`'s summary and intro, and `Sitemap`, use tokens; their hand-maintained numbers go away.

## Non-goals

- Live lists / tables (follow-up: a `cluster:`-membership listing that retires `HubSetPlugin`'s
  `related:` source).
- Tokens in `title`, `cluster`, `tags`, `related` or any frontmatter field other than `summary`.
- Per-viewer values (a count that differs by who is looking).
- Expressions, arithmetic, user-defined values, or values from outside the wiki.
- Re-writing historical numbers in design docs ("14 clusters" in a 2.4.0 design is a dated
  measurement and stays literal).

## Anti-goals (failure even if it "works")

- **A token ever baked into the stored source** — any write path that receives a substituted value and
  saves it destroys the token silently. This is the content-destroying class of defect; treat it so.
- **Leaking private clusters** — a count or value that reveals the existence or size of a private cluster.
- **Main's render-cache hit rate collapsing** — Main is the highest-traffic page.
- A literal `{{wiki:…}}` reaching any reader, crawler or agent.

## Approaches considered

1. **Read-time substitution (chosen).** Token stays in source; each output substitutes. Always current.
   Cost: ~5 shared substitution points plus ~10 stragglers must all call it; leak and bake-in risks are
   real and need cross-surface tests.
2. **Materialise into the page.** A server job computes values and saves the page through the normal
   save path when a rounded value changes. Zero reader changes; cost is version/re-embed/feed churn and
   lag. Rejected by owner in favour of always-current values.
3. **Hybrid** — materialised values, live plugin for lists. Rejected with 2.

## The token

```
{{wiki:<name>}}
{{wiki:<name>|round=<N>}}
{{wiki:<name>|round=<N>|else=<text>}}
```

- `<name>` matches `[a-z][a-z0-9.-]*` (dots separate segments; cluster paths contain `-` and `/`, see
  below). Only **known** names expand; an unknown name is left verbatim (and is a save-time WARNING,
  below).
- `round=N` (N a positive integer): floor to a multiple of N; append `+` when the true value exceeds the
  floor. 1,142 with `round=100` → `1,100+`; 1,100 → `1,100`. Without `round`, the exact value.
- Numbers format with `,` grouping (en-US), independent of viewer locale — the value must be identical
  for every viewer so caches stay principal- and locale-free.
- In the **body**, tokens inside fenced code blocks, indented code and inline code spans never expand
  (8 corpus pages show `{{…}}` in code examples today; none uses the `wiki:` prefix).
- In frontmatter, only `summary` expands.

### Vocabulary (v1)

| Name | Value |
|---|---|
| `articles` | Public pages that are not hubs and not system pages (`SystemPageRegistry`) |
| `pages` | All public pages |
| `clusters` | Declared top-level clusters with at least one public page |
| `subclusters` | Declared sub-clusters (`parent/child`) with at least one public page |
| `cluster.<path>.pages` | Public member pages of that cluster (sub-cluster path uses `/`, e.g. `cluster.agentic-ai/rag.pages`); unknown or private cluster → token left verbatim, never `0` |
| `tools.knowledge` | Tools registered on `/knowledge-mcp` |
| `tools.admin` | Tools registered on `/wikantik-admin-mcp` |
| `tools.openapi` | Tools on `/tools/*` |
| `version` | Running release (`Release`) |

`updated` (date of the latest public change) was considered and **dropped from v1** (designer's call): it
moves daily, so any page using it would lose its render cache daily — against the Main anti-goal.

**Counting rules (designer's calls, cheap to change):** a page listing several clusters counts in every
cluster it lists; a hub does not count as a member of its own cluster; derived pages (`derived_from`) and
agent-authored pages count as articles — readers can read them.

**Unwired sources (designer's call).** A *known* name whose source isn't wired in this deployment (e.g.
`tools.admin` with the admin MCP disabled) renders as the token's `else` text — `{{wiki:tools.admin|else=several}}`
— or as an empty string when no `else` is given; it never renders verbatim. An *unknown* name renders
verbatim, but is caught earlier by the save-time WARNING and the drift report, so it reaches a reader only
if an author ignores both.

**"Public"** = visible to an anonymous reader: excludes every page in a private cluster
(`wikantik.access.privateClusters`, `PrivateClusterGate`) and pages whose ACL denies anonymous `view`.
Visibility is decided by a guest-session permission check (`DefaultAuthorizationManager`, which already
applies `PrivateClusterGate` and page ACLs) over every page when the structural index publishes a new
snapshot — a ~1,200-check pass on snapshot change, not per request (designer's call; the index itself
carries no ACL data). Counts are computed once for the public view — never per viewer — so a value is the same everywhere and
can't disclose private content. A private cluster's `cluster.<path>.pages` must behave exactly like an
unknown cluster (verbatim token), so probing names reveals nothing.

## Architecture

### `LiveValues` service (wikantik-api interface, wikantik-main implementation)

- `String expandBody(String markdown)` — code-aware body expansion.
- `String expandText(String plain)` — for `summary` and other plain strings.
- `Map<String,String> snapshot()` / a `generation()` token: a hash of the full map of **rendered**
  (post-rounding) values. One global generation (designer's call): any rendered value moving invalidates
  every token-bearing page. Acceptable because rounded values move a few times a year; per-name dependency
  tracking is not worth its cost. Pages without tokens are never affected. Transcluded pages
  (`InsertPage`) expand against the same snapshot as the host render.
- Values are computed from the structural index snapshot plus the public-visibility predicate, cached
  in-process and recomputed when the structural index publishes a new snapshot (`generatedAt` changes).
  Expansion itself is a string scan; it must be cheap enough to run on every served summary.
- **Value sources are pluggable**: a `LiveValueSource` SPI in wikantik-api; wikantik-main contributes the
  corpus values and `version`; wikantik-knowledge, wikantik-admin-mcp and wikantik-tools each contribute
  their own `tools.*` count from their registries (wikantik-main cannot see those modules). A source
  that isn't wired (module absent, endpoint disabled) simply doesn't define its names → verbatim token.

### Where substitution happens

The raw source comes from `PageManager.getPureText` (~40 callers). Substitution is applied at these
shared points, **on read, never on load** — every one of them sits in front of a cache keyed by page
version, so substituting at load would freeze the value:

1. **Body render** — `DefaultRenderingManager` (`getRenderedDocument`, `cachedOrRenderedHtml`), via
   `DisplayText.of` (already "what a display render parses"; already strips access rules). WYSIWYG mode
   and bare link-collection parses (`justParse`) do **not** expand. Both render caches validate entries
   against a hash of the page text; that validation must cover the **expanded** text (or include
   `LiveValues.generation()`), so an entry is invalidated exactly when a displayed value changes and Main
   stays cached the rest of the time. Covers SSR body, `/api/pages` `contentHtml`, editor live preview
   (`ConvertResource`), `InsertPage`, recent-articles render.
2. **SSR head + island** — `SpaRoutingFilter` (~629–650) parses frontmatter once and feeds
   `PageSeoModel`, `renderBodyFragment` and `buildPageIsland`: substitute `summary` in that one parsed
   object. Covers `<meta description>`, OG/Twitter, JSON-LD, the no-JS fallback and the JSON island.
3. **Structural descriptors** — `PageDescriptor.summary()` is built once in
   `DefaultStructuralIndexService.toDescriptor` and held in the in-memory projection. Substitute at the
   read side (accessor or a serving wrapper), not in `toDescriptor`. Covers `/api/structure`,
   `/api/pages/by-id`, `/api/pages/for-agent`, `get_page_by_id`, `get_page_neighbors`,
   `list_pages_by_filter`, `list_clusters`, for-agent projection summary, briefing pointers, insights,
   ontology `dct:description`.
4. **Frontmatter metadata cache** — `FrontmatterMetadataCache` (Caffeine, keyed page+lastModified):
   substitute on read of `summary`. Covers `/api/search`, `retrieve_context`, knowledge `get_page` /
   `list_pages`, `/tools` `search_wiki`, briefing items.
5. **Reader-text view** — `ReaderText` / `AclDirectiveText.forViewer` already sit after `getPureText` on
   most reader paths. Add live-value body expansion to the reader view used by `read_pages`, briefing
   body sections, `?format=md|json`, `/tools` `get_page`, for-agent projection body, page excerpts and
   link previews, export zip. **`FrontmatterDefaultsFilter` uses `ReaderText.derivedBody` on text it may
   save — it must stay raw**; the expansion must be a separate, explicitly named step, not folded into
   `ReaderText` functions that write paths share.

**Stragglers** that read raw text directly and must each call `LiveValues`: `AtomFeedServlet`
(`<summary>`), `DefaultRecentArticlesManager` (excerpts), `WikiPageFormatFilter` (frontmatter summary in
md/json), wikantik-tools `GetPageTool`/`ResultShaper`, `DefaultForAgentProjectionService` body,
`AgentHintsDeriver`, `BundleServiceWiring`, `PreviewStructuredDataTool` (should show what crawlers will
see — route through the same `PageSeoModel` path), `HubSetPlugin`, admin-MCP `WikiResources`.

### Persisted indexes (Lucene, chunks, embeddings)

These persist text and are not rebuilt when a value changes. Rule: **store raw, serve expanded.**

- Chunk text (`kg_content_chunks`) and the Lucene stored `summary` field keep the token. Anything that
  returns chunk text or a stored summary to a caller (retrieval, bundles, search snippets) expands on the
  way out. There is no single result-shaping point: the builder enumerates and wraps the result builders
  (dense, hybrid, BM25 snippet, bundle assembly), and the leak IT proves each.
- Embedding input and the Lucene analysed text are built from the **expanded** text at index time; a
  value that goes slightly stale inside a vector or posting list is harmless and is refreshed on the
  page's next reindex. No reindex is triggered by a value change.

### Write and editing surfaces stay raw

`read_page`, `diff_page`, `update_page`, `write_pages`, `mark_page_verified`, `verify_pages`,
`delete_pages`, `PageSaveHandler`, frontmatter backfill, `FrontmatterDefaultsFilter`, page rename and
`rename_cluster`, citation wiring, derived-ingest and drift checks, all validators (`FrontmatterSchema`,
`MetadataCheck`, `PageChecks`, content-health) read and write the raw token.

**`GET /api/pages/{name}` is both the SPA's display source and its editor source.** Its `content` and
`metadata` fields stay raw (the editor round-trips them). Display-only substituted values are added
alongside (e.g. a `resolved` block with `summary`), and the SPA's reader view (`PageMeta` etc.) reads
those. `contentHtml` is already rendered through point 1. The builder decides the exact field shape.
**The SPA changes are in scope for v1** (designer's call): without them a reader on the SPA sees the raw
token in `PageMeta`, which is a leak. Frontend reader components that show `summary` move to the resolved
field, with vitest coverage; the editor keeps reading the raw fields.

### Validation

- `FrontmatterSchema`'s summary-length WARNING (50–160) measures the **expanded** summary.
- A `{{wiki:…}}` with an unknown name, a malformed `round`, or a token in a non-`summary` frontmatter
  field is a save-time **WARNING** (consistent with the curation policy: only malformed YAML is a 422).
- `/admin/drift` (or the frontmatter-issues report) lists pages carrying unknown tokens.

### Configuration

`wikantik.liveValues.enabled` (Boolean, default `true`) — kill switch; when `false` every token is served
verbatim. Declared in `ini/wikantik.properties` first, per the config-surface rules.

## Content rollout

- `Main.pins.yaml`: `summary` and `intro` use `{{wiki:articles|round=100}}` / `{{wiki:clusters}}`.
  `GenerateMainPageCli` passes tokens through verbatim (it must not try to compute them — the repo is not
  the prod corpus). Push to prod per the system-pages procedure.
- `Sitemap.md`: replace the hand counts with tokens; push to prod via MCP.
- Platform overview pages that state live tool counts: convert where the number is meant to be current;
  leave dated measurements in design docs alone.

## Testing (TDD; content-destroying class → unit **and** IT)

- `LiveValues` unit tests: rounding, formatting, code-span/fence immunity, unknown names verbatim,
  private-cluster and unknown-cluster indistinguishable, generation changes only on rounded-value change.
- Render-cache test: Main-like page stays a cache hit across corpus changes that don't move a rounded
  value; is re-rendered when one does.
- **Cross-surface leak IT** (wikantik-it-test-rest): a fixture page with tokens in summary and body;
  every output surface listed above returns the expected value and no `{{wiki:`; every raw surface
  (`read_page`, editor fetch `content`/`metadata`) returns the token.
- **Bake-in tests**, one per write path (unit + IT for the MCP writes, per project convention): read →
  save round-trip leaves the token byte-identical.
- An ArchUnit or drift test is welcome if the builder finds a cheap way to flag new `getPureText` output
  callers that skip `LiveValues`; not required.

## Left to the builder

- Exact class names and package, the `/api/pages` display-field shape, how `generation()` is computed,
  how the SPI is registered, and whether body expansion runs as a flexmark-aware pass or a code-aware
  text scan — subject to the constraints above.
- Order of work. Suggested: service + vocabulary → render path + caches → SSR head → descriptors and
  metadata cache → reader view and stragglers → indexes → validation/config → leak IT → content rollout.
