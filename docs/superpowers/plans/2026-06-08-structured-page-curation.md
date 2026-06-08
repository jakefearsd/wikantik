# Structured Page Curation Editor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, this session — chosen by the user) to implement task-by-task. Steps use checkbox (`- [ ]`) syntax. TDD: write the failing test, watch it fail, minimal impl, watch it pass, commit. Spec: `docs/superpowers/specs/2026-06-08-structured-page-curation-design.md`.

**Goal:** A structured, server-schema-driven frontmatter form plus a page-scoped Knowledge-Graph curation panel, both in the page editor, replacing raw-YAML authoring and giving humans + agents one rule set (validation + SHACL) for curating page concepts and KG instances.

**Architecture:** One server-authoritative `FrontmatterSchema` drives a `SchemaDrivenFrontmatterValidator` enforced on every save path; REST returns field-addressable `FieldViolation`s (422 errors / 200 warnings). A new page-scoped KG read (`getPageSlice`) + page-edit-gated curation endpoints reuse the existing `KgCurationOps` facade (SHACL gate + provenance + audit). The React editor's structured region carries `Frontmatter | Knowledge` tabs above a body-only CodeMirror.

**Tech Stack:** Java 21 (`wikantik-api`/`-main`/`-rest`/`-admin-mcp`), JUnit 5 + Mockito; React + Vite + CodeMirror 6 (`wikantik-frontend`), vitest; SnakeYAML; Apache Jena (existing).

**Code-density note (inline execution):** Code blocks below show the load-bearing decisions — type signatures, validator rules, SQL, the 422 contract, filter wiring. Mechanical React widget/CSS bodies are specified by contract (props, behavior, test names); they are written during execution following the existing `ui/` patterns. This adapts writing-plans for same-session inline execution per the project's token-efficiency rules.

**Build/verify commands (per CLAUDE.md):**
- Single module compile: `mvn compile -pl <module> -q`
- Single test class: `mvn test -pl <module> -Dtest=ClassName`
- After signature changes: `mvn test-compile -pl <module>` (compile skips test sources)
- Frontend: `cd wikantik-frontend && npm run test -- <file>` (run a flaky file alone before chasing)
- Final gate: unit reactor `mvn clean install -T 1C -DskipITs` then IT reactor `mvn clean install -Pintegration-tests -fae` (NO `-T` on ITs)

---

# Phase 1 — Server: schema model + validator + save wiring

### Task 1: Frontmatter schema model (`wikantik-api`)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/Severity.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/FieldViolation.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/FieldSpec.java`
- Create: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/FrontmatterSchema.java`
- Test: `wikantik-api/src/test/java/com/wikantik/api/frontmatter/schema/FrontmatterSchemaTest.java`

Types (records; all fields final, null-safe canonical accessors):

```java
public enum Severity { ERROR, WARNING }

public record FieldViolation(String field, Severity severity, String code,
                             String message, String suggestion) {}   // suggestion nullable

public enum Widget { TEXT, TEXTAREA, ENUM, TAGS, PAGE_REFS, DATE, DATETIME,
                     TRISTATE, RUNBOOK_BLOCK, READONLY }

public record FieldSpec(String key, String label, Widget widget,
                        List<String> canonicalValues, boolean open,
                        Integer minLen, Integer maxLen, String pattern,
                        Map<String,String> suggestionMap) {
    public FieldSpec {                       // null-safe collections
        canonicalValues = canonicalValues == null ? List.of() : List.copyOf(canonicalValues);
        suggestionMap   = suggestionMap   == null ? Map.of()  : Map.copyOf(suggestionMap);
    }
}

