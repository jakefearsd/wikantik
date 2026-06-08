# Structured Frontmatter Editor — Design

**Status:** Approved for planning (2026-06-08)
**Author:** Jake Fear + Claude Code (brainstorming session)

**Goal:** Replace raw‑YAML frontmatter authoring with a structured, constraint‑guided form that lives
beside the Markdown body editor — turning frontmatter curation from a source of silent breakage into a
guided experience for the 99% case, while preserving a raw "break‑glass" escape hatch and keeping the
Markdown body editing untouched.

**Architecture in one breath:** A single server‑authoritative *frontmatter schema* becomes the one
source of field rules. A schema‑driven validator enforces those rules on every save path (REST, MCP,
provider) and returns *field‑addressable* violations. The React editor fetches the schema, renders
per‑field widgets from it, always attempts the save, and surfaces the server's violations inline. The
body editor (CodeMirror) becomes body‑only; the structured frontmatter object is the canonical
in‑editor representation, with a Raw‑YAML toggle and an Advanced key/value area so nothing is ever lost.

**Tech stack:** Java 21 / `wikantik-api` + `wikantik-main` + `wikantik-rest` + `wikantik-admin-mcp`;
React + Vite (`wikantik-frontend`, CodeMirror 6); SnakeYAML (existing `FrontmatterParser`/`FrontmatterWriter`).

---

## 1. Context — what exists today

- **Editor:** `wikantik-frontend/src/components/PageEditor.jsx` (CodeMirror 6 via `CodeEditor.jsx`).
  Frontmatter is edited as raw YAML text inline at the top of the body. On load the editor calls
  `reconstructContent(metadata, body)` to splice the metadata object back into a `---` block; on save it
  sends the whole spliced string as `content`.
- **API already separates metadata from body:** `GET /api/pages/{name}` returns
  `{ metadata: {...}, content: <body‑only> }`. `PUT /api/pages/{name}` accepts `{ content, metadata, … }`
  (the `metadata` field is currently unused by the editor). `PATCH /api/pages/{name}` accepts
  `{ metadata, action }`.
- **No central machine‑readable schema.** Field rules are scattered across `preSave` `PageFilter`s:
  - `FrontmatterValidationPageFilter` — strict YAML parse; throws an opaque `FilterException` *string*.
  - `StructuralSpinePageFilter` — auto‑mints a ULID `canonical_id` (reuses the slug‑bound id).
  - `RunbookValidationPageFilter` / `FrontmatterRunbookValidator` — the nested `runbook:` block shape.
  - Conventions (type/status/cluster/SEO) live only in the `wiki-content` skill doc.
- **No reusable form primitives** in `src/components/ui/` (Badge, Card, Chip, Modal, Spinner, Toast,
  etc. exist — but no Select, Combobox, TagInput, or Tabs).
- **Corpus reality (≈1.8K pages):** `type` and `status` are *not closed sets* in practice. Canonical
  `type` values dominate (`article` 1546, `hub` 179, `reference` 22, `runbook` 18, `design` 6) but a long
  tail exists (`report`, `intelligence`, `blueprint`, `explainer`, `concept`, …). `status` is similar
  (`active` 1068, plus `published`, `official`, `deployed`, `ongoing`, …). A hard‑closed enum would break
  saves on ~150+ legacy pages.

## 2. Decisions (locked during brainstorming)

1. **Schema authority:** server‑authoritative. One schema is the single source of field rules.
2. **Schema role:** the schema *drives* validation (focused refactor). A schema‑driven validator
   enforces the simple field constraints on every save path. Procedural logic stays dedicated:
   `StructuralSpinePageFilter` (ULID mint) and the nested `runbook:` validator (whose issues are funneled
   through the same violation channel).
3. **Editing model:** frontmatter leaves CodeMirror. Body editor becomes body‑only; the parsed metadata
   *object* is canonical in the editor; a Raw‑YAML toggle is the break‑glass; unknown/odd keys are
   preserved in an Advanced area.
4. **Field scope:** full structured coverage — all *authored* fields get widgets, including the nested
   `runbook:` editor. (`verification` is **not** nested — see §3.)
5. **Validation UX:** server‑only enforcement; the client never blocks locally. The form renders
   *advisory* hints from the schema descriptor, always attempts the save, and maps the server's returned
   violations back to the offending widgets. Because only the server enforces, client and server cannot
   disagree.
6. **Curated‑open enums with stern, actionable, transitional warnings** (§4.2).

