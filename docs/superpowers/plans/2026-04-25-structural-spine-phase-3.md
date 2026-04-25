# Structural Spine — Phase 3 Implementation Plan

> **Status:** Implemented (commits `b504cd058..` on `main`).

**Goal:** Replace the hand-curated `docs/wikantik-pages/Main.md` with a
deterministic, generated artifact derived from frontmatter + a pins
sidecar, so the wiki's front page can never silently drift from the
underlying taxonomy.

**Design source:** [docs/wikantik-pages/StructuralSpineDesign.md](../../wikantik-pages/StructuralSpineDesign.md)
("Migration path > Phase 3 — `Main.md` generation").

**Architecture:**

- `docs/wikantik-pages/Main.pins.yaml` is the editorial seam. It carries
  intro, footer, and an ordered list of sections; each section lists pages
  by `canonical_id` (ULID), with optional title and summary overrides per
  page.
- `wikantik-extract-cli`'s new `GenerateMainPageCli` reads the pins file +
  parses every `*.md` frontmatter under the pages directory, joins them on
  `canonical_id`, and renders to a Mustache template at
  `wikantik-extract-cli/src/main/resources/Main.md.mustache`.
- The generated `Main.md` carries an `AUTO-GENERATED` header comment and
  the same `canonical_id` as the original — only the body is replaced.
- A unit-test regression gate (`MainPageRegressionTest` in
  `wikantik-extract-cli`) runs `--check` against the project root on every
  unit-test build. CI fails on drift; authors fix by running the CLI with
  `--write`.

**Tech stack delta:** Single new dependency on `com.samskivert:jmustache:1.16`
via `wikantik-bom`; consumed by `wikantik-extract-cli`. SnakeYAML was
already on the classpath via `wikantik-api`'s `FrontmatterParser`.

---

## What ships at end of Phase 3

| Layer | Class / file | Behaviour |
|-------|--------------|-----------|
| Pins schema | `PinsConfig` (record) | intro / footer / sections; each section has label + cluster + ordered pages; each page has `canonical_id`, optional `title`, optional `summary` |
| Pins parser | `PinsParser` | SnakeYAML-based, accepts `id` and `canonical_id` aliases, short-form `[01ABC...]` short list, throws with section/page index hint on malformed entries |
| Resolved view | `MainPageData` (record) | Resolved Sections + Pages with slug/title/summary, plus a `warnings` list for any unresolvable canonical_id |
| Loader | `MainPageDataLoader` | Walks `docs/wikantik-pages/`, indexes by canonical_id, joins with pins. Offline — does not depend on the running structural-index service or DB |
| Template | `wikantik-extract-cli/src/main/resources/Main.md.mustache` | Carries the `canonical_id` frontmatter block + AUTO-GENERATED comment + intro / sections / footer regions, gated by `hasIntro` / `hasSections` / `hasFooter` booleans |
| Renderer | `MainPageRenderer` | Mustache compile-once; output is normalized to LF endings, single trailing newline, no triple blank lines |
| CLI | `GenerateMainPageCli` (`--check` / `--write`) | Exit 0 on sync/write, 2 on drift, 3 on missing inputs |
| Migration | `Main.pins.yaml` + regenerated `Main.md` | Original Main.md replaced by the generated output; pins capture the editorial layout |
| Regression gate | `MainPageRegressionTest` | Unit test — runs `--check` against `docs/wikantik-pages/`, fails on drift, gracefully skips when project root isn't reachable (IDE runs) |

---

## Task ledger (executed)

| # | Task | Commit |
|---|------|--------|
| P3-T1 | Add jmustache 1.16 dep | `b504cd058` |
| P3-T2 | `PinsConfig` + `MainPageData` records | `0e9d805e0` |
| P3-T3 | `PinsParser` + tests | `7af595a1a` |
| P3-T4 | `MainPageDataLoader` + tests | `550fccfd4` |
| P3-T5 | Mustache template + `MainPageRenderer` + tests | `0c9f9a863` |
| P3-T6 | `GenerateMainPageCli` (`--check` / `--write`) + tests | `8b75a3511` |
| P3-T7 | Maven plugin wiring deferred — see deviation below | (none) |
| P3-T8 | Migration: `Main.pins.yaml` + regenerated `Main.md` (+ titleOverride field added inline) | `6e2473c5b` |
| P3-T9 | Regression test + this plan + doc updates | (this commit) |
| P3-T10 | Full verification build | (this commit) |