public final class FrontmatterSchema {
    private final List<FieldSpec> fields;            // ordered
    public List<FieldSpec> fields() { return fields; }
    public Optional<FieldSpec> field(String key) { ... }
    public static FrontmatterSchema defaultSchema() { ... }   // encodes the §3 table
}
```

`defaultSchema()` encodes the §3 field inventory: `type` (ENUM open, canonical article/hub/reference/runbook/design, suggestionMap report→article etc.), `status` (ENUM open, draft/active/archived, suggestionMap published→active etc.), `summary` (TEXT min 50 max 160), `tags` (TAGS), `cluster` (COMBOBOX→ENUM open=false? no: TEXT with pattern slug), `related` (PAGE_REFS), `date` (DATE), `author` (TEXT), `kg_include` (TRISTATE), `verified_at` (DATETIME), `verified_by` (TEXT), `audience` (ENUM closed humans/agents/both), `confidence`/`agent_hints`/`canonical_id` (READONLY), `runbook` (RUNBOOK_BLOCK), `title` (TEXT). Use `cluster` widget = TEXT with `pattern` = the slug regex (the frontend renders it as a combobox; the schema only carries the regex constraint).

- [ ] **Step 1 (RED):** `FrontmatterSchemaTest` — assert `defaultSchema().field("type")` is present, `widget()==ENUM`, `open()==true`, `canonicalValues()` contains `article`+`hub`, `suggestionMap().get("report")=="article"`; `field("audience").open()==false`; `field("summary").minLen()==50 && maxLen()==160`; `field("cluster").pattern()` non-null; `field("canonical_id").widget()==READONLY`; field order has `canonical_id` first. Run: `mvn test -pl wikantik-api -Dtest=FrontmatterSchemaTest` → FAIL (classes absent).
- [ ] **Step 2 (GREEN):** create the four types + `defaultSchema()`. `mvn test -pl wikantik-api -Dtest=FrontmatterSchemaTest` → PASS.
- [ ] **Step 3:** `git add` the 5 files; commit `feat(frontmatter): schema model + defaultSchema (FieldSpec/FieldViolation/Severity)`.

### Task 2: `SchemaDrivenFrontmatterValidator` (`wikantik-main`)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/frontmatter/schema/ValidationCtx.java`
- Create: `wikantik-main/src/main/java/com/wikantik/frontmatter/schema/SchemaDrivenFrontmatterValidator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/frontmatter/schema/SchemaDrivenFrontmatterValidatorTest.java`

```java
public record ValidationCtx(Predicate<String> pageResolves,
                            Predicate<String> isTrustedAuthor,
                            Severity nonCanonicalEnumSeverity) {
    public static ValidationCtx lenient() {            // for unit tests / no-service contexts
        return new ValidationCtx(p -> true, a -> true, Severity.WARNING);
    }
}

public final class SchemaDrivenFrontmatterValidator {
    private final FrontmatterSchema schema;
    public SchemaDrivenFrontmatterValidator(FrontmatterSchema schema) { this.schema = schema; }
    public List<FieldViolation> validate(Map<String,Object> metadata, ValidationCtx ctx) { ... }
}
```

Rules (one private method per widget kind; each emits `FieldViolation`s):
- ENUM closed (`audience`): value not in `canonicalValues` → ERROR `code=<key>.enum.invalid` listing allowed values.
- ENUM open (`type`,`status`): value not in canonical → severity = `ctx.nonCanonicalEnumSeverity`; message lists canonical set + "tolerated for now…"; `suggestion` = `suggestionMap.get(value)` (nullable). `code=<key>.noncanonical`.
- TEXT minLen/maxLen (`summary`): outside range or empty → WARNING `summary.length`.
- TEXT pattern (`cluster`): non-match → ERROR `cluster.slug.malformed`, suggestion = a kebab-ified guess.
- DATE/DATETIME: unparseable → ERROR `<key>.date.malformed` (use `LocalDate.parse` / `Instant.parse`).
- TAGS: non-kebab item → WARNING `tags.kebab`.
- PAGE_REFS (`related`): item where `!ctx.pageResolves.test(item)` → WARNING `related.unresolved`.
- `verified_by`: `!ctx.isTrustedAuthor.test(value)` → WARNING `verified_by.untrusted`.
- RUNBOOK_BLOCK: delegate to `FrontmatterRunbookValidator.validate(metadata, ...)`; map each `Issue` → `FieldViolation(field="runbook."+kindToField(kind), ERROR, "runbook."+kind, issue.detail(), null)`.
- Unknown keys / READONLY fields: no violations.