## 3. Field inventory (authored vs. derived)

Authored fields get widgets. Derived fields render read‑only. Anything not in this table round‑trips
verbatim through the Advanced key/value area.

| key | widget | constraints | default severity of violation | notes |
|-----|--------|-------------|-------------------------------|-------|
| `canonical_id` | readonly | ULID, system‑minted | — | owned by `StructuralSpinePageFilter`; never user‑edited |
| `title` | text | optional; long‑title soft cap (~120) | warning if very long | derived from page name if absent |
| `type` | enum‑open | canonical `{article, hub, reference, runbook, design}`; **open** | non‑canonical → **warning** (§4.2) | governance lever |
| `status` | enum‑open | canonical `{draft, active, archived}`; **open** | non‑canonical → **warning** (§4.2) | governance lever |
| `summary` | text + counter | 50–160 chars (SEO); should be unique | <50 / >160 / empty → warning | live counter is cosmetic |
| `tags` | tags | list<string>; lowercase‑kebab suggested | non‑kebab item → warning | autocomplete from existing tags |
| `cluster` | combobox‑open | slug `^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*)?$`; `/` = sub‑cluster | malformed slug → **error** | suggestions from existing clusters |
| `related` | page‑refs | list<page‑name>; targets should resolve | unresolved target → warning | search‑backed multiselect (`/api/search`) |
| `date` | date | ISO `YYYY-MM-DD` | malformed → **error** | |
| `author` | text/combobox | freeform | — | suggests known authors |
| `kg_include` | tri‑state | `true` / `false` / unset | — | unset = inherit cluster KG policy |
| `verified_at` | datetime | ISO‑8601 instant | malformed → **error** | feeds `ConfidenceComputer` |
| `verified_by` | author‑combobox | `login_name` | unknown/untrusted author → warning | affects derived confidence |
| `audience` | enum (closed) | `{humans, agents, both}` (via `Audience.fromFrontmatter`) | non‑member → **error** | authored; tolerant parse, canonical write |
| `confidence` | readonly | derived (`ConfidenceComputer`) | — | display only |
| `agent_hints` | readonly | derived | — | display only |
| `runbook` | runbook‑block | nested (see §3.1); required iff `type: runbook` | per `IssueKind`; missing block when `type:runbook` → **error** | rendered only when `type` is `runbook` |
| *(unknown keys)* | keyvalue (Advanced) | preserved verbatim | — | passthrough; never dropped |

**Severity rule of thumb:** structurally malformed values (slug regex, date, audience membership,
unparseable YAML) are **errors** (block the write); soft / style / governance issues (non‑canonical
enum, summary length, unresolved `related`, untrusted `verified_by`, non‑kebab tags) are **warnings**
(write proceeds, advisory returned).

### 3.1 `runbook:` nested block (unchanged from `FrontmatterRunbookValidator`)

A map with list‑valued fields, validated when `type: runbook`:
- `when_to_use` — list<string>, **≥1** entry.
- `inputs` — list<string>, optional.
- `steps` — list<string>, **≥2** entries.
- `pitfalls` — list<string>, **≥1** (use `"(none known)"` if truly none).
- `related_tools` — list<string>, each matches `^/(api|knowledge-mcp|wikantik-admin-mcp|tools)/.+$`
  **or** a bare snake_case tool name `^[a-z][a-z0-9_]*$`.
- `references` — list<string>, each must resolve to a `canonical_id` **or** a page title.

The `RunbookBlockEditor` widget renders one list editor per field with these constraints as inline
hints. The existing `FrontmatterRunbookValidator` remains the authority; its `Issue`s are adapted to
`FieldViolation`s with `field` paths like `runbook.steps`, `runbook.pitfalls`, `runbook.references`.

## 4. Server design

### 4.1 The schema model (`wikantik-api`, package `com.wikantik.api.frontmatter.schema`)

- `Severity` — enum `{ ERROR, WARNING }`.
- `FieldViolation` — record `(String field, Severity severity, String code, String message, String suggestion)`.
  `field` is dotted for nesting (`runbook.steps`); `suggestion` is nullable (a proposed canonical
  replacement when one exists).
- `FieldSpec` — record describing one field: `key`, `label`, `widget` (enum:
  `TEXT, TEXTAREA, ENUM, TAGS, PAGE_REFS, DATE, DATETIME, TRISTATE, RUNBOOK_BLOCK, READONLY`),
  `canonicalValues` (list, for enums), `open` (boolean — open vs. closed enum), `minLen`/`maxLen`,
  `pattern` (regex), item‑level constraints for lists, and a `suggestionMap` (legacy→canonical) used to
  populate `FieldViolation.suggestion`.
