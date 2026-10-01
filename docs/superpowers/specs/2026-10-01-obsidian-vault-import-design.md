# Obsidian Vault Import — Design

**Status:** approved for implementation 2026-10-01 (user directive: "handle it fully, design and
implementation"). Rulings recorded inline as **Ruling:**.
**Depends on:** `2026-10-01-native-wikilinks-design.md` (shipped first) — the wiki stores and resolves
`[[ ]]` natively, so the importer keeps vault links verbatim and only re-maps targets whose page name
had to change.
**Scope:** upload a zipped Obsidian vault, review a dry-run plan, import it as wiki pages + attachments
in a background job, see a per-page result. The inverse of the shipped Obsidian export.
**Explicitly out of scope:** overwriting or merging into existing pages (existing names are skipped),
`.canvas` / Excalidraw, Dataview query execution, block references (`^id` — stripped, reported),
highlights `==x==`, persisted import history, a CLI or MCP tool, multi-node job coordination.

## 1. Problem

An Obsidian user evaluating the wiki has hundreds of notes. Export exists; import does not, so the
first experience is copy-paste one note at a time, losing links, attachments, folders and tags.

## 2. User flow

1. Sidebar "Export…" gains a sibling "Import Obsidian vault…" (and command palette command
   `import-vault`, section Page), shown when the user has `createPages`.
2. `ImportDialog`: choose a `.zip` → **Plan** view: totals (pages new / skipped-existing / failing,
   attachments, clusters + hubs to create/join), a filterable table of pages (vault path → page name,
   status, warnings), grouped warnings, and one option: **Folders become clusters** (default on) vs
   **Put everything in cluster…** (an existing declared cluster, picker) vs **No clusters**.
   Changing the option re-plans.
3. **Import N pages** → progress (pages done / total, current page) → **Result**: created pages
   (links), skipped, failed with reasons, and "Open imported hub" when one was created.
   Closing the dialog does not cancel the job; reopening shows its state (one job per user).

## 3. API (wikantik-rest, `ObsidianImportResource`)

| Request | Behaviour |
|---|---|
| `POST /api/import/obsidian/plan` (multipart `file`, form fields `clusterMode=folders\|fixed\|none`, `cluster`) | parses the zip, returns `ImportPlan` JSON incl. `planHash`. Writes nothing. |
| `POST /api/import/obsidian/apply` (same multipart + `planHash`) | recomputes the plan; 409 if its hash differs (the vault or wiki changed since review); otherwise copies the zip to a temp file, starts the job, **202** `{jobId}`. 409 if the user already has a running job; 429 if `wikantik.import.maxConcurrent` jobs are running. |
| `GET /api/import/obsidian/jobs/{jobId}` | `{state: RUNNING\|DONE\|FAILED, done, total, current, results[], summary}`; only the job's owner (or an admin) may read it. |
| `GET /api/import/obsidian/jobs/current` | the caller's latest job (≤ 1 h old) or 404. |

- Gate: authenticated **and** `WikiPermission.CREATE_PAGES` (as `/api/ingest`); 401/403 otherwise.
  **Ruling:** no new permission — import creates only new pages attributed to the caller, exactly what
  they could do one at a time; caps (below) bound the blast radius.
- Stateless plan → apply with `planHash` (sha256 over zip bytes + options + the set of existing page
  names the plan collided with). **Ruling:** re-uploading on apply costs bandwidth but avoids
  server-side upload storage, expiry and ownership bookkeeping between the two steps.
- Async apply. **Ruling:** prod sits behind Cloudflare (100 s request cap) and every page save runs
  filters, events and reindexing; a 500-page import cannot be synchronous. Jobs live in an in-memory
  `ImportJobRegistry` (single node, results kept 1 h, temp zip deleted when the job ends, on any
  outcome, and on engine shutdown).
- `web.xml`: servlet with its own `<multipart-config>` (max-file/request size 209715200); the
  effective limit is enforced in code. Update the CLAUDE.md `/api/*` servlet count.

### Limits (new config keys, declared in `ini/wikantik.properties` with sections/types/defaults)

| Key | Default |
|---|---|
| `wikantik.import.maxUploadBytes` | 104857600 (100 MB) |
| `wikantik.import.maxUncompressedBytes` | 524288000 (500 MB) |
| `wikantik.import.maxEntries` | 20000 |
| `wikantik.import.maxPages` | 2000 |
| `wikantik.import.maxConcurrent` | 1 |

Exceeding any limit → 413 with a message naming the limit (plan and apply).

## 4. Zip safety (`VaultArchiveReader`, wikantik-main `com.wikantik.importer`)

From scratch — the codebase has no zip reader. Reject the archive (400) on: an entry name that is
absolute, contains `\`, NUL, a `.`/`..` segment, or is not valid UTF-8; cumulative uncompressed bytes
over the cap (counted while streaming, never trusting header sizes); entry count over the cap; any
entry whose compression ratio exceeds 100:1 once over 1 MB. Directories are implicit. Entries are
read with `ZipInputStream` only and never written to disk, so symlink entries are inert ordinary files
(**Ruling**, 2026-10-01: no hand-written central-directory parser). Read into memory per entry only for `.md` (≤ `wikantik.api.maxPageBytes`)
and attachment candidates; skip everything else without buffering.

Ignored paths (silently): `.obsidian/`, `.trash/`, `.git/`, `__MACOSX/`, any dot-file or dot-folder,
`.wikantik/manifest.json` and `Wikantik Export.md` (round-trip hints of our own export). A single
top-level folder that wraps the whole vault (common when zipping a folder) is stripped.

## 5. Mapping (`VaultImportPlanner`, pure: archive entries + existing-page lookup + options → plan)

### 5.1 Notes → page names
- Name = file basename without `.md`, made legal: characters outside `MarkupParser.cleanLink`'s allowed
  set are replaced with a space, whitespace collapsed, trimmed, capped at 128 (`WikiPageNameValidator`).
  If the legal name differs from the basename, frontmatter `title` is set to the original basename
  (unless the note already has `title`).
- Two vault notes with the same name (case-insensitive) in different folders: the first in path order
  keeps the name, later ones get ` (<parent folder name>)`, then ` 2`, ` 3`.
- A name that already exists in the wiki (via `WikiLinkResolver` exact/case-insensitive step) →
  status `SKIPPED_EXISTS`; links to it from other imported notes resolve to the existing page.
- System-page names (the registry `write_pages` consults) → `SKIPPED_RESERVED`.

### 5.2 Frontmatter
- Parsed with the existing YAML frontmatter parser; malformed YAML → the note imports with the YAML
  block kept as body text inside a fenced block and a warning (never a failed page).
- Dropped keys: `canonical_id`, `wikantik_url`, `wikantik_version`, and every schema field marked
  readonly (`confidence`, `agent_hints`) plus `verified_at`/`verified_by`.
- `tags`: string or list, leading `#` stripped, `/` → `-`, lowercased; unioned with inline body tags
  (Obsidian rule: `#` at start or after whitespace, followed by `[\p{L}\p{N}_/-]+` containing a
  non-digit; not in code, links, headings or URLs).
- `aliases`/`alias` (string or list) → `aliases`, plus the original basename when the name changed.
- `type`: kept when one of the schema's types; otherwise dropped with a warning (default applies).
- `cluster` from the vault is replaced by the cluster mode's result. Everything else is preserved.
- The plan runs `SchemaDrivenFrontmatterValidator` on the final metadata: ERRORs → status `WILL_FAIL`
  with the violation; WARNING count shown.

### 5.3 Clusters (mode `folders`, the default)
- Folder path segments are slugified (`lowercase`, runs of non-`[a-z0-9]` → `-`, trimmed) to satisfy
  `CLUSTER_SLUG_PATTERN`; depth > 2 folds into the second segment (`a/b/c/d` → `a/b`). Root-level
  notes get no cluster. A segment that slugifies to empty is skipped.
- Each resulting cluster: if a hub already declares it → pages join it. Otherwise a hub is created:
  the folder note (`Folder/Folder.md`, Obsidian's folder-note convention) becomes `type: hub` with
  `cluster: <slug>`; with no folder note a hub page named `<Folder Name> Hub` (collision rules of 5.1)
  is generated with `type: hub`, `cluster`, `title: <Folder Name>`, summary "Notes imported from the
  Obsidian folder <Folder Name>." and body `# <Folder Name>`. A sub-cluster `a/b` additionally needs
  `a` declared — handled by the same rule. The plan lists each cluster as JOIN or CREATE (with hub).
- Mode `fixed`: every page gets the chosen (already declared) cluster; mode `none`: no clusters.

### 5.4 Body rewriting (`VaultBodyRewriter`, code-fence aware)
- `[[T…]]` / `![[T…]]`: `T` resolved Obsidian-style among vault notes — exact relative path match
  (`folder/Note`), then unique basename (case-insensitive), then shortest path. Resolved to an imported
  page whose name ≠ `T` → target replaced, original text preserved as alias when no alias was given
  (`[[Old name]]` → `[[New name|Old name]]`). Resolved to an existing wiki page → left to the wiki's
  resolver. Unresolved → left as written (renders as a create-page link), warning.
- Heading fragments kept (`#Heading`); block refs `#^id` and trailing `^id` markers stripped, warning.
- Attachment references (`![[f.png]]`, `[[f.pdf]]`, `![alt](path/f.png)`, `[x](f.pdf)`): resolved
  among vault non-`.md` files with the same path rules → rewritten to `![[Owner/f.png]]` /
  `[[Owner/f.pdf]]` (preserving `|size`), where Owner = the first page (path order) referencing the
  file. Markdown-style links to notes (`[x](Folder/Note.md)`, URL-encoded) → `[[Page|x]]`.
- `%% … %%` comments removed (count reported). Callouts, math, tables, code pass through.

### 5.5 Attachments
- Only referenced files are imported, attached to their Owner page after the page is created.
  Unreferenced files are listed as skipped. Blocked extensions (`AttachmentManager`
  `BLOCKED_UPLOAD_EXTENSIONS`, incl. `svg`) and `AttachmentUploadPolicy` violations (checked by the
  importer itself — `storeAttachment` does not) are reported per file and their references left as-is.

## 6. Apply (`VaultImportJob`)

Pages are created in plan order with `PageSaveHelper.saveText(name, body, SaveOptions… author=<user>,
changeNote="Imported from Obsidian vault <zip name>", markupSyntax=markdown, metadata, replaceMetadata=true)`,
reusing `write_pages`' validation and warning-sink handling; hubs first, then notes, then attachments.
Never aborts: each page/attachment gets `CREATED | SKIPPED_EXISTS | FAILED(reason)`; a page that
started existing after planning is skipped, never overwritten. No rollback — pages created stay (a
re-run skips them). The job checks `createPages` once at start and runs under the caller's session
identity for authoring; failures are `LOG.warn`ed with page names.

## 7. Error handling

- Plan/apply: 400 malformed zip or unsafe entry (message names the entry), 413 limit, 415 not
  multipart, 409 hash mismatch / job running, 429 global cap. All via `RestServletBase.sendError`.
- Job thread: a catastrophic exception → state FAILED with message; per-item errors never fail the job.

## 8. Testing

- Pure unit tests (no engine): archive reader (zip-slip variants, bomb ratio, entry/size caps, symlink,
  wrapper-folder strip, ignored paths); planner (names, collisions, reserved, existing, frontmatter
  rules, inline tags, clusters incl. folder notes, depth folding, fixed/none modes, plan hash
  stability); body rewriter (every link/embed/attachment form, code fences untouched, block refs,
  comments, URL-encoded md links).
- Service tests against `TestEngine`: apply creates pages/hubs/attachments, skips a page created
  between plan and apply, blocked attachment reported, frontmatter ERROR page fails alone.
- REST tests: permission gates, 409/413/429, job ownership.
- Frontend: `ImportDialog` plan → apply → progress → result, option re-plan, reopen shows job.
- IT (wikantik-it-test-rest): import a fixture vault (zip built in the test) end-to-end and assert
  pages, a `[[ ]]` backlink and an attachment.
- A fixture vault under `src/test/resources` exercising every rule, reused by planner and IT tests.

## 9. Review focus

1. A vault zipped with Windows tools (backslash entry names, CP437 names) — rejected cleanly or
   decoded, never written outside the vault model.
2. A vault whose notes are named `Main`, `LeftMenu` or other system pages — skipped, reported.
3. Two notes `Ideas.md` and `ideas.md` in the same folder (case-sensitive filesystems allow it).
4. Applying after another user created one of the planned pages — 409 at apply, or skipped mid-job.
5. A 100 MB vault where 95 MB is unreferenced images — imports quickly, reports the skipped files.