- [ ] **Step 1 (RED):** test cases (names): `closedEnumRejectsUnknownAudience`, `openEnumWarnsAndSuggestsForNonCanonicalStatus` (asserts severity=WARNING, suggestion="active"), `openEnumEscalatesToErrorWhenCtxSaysSo`, `summaryTooShortWarns`, `malformedClusterSlugErrors`, `badDateErrors`, `unresolvedRelatedWarns` (ctx pageResolves=false), `runbookMissingStepsMapsToRunbookStepsViolation`, `cleanMetadataYieldsNoViolations`. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement validator. `mvn test-compile -pl wikantik-main` then `mvn test -pl wikantik-main -Dtest=SchemaDrivenFrontmatterValidatorTest` → PASS.
- [ ] **Step 3:** commit `feat(frontmatter): schema-driven validator with field-addressable violations`.

### Task 3: `FrontmatterValidationException` + `SchemaValidationPageFilter` + wiring

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/exceptions/FrontmatterValidationException.java` (extends `FilterException`; carries `List<FieldViolation> violations()` — errors only)
- Create: `wikantik-main/src/main/java/com/wikantik/frontmatter/schema/SchemaValidationPageFilter.java` (implements `PageFilter`)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` (register near `:1599`; remove the `FrontmatterValidationPageFilter` registration — find it first)
- Test: `wikantik-main/src/test/java/com/wikantik/frontmatter/schema/SchemaValidationPageFilterTest.java`

`FrontmatterValidationException extends FilterException` so it flows through the existing preSave pipeline and `PageResource` can catch it specifically. The filter:

```java
public String preSave(Context ctx, String content) throws FilterException {
    if (content == null || !startsWithFrontmatter(content)) return content;
    ParsedPage parsed;
    try { parsed = FrontmatterParser.parseStrict(content); }
    catch (FrontmatterParseException e) {
        throw new FrontmatterValidationException(List.of(new FieldViolation(
            "__yaml__", ERROR, "yaml.parse", e.getMessage()+lineCol(e), null)));
    }
    List<FieldViolation> v = validator.validate(parsed.metadata(), buildCtx(ctx));
    List<FieldViolation> errors = v.stream().filter(x -> x.severity()==ERROR).toList();
    if (!errors.isEmpty()) throw new FrontmatterValidationException(errors);
    // warnings: stash on a request-scoped sink for the REST layer
    FrontmatterWarningSink.put(ctx, v.stream().filter(x -> x.severity()==WARNING).toList());
    return content;
}
```

Add a tiny `FrontmatterWarningSink` (a `ThreadLocal<List<FieldViolation>>` keyed per request, set/drained by `PageResource`) OR stash on `Context` if it has an attribute map — check `Context` for an attributes/property bag during execution; prefer that over ThreadLocal. (Decision recorded at execution; default to ThreadLocal with explicit clear in a `finally`.)

Wiring: `SchemaValidationPageFilter` must run **after** `StructuralSpinePageFilter` (canonical_id present). Register it in `WikiEngine` with a priority later than the spine filter. Locate the existing `FrontmatterValidationPageFilter` registration (grep `FrontmatterValidationPageFilter` across `wikantik-main` main sources) and replace it (the new filter subsumes the strict-YAML parse).

- [ ] **Step 1 (RED):** `SchemaValidationPageFilterTest` — `malformedYamlThrowsWithYamlViolation`; `badAudienceThrowsValidationException` (assert `violations()` has `audience` ERROR); `nonCanonicalStatusDoesNotThrow_warningStashed`; `cleanPagePassesThrough`. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement exception + filter + sink. `mvn test -pl wikantik-main -Dtest=SchemaValidationPageFilterTest` → PASS.
- [ ] **Step 3:** wire into `WikiEngine`, remove old filter registration. `mvn compile -pl wikantik-main -q`.
- [ ] **Step 4:** commit `feat(frontmatter): SchemaValidationPageFilter replaces strict-YAML filter; structured exception`.