- `FrontmatterSchema` — ordered `List<FieldSpec>` + factory `FrontmatterSchema.defaultSchema()` that
  encodes the §3 table in code (no admin UI in v1). One place to change as the ontology evolves.

### 4.2 Curated‑open enums (the governance lever)

`type` and `status` are **open** enums: the schema lists the canonical values (offered first in the UI),
but a non‑canonical value does **not** block. Instead it yields a **stern, actionable warning** that:
- names the value as non‑canonical and lists the canonical set, and
- carries a `suggestion` (canonical replacement) drawn from the field's `suggestionMap` when a mapping
  exists — so the **form offers one‑click "change to `active`"** and **agents reading the MCP text can
  self‑correct**.

Example message:
> `status: "published"` is not a canonical status. Canonical values are: draft, active, archived.
> This value is tolerated for now but will be rejected once the corpus is normalized. Suggested
> replacement: `active`.

Initial `suggestionMap`s (curator‑tunable, defined in `defaultSchema()`):
- `status`: `published→active, official→active, deployed→active, production→active, ongoing→active,
  designed→draft, proposed→draft, in_progress→draft`.
- `type`: `report→article, intelligence→article, explainer→article, blueprint→design,
  implementation→design, concept→reference`.
Values with no mapping still warn (sternly, with the canonical list) but carry no `suggestion`.

**Transitional escalation.** A property `wikantik.frontmatter.enum.nonCanonical.severity`
(default `warning`) flips non‑canonical enum violations to `ERROR` once the corpus is normalized — at
which point editing a drifted page is blocked until corrected. The canonical lists themselves live only
in `defaultSchema()`, so a curator promotes a new value to canonical by editing that one source.

### 4.3 The validator (`wikantik-main`, `com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator`)

