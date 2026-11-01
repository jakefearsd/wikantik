# Pre-WYSIWYG UI + Security Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close #106/#105/#104/#107, then ship Obsidian Phase 2 (`==highlight==`, `%% comments %%`), Phase 4 (inline `#tags`) and paste-uploaded embedded images.

**Architecture:** Security items are server-side changes in wikantik-main / wikantik-extract-cli / migrations. #104 replaces two ACL readers with one immutable reader (`AccessRules.read`) that the render path no longer writes to. The syntax phases add one server flexmark extension or pre-parse strip plus a matching remark plugin, live-preview range spec and parity fixture per construct.

**Tech Stack:** Java 25, flexmark 0.64.8, JUnit 5, PostgreSQL (`PostgresTestDb`), React 19 + CodeMirror 6 + remark/micromark, Turndown 7.2.4, vitest 4, Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-01-pre-wysiwyg-ui-and-security-design.md` (§ refs below), plus the normative Phase 2/4 sections of `docs/superpowers/specs/2026-10-31-obsidian-block-references-design.md` (“OBR”).

## Global Constraints

- TDD: every task starts with a test that fails for the stated reason (CLAUDE.md).
- No empty catch blocks — at least `LOG.warn()` with context.
- Work on `main`; stage files by name (never `git add -A`); 1–3 line commit messages ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Schema change ⇒ numbered idempotent migration, DDL only, `:app_user` grants. Next free: `V065`.
- New config keys (none expected) would need `ini/wikantik.properties` declaration (ConfigSurfaceDriftTest).
- Editor round-trip invariant: switching modes / moving caret never changes text (`livePreview/roundTrip.test.js`, `corpus.test.js` stay green).
- Live-preview typing cost must not regress (2.5.5 baseline; `src/bench/editorPreview.bench.jsx`).
- Widgets build DOM with `textContent`; no new `innerHTML`.
- `CHANGELOG.md` `[Unreleased]` entry per item; no News.md entries.
- Single-module checks: `mvn test -pl <module> -Dtest=<Class> -q`; frontend: `cd wikantik-frontend && npx vitest run <path>`.
- Never two Maven builds in one working tree; long builds via `bin/agent-build.sh`.
- Prod content tasks (marked **PROD**) use the MCP content tools, verify every write by re-pull, and are run by the controller, not an implementer subagent.

## Review Focus

1. A page that is locked by #104 is still editable by an Admin and the Admin can save a fix that removes the disputed span — pinned in Task 4.
2. A crafted body that blows the render budget still renders the *rest* of the page, and the fallback is cached rather than recomputed per view — pinned in Task 2.
3. `%%` inside a Mermaid/PlantUML fence or inline code is untouched, and an unterminated `%%` stays literal — pinned in Task 9.
4. `#123`, `#fff` in a CSS fence, `https://x/#frag`, `[[Page#Heading]]` and a `# Heading` line never produce tags; typing `# ` at line start never opens tag completion — pinned in Tasks 11 and 13.
5. Pasting a Google Doc with 3 embedded images into an **unsaved new page** leaves 3 placeholders at their positions that upload after first save, or vanish on cancel — pinned in Task 15.

---

### Task 1: #106 — directive syntax in config-reference prose becomes code

**Files:**
- Modify: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/configref/DescriptionRenderer.java`
- Test: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/configref/GenerateConfigReferenceCliTest.java`
- Regenerate: `docs/ConfigurationReference.md`, `docs/wikantik-pages/WikantikConfigurationReference.md` (via script only)