Total commits in Phase 3 so far: 8 functional + this doc.
Total new tests in Phase 3: **21** (6 `PinsParserTest`, 5 `MainPageDataLoaderTest`,
5 `MainPageRendererTest`, 4 `GenerateMainPageCliTest`, 1 `MainPageRegressionTest`).

---

## Deviations from the design doc

1. **Maven plugin wiring deferred.** The design called for an
   `exec-maven-plugin` invocation in `wikantik-wikipages-builder`'s
   `package` phase. The parent pom's module order builds
   `wikantik-wikipages` *before* `wikantik-extract-cli`, so the
   generator artifact does not yet exist when the wikipages module's
   package phase runs. Restructuring the module order is out of scope
   for Phase 3.

   The regression gate is instead a unit test
   (`MainPageRegressionTest`) that runs in
   `wikantik-extract-cli`'s test phase. It re-executes the same
   `--check` path the design specified — the gate semantics are
   identical, just bound to a different lifecycle phase. Authors who
   regenerate locally run the CLI directly:

   ```bash
   java -cp wikantik-extract-cli/target/wikantik-extract-cli.jar \
        com.wikantik.extractcli.GenerateMainPageCli docs/wikantik-pages --write
   ```

2. **`titleOverride` added.** Most pages in the corpus do not carry a
   `title:` frontmatter field — the human-readable titles in the
   original `Main.md` were hand-typed. To preserve the editorial voice
   without a corpus-wide frontmatter rewrite, `PinsPage` accepts an
   optional `title` override; the migration's `Main.pins.yaml` populates
   it for every entry. Authors who later add `title:` to frontmatter can
   simply drop the override from pins. Long-term cleanup is to backfill
   frontmatter titles and remove the overrides — a Phase 4-adjacent
   editorial task, not blocking.

3. **`OperationsResearch` section dropped.** The page no longer exists in
   `docs/wikantik-pages/` (deleted in unrelated content updates).

---

## Out of scope, explicitly deferred

| Item | Why deferred |
|------|--------------|
| Pre-commit hook | The CI gate is authoritative; a local hook is convenience |
| Multi-language Main.md generation | `wikantik-wikipages` has `-en/-es/-ru` submodules but Phase 3 generates only the canonical English Main.md |
| Per-cluster sub-page generation (e.g. `WikantikDevelopment.md`) | Bigger lift, risks losing editorial voice; not part of the design's Phase 3 scope |
| Web admin UI for pins | Editing Main.pins.yaml in the IDE / via PR is fine for a single-developer wiki |
| Cross-check against `/api/structure/clusters` (running WAR) | Both paths derive from the same frontmatter; cross-checking is belt-and-suspenders, not necessary |

---

## Verification

`mvn install -T 1C -DskipITs -fae -pl '!wikantik-admin-mcp' -am` is **green**
across all 19 buildable modules including the new `MainPageRegressionTest`.

The generator runs in ~0.5s against the 934-page wiki on a warm box (T2-T6
unit tests round-trip frontmatter for ~5 fixture pages each in well under a
second).

---

## Authoring workflow (post-Phase 3)

1. **Editing the wiki front page:** edit `docs/wikantik-pages/Main.pins.yaml`,
   then run:

   ```bash
   mvn package -pl wikantik-extract-cli -am -DskipTests -q
   java -cp wikantik-extract-cli/target/wikantik-extract-cli.jar \
        com.wikantik.extractcli.GenerateMainPageCli docs/wikantik-pages --write
   git add docs/wikantik-pages/Main.pins.yaml docs/wikantik-pages/Main.md
   git commit -m "docs(main): <reason>"
   ```

2. **Adding a page to the front page:** find its `canonical_id` (`grep
   '^canonical_id:' docs/wikantik-pages/<Page>.md`), add a pin block to
   the appropriate section in `Main.pins.yaml`, regenerate.

3. **Renaming a page:** no Main.md change required — the pin references
   the stable canonical_id, and the slug is re-resolved on the next
   regeneration.

4. **CI failure with "Main.md is out of sync"**: someone hand-edited
   Main.md. Either (a) discard the change and regenerate, or (b) port
   the change to `Main.pins.yaml` and regenerate.

---

## Next phases

- **Phase 4 — Enforcement:** flip `FrontmatterRelationValidator` from
  warn-only to reject; require `canonical_id` on save; admin UI for
  structural conflicts.
- **Editorial cleanup:** backfill missing `title:` fields in frontmatter
  so future pin entries don't need title overrides.
- **Generator into WAR build:** if/when module ordering changes are
  worthwhile, move the gate from a unit test to a real Maven plugin
  binding.

When ready, ask for a Phase 4 plan and I'll write it from the design
doc's "Migration path > Phase 4" section.