Pure function: `validate(Map<String,Object> metadata, ValidationCtx ctx) → List<FieldViolation>`, where
`ValidationCtx` supplies the predicates the rules need without dragging in heavy services
(`Predicate<String> pageResolves`, `Predicate<String> clusterExists`, `Predicate<String> isTrustedAuthor`,
the non‑canonical severity setting). For the `runbook:` field it **delegates** to the existing
`FrontmatterRunbookValidator` and adapts its `Issue`s into `FieldViolation`s. Strict‑YAML parse failures
(today's `FrontmatterValidationPageFilter`) are mapped to a single `FieldViolation` on a synthetic
`__yaml__` field carrying line/column.

### 4.4 Save pipeline wiring

A new `SchemaValidationPageFilter` (preSave) runs the validator after `StructuralSpinePageFilter` (so
`canonical_id` is present). It *adds* schema‑driven field validation, which the codebase has nowhere
today. The strict‑YAML‑parse responsibility currently in `FrontmatterValidationPageFilter` is folded
into the validator (parse‑then‑validate, surfacing a `__yaml__` violation with line/column), so that
filter is retired in favor of the new one. Behavior:
- Any `ERROR`‑severity violation → throw a structured `FrontmatterValidationException`
  (carrying `List<FieldViolation>`); the page is **not** written.
- `WARNING`‑severity violations → attach to the `Context` (a request‑scoped sink) so the REST layer can
  return them on the successful response; the page **is** written.

### 4.5 Endpoints & error contract (`wikantik-rest`)

- **`GET /api/frontmatter-schema`** → the descriptor as JSON (field order, widgets, canonical values,
  `open` flags, constraints, labels). Cacheable; same read visibility as page reads. Drives the form.
- **`POST /api/frontmatter/validate`** → body `{ "frontmatter": "<raw YAML>" }` or `{ "metadata": {…} }`;
  parses with SnakeYAML (the *one* YAML implementation) + runs the validator; returns
  `{ "metadata": {…}, "violations": [ … ] }` **without saving**. Powers Raw→Form sync (authoritative
  parse, no divergent client YAML parser) and optional pre‑save warning preview.
- **`PUT /api/pages/{name}`** (modified):
  - error violations → **HTTP 422** `{ "error": "frontmatter_validation_failed", "violations": [ … ] }`;
    page not written.
  - success with warnings → **HTTP 200** `{ "ok": true, "version": N, "warnings": [ … ] }`; page written.
  - A JAX‑RS `ExceptionMapper` for `FrontmatterValidationException` produces the 422 body.

`FieldViolation` JSON shape (errors and warnings share it):
```json
{ "field": "cluster", "severity": "error", "code": "cluster.slug.malformed",
  "message": "cluster 'Interval Trees' is not a valid slug — use lowercase kebab-case, e.g. 'interval-trees'",
  "suggestion": "interval-trees" }
```

### 4.6 MCP parity (`wikantik-admin-mcp`)

The write tools (`write_page`/`update_page`/batch variants) already pre‑normalize via
`FrontmatterNormalizer`. They additionally run `SchemaDrivenFrontmatterValidator`:
- error violations → the tool **refuses**, citing the violations as text (same `message` + `suggestion`
  strings the form shows) so an agent can fix and retry.
- warning violations → included in the tool result text (stern hint + suggestion) but the write proceeds.

This is what makes the ontology evolvable under *shared* human/AI control: both faces read the same
schema and receive the same actionable, suggestion‑bearing messages.

## 5. Frontend design (`wikantik-frontend`)

### 5.1 New shared UI primitives (`src/components/ui/`)

`Select.jsx`, `Combobox.jsx` (search‑backed, async option source), `TagInput.jsx` (chips reusing the
existing `Chip` + autocomplete), `Tabs.jsx`. Each with matching CSS and vitest tests. These are net‑new
reusable primitives (extending the existing `ui/` layer, per the project's "reuse `ui/` before
re‑rolling" rule).

### 5.2 The frontmatter surface (`src/components/frontmatter/`)

- `schemaClient.js` — fetches and caches `GET /api/frontmatter-schema`.
- `FrontmatterEditor.jsx` — the surface. Owns the metadata **object** state. Renders **Form ⇄ Raw YAML**
  tabs and an **Advanced** disclosure. Maps each schema `FieldSpec` to a field component; renders
  read‑only chips for `canonical_id` / `confidence` / `agent_hints`; routes unknown keys to the Advanced
  `KeyValueEditor`. Accepts a `violations` prop (from the last save / dry‑run) and shows them inline on
  the matching widget (error styling vs. advisory), with a one‑click "apply suggestion" affordance when
  `suggestion` is present.
- `fields/` — `EnumField`, `TagsField`, `PageRefsField`, `SummaryField` (counter), `DateField`,
  `AudienceField`, `KgIncludeField`, `RunbookBlockEditor`, `ReadOnlyField`, `KeyValueEditor`.

### 5.3 Editing model & round‑trip

- **Load:** `GET /api/pages/{name}` → metadata object into `FrontmatterEditor`, body into CodeMirror.
  `reconstructContent` is no longer used in the editor path; the body editor never sees the `---` block.
- **Save (Form mode):** send `{ content: <body>, metadata: <object> }`. The **server composes** the
  frontmatter (existing `FrontmatterWriter`) and runs the full preSave pipeline — so there is no
  client‑side YAML serializer to drift, and every existing filter still applies.
- **Break‑glass (Raw mode):** a YAML textarea (CodeMirror `lang-yaml` for highlighting). Switching
  Form→Raw serializes the current object to YAML for display (client `metadataToYaml`, display‑only).
  Switching Raw→Form calls `POST /api/frontmatter/validate` to parse authoritatively; on success the
  returned `metadata` repopulates the form, on parse error the Raw view stays authoritative and shows
  line/column. Saving in Raw mode sends the composed text in `content` (no `metadata`), letting the
  server parse and validate as the single authority.
- **Fidelity:** the body is always untouched. In Form mode, YAML comments / key order are not preserved
  (acceptable — that is precisely what break‑glass covers). Unknown keys are always preserved via the
  Advanced area.

### 5.4 Validation UX (server‑only enforcement)

The form always attempts the save. On **422** it maps `violations` to widgets inline (must‑fix) and
shows a summary; on **200 with warnings** it surfaces them inline as advisory (already saved). Live,
purely cosmetic hints (e.g. the summary counter color, enum "non‑canonical" badge) are derived from the
descriptor for instant feedback but carry no authority — the authoritative warnings always come from the
server response. Optionally the editor may call `POST /api/frontmatter/validate` on idle to preview
warnings before saving (nice‑to‑have, not required for v1).

### 5.5 New‑page flow

`NewArticleModal.jsx` slims to "page name + starting type". On create it routes into the editor with an
empty body and a **seeded metadata object** (type, status=`active`, date=today); the structured
`FrontmatterEditor` takes over. The modal's current ad‑hoc raw‑YAML string building is retired.

## 6. Data flow

```
LOAD:   GET /api/pages/{name}  ->  { metadata, content(body) }
            metadata --> FrontmatterEditor (object, canonical)
            content  --> CodeMirror (body only)

SAVE (form):  PUT /api/pages/{name}  { content: body, metadata: object }
            server: FrontmatterWriter(metadata)+body
                    -> StructuralSpinePageFilter (mint canonical_id)
                    -> SchemaValidationPageFilter (SchemaDrivenFrontmatterValidator)
                         errors  -> 422 { violations }      (not written)
                         warnings-> attach to context        (written)
                    -> 200 { ok, version, warnings }
            form: map violations/warnings -> widgets (inline, with suggestions)

SAVE (raw):   PUT { content: <full text incl ---> }   (server parses + validates)

SYNC raw->form / preview:  POST /api/frontmatter/validate { frontmatter | metadata }
                           -> { metadata, violations }   (no write)
```

## 7. Error handling

- Malformed YAML in Raw mode → server returns a `__yaml__` violation with line/column; the form keeps
  Raw authoritative and pins the message.
- Network / 5xx on save → existing toast error path; the editor state is retained (no data loss).
- A field the schema doesn't know → never an error; it lands in Advanced and is written verbatim.
- The validator and every filter log at least `WARN` with context on any swallowed/degraded path (per
  project rule — no empty catches).

## 8. Testing strategy

**Java (unit):**
- `FrontmatterSchema.defaultSchema()` shape; `SchemaDrivenFrontmatterValidator` per rule class — open vs.
  closed enums, summary length, slug regex, date/datetime, audience membership, tags kebab, `related`
  resolution, `verified_by` trust; severity assignment; `suggestion` population from `suggestionMap`;
  non‑canonical severity escalation via the property.
- Runbook delegation: `FrontmatterRunbookValidator` `Issue`s adapt to `FieldViolation`s with correct
  `field` paths.
- **Drift guard:** schema `type` canonical set ⊇ `NodeTypeMapping` page‑type keys; keeps the form's
  vocabulary tied to the ontology mapping (mirrors `EntityTypeVocabularyDriftTest`).

**Java (REST IT):** the 422 contract (error violations, page not written), the 200‑with‑warnings
contract (page written), `GET /api/frontmatter-schema`, `POST /api/frontmatter/validate`. **MCP IT:**
write tool refuses on error violations citing them, and includes warnings on success (per the
"MCP write‑surface needs unit + wire‑level IT" project rule).

**Frontend (vitest):** each new `ui/` primitive; each field widget (renders from descriptor, emits
object changes, shows inline violation + applies suggestion); round‑trip fidelity (load→edit→save
preserves unknown keys); Form⇄Raw sync incl. invalid‑YAML; 422→inline mapping. *(Mind the known vitest
concurrency flakiness — re‑run a file in isolation before chasing a failure.)*

**IT (Selenide):** open a seeded page, edit a field via the form, save, verify persisted; break‑glass
round‑trip; non‑canonical enum shows the stern warning + one‑click suggestion. *(Use startup fixtures,
not freshly‑seeded pages, per the structural‑index seed‑lag note.)*

## 9. Scope boundaries (YAGNI) & fast‑follows

**In v1:** the schema + validator + 3 endpoints + the structured surface with all authored‑field widgets
+ Advanced passthrough + Raw break‑glass + MCP parity + curated‑open enums with stern suggestions +
escalation property.

**Explicitly out (fast‑follows):**
- **Curator schema‑admin UI** — v1 edits the schema in `defaultSchema()` code/config. A UI to manage
  canonical sets / suggestion maps is a later phase.
- **Bulk re‑typing / corpus normalization sweep** — the suggestions make per‑page fixes easy; a batch
  "normalize all non‑canonical" job is separate.
- **YAML comment / key‑order preservation in Form mode** — break‑glass covers it.
- **Live pre‑save warning preview** — optional polish on top of `POST /api/frontmatter/validate`.

## 10. Open items resolved during brainstorming

- `verification` is **two flat authored fields** (`verified_at`, `verified_by`) plus the authored enum
  `audience` — **not** a nested block. `confidence` is derived (read‑only).
- Enums are **curated‑open**: non‑canonical values warn (sternly, with a suggested canonical replacement)
  rather than block, to avoid breaking ~150 legacy pages; escalatable to error via
  `wikantik.frontmatter.enum.nonCanonical.severity` once normalized.