- [ ] **Step 1: Failing test** `pluginAndDirectiveSyntaxInProseIsRenderedAsCode`: `renderDescription(List.of("For example: \"[{ALLOW edit Charlie}]\". Also [{TableOfContents}]."))` — `full()` contains `` `[{ALLOW edit Charlie}]` `` and `` `[{TableOfContents}]` ``; an input already in backticks (`` `[{X}]` ``) is not double-wrapped; summary obeys the same rule.
- [ ] **Step 2:** Run `mvn test -pl wikantik-extract-cli -Dtest=GenerateConfigReferenceCliTest -q` → FAIL.
- [ ] **Step 3:** Add `private static String codeSpanDirectives(String prose)` applied wherever `escapeProseDollars` is (prose segments only; skip text inside existing code spans, same code-span walk as `escapeProseDollars`). Pattern `\[\{.*?\}\]` (non-greedy, single line).
- [ ] **Step 4:** Test passes; run `bin/config-reference.sh --write`; `mvn test -pl wikantik-extract-cli -Dtest=ConfigReferenceRegressionTest -q` passes; `grep -n 'ALLOW edit Charlie' docs/wikantik-pages/WikantikConfigurationReference.md` shows it in backticks.
- [ ] **Step 5: Commit** generator + test + both regenerated docs: `fix(config-ref): render [{…}] in prose as code (#106)`.
- [ ] **Step 6 (PROD, controller):** push the regenerated page to prod (system page: `bin/remote.sh pages-push` path per memory `reference_system_pages_push_via_pages_push`), re-pull, confirm no enforced ACL (anonymous `GET /api/pages/WikantikConfigurationReference` permissions.edit for testbot non-admin view). Close #106 with the commit SHA.