---

# Phase 2 — REST endpoints + MCP parity (Part A)

### Task 4: `GET /api/frontmatter-schema`

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/FrontmatterSchemaResource.java`
- Modify: REST application/registration class (find where resources are registered — grep `register(` / `@ApplicationPath` / a `RestApplication` class) to register the new resource.
- Test: `wikantik-rest` IT (deferred to Task 14); a unit test asserting the JSON shape is built from `FrontmatterSchema.defaultSchema()`.

Resource: `@GET @Path("/frontmatter-schema") @Produces(APPLICATION_JSON)` → serialize `FrontmatterSchema.defaultSchema()` to JSON: `{ fields: [ { key, label, widget, canonicalValues, open, minLen, maxLen, pattern, suggestionMap } ] }`. No auth beyond normal read. Cache-Control header (e.g. `max-age=300`).

- [ ] **Step 1 (RED):** unit test `FrontmatterSchemaResourceTest.returnsFieldsFromDefaultSchema` (call the method, parse JSON, assert `type` field present with `open=true`). Run → FAIL.
- [ ] **Step 2 (GREEN):** implement + register. `mvn test -pl wikantik-rest -Dtest=FrontmatterSchemaResourceTest` → PASS.
- [ ] **Step 3:** commit `feat(rest): GET /api/frontmatter-schema`.

### Task 5: `POST /api/frontmatter/validate`

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/FrontmatterValidateResource.java`
- Test: unit `FrontmatterValidateResourceTest`.

`@POST @Path("/frontmatter/validate")`: body `{ frontmatter?: string, metadata?: object }`. If `frontmatter` present → `FrontmatterParser.parse` (lenient, catch parse error → return `{metadata:null, violations:[__yaml__ error]}`). Run `SchemaDrivenFrontmatterValidator.validate` with a `ValidationCtx` whose predicates are backed by the engine (page resolves via `PageManager`, trusted author via the trusted-authors registry, nonCanonical severity from the property). Return `{ metadata, violations }`. No write. Auth: normal read.

- [ ] **Step 1 (RED):** `validateParsesYamlAndReturnsViolations` (post malformed YAML → __yaml__ violation; post `metadata` with bad audience → audience violation; post non-canonical status → warning+suggestion). Run → FAIL.
- [ ] **Step 2 (GREEN):** implement. Test → PASS.
- [ ] **Step 3:** commit `feat(rest): POST /api/frontmatter/validate (dry-run parse+validate)`.

### Task 6: `PageResource` PUT → 422/200 contract + warnings

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java` (the PUT/save handler)
- Test: unit `PageResourceSaveContractTest` (mock `PageManager` to throw `FrontmatterValidationException`; assert 422 body) + IT in Task 14.

In the save handler: accept `{ content, metadata?, changeNote, expectedVersion, markupSyntax }`. **If `metadata` present**, compose full text via `FrontmatterWriter.write(metadata, content)` (server-canonical); else treat `content` as full text (raw-mode/legacy). Call the existing save path. Wrap in:

```java
try {
    ... pageManager.saveText(...) ...
    List<FieldViolation> warnings = FrontmatterWarningSink.drain(...);
    return Response.ok(Map.of("ok", true, "version", v, "warnings", warnings)).build();
} catch (FrontmatterValidationException e) {
    return Response.status(422).entity(Map.of(
        "error", "frontmatter_validation_failed",
        "violations", e.violations())).build();
}
```

(Keep existing conflict/permission handling intact; only add the 422 branch + warnings on success + the metadata-compose branch.)

- [ ] **Step 1 (RED):** `saveWithErrorViolationReturns422`; `saveWithWarningsReturns200WithWarnings`; `saveWithMetadataComposesFrontmatter`. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement. Test → PASS.
- [ ] **Step 3:** commit `feat(rest): PUT /api/pages 422 errors / 200 warnings + metadata compose`.

### Task 7: MCP parity (`wikantik-admin-mcp`)

**Files:**
- Modify: the write tools that pre-normalize via `FrontmatterNormalizer` (grep for `FrontmatterNormalizer` usages in `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/` — `write_page`/`update_page`/batch).
- Test: `wikantik-admin-mcp` unit test `McpFrontmatterValidationTest` + wire-level IT in Task 15.

After normalization, run `SchemaDrivenFrontmatterValidator.validate`. Errors → tool refuses, result text cites each `message` (+`suggestion`). Warnings → write proceeds, warnings appended to result text. (Per project rule: MCP write-surface changes ship with a Mockito unit + a Cargo IT; refusal must cite the reason.)

- [ ] **Step 1 (RED):** `writePageRefusesOnErrorViolationCitingReason`; `writePageProceedsWithWarningTextOnNonCanonicalStatus`. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement. Test → PASS.
- [ ] **Step 3:** commit `feat(mcp): admin write tools enforce schema validator (refuse errors, surface warnings)`.

---

# Phase 3 — Frontend: structured frontmatter surface (Part A)

### Task 8: Shared UI primitives

**Files (create + co-located CSS + vitest):** `wikantik-frontend/src/components/ui/Select.jsx`, `Combobox.jsx`, `TagInput.jsx`, `Tabs.jsx` (+ `*.test.jsx`).

Contracts:
- `Select({value, options:[{value,label}], onChange, disabled})` — native-ish styled select.
- `Combobox({value, onChange, fetchOptions(query)->Promise<[{value,label}]>, allowFreeEntry, placeholder})` — debounced async options (200ms, mirror `SearchOverlay`), keyboard nav, free-entry when `allowFreeEntry`.
- `TagInput({value:[], onChange, suggestions?|fetchSuggestions})` — chips (reuse `ui/Chip`), add on Enter/comma, remove via Chip `onRemove`.
- `Tabs({tabs:[{id,label}], active, onChange, children})` — ARIA tablist; renders active panel.

- [ ] **Step 1 (RED):** vitest per primitive — `Select.test` (renders options, fires onChange), `Combobox.test` (debounced fetch, free entry), `TagInput.test` (add/remove chip), `Tabs.test` (switch active panel). Run `npm run test -- src/components/ui/Select.test.jsx` etc → FAIL.
- [ ] **Step 2 (GREEN):** implement each. Tests → PASS (run each file alone if concurrency-flaky).
- [ ] **Step 3:** commit `feat(ui): Select, Combobox, TagInput, Tabs primitives`.

### Task 9: `schemaClient` + `FrontmatterEditor` + field widgets

**Files:** `wikantik-frontend/src/api/...` add `getFrontmatterSchema()` + `validateFrontmatter(payload)` to `client.js`; create `src/components/frontmatter/schemaClient.js` (fetch+cache schema), `FrontmatterEditor.jsx`, and `fields/` (`EnumField`, `TagsField`, `PageRefsField`, `SummaryField`, `DateField`, `AudienceField`, `KgIncludeField`, `RunbookBlockEditor`, `ReadOnlyField`, `KeyValueEditor`). Tests: `FrontmatterEditor.test.jsx` + key field tests.

`FrontmatterEditor({ metadata, onChange, violations })`:
- Owns no fetch; receives `metadata` object + `violations` (from last save/dry-run). Calls `schemaClient` to get the descriptor; renders one widget per `FieldSpec` (in schema order), read-only chips for READONLY keys, unknown keys → `KeyValueEditor` in an Advanced `<details>`.
- Form ⇄ Raw sub-tabs (use `ui/Tabs`): Raw shows `metadataToYaml(metadata)` (existing util) in a CodeMirror yaml instance; switching Raw→Form calls `validateFrontmatter({frontmatter})` and repopulates `metadata` from the returned parsed `metadata` (pins line/col on parse error).
- Inline violations: for each widget, show matching `violations[].field`; error vs warning styling; "apply suggestion" button when `suggestion` present (sets the field to the suggestion).

- [ ] **Step 1 (RED):** `FrontmatterEditor.test.jsx` — renders type as a Select with canonical options; shows an inline error for a `cluster` violation; "apply suggestion" sets status→active; unknown key appears in Advanced and survives onChange; Raw tab shows YAML. `EnumField`/`SummaryField` unit tests (counter colour by length). Run → FAIL.
- [ ] **Step 2 (GREEN):** implement widgets + editor + schemaClient + client methods. Tests → PASS.
- [ ] **Step 3:** commit `feat(frontend): structured FrontmatterEditor + field widgets + schema client`.

### Task 10: `PageEditor` integration + `NewArticleModal` slim

**Files:** Modify `wikantik-frontend/src/components/PageEditor.jsx`, `client.js` (savePage payload + 422/200 handling), `NewArticleModal.jsx`. Update `PageEditor` tests.

- `PageEditor`: body-only CodeMirror (stop calling `reconstructContent`; load `metadata` into `FrontmatterEditor` state, `content` body into CodeMirror). Structured region uses `ui/Tabs` with a `Frontmatter` tab now (the `Knowledge` tab is added in Task 13 — leave a single-tab `Tabs` or a placeholder array for now). Save: `savePage(name, { content: body, metadata })`; on 422 set `violations` state (passed to `FrontmatterEditor`) + toast; on 200 surface `warnings` inline + success toast. Raw-mode save sends `{ content: composedText }` (no metadata).
- `client.savePage` returns parsed body for both 200 (with `warnings`) and 422 (with `violations`); throw a typed error carrying `violations` on 422 so the editor can catch+map.
- `NewArticleModal`: reduce to name + starting type; seed `{ type, status:'active', date: today }` metadata object; navigate to editor with that object (router state) instead of building a raw YAML string.

- [ ] **Step 1 (RED):** `PageEditor.test.jsx` — loads metadata into the form (not into CodeMirror text); save sends `{content, metadata}`; a 422 response maps violations into the form; body editor text has no `---` block. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement. Tests → PASS.
- [ ] **Step 3:** commit `feat(frontend): PageEditor uses structured frontmatter; NewArticleModal slimmed`.

---

# Phase 4 — Server: page-scoped KG slice (Part B)

### Task 11: `getPageSlice` service + DAO query

**Files:**
- Modify: `wikantik-api/.../knowledge/KnowledgeGraphService.java` — add `PageKnowledgeSlice getPageSlice(String pageName)` + DTOs `PageKnowledgeSlice(List<KgNode> entities, List<KgEdgeView> edges)` and `KgEdgeView(UUID id, UUID sourceId, UUID targetId, String sourceName, String targetName, String relationshipType, Provenance provenance)`.
- Modify: the `DefaultKnowledgeGraphService` impl (find it: grep `implements KnowledgeGraphService` in `wikantik-main`) — implement `getPageSlice`.
- Reference: model the SQL on `PageMentionsLoader` (`wikantik-main/.../search/hybrid/PageMentionsLoader.java`, `SELECT c.page_name, m.node_id FROM kg_content_chunks c JOIN chunk_entity_mentions m ...`).
- Test: `wikantik-main` unit/DB test `GetPageSliceTest`.

Query: node_ids mentioned on the page → `SELECT DISTINCT m.node_id FROM kg_content_chunks c JOIN chunk_entity_mentions m ON m.chunk_id=c.id WHERE c.page_name = ?`. Load those `KgNode`s (existing `getNode`/batch). Edges among them: `getEdgesForNode` per node filtered to both-endpoints-in-set, deduped by edge id; resolve endpoint names via `getNodeNames`. Build `KgEdgeView`s.

- [ ] **Step 1 (RED):** `GetPageSliceTest` — seed a page with chunks+mentions+two nodes+one edge; assert `getPageSlice(page).entities()` has both nodes and `.edges()` has the intra-page edge with resolved names; an edge to an off-page node is excluded. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement. `mvn test -pl wikantik-main -Dtest=GetPageSliceTest` → PASS.
- [ ] **Step 3:** commit `feat(kg): getPageSlice — entities + intra-page edges for a page`.

### Task 12: `PageKnowledgeResource` endpoints

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/PageKnowledgeResource.java` (extends `RestServletBase`)
- Register in the REST application class.
- Test: unit `PageKnowledgeResourceTest` (mock service + `KgCurationOps`) + IT in Task 14.

Endpoints (path base `/pages/{name}/knowledge`):
- `GET` → view-gated (`checkPagePermission(request, page, "view")` — confirm exact signature at `RestServletBase:436`) → `service.getPageSlice(name)`.
- `POST /entities` → edit-gated → `curationOps.tryUpsertNode(body.name, body.nodeType, name, body.properties, actor)`; map `NodeResult.error()` → 422.
- `POST /entities/{id}/confirm` → edit-gated → re-upsert stamping `HUMAN_CURATED` (load node, `tryUpsertNode` with provenance property).
- `DELETE /entities/{id}` → edit-gated → `tryDeleteNode`.
- `POST /edges` → edit-gated → `tryUpsertEdge(srcId,tgtId,relType,props,actor)`; `EdgeResult.error()` → **422** `{violations:[{field:"edge",severity:"error",code:"kg.edge.refused",message:<error>}]}`.
- `POST /edges/{id}/confirm` → `tryConfirmEdge`; `DELETE /edges/{id}` → `tryDeleteEdge`; `POST /edges/{id}/reject` → `tryDeleteAndRejectEdge`.

Reuse the `FieldViolation` JSON shape from Task 6 for the 422 envelope.

- [ ] **Step 1 (RED):** `PageKnowledgeResourceTest` — `getRequiresViewPermission`; `postEdgeRequiresEditPermission`; `shaclRefusalReturns422WithViolation` (mock `tryUpsertEdge` → `EdgeResult.fail("…SHACL…")`). Run → FAIL.
- [ ] **Step 2 (GREEN):** implement + register. Test → PASS.
- [ ] **Step 3:** commit `feat(rest): page-scoped KG endpoints (view read, edit-gated curation, SHACL->422)`.

---

# Phase 5 — Frontend: Knowledge tab panel (Part B)

### Task 13: `KnowledgeGraphPanel` + Knowledge tab

**Files:** Create `wikantik-frontend/src/components/knowledge/KnowledgeGraphPanel.jsx` (+ test); add client methods `getPageKnowledge/upsertEntity/confirmEntity/deleteEntity/upsertEdge/confirmEdge/deleteEdge`; add the `Knowledge` tab to `PageEditor`'s `ui/Tabs`.

`KnowledgeGraphPanel({ pageName })`:
- On mount, `getPageKnowledge(pageName)` → render Entities list (name + `nodeType` `Select` over the 9 `EntityTypeVocabulary` values + provenance badge + confirm/remove) and Relations list (`src → predicate → tgt` + remove) + add-relation row (two entity `Select`s + a predicate `Select` over the 21 `RelationshipTypeVocabulary` values + Add).
- All 21 predicates offered; on add-edge 422, show the SHACL refusal message inline by the add row (reuse the violation styling). Re-fetch the slice after each successful mutation.
- Hardcode the 9 entity classes + 21 predicates as JS constants mirroring the Java vocab (small, stable; a drift note in a comment — a server enum endpoint is a fast-follow).

- [ ] **Step 1 (RED):** `KnowledgeGraphPanel.test.jsx` — renders entities+relations from a mocked slice; changing a `nodeType` Select calls `upsertEntity`; Add relation calls `upsertEdge`; a 422 SHACL refusal renders inline. Run → FAIL.
- [ ] **Step 2 (GREEN):** implement panel + client methods + wire the `Knowledge` tab into `PageEditor`. Tests → PASS.
- [ ] **Step 3:** commit `feat(frontend): page-scoped Knowledge tab panel (entities + relations curation)`.

---

# Phase 6 — Integration tests + final verification

### Task 14: REST integration tests

**Files:** add to `wikantik-it-tests/wikantik-selenide-tests/.../its/rest/` (REST IT module). Tests: `FrontmatterSchemaIT` (GET schema), `FrontmatterSaveContractIT` (PUT 422 error / 200 warnings / metadata compose), `FrontmatterValidateIT` (dry-run), `PageKnowledgeIT` (GET slice; POST edge SHACL refusal → 422; view/edit permission gating).

- [ ] Write each IT following existing REST IT patterns (use `RestSeedHelper.awaitAdminReady`; startup fixtures per seed-lag note). Run the REST IT module alone: `mvn clean install -pl wikantik-it-tests/wikantik-selenide-tests -Pintegration-tests -Dit.test=Frontmatter*,PageKnowledge* -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false` (NO `-T`). Commit `test(it): frontmatter contract + page-knowledge REST ITs`.

### Task 15: MCP wire-level IT

**Files:** add `McpFrontmatterValidationIT` to the custom-jdbc IT suite (Cargo-launched). Assert `write_page`/`update_page` refuses an error-violation citing the reason, and surfaces a warning on a non-canonical status. Run that IT module alone (NO `-T`). Commit `test(it): MCP write-tool schema-validation parity`.

### Task 16: Selenide editor ITs

**Files:** add `StructuredFrontmatterEditorIT` + `KnowledgeTabIT` to the Selenide suite. Frontmatter: open a startup-fixture page, edit a field via the form, save, verify persisted; break-glass round-trip; non-canonical enum shows the stern warning + apply-suggestion. Knowledge: open a fixture page with a seeded entity, confirm it, add an intra-page relation, see a non-conformant predicate refused inline. Run the Selenide module alone (NO `-T`). Commit `test(it): Selenide structured editor + knowledge tab flows`.

### Task 17: Full gate

- [ ] `mvn clean install -T 1C -DskipITs` (unit reactor) → BUILD SUCCESS.
- [ ] `cd wikantik-frontend && npm run test` (full vitest) → green (re-run flaky files alone).
- [ ] `mvn clean install -Pintegration-tests -fae` (full IT reactor, NO `-T`) → BUILD SUCCESS.
- [ ] Update `CLAUDE.md` (frontend editor + the two new endpoint groups) and the spec status. Commit `docs: structured page curation editor shipped`.

---

## Self-review checklist (run after writing, before executing)

1. **Spec coverage:** §3 fields → Task 1/9; §4.2 enums → Task 1/2; §4.3 validator → Task 2; §4.4 wiring → Task 3; §4.5 endpoints → Task 4/5/6; §4.6 MCP → Task 7; §5 frontend → Task 8/9/10; §8.2 getPageSlice → Task 11; §9 endpoints → Task 12; §10 panel → Task 13; §11 testing → Task 14/15/16; §12 scope (fast-follows excluded). ✓
2. **Type consistency:** `FieldViolation(field,severity,code,message,suggestion)`, `FieldSpec`, `Severity`, `Widget`, `ValidationCtx`, `FrontmatterValidationException.violations()`, `PageKnowledgeSlice`/`KgEdgeView`, client methods — names match across tasks. ✓
3. **Open execution-time confirmations (decide, don't placeholder):** (a) `Context` attribute bag vs ThreadLocal for the warning sink; (b) exact `checkPagePermission` signature/perm string; (c) the REST application registration class; (d) `FrontmatterValidationPageFilter` registration site to remove; (e) `DefaultKnowledgeGraphService` class name. Each is a `grep` at the start of its task.
