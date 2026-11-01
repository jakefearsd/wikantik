# Pre-WYSIWYG UI work + security issue sweep — Design

Status: approved for build 2026-11-01 (the maintainer opted out of further questions: "move forward yourself
deriving judgement from the previous work"). Every **Call** below is the author's, not the maintainer's.

## 0. Why

Two programs before a rich-text (WYSIWYG) editor is designed:

1. **Freeze the syntax surface.** A rich-text editor needs a schema node/mark + serializer per construct; any
   construct added after it ships is built twice. So the outstanding Obsidian syntax (Phase 2 highlight +
   comments, Phase 4 inline tags) lands first, plus the paste-pipeline gap (embedded images) the WYSIWYG
   paste path will reuse. Obsidian Phase 3 (reader task toggle) is independent and is **not** in scope.
2. **Close the security-adjacent issues** filed during the 2.4.58 display-names work: #104, #105, #106,
   #107. #63's code shipped in 2.4.26; only a prod KG re-materialisation remains, which the maintainer is
   holding — **out of scope**.

### Anti-goals (failure even if it "works")
- Toggling editor modes, opening a page or moving the caret changes saved text (2.5.5's round-trip
  invariant stays green, now covering the new syntax).
- Any page becomes viewable/editable by someone who could not see/edit it under **both** of today's ACL
  readers (#104) — except the deliberate per-page fixes in §5.4, each recorded.
- Typing latency regression on large pages (live preview baseline from 2.5.5).

---

## 1. Obsidian Phase 2 — `==highlight==` and `%% comments %%`

**Normative spec:** `2026-10-31-obsidian-block-references-design.md` §"Phase 2", §5 (corpus audit), §6
(testing), §7 (docs), §8 items 7. This section only adds what that spec could not know before 2.5.5.

- **Live preview** (2.5.5 machinery): highlight → mark decoration `cm-lp-mark` hiding the `==` delimiters
  off the cursor's line, same as `~~`; comments → `cm-lp-comment` dimmed (delimiters stay visible — the
  editor must show comments are there). Plug into `utils/livePreview/ranges.js` specs; no new widget type.
- **Parity:** add cases to `src/utils/__fixtures__/live-preview-parity.json` (live preview / preview pane /
  flexmark reader agree) as well as the per-feature `highlight.json`; `corpus.test.js` stays green.
- **Keys — Call:** `Mod-Shift-H` Highlight. `Mod-/` "Toggle comment" — verify `@codemirror/commands`'
  default `toggleComment` (HTML `<!-- -->` in markdown) is not what fires; the Obsidian `%%` command takes
  precedence in the editor keymap. Both also in the command palette and `EditPageHelp`.
- **Paste-as-Markdown:** Turndown rule `<mark>` → `==…==`.
- Corpus audit (prod first) **before deploy**, appended to `2026-10-31-obsidian-syntax-corpus-audit.md`.

## 2. Obsidian Phase 4 — inline `#tags`

**Normative spec:** same file, §"Phase 4", §5, §6, §8 item 8. Additions:

- `InlineTags` (wikantik-main `com.wikantik.importer`) moves to wikantik-api as `InlineTagSyntax` with the
  new math exclusion; `InlineTagsTest` moves/extends with it.
- Live preview: chip `cm-lp-tag` (mark decoration, text stays as written — no replace widget, so the caret
  never jumps). Completion on `#` + a character in prose from the tag distribution (ranked by count), the
  same `@codemirror/autocomplete` source shape as `wikiLinkComplete.js` / `slashComplete.js`. `#` at
  line start followed by a space is a heading and must never trigger completion.
- Parity cases in `live-preview-parity.json` + `inline-tags.json`.
- Corpus audit before deploy (`#fff`-style CSS colours in prose, issue numbers like `#104` are excluded by
  the at-least-one-non-digit rule; `#104a` is not).

## 3. Paste-as-Markdown: upload embedded images

Today `htmlToMarkdown` drops `data:/blob:/file:/cid:` images and toasts the count (2.5.4 known limit).

- **What uploads — Call:** `data:` URIs of raster image types (`image/png`, `image/jpeg`, `image/gif`,
  `image/webp`). They are decoded in the browser to a `File` and uploaded as page attachments through the
  existing `useAttachmentUpload` path (naming via `pastedImageName`, reservation, per-file failure toast,
  deferred start on a new unsaved page — exactly as a pasted OS image file). `data:image/svg+xml` is
  **not** uploaded (script-carrying format; the attachment layer's own SVG policy is not relitigated
  here) — it stays dropped and counted. `blob:`, `file:`, `cid:` cannot be read from a paste and stay
  dropped and counted; the toast wording changes to say only those were skipped.
- **Placement:** each uploaded image lands **where it was in the pasted content** (an upload placeholder
  `![Uploading name…]()` in the converted Markdown at the image's position, replaced by the attachment
  markup on success, removed on failure) — not stacked at the caret. `useAttachmentUpload` gains an entry
  point that takes pre-placed placeholders; the existing stacked path is unchanged.
- **Limits:** a single paste uploads at most 20 images (more → the rest dropped and counted). Size limits
  are the server's attachment limits; an oversize image fails through the normal per-file toast.
- Not inside code / not on legacy pages — inherited (conversion never runs there).
- Tests: `htmlToMarkdown` fixtures (Google Docs + Word data-URI samples) assert placeholders at position;
  hook tests for placed placeholders incl. failure + deferred; `PasteAsMarkdownIT` gains a data-URI case
  that ends with an attachment on the page.

---

## 4. #106 — the config-reference page restricts its own editing

`DescriptionRenderer` (wikantik-extract-cli) renders comment prose; the `wikantik.aclManager` description
contains `"[{ALLOW edit Charlie}]"`, which is enforced on the generated page. Fix: a prose transform beside
`escapeProseDollars` wraps any `[{…}]` outside a code span in backticks (all plugin/directive syntax, not
just ACLs — a `[{Plugin}]` in prose would execute too). Failing test first in the generator's tests;
regenerate with `bin/config-reference.sh --write`; push the regenerated `WikantikConfigurationReference` to
prod via `bin/remote.sh pages-push`-style system-page push (memory: system pages are MCP-protected).

## 5. #104 — one reader of access rules

### 5.1 Today (investigation, 2026-11-01)

Two readers feed the same `DefaultAclManager.parseAcl(page, ruleLine)`:

- **Text scan** (`getPermissions` → `EnforcedAclDirectives.spans` → `AclDirectiveScanner`, full text incl.
  frontmatter, vanilla CommonMark code exclusion only): one line, literal `ALLOW`, lowercase permission.
- **Render path** (`AccessRuleLinkNodePostProcessorState` → `parseAcl(realPage)`): any link label starting
  `{ALLOW`/`{DENY`, body only, real flexmark structure (code, HTML blocks, math, escapes, real links excluded).

`parseAcl` **mutates** `page.getAcl()`; the Page cache returns the shared instance; `getPermissions`
returns a non-null `page.getAcl()` without rescanning, and the `aclCache` hit shares the same Acl object.
So enforcement is order-dependent: render-first = render's reading; scan-first = union, accumulated into
the cached Acl. Disagreeing shapes:

| Shape | Text scan | Render path |
|---|---|---|
| wrapped over lines (no blank line) | none | rule |
| `DENY …` | none | **grant** (first word discarded) |
| upper/mixed-case keyword or permission (`ALLOW VIEW`) | none | rule (`VIEW`); keyword must be `ALLOW` |
| `ALLOWED …` | none | rule |
| inside an HTML block | rule | none |
| inside inline/display math | rule | none |
| in frontmatter | rule | none |
| `[{ALLOW …}](url)` (a real link) / `\[{ALLOW …}]` (escaped) | rule | none |
| `[{ ALLOW …}]` (space after brace) | rule | none |

### 5.2 Target model (the maintainer's decision: lock down first, then open up, then fix pages)

One function, `AccessRules.read(fullText) → AccessRules.Reading` (wikantik-main `com.wikantik.auth.acl`),
is the **only** source of an enforced ACL. It is built on the render path's real parse
(`RenderPathAccessRules`' parser, budget and `Unreadable`), plus a scan of the frontmatter and of the
regions the parse excludes, so it can *see* every shape above. A `Reading` is one of:

- `Rules(List<RuleLine>, spans)` — every rule-shaped span is unambiguous; the ACL is built **fresh** from
  these lines (no mutation of any shared Acl).
- `Locked(reasons, spans)` — at least one span is in a disputed shape (§5.3) or the text is `Unreadable`.

**Enforcement:**
- `getPermissions` = `AccessRules.read(page text)` cached by (name, version, lastModified) — as today's
  `aclCache`, but the cached value is immutable and never written back by a render.
- **The render path stops setting the page ACL.** `AccessRuleLinkNodePostProcessorState` keeps hiding the
  rule node (and its error node for an unparseable permission) but no longer calls `parseAcl`/`setAcl`.
  This removes the order dependence by construction.
- `parseAcl` builds into a **new** Acl; `page.setAcl` is only ever given the reader's result.
- **Locked** = only principals with `AllPermission` (Admin group) may view/comment/edit/… the page.
  Everyone else gets the normal 403/404 surfaces (no existence oracle beyond today's). Admins see a page
  banner "Access rules on this page can't be read: <reasons>" with each offending span's line. Locked
  pages are listed for admins (a `StructuralConflict`/drift kind `UNREADABLE_ACCESS_RULE` on
  `/admin/drift` if it fits that framework; otherwise an `/admin/content` report — builder's call, but
  admins must be able to list them). Every lock logs `LOG.warn` once per (page, version).
- **Save path:** a save whose body newly contains a disputed shape outside code is **refused** (422 with
  the canonical form to write instead, same structured-error shape as frontmatter validation; MCP parity
  — the refusal cites the reason). An unchanged disputed span already present in the previous version is
  allowed through, so admins can still save other fixes to a locked page. **Call:** without this, any
  editor can lock a page out (including from themselves) by typing `[{DENY …}]`.
- `setPermissions` / `changeAcl` / profile renames remove and rewrite spans using the reader's spans, not
  the old scanner's.
- `AclDirectiveText` (hiding for non-editors) keeps its guarantee (hidden ⊇ enforced) and its fail-closed
  path; it may now take the reader's spans as one of its sets. Its tests stay green unchanged.

### 5.3 Shapes after "open up slightly"

Built in two commits so the lock-down is reviewable on its own: commit 1 treats **every** row of §5.1 as
`Locked`; commit 2 reclassifies the rows where the author's intent is unambiguous.

| Shape | Final classification | Why |
|---|---|---|
| canonical one-line `[{ALLOW perm principals}]` in body prose | rule | both readers agree today |
| wrapped over lines (no blank line) | **rule** | intent clear; joined with spaces |
| keyword `ALLOW` in any case; permission in any case | **rule** | intent clear; `PagePermission` is case-insensitive already |
| `[{ ALLOW …}]` (whitespace after brace) | **rule** | intent clear |
| escaped `\[{ALLOW …}]` | **literal text** (not a rule, not locked) | escaping is how an author says "not markup" |
| inside code span / fence (both agree today) | literal | unchanged |
| `DENY …` | **locked** | the ACL model is allow-only; no faithful reading exists |
| `ALLOWED …` / any other first word starting `ALLOW` | **locked** | ambiguous |
| inside an HTML block or math | **locked** | readers disagree on whether it is content at all |
| in frontmatter | **locked** | same |
| `[{ALLOW …}](url)` (a link) | **locked** | same |
| a permission `parseAcl` rejects (`viewbob`) | **locked** (today: silently dropped → page open) | fail closed |

### 5.4 Corpus audit and page fixes (before the release is deployed)

Prod is authoritative (memory: corpus divergence); the repo corpus is audited too.

1. A detector (the reader itself, run over every page via the live index/MCP read tools, plus a repo
   corpus test) lists every page whose final classification is `Locked`, **and** every page whose
   effective ACL under the new reader differs from what either old reader enforces.
2. Each finding gets a per-page decision recorded in
   `docs/superpowers/specs/2026-11-01-acl-shape-audit.md`: page, span, old text-scan ACL, old render ACL,
   new ACL, the fix, and why. Fix rules: write the canonical shape that expresses the author's evident
   intent; where intent is not expressible (DENY) choose the **narrower** of the two old readings and say
   so; documentation that merely *mentions* a directive gets it put in a code span.
3. Fixes are pushed with the MCP content tools **before deploy** (a canonical rewrite is read identically
   by both old readers, so pre-deploy edits are safe), each verified by re-pull. Repo-corpus pages are
   fixed in git in the same series. At deploy, the detector must report zero locked pages.

### 5.5 Tests (failing first)

- A fixture table (extend `AclDirectiveFixtures`) of every §5.3 row → expected `Reading`.
- **Order independence:** for every fixture, render-then-check == check-then-render == check alone, on a
  shared cached Page, repeated renders included (today's accumulation bug).
- Locked page: anonymous / editor / Admin outcomes through `DefaultAuthorizationManager` and one REST IT.
- Save refusal (REST 422 + MCP refusal citing reason) and the "unchanged disputed span" allowance.
- `AclEnforcementUnchangedTest` is **retired deliberately** (it pins the old split); its fixtures move to
  the new table, and each changed expectation is listed in the commit message.
- Repo-corpus test: zero locked pages after the repo fixes.

## 6. #105 — bounded render cost

flexmark 0.64.8's attributes extension matches `{…}` with a backtracking expression: quadratic on `{edit` +
a long whitespace run, stack overflow on a long run of attribute-like words. 2.4.58 bounded only the
read-path rule check (`RenderPathAccessRules`, a `CountedText` read budget, fail-closed `Unreadable`). The
render (`MarkdownParser` / `MarkdownDocument`) is unbounded.

- **Mechanism — Call:** reuse the same deterministic read budget for the render's parse (extract
  `CountedText` to a package-level `BudgetedText` shared by both; same constants unless measurement over
  the repo corpus says the render needs more — record the measured max reads/char in the class comment).
  No wall-clock timer, no thread pool: regex does not honour interrupts, a counter does.
- **On exhaustion or `StackOverflowError`:** re-parse once **without the attributes extension** (still
  budgeted). Attributes are cosmetic (`{.class #id}`), so the page stays readable with everything else
  intact. If that also fails, the body renders as escaped preformatted text. Every fallback logs
  `LOG.warn` with page name, body length and which stage failed, and the render is flagged
  `VAR_VIEWER_SENSITIVE`-style **not** cacheable? — **Call: it IS cached.** The fallback is a pure
  function of the text, and caching it is what stops a crafted page being re-parsed on every view.
- **Save path:** unchanged (no save-time refusal; the issue is cost, not validity). Editor preview is
  client-side remark, unaffected.
- Tests (failing first): the two known inputs from `RenderPathAccessRulesTest` (`{edit` + 200k spaces;
  the footnote `[^1]: [[^1]\\[{y}]` "is not tracked" throw) rendered through `MarkdownParser` under
  `assertTimeoutPreemptively(5s)`, asserting the rest of the page's text is present; a normal page with
  `{.class}` attributes still gets them; the budget is never hit across the repo corpus (corpus test).

## 7. #107 — deleted accounts keep their handle on old content

`DefaultPublicIdentity.displayName` shows a login with no account "as written" (email-shaped → "unknown
contributor"). Only **hard** delete (`UserDeletionService.delete`, admin REST) removes the row; SCIM and
`UserLifecycleService.deactivate` keep it, so they already resolve to the full name.

- **Tombstone:** migration `V065__deleted_logins.sql` — `deleted_logins(login_name TEXT PRIMARY KEY,
  deleted_at TIMESTAMPTZ NOT NULL DEFAULT now())`, grants via `:app_user`, DDL only (no backfill).
  `UserDeletionService.delete` records the tombstone before `deleteByLoginName` (via a small repository
  on `Jdbc`; `FaultInjectingDataSource` test that a failed tombstone write aborts the delete — never a
  deleted account with no tombstone).
- **Display:** the account snapshot also loads the tombstone set; a login missing from accounts but in
  the set → new constant `PublicIdentity.FORMER_MEMBER` = "former member". The snapshot is already
  invalidated by `profileDeleted`.
- **Reuse — Call:** a tombstoned login cannot be registered again (self-registration, admin create, SCIM
  create refuse with "this login name belonged to a deleted account"). Otherwise a new user who takes the
  name inherits the old account's edits and comments — an attribution hijack, which is the security half
  of this issue. Releasing a name is a deliberate admin act (delete the row via psql; documented in the
  operations runbook).
- **Legacy deletions** (before V065) stay as written — no tombstone exists and backfilling is a data fix
  (memory: migrations are DDL only). The release note says so.
- `PolicyVerifier`'s self-test `deleteByLoginName` (bypasses `UserDeletionService`) must not tombstone —
  verify, and keep it that way.

---

## 8. Delivery

- **Order — Call:** #106 → #105 → #104 (lock-down, then open-up) → #107 → Phase 2 → Phase 4 → paste images.
  Security first; #105 before #104 because both extract the shared read budget (`CountedText`), and #104's
  reader runs the budgeted parse. #107, #106 and the paste work touch disjoint files and may run in
  parallel with the chain (separate worktrees; never two Maven builds in one tree).
- Each item: failing test first, its own commit series, `CHANGELOG.md` `[Unreleased]` entry (no News.md).
- Gate before declaring done: `bin/run-tests.sh --parallel 4` **and** `npx vitest run --coverage` in
  wikantik-frontend (the gate script skips vitest).
- **Prod content work** (outward-facing, the maintainer asked for it): #104 page fixes (§5.4), the Phase
  2/4 corpus audits (§1, §2), the regenerated config-reference page (§4). Each verified by re-pull.
- **Release + deploy** is a separate maintainer go-ahead; the #104 audit must report zero locked prod
  pages first, and the release note lists every page whose effective access changed.
- Wiki design page: publish this document to the live wiki under the Wikantik development hub
  (memory: designs are also published to the wiki).