### Task 2: #105 — budgeted render parse with graceful fallback

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/parser/markdown/BudgetedText.java` (moved from `RenderPathAccessRules.CountedText`)
- Modify: `RenderPathAccessRules.java` (use `BudgetedText`), `MarkdownParser.java` (`parse()` ~:68-100), `MarkdownDocument.java` (an `options(..., boolean withAttributes)` overload)
- Test: `wikantik-main/src/test/java/com/wikantik/parser/markdown/MarkdownParserRenderBudgetTest.java`

**Interfaces:**
- Produces: `final class BudgetedText implements CharSequence` with `static BudgetedText of(String text, long budget)`, `static long budgetFor(int length)` (= `BUDGET_BASE + BUDGET_PER_CHAR * length`, constants moved here), and `static final class Exhausted extends RuntimeException`. Task 3 uses these.

- [ ] **Step 1: Failing tests:**
  - `quadraticAttributeInputRendersWithinBudget`: body `"intro para\n\n{edit" + " ".repeat(200_000) + "x\n\nTAIL-MARKER"` rendered through `TestEngine` `textToHTML` under `assertTimeoutPreemptively(Duration.ofSeconds(5))`; HTML contains `intro para` and `TAIL-MARKER`.
  - `untrackedFootnoteAttributeDoesNotFailTheRender`: `"[^1]: [[^1]\\[{y}]\n\nTAIL-MARKER"` renders, contains `TAIL-MARKER`.
  - `ordinaryAttributesStillApply`: `"# Title {#custom-id .note}"` → `id="custom-id"` and `class="note"`.
  - `fallbackIsCached`: render the crafted page twice via the engine; second render does not re-enter the parser (count via the render-cache seam, memory `reference_render_cache_test_seams`).
  - Corpus guard `repoCorpusNeverExhaustsTheBudget`: every `docs/wikantik-pages/*.md` parses with `BudgetedText` without `Exhausted`; record max reads/char in the assertion message.
- [ ] **Step 2:** Run `mvn test -pl wikantik-main -Dtest=MarkdownParserRenderBudgetTest -q` → first two FAIL (timeout / exception).
- [ ] **Step 3:** Extract `BudgetedText`; in `MarkdownParser.parse()` parse `BasedSequence.of(BudgetedText.of(body, budgetFor(body.length())))`; on `Exhausted | StackOverflowError | RuntimeException` from flexmark: `LOG.warn("render budget: page {} ({} chars) fell back to {}", …)`, re-parse with a parser built from `MarkdownDocument.options(…, false)` (no `ATTRIBUTES_EXT`, still budgeted); on second failure produce a document with one `<pre>` of the escaped body. Parsers for the no-attributes variant are built lazily once per `MarkdownParser` instance. Update the `RenderPathAccessRules` class comment to point at `BudgetedText`.
- [ ] **Step 4:** Tests pass; `mvn test -pl wikantik-main -Dtest='RenderPathAccessRulesTest,MarkdownParser*Test' -q` green.
- [ ] **Step 5: Commit** `fix(render): bound the markdown parse; fall back without attributes (#105)`.

### Task 3: #104 lock-down — one immutable access-rule reader

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/auth/acl/AccessRules.java`
- Modify: `auth/acl/DefaultAclManager.java` (`getPermissions` :148-177, `extractAclFromPageText`, `parseAcl` ~:100-135, `setPermissions` ~:210), `markdown/extensions/wikilinks/postprocessor/AccessRuleLinkNodePostProcessorState.java` (:78-97), `auth/acl/EnforcedAclDirectives.java` (delegate or retire), `RenderPathAccessRules.java` (expose the parse it already does)
- Test: `wikantik-main/src/test/java/com/wikantik/auth/acl/AccessRulesTest.java`, `AccessRulesOrderIndependenceTest.java`; extend `AclDirectiveFixtures.java`; delete `AclEnforcementUnchangedTest.java` (deliberate — list changed expectations in the commit body)

**Interfaces:**
- Consumes: `BudgetedText` (Task 2), `RenderPathAccessRules.find(String)` / `Unreadable`.
- Produces:
  ```java
  public final class AccessRules {
      public static Reading read(String fullText);           // never null
      public sealed interface Reading permits Rules, Locked { List<Span> spans(); }
      public record Rules(List<String> ruleLines, List<Span> spans) implements Reading {}
      public record Locked(List<Reason> reasons, List<Span> spans) implements Reading {}
      public record Span(int start, int end, Shape shape) {}
      public enum Shape { CANONICAL, WRAPPED, CASE_VARIANT, LEADING_SPACE, ESCAPED,
                          DENY, ALLOW_PREFIXED_WORD, IN_HTML_BLOCK, IN_MATH, IN_FRONTMATTER,
                          LINK_WITH_TARGET, BAD_PERMISSION, UNREADABLE }
      public record Reason(Shape shape, int line, String text) {}
      static boolean disputed(Shape s);   // Task 3: every shape except CANONICAL is disputed; Task 5 narrows it
  }
  ```
  `DefaultAclManager.LOCKED_PRINCIPAL` (a `Principal` no session can hold) — a Locked reading yields an Acl granting every page permission to it alone.

- [ ] **Step 1: Failing tests** (`AccessRulesTest`, one parameterised row per spec §5.1 shape from `AclDirectiveFixtures`): canonical → `Rules` with `ruleLines == ["ALLOW view Admin"]`; every other shape → `Locked` with the matching `Shape`; `Unreadable` input (`{edit`+200k spaces with an ALLOW) → `Locked(UNREADABLE)`; text with no `{allow`/`{deny` in any case → `Rules([], [])` without parsing (assert via a parse counter seam).
  `AccessRulesOrderIndependenceTest`: for each fixture on a `TestEngine` page — (a) `getPermissions` alone, (b) render then `getPermissions`, (c) `getPermissions`, render ×3, `getPermissions` — all three Acls equal as `principal:action` sets; the Acl returned from cache is not mutated by a render (identity + content check).
  Enforcement: a `Locked` page → anonymous and a non-admin editor denied `view` and `edit` (`isPermitted`), Admin-group member allowed.
- [ ] **Step 2:** Run → FAIL (class missing / order tests show union).
- [ ] **Step 3:** Implement `AccessRules.read`: body rules from the flexmark structural parse (re-using `RenderPathAccessRules`' parser over `BudgetedText`), plus a case-insensitive directive-shape scan (`DirectiveShapes`) over frontmatter and over every region the parse classifies as HTML block / math / link-with-target / escaped, each hit tagged with its `Shape`. `parseAcl` builds into a **new** `Acl` (signature becomes `Acl parseAcl(Page page, Acl into, String ruleLine)` or equivalent — no `page.getAcl()` read). `getPermissions` caches `(version, lastModified) → immutable Acl` and never returns `page.getAcl()` set by anyone else; a `Rules` line that `parseAcl` rejects becomes `Locked(BAD_PERMISSION)`. `AccessRuleLinkNodePostProcessorState` keeps node hiding / error node, removes the `parseAcl`/`setAcl` calls. `setPermissions` removes spans from `AccessRules.read(text).spans()`. `LOG.warn` once per (page, version) on Locked.
- [ ] **Step 4:** `mvn test -pl wikantik-main -Dtest='AccessRules*Test,AclDirective*Test,*AclDirectiveTest,DefaultAclManager*Test,DefaultAuthorizationManager*Test,AclDirectiveTextTest,RenderPathAccessRulesTest' -q` green.
- [ ] **Step 5: Commit** `fix(acl): one immutable access-rule reader; disputed shapes lock the page (#104)` with the retired-test expectation list in the body.

### Task 4: #104 — locked-page visibility and save-time refusal

**Files:**
- Modify: the save filter chain — add `wikantik-main/src/main/java/com/wikantik/auth/acl/AccessRuleShapeFilter.java` registered beside `StructuralSpinePageFilter`; REST 422 mapping in `wikantik-rest` page save (`PageResource` PUT, same structured-error shape as frontmatter validation); MCP `write_pages`/`update_page` refusal text in `wikantik-admin-mcp`.
- Modify: page payload (`/api/pages/{name}`) gains `accessRules: { locked: true, reasons: [{shape,line,text}] }` **only for callers with AllPermission**; `PageView.jsx` renders an admin banner from it.
- Admin listing: `/admin/content` report (or a drift kind `UNREADABLE_ACCESS_RULE` if `StructuralConflict.Kind` fits — check `/admin/drift` wiring first and pick one; record which in the commit).
- Test: `AccessRuleShapeFilterTest`, REST IT in `wikantik-it-tests/wikantik-it-test-rest` (`LockedAccessRulesIT`), MCP unit + IT pair (memory `feedback_mcp_write_surface_pairing`), `PageView` vitest for the banner.

**Interfaces:** Consumes `AccessRules.read`, `AccessRules.disputed(Shape)`.

- [ ] **Step 1: Failing tests:** saving a body that adds `[{DENY view Bob}]` → filter throws with message naming the shape, line, and canonical alternative ("write `[{ALLOW …}]`; to show the syntax, put it in `code`"); saving a locked page as Admin with the disputed span **unchanged** and other text edited → succeeds; removing the span → page unlocked on next `getPermissions`; REST PUT → 422 with `errors[0].code == "ACCESS_RULE_SHAPE"`; MCP `update_page` → tool error citing the reason; non-admin GET of a locked page → 403/404 exactly as for any restricted page; Admin GET → `accessRules.locked == true`; banner renders reasons with line numbers.
- [ ] **Step 2–4:** run the unit tests (`mvn test -pl wikantik-main,wikantik-rest,wikantik-admin-mcp -Dtest=… -q`, `npx vitest run src/components/PageView`), implement, re-run green; REST IT via `bin/run-tests.sh --module rest`.
- [ ] **Step 5: Commit** `feat(acl): refuse new disputed access-rule shapes; show admins why a page is locked (#104)`.

### Task 5: #104 open-up — reclassify unambiguous shapes

**Files:** `AccessRules.java` (`disputed`, rule-line normalisation), `AclDirectiveFixtures.java`, `AccessRulesTest.java`, `AclDirectiveText` tests if the hiding set changes (it must remain ⊇ enforced).

- [ ] **Step 1: Failing tests** — spec §5.3 final table, row by row: `WRAPPED` → `Rules` with the lines joined by single spaces (`"ALLOW edit a, b"`); `CASE_VARIANT` (`[{allow VIEW Bob}]`) → `Rules(["ALLOW view Bob"])`; `LEADING_SPACE` → `Rules`; `ESCAPED` → neither a rule nor a lock (`Rules([], spans with ESCAPED)`); `DENY`, `ALLOW_PREFIXED_WORD`, `IN_HTML_BLOCK`, `IN_MATH`, `IN_FRONTMATTER`, `LINK_WITH_TARGET`, `BAD_PERMISSION`, `UNREADABLE` → still `Locked`. Order-independence test still green. `AclDirectiveText` invariant test: no enforced span survives stripping.
- [ ] **Step 2–4:** fail → implement → `mvn test -pl wikantik-main -Dtest='AccessRules*Test,AclDirectiveText*Test,*AclDirectiveTest' -q` green.
- [ ] **Step 5: Commit** `feat(acl): read wrapped, case-variant and spaced rules; escaped rules are text (#104)`.

### Task 6: #104 audit + page fixes

**Files:** Create `wikantik-main/src/test/java/com/wikantik/auth/acl/RepoCorpusAccessRulesTest.java`; create `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/AclShapeAuditCli.java` (reads pages over the REST API as testbot, prints per page: name, `Reading`, old text-scan ACL, old render ACL, new ACL — old readers kept as test-only/CLI-only copies, not in production code); create `docs/superpowers/specs/2026-11-01-acl-shape-audit.md`.

- [ ] **Step 1:** `RepoCorpusAccessRulesTest` asserts zero `Locked` readings over `docs/wikantik-pages/` and `wikantik-wikipages/**` — run, record failures.
- [ ] **Step 2:** Fix each repo page per spec §5.4 rule 2; commit pages + test: `content: canonical access rules in repo pages (#104)`.
- [ ] **Step 3 (PROD, controller):** run the CLI against `https://wiki.wikantik.com`; write every finding and decision into the audit doc; fix each prod page with `update_page`, re-pull to verify; re-run until zero locked and every changed-ACL page is listed. Commit the audit doc. Note: pre-deploy, a canonical rewrite is read identically by both old readers.

### Task 7: #107 — tombstone deleted logins

**Files:**
- Create: `bin/db/migrations/V065__deleted_logins.sql`; `wikantik-main/src/main/java/com/wikantik/auth/user/DeletedLoginRepository.java` (holds a `Jdbc`)
- Modify: `auth/UserDeletionService.java` (:99-110), `auth/identity/DefaultPublicIdentity.java` (:73-90 + snapshot loader), `wikantik-api/.../identity/PublicIdentity.java` (`FORMER_MEMBER = "former member"`), `DefaultUserManager.setUserProfile` (:175) / `validateProfile` (:294) for the reuse refusal, SCIM create path in `wikantik-scim`, `docs/admin/WikantikOperations.md` (releasing a name)
- Test: `DeletedLoginRepositoryTest` (`@RequiresPostgres`), `UserDeletionServiceTest`, `DefaultPublicIdentityTest`, `DefaultUserManagerTest`, SCIM create test

**Interfaces:** `DeletedLoginRepository { void record(String login); boolean isDeleted(String login); Set<String> all(); }`.

- [ ] **Step 1: Failing tests:** after `UserDeletionService.delete("alice")` — `isDeleted("alice")`; `PublicIdentity.displayName("alice") == "former member"`; a never-account value `"Imported Author"` still shows as written; email-shaped still "unknown contributor"; `FaultInjectingDataSource` failing the tombstone insert ⇒ delete throws and the `users` row still exists; creating a profile with login `alice` (self-registration, admin create, SCIM POST) refused with `"this login name belonged to a deleted account"`; `PolicyVerifier` self-test path writes no tombstone. Migration re-applied is a no-op (`MigrationApplier` test convention).
- [ ] **Step 2–4:** fail → implement (tombstone recorded before `deleteByLoginName`, in the same `inTransaction` if the user delete runs through `Jdbc`; otherwise record-first so a failure aborts) → `mvn test -pl wikantik-main,wikantik-scim -Dtest='DeletedLogin*,UserDeletionService*,DefaultPublicIdentity*,DefaultUserManager*,Scim*User*' -q` green.
- [ ] **Step 5: Commit** `fix(identity): deleted accounts show as "former member"; their logins can't be reused (#107)`.

### Task 8: Phase 2 — `==highlight==`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/highlight/HighlightExtension.java` (+ delimiter processor, node, renderer); register in `MarkdownDocument` next to `STRIKETHROUGH_EXT` (both full and structural options).
- Create: `wikantik-frontend/src/utils/remarkHighlight.js` (micromark syntax extension + mdast `mark` → `<mark>`), wire into `PageEditor.jsx` remark plugin list beside `remarkInlineMath`; fixture `src/utils/__fixtures__/highlight.json`; parity tests `wikantik-main/src/test/java/com/wikantik/markdown/HighlightParityTest.java` + `src/utils/remarkHighlight.test.jsx`.
- Modify: `utils/livePreview/ranges.js` (spec `cm-lp-mark`), `__fixtures__/live-preview-parity.json`, `utils/markdownFormat.js` + `utils/editorCommands.js` (Highlight, `Mod-Shift-H`), `utils/paste/htmlToMarkdown.js` (`addRule('mark', …)` → `==…==`), excerpt/plain-text derivation (`ReaderText` path drops delimiters), CSS token `--highlight-bg` light + dark (beside existing theme tokens), `WikantikHtmlSanitizer` test that `<mark>` survives.

- [ ] **Step 1: Failing tests:** fixture cases (both sides read the same JSON): `==x==`→`<mark>x</mark>`; `==**x**==`→`<mark><strong>x</strong></mark>`; `**==x==**`; adjacent `==a====b==`; `a == b` and `== x ==` (no mark — flanking); `` `==x==` `` code; `$==x==$` math; `===x===` (run of 3: no mark, matches flexmark strikethrough's run-length rule); live-preview parity rows; Turndown `<mark>y</mark>`→`==y==`; `Mod-Shift-H` wraps selection / unwraps; excerpt of `==x==` is `x`.
- [ ] **Step 2–4:** fail → implement → `mvn test -pl wikantik-main -Dtest='HighlightParityTest,LivePreviewParityTest,*Sanitizer*Test' -q` and `npx vitest run src/utils/remarkHighlight src/utils/livePreview src/utils/paste src/utils/editorCommands` green.
- [ ] **Step 5: Commit** `feat(markdown): ==highlight== (Obsidian phase 2)`.

### Task 9: Phase 2 — `%% comments %%`

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/markdown/ObsidianComments.java` (`static String strip(String text)`); test `ObsidianCommentsTest`.
- Modify: `MarkdownParser.parse()` (strip before `MarkdownBodySource.prepare`), `ReaderText.derived`/`derivedBody` (OBR §1.11a per-caller rule), importer `VaultBodyRewriter.java` (stop stripping; drop the `comment:` warning) + its test.
- Frontend: `src/utils/obsidianComments.js` (same contract, used by the preview pipeline before remark), `__fixtures__/comments.json` shared with a Java parity test; `livePreview/ranges.js` `cm-lp-comment` (dimmed, delimiters visible); "Toggle comment" `Mod-/` in `editorCommands.js` — must take precedence over `@codemirror/commands` `toggleComment`.

- [ ] **Step 1: Failing tests:** `a %%hidden%% b`→`a  b`; multi-line comment replaced by the same number of `\n`; unterminated `a %%b` unchanged; inside `` `%%x%%` ``, fenced code, ```` ```mermaid ```` with `%% mermaid comment` → unchanged; inside `$…$` / `$$…$$` unchanged; `?format=md`, for-agent projection, chunk text, Lucene document text and excerpt all lack `hidden` (one test per consumer through `ReaderText`); editor live preview shows the comment with `cm-lp-comment`; `Mod-/` on a selection wraps in `%%…%%`, again unwraps; importer keeps `%%x%%` and emits no `comment:` warning.
- [ ] **Step 2–4:** fail → implement → `mvn test -pl wikantik-api,wikantik-main -Dtest='ObsidianComments*,*ReaderText*,*Chunker*,VaultBodyRewriter*,LivePreviewParityTest' -q`; vitest on touched dirs green.
- [ ] **Step 5: Commit** `feat(markdown): %% comments %% hidden from every reader surface (Obsidian phase 2)`.
- [ ] **Step 6:** `EditPageHelp.md` (both copies: `docs/wikantik-pages/` and `wikantik-wikipages/en/src/main/resources/`) gains Highlight + Comments, stating "comments are not private — anyone who can edit or view history sees them". Commit with CHANGELOG entry.

### Task 10 (PROD, controller): Phase 2 corpus audit

- [ ] Run the OBR §5 detectors (`==…==` spans, any `%%` outside code/math) over the prod corpus (`list_pages` + `read_pages`) and the repo corpus; append findings + fixes to `docs/superpowers/specs/2026-10-31-obsidian-syntax-corpus-audit.md` (Phase 2 section, same table format as Phase 1); escape unintended hits (`\=\=`, `\%\%`) via `update_page`, re-pull to verify; commit the audit doc.

### Task 11: Phase 4 — `InlineTagSyntax` + server rendering

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/markdown/InlineTagSyntax.java` (moved from `wikantik-main/.../importer/InlineTags.java`; `static List<Tag> scan(String body)`, `static String normalise(String tag)` = importer's `normaliseTag`; `record Tag(int start, int end, String written, String normalised)`) + math exclusion; move `InlineTagsTest` → `InlineTagSyntaxTest`.
- Create: flexmark inline extension `wikantik-main/.../markdown/extensions/inlinetags/InlineTagExtension.java` rendering `<a class="tag inline-tag" href="/search?tag=a-b">#a/b</a>`; register in `MarkdownDocument`.
- Fixture `wikantik-frontend/src/utils/__fixtures__/inline-tags.json` + `InlineTagParityTest.java`.

- [ ] **Step 1: Failing tests:** `#foo`→tag foo; `#Foo`→normalised `foo`, rendered text `#Foo`; `#a/b`→`a-b`, text `#a/b`; `#123` no; `#123a` yes; `x#foo` no (needs preceding whitespace/SOL); `# Heading` / `## H #x` heading lines no; `` `#x` ``, fenced, `$#x$` no; `https://e.com/#frag`, `[[Page#Heading]]`, `[t](#frag)` no; `#fff` in prose → tag (OBR §8.8); the rendered href is URL-encoded.
- [ ] **Step 2–4:** fail → implement → `mvn test -pl wikantik-api,wikantik-main -Dtest='InlineTagSyntax*,InlineTagParityTest,VaultFrontmatterMapper*' -q` green.
- [ ] **Step 5: Commit** `feat(markdown): inline #tags render as tag links (Obsidian phase 4)`.

### Task 12: Phase 4 — tags for finding

**Files:** `search/subsystem/lucene/DefaultLuceneIndexer.java` (tags field = frontmatter ∪ inline, normalised), `pagegraph/spine/DefaultStructuralIndexService.java` (page tag set + tag filter compare normalised forms), REST structure/search endpoint that serves `?tag=` (locate the `/api/structure` tag filter resource), `SearchResultsPage.jsx` (`/search?tag=x` with no `q` lists tagged pages), `PageView.jsx:821` frontmatter chips → `/search?tag=<normalised>`.
- SEO/feeds/sitemap/JSON-LD stay frontmatter-only — add one regression test asserting `<meta keywords>` excludes an inline-only tag.

- [ ] **Step 1: Failing tests:** a page with frontmatter `tags: [Foo]` and body `#foo #bar/baz` → structural index tags `{foo, bar-baz}`; `list_tags` count for `foo` is 1 (no dup); `list_pages_by_filter(tag="FOO")` finds it; Lucene `tags` field holds both; `SemanticHeadRenderer` keywords = `Foo` only; vitest: `/search?tag=foo` renders the tagged list, chips link to `/search?tag=foo`.
- [ ] **Step 2–4:** fail → implement → targeted `mvn test` + vitest green.
- [ ] **Step 5: Commit** `feat(search): inline tags count for tag lists, filters and the tag view (Obsidian phase 4)`.

### Task 13: Phase 4 — editor (preview, live preview chip, completion) + importer

**Files:** `src/utils/remarkInlineTags.js` (+ test, reads `inline-tags.json`), wire in `PageEditor.jsx`; `livePreview/ranges.js` `cm-lp-tag` mark decoration (no replace widget); `src/utils/tagComplete.js` (`@codemirror/autocomplete` source, shape of `wikiLinkComplete.js`; data from the structural tag distribution API, ranked by count); `live-preview-parity.json` rows; importer `VaultFrontmatterMapper.java:107` stops lifting inline tags (+ test); `EditPageHelp.md` both copies; CHANGELOG.

- [ ] **Step 1: Failing tests:** preview renders `#foo` as `a.tag.inline-tag`; live preview marks `#foo` off-line and shows raw on the caret line; completion opens after `#f` in prose, not after `# ` at line start, not in code, not inside `[[`; picking inserts the tag's written form; round-trip + corpus tests green; importer keeps `#x` in body and does not add it to frontmatter.
- [ ] **Step 2–4:** fail → implement → vitest dirs + `mvn test -pl wikantik-main -Dtest='VaultFrontmatterMapper*,LivePreviewParityTest' -q` green; one Selenide IT `InlineTagIT` (click inline tag → tag view lists the page) via `bin/run-tests.sh --module selenide` (or the module name the script uses).
- [ ] **Step 5: Commit** `feat(editor): inline #tag preview, live-preview chip and completion (Obsidian phase 4)`.

### Task 14 (PROD, controller): Phase 4 corpus audit

- [ ] Detector: `InlineTagSyntax.scan` over prod + repo corpora; append to the corpus audit doc; escape unintended (`\#`) via `update_page`, re-pull; commit.

### Task 15: Paste — upload embedded data-URI images in place

**Files:** `src/utils/paste/htmlToMarkdown.js` (`dropEmbeddedImages` → split into `extractEmbeddedImages` returning `{ files: File[], dropped: number }` and leaving a placeholder token per extracted image), `src/utils/paste/markdownPaste.js` (decision carries `images`), `src/hooks/useAttachmentUpload.js` (new returned function `uploadPlaced(images, { defer })` where each image has `{ file, token }`; it picks names, substitutes `![Uploading name…]()` for each token in the insert text **before** insertion, then uploads), `components/PageEditor.jsx` (`handlePasteConverted` routes images; new-page deferral like `handleFiles`; toast says only skipped `blob:/file:/cid:/svg` counts).

**Interfaces:** raster types `image/png|jpeg|gif|webp` only; max 20 per paste (`MAX_PASTED_IMAGES = 20`), the rest counted as dropped.

- [ ] **Step 1: Failing tests:** Google-Docs-shaped HTML with 2 `data:image/png` imgs between paragraphs → markdown has 2 tokens at those positions, `files.length == 2` with correct MIME; `data:image/svg+xml` and `blob:` counted as dropped, no file; 21 images → 20 files + 1 dropped; hook: `uploadPlaced` success replaces each placeholder with `attachmentMarkup(name, true)`, a failed upload removes only its placeholder and toasts; `defer` returns `{start, cancel}` and cancel removes placeholders; `PasteAsMarkdownIT` gains `pastedDataUriImageBecomesAnAttachment` (synthetic ClipboardEvent with a 1×1 PNG data URI → page attachment list contains `pasted-image-*.png` and body links it).
- [ ] **Step 2–4:** fail → implement → `npx vitest run src/utils/paste src/hooks/useAttachmentUpload src/components/PageEditor` green; IT via the selenide module.
- [ ] **Step 5: Commit** `feat(editor): pasted embedded images upload as attachments in place`.

### Task 16: Final verification + docs

- [ ] CHANGELOG `[Unreleased]` covers every item (security items under **Security**, #104 note lists the shape table and "pages whose access changed are listed in the audit doc", #107 notes legacy deletions stay as written).
- [ ] `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll to SUCCESS. `cd wikantik-frontend && npx vitest run --coverage` green. `mvn pmd:check -Pcomplexity-gate` green.
- [ ] Final whole-branch review (opus) against the spec; fix findings.
- [ ] Publish the design doc to the live wiki under the Wikantik development hub (MCP `write_pages`, re-pull).
- [ ] Report to the maintainer; release + deploy only on their go-ahead, after Task 6's audit reports zero locked prod pages.
