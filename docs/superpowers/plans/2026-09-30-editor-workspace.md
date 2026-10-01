# Editor Workspace & Callouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the wiki an Obsidian-style editing workspace — a unified quick switcher / command palette / slash menu, link hover previews and Ctrl-click, type-aware templates with `aliases:`, outgoing unlinked mentions, heading folding, tag autocomplete, an in-app unsaved-changes guard — plus Obsidian callouts rendered identically by the server and the editor preview.

**Architecture:** Four small server additions (`?q=` title/alias ranking via a `PageTitleLookup` owned by the structural index, `GET /api/pages/{name}/preview`, `GET /api/page-templates`, `POST /api/mentions/scan`) and a Flexmark callout extension, consumed by a React command registry that drives one overlay, the toolbar, keyboard shortcuts and a CodeMirror slash completion. A shared JSON fixture pins server/preview callout parity.

**Tech Stack:** Java 25, Flexmark 0.64.8, JUnit 5 + Mockito, Jakarta Servlets; React 19, react-markdown 10 (remark/rehype, mdast), CodeMirror 6 (`@uiw/react-codemirror`, `@codemirror/language`), vitest 4 + happy-dom + Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-30-editor-workspace-design.md`

## Global Constraints

- TDD for every task: write the test, run it and capture the RED failure, implement, run GREEN. Report both in the task report.
- Never swallow exceptions: Java catches log `LOG.warn( "...context...: {}", e.getMessage(), e )` at minimum; frontend catches `console.warn('[area] context', err)` and show the degraded UI state the spec names.
- REST errors only through `RestServletBase.sendError(...)` (never `response.sendError`).
- No new configuration keys, no DB migrations, no additions to `build-support/pmd-complexity-baseline.properties`.
- New Java files carry the Apache license header copied from a neighbouring file in the same package.
- Stage files by name (`git add <paths>`), never `git add -A`. Commit messages 1–3 lines ending with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg
  ```
- Frontend dependency: declare `"@codemirror/language": "^6.12.4"` in `wikantik-frontend/package.json` dependencies (already installed transitively; do not add any other runtime dependency).
- Theme colours are CSS custom properties defined in `wikantik-frontend/src/styles/globals.css` under `:root` (light) and `[data-theme="dark"]` (dark). Never reference a token that is not defined in both.
- User-visible strings, verbatim:
  - Guard dialog: title `Unsaved changes`, body `You have unsaved changes. Your draft is kept in this browser but isn't saved to the wiki.`, buttons `Stay` / `Leave without saving`
  - Overlay rows: `Search full text for “{q}”`, `Create page “{q}”`; title-search failure row `Page search failed`
  - Command failure toast: `Command failed: {title}`
  - Preview card for a missing target: `Not created yet`
  - Templates fetch failure: `Templates unavailable`
  - Mentions panel: `No unlinked mentions`, `Mentions available shortly`, `Couldn't scan for mentions`, `Text changed — rescanned`
- Frontend checks: `cd wikantik-frontend && npx vitest run <files>` while iterating; `npm run lint` before commit.
- Java checks: `mvn test -pl <module> -Dtest=<Class> -q` while iterating (add `-am` only when an upstream module changed; use `mvn -pl wikantik-api install -q -DskipTests` first when wikantik-api changed so downstream modules see it).

## Review Focus

1. **Ctrl/Cmd-K inside the editor** must insert a link and must NOT also open the overlay (CodeMirror handles it and calls `preventDefault`; the global handler must skip `defaultPrevented` events). Test pinned in Task 11.
2. **Drafts with emoji / astral characters and CRLF line endings**: mention offsets are UTF-16 code units on both sides, so **Link** must rewrite exactly the matched phrase. Tests pinned in Task 6 (server offsets) and Task 17 (client replace).
3. **Hovering a link to a restricted page** must reveal nothing: the preview endpoint returns the identical 404 used for a missing page, and the card shows `Not created yet`. Tests pinned in Task 4 (unit) and Task 7 (ACL IT).
4. **Clicking a link inside the editor's own preview pane (or the sidebar) with a dirty draft** must open the guard dialog; Ctrl-click and `target=_blank` links must not. Test pinned in Task 10.
5. **A `/` typed mid-word, in a URL path, inside inline code or a fence** must not open the slash menu. Test pinned in Task 14.

## Pre-execution rulings (the plan's reading of the spec)

- **R1 — Where `PageTitleIndex` lives.** The structural index already parses every page's frontmatter on startup rebuild and on save/delete/rename (rename = save under the same `canonical_id`). The title index is therefore maintained inside `DefaultStructuralIndexService` and exposed through a new `default Optional<PageTitleLookup> titleLookup()` on `StructuralIndexService` (wikantik-api). This satisfies "built in the background at startup, kept current by save/rename/delete events" without a second event listener, a second pass over the corpus, or a new `getManager` call (DecompositionArchTest R-2 freezes those). "Warming" = the structural index has not completed its first rebuild (`IndexHealth.Status` is not `UP`), signalled by `titleLookup()` returning `Optional.empty()`.
- **R2 — Runbook template.** `FrontmatterRunbookValidator` rejects an empty `runbook:` block for `type: runbook` (needs ≥1 `when_to_use`, ≥2 `steps`, ≥1 `pitfalls`). The spec requires both "the empty block skeleton" and "zero errors"; zero errors wins, so the runbook template carries short placeholder prompts.
- **R3 — Sanitizer.** When `allowHTML=true`, `WikantikHtmlSanitizer` would strip `data-callout` and `details[open]`. The allowlist gains `data-callout` (values matching `[a-z]+`) on `div`/`details` and `open` on `details`. Callout styling keys off classes (`callout`, `callout-<style>`), which survive regardless.
- **R4 — Defect found during planning, fixed in Task 1.** `api.savePage` (wikantik-frontend/src/api/client.js) destructures away the `replaceMetadata: true` that `PageEditor` passes, so the server merges metadata and a field removed in the structured frontmatter editor is never removed. This would also make removing an alias impossible. One-line fix with a test.
- **R5 — Defect found during planning, fixed in Task 12.** `NewArticleModal`'s duplicate-name check and cluster list come from `Sidebar`'s `listPages({limit: 500})`, so a duplicate slug beyond the first 500 pages is not detected. The modal becomes self-contained (live `listPages({names:[slug]})` check, clusters from `/api/structure/clusters`) and is rendered once by a `NewPageProvider` at the App root so the overlay's "Create page" row can open it.
- **R6 — `aliases` widget.** The `TAGS` widget carries tag-specific kebab-case warnings, so `aliases` gets a new `Widget.STRING_LIST` (rendered with `TagInput`, validated for blank / >100 chars / non-list).
- **R7 — Preview permission.** `checkPagePermission` answers 403; the spec requires the same 404 for missing and not-viewable. The preview path uses the silent `hasPagePermission` and answers 404 in both cases.
- **R8 — Mention-scan parser.** `MarkdownDocument.options(...)` needs a wiki `Context` and adds context-bound link post-processing the scan doesn't need. The scanner builds one static Flexmark parser with the same stateless extensions `MarkdownDocument` registers (tables, GitLab block math, footnotes, definitions, attributes, TOC) plus `InlineMathParser`, so the AST node kinds match the production render. `[{Plugin}]` spans are masked before parsing.
- **R9 — Fold reveal.** Implemented once inside the `CodeEditor` handle (`setSelection`, `scrollToLine`, `jumpToLineAligned` unfold any fold containing the target first) so every current and future jump source is covered.
- **R10 — New servlets.** `PageTemplatesResource` (`/api/page-templates`) and `MentionScanResource` (`/api/mentions/scan`); preview is a sub-path of the existing `PageResource`. The documented servlet counts in `CLAUDE.md`, `README.md` and `docs/wikantik-pages/WikantikArchitecture.md` are bumped in Task 18.

## File map

**wikantik-api**
- `api/frontmatter/schema/Widget.java` — add `STRING_LIST` (T2)
- `api/frontmatter/schema/FrontmatterSchema.java` — add `aliases` (T2)
- `api/pagegraph/PageTitleLookup.java` — new interface (T3)
- `api/pagegraph/StructuralIndexService.java` — `titleLookup()` default (T3)
- `api/frontmatter/schema/PageTemplates.java` — new (T5)

**wikantik-main**
- `frontmatter/schema/SchemaDrivenFrontmatterValidator.java` — `STRING_LIST` case (T2)
- `pagegraph/spine/PageTitleIndex.java` — new (T3)
- `pagegraph/spine/DefaultStructuralIndexService.java` — maintain aliases, expose lookup (T3)
- `export/HeadingSlugs.java` — `sectionBody(...)` (T4)
- `preview/PageExcerpts.java` — new (T4)
- `mentions/MentionScanner.java`, `mentions/Mention.java` — new (T6)
- `markdown/extensions/callouts/{CalloutExtension,CalloutBlock,CalloutPostProcessor,CalloutNodeRenderer,CalloutTypes}.java` — new (T8)
- `parser/markdown/MarkdownDocument.java` — register callout extension (T8)
- `parser/markdown/WikantikHtmlSanitizer.java` — allow callout attributes (T8)

**wikantik-rest**
- `PageNameQuery.java`, `PageListResource.java` — title-aware ranking (T3)
- `PageResource.java` — `/preview` sub-path (T4)
- `PageTemplatesResource.java` — new (T5)
- `MentionScanResource.java` — new (T7)

**wikantik-war** — `WEB-INF/web.xml` servlet + mapping blocks (T5, T7)

**wikantik-admin-mcp** — `wikantik-mcp-instructions.txt` adds `aliases` (T2)

**wikantik-it-tests** — `wikantik-it-test-rest/.../EditorWorkspaceAclIT.java` (T7); `wikantik-selenide-tests` browser IT (T18)

**wikantik-frontend/src**
- `api/client.js` — `savePage` fix (T1); `listTags`, `listClusters`, `getPagePreview`, `getPageTemplates`, `scanMentions` (T2–T17)
- `components/frontmatter/{FieldWidget,FrontmatterEditor}.jsx`, `hooks/useTagSuggestions.js` (T2)
- `utils/__fixtures__/callouts.json`, `utils/remarkCallouts.js`, `styles/article.css` (T8/T9)
- `navigation/{NavigationGuardProvider.jsx,navigationGuard.js}` (T10)
- `commands/{registry.js,useRegisterCommands.js,useGlobalCommands.js}`, `hooks/useGlobalHotkeys.js` (T11)
- `newpage/NewPageProvider.jsx`, `components/NewArticleModal.jsx`, `components/Sidebar.jsx` (T12)
- `components/QuickOverlay.jsx` (replaces `SearchOverlay.jsx`), `utils/fuzzy.js` (T13)
- `utils/slashComplete.js`, `utils/editorCommands.js`, `utils/markdownFold.js`, `components/{CodeEditor,EditorToolbar,PageEditor}.jsx` (T14, T15)
- `hooks/{usePagePreview,useLinkPreview}.js`, `components/LinkPreviewCard.jsx`, `utils/linkInteraction.js` (T16)
- `hooks/useUnlinkedMentions.js`, `components/editor/UnlinkedMentionsPanel.jsx` (T17)

Task order: 1 → 18 as numbered. Server tasks 2–8 are independent of frontend tasks 10–17 except where an **Interfaces → Consumes** block names a server endpoint.

---

### Task 1: `api.savePage` forwards `replaceMetadata` (defect R4)

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` (the `savePage` entry, ~line 91)
- Test: `wikantik-frontend/src/api/client.test.js`

**Interfaces:**
- Produces: `api.savePage(name, { content, metadata, replaceMetadata, changeNote, author, expectedVersion, expectedContentHash, markupSyntax })` — `replaceMetadata` is now sent in the PUT body when provided.

- [ ] **Step 1: Write the failing test** — append to `client.test.js` (it already defines `mockFetchResponse`):

```js
describe('api.savePage', () => {
  beforeEach(() => { global.fetch = vi.fn(); });
  afterEach(() => { vi.restoreAllMocks(); });

  it('forwards replaceMetadata so a field removed in the structured editor is removed on save', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { success: true } }));
    await api.savePage('P', { content: 'b', metadata: { type: 'article' }, replaceMetadata: true });
    const sent = JSON.parse(global.fetch.mock.calls[0][1].body);
    expect(sent.replaceMetadata).toBe(true);
    expect(sent.metadata).toEqual({ type: 'article' });
  });

  it('omits replaceMetadata when the caller does not ask for it', async () => {
    global.fetch.mockResolvedValue(mockFetchResponse({ status: 200, body: { success: true } }));
    await api.savePage('P', { content: 'b' });
    const sent = JSON.parse(global.fetch.mock.calls[0][1].body);
    expect(sent).not.toHaveProperty('replaceMetadata');
  });
});
```

- [ ] **Step 2: Run to verify RED** — `cd wikantik-frontend && npx vitest run src/api/client.test.js` → the first test FAILS (`expected undefined to be true`).

- [ ] **Step 3: Implement** — replace the `savePage` entry:

```js
  savePage: (name, { content, metadata, replaceMetadata, changeNote, author, expectedVersion, expectedContentHash, markupSyntax }) =>
    request(`/api/pages/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify({ content, metadata, replaceMetadata, changeNote, author, expectedVersion, expectedContentHash, markupSyntax }),
    }),
```

(`JSON.stringify` drops `undefined`, so callers that don't pass it are unchanged.)

- [ ] **Step 4: Run GREEN** — same command; both pass. Also run `npx vitest run src/components/PageEditor.test.jsx` (unchanged behaviour for its mocks).

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/api/client.test.js
git commit -m "fix(editor): send replaceMetadata so removed frontmatter fields are removed on save"
```

---

### Task 2: `aliases:` field, `STRING_LIST` widget, tag autocomplete

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/Widget.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/FrontmatterSchema.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/frontmatter/schema/SchemaDrivenFrontmatterValidator.java`
- Test: `wikantik-main/src/test/java/com/wikantik/frontmatter/schema/SchemaDrivenFrontmatterValidatorTest.java`
- Modify: `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`
- Modify: `wikantik-frontend/src/api/client.js` (add `listTags`)
- Create: `wikantik-frontend/src/hooks/useTagSuggestions.js` + `useTagSuggestions.test.js`
- Modify: `wikantik-frontend/src/components/frontmatter/FieldWidget.jsx` (+ test), `FrontmatterEditor.jsx`, `fieldLayout.js`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (pass `tagSuggestions`); `PageEditor.test.jsx` mock gains `listTags`

**Interfaces:**
- Produces (server): `Widget.STRING_LIST`; schema field `aliases` = `new FieldSpec( "aliases", "Aliases", Widget.STRING_LIST, List.of(), false, null, 100, null, Map.of() )` placed directly after `title`. Violation codes: `aliases.list` (WARNING, value not a list), `aliases.blank` (WARNING), `aliases.length` (WARNING, an entry > 100 chars).
- Produces (frontend): `api.listTags()` → `{ tags: [{ tag, count, top_pages }] }` (envelope auto-unwrapped); `useTagSuggestions()` → `string[]` sorted by count desc then name; `FieldWidget` prop `tagSuggestions` (default `[]`); `FrontmatterEditor` prop `tagSuggestions`.

- [ ] **Step 1: Write the failing Java tests** — append to `SchemaDrivenFrontmatterValidatorTest`:

```java
    @Test
    void aliasesAcceptsAListOfPhrases() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "aliases", List.of( "index fund", "Index Funds" ) ), ValidationCtx.lenient() );
        assertTrue( first( vs, "aliases" ).isEmpty(), "plain phrases with spaces are valid aliases" );
    }

    @Test
    void aliasesWarnsOnBlankAndOverlongEntries() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "aliases", List.of( " ", "x".repeat( 101 ) ) ), ValidationCtx.lenient() );
        assertTrue( vs.stream().anyMatch( v -> v.code().equals( "aliases.blank" ) && v.severity() == Severity.WARNING ) );
        assertTrue( vs.stream().anyMatch( v -> v.code().equals( "aliases.length" ) && v.severity() == Severity.WARNING ) );
    }

    @Test
    void aliasesWarnsWhenNotAList() {
        final FieldViolation v = first( validator.validate( Map.of( "aliases", "solo" ), ValidationCtx.lenient() ),
                "aliases" ).orElseThrow();
        assertEquals( "aliases.list", v.code() );
        assertEquals( Severity.WARNING, v.severity() );
    }

    @Test
    void aliasesIsDeclaredInTheSchemaAsAStringList() {
        final var spec = FrontmatterSchema.defaultSchema().field( "aliases" ).orElseThrow();
        assertEquals( com.wikantik.api.frontmatter.schema.Widget.STRING_LIST, spec.widget() );
        assertEquals( 100, spec.maxLen() );
    }
```

- [ ] **Step 2: Run RED** — `mvn -q -pl wikantik-api install -DskipTests && mvn test -pl wikantik-main -Dtest=SchemaDrivenFrontmatterValidatorTest -q` → compilation fails (`STRING_LIST` missing). That is the expected RED.

- [ ] **Step 3: Implement server side**

`Widget.java` — add the constant after `TAGS`:

```java
    /** A list of free-text strings (spaces allowed), e.g. {@code aliases}. */
    STRING_LIST,
```

`FrontmatterSchema.defaultSchema()` — insert immediately after `FieldSpec.text( "title", "Title" ),`:

```java
                new FieldSpec( "aliases", "Aliases", Widget.STRING_LIST, List.of(), false,
                        null, 100, null, Map.of() ),
```

`SchemaDrivenFrontmatterValidator.validate(...)` — add a case beside `TAGS`:

```java
                case STRING_LIST -> validateStringList( spec, raw, out );
```

and the method next to `validateTags`:

```java
    private void validateStringList( final FieldSpec spec, final Object raw, final List< FieldViolation > out ) {
        if ( raw == null ) {
            return;
        }
        if ( !( raw instanceof List< ? > list ) ) {
            out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".list",
                    "'" + spec.key() + "' should be a list, e.g. [first name, second name]." ) );
            return;
        }
        for ( final Object o : list ) {
            final String s = o == null ? "" : o.toString().trim();
            if ( s.isEmpty() ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".blank",
                        "'" + spec.key() + "' contains a blank entry." ) );
            } else if ( spec.maxLen() != null && s.length() > spec.maxLen() ) {
                out.add( FieldViolation.of( spec.key(), Severity.WARNING, spec.key() + ".length",
                        "'" + spec.key() + "' entry is longer than " + spec.maxLen() + " characters." ) );
            }
        }
    }
```

(If the `validate` switch's argument variable is not named `raw`, use the name the surrounding cases use.)

`wikantik-mcp-instructions.txt` — in the "Standard frontmatter fields" list, insert after the `summary` line (keep the column alignment of the existing lines):

```
  aliases     — list of alternative names for the page; used by the quick switcher and unlinked-mention matching
```

- [ ] **Step 4: Run GREEN (Java)** — `mvn test -pl wikantik-main -Dtest=SchemaDrivenFrontmatterValidatorTest -q` passes. Then `mvn test -pl wikantik-rest -Dtest=FrontmatterSchemaResourceTest -q` and `mvn test -pl wikantik-admin-mcp -Dtest=InstructionsRegistryDriftTest -q` (both must stay green; `aliases` is not tool-shaped).

- [ ] **Step 5: Write the failing frontend tests**

`src/hooks/useTagSuggestions.test.js`:

```js
import { renderHook, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/client', () => ({ api: { listTags: vi.fn() } }));
import { api } from '../api/client';
import { useTagSuggestions, __resetTagSuggestionsForTest } from './useTagSuggestions';

describe('useTagSuggestions', () => {
  beforeEach(() => { vi.clearAllMocks(); __resetTagSuggestionsForTest(); });

  it('returns tag names ordered by usage count, then name', async () => {
    api.listTags.mockResolvedValue({ tags: [
      { tag: 'b-tag', count: 2 }, { tag: 'a-tag', count: 2 }, { tag: 'top', count: 9 },
    ] });
    const { result } = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(result.current).toEqual(['top', 'a-tag', 'b-tag']));
  });

  it('fetches once per session and shares the result', async () => {
    api.listTags.mockResolvedValue({ tags: [{ tag: 'x', count: 1 }] });
    const a = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(a.result.current).toEqual(['x']));
    const b = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(b.result.current).toEqual(['x']));
    expect(api.listTags).toHaveBeenCalledTimes(1);
  });

  it('degrades to no suggestions and logs when the fetch fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listTags.mockRejectedValue(new Error('down'));
    const { result } = renderHook(() => useTagSuggestions());
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current).toEqual([]);
    warn.mockRestore();
  });
});
```

Append to `FieldWidget.test.jsx`:

```jsx
  it('renders a STRING_LIST field as a chip list that accepts phrases with spaces', () => {
    const onChange = vi.fn();
    render(<FieldWidget spec={{ key: 'aliases', label: 'Aliases', widget: 'STRING_LIST' }}
      value={['index fund']} onChange={onChange} />);
    expect(screen.getByText('index fund')).toBeInTheDocument();
    const input = screen.getByPlaceholderText('Aliases');
    fireEvent.change(input, { target: { value: 'low cost funds' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(['index fund', 'low cost funds']);
  });

  it('offers tag suggestions to the TAGS widget', () => {
    const { container } = render(<FieldWidget spec={{ key: 'tags', label: 'Tags', widget: 'TAGS' }}
      value={[]} onChange={() => {}} tagSuggestions={['finance', 'investing']} />);
    const options = [...container.querySelectorAll('datalist option')].map((o) => o.value);
    expect(options).toEqual(['finance', 'investing']);
  });
```

(Before relying on the placeholder in the first test, open `components/ui/TagInput.jsx` and confirm it renders `placeholder` on its input and commits on Enter; if its commit keys differ, adapt the test's key event to TagInput's actual commit key — do not change TagInput.)

- [ ] **Step 6: Run RED** — `npx vitest run src/hooks/useTagSuggestions.test.js src/components/frontmatter/FieldWidget.test.jsx` → hook module not found; STRING_LIST renders a text field; datalist empty.

- [ ] **Step 7: Implement frontend**

`api/client.js` — add near `getFrontmatterSchema`:

```js
  listTags: () => request('/api/structure/tags'),
```

`src/hooks/useTagSuggestions.js`:

```js
import { useEffect, useState } from 'react';
import { api } from '../api/client';

// One fetch per browser session, shared by every editor that mounts.
let cached = null;

function load() {
  if (!cached) {
    cached = api.listTags()
      .then((data) => (data?.tags || [])
        .slice()
        .sort((a, b) => (b.count - a.count) || a.tag.localeCompare(b.tag))
        .map((t) => t.tag))
      .catch((err) => {
        console.warn('[tag-suggestions] could not load tags; free entry still works', err?.message || err);
        cached = null; // allow a later editor mount to retry
        return [];
      });
  }
  return cached;
}

export function useTagSuggestions() {
  const [tags, setTags] = useState([]);
  useEffect(() => {
    let cancelled = false;
    load().then((t) => { if (!cancelled) setTags(t); });
    return () => { cancelled = true; };
  }, []);
  return tags;
}

export function __resetTagSuggestionsForTest() { cached = null; }
```

`FieldWidget.jsx` — add `tagSuggestions = []` to the props destructure; change the `TAGS` case to pass `suggestions={tagSuggestions}`; add:

```jsx
    case 'STRING_LIST':
      control = <TagInput value={Array.isArray(value) ? value : []} onChange={onChange} placeholder={label} id={key} />;
      break;
```

(match the exact `control = …; break;` / return shape the surrounding cases use).

`fieldLayout.js` — add `'STRING_LIST'` to `WIDE_WIDGETS`.

`FrontmatterEditor.jsx` — accept a `tagSuggestions` prop and pass `tagSuggestions={tagSuggestions}` on the `<FieldWidget …>` it renders (line ~195), documenting it in the prop comment block at the top beside `pageSearch`.

`PageEditor.jsx` — `import { useTagSuggestions } from '../hooks/useTagSuggestions';`, call `const tagSuggestions = useTagSuggestions();` with the other hooks, and pass `tagSuggestions={tagSuggestions}` to `<FrontmatterEditor …>` (line ~776). In `PageEditor.test.jsx` (and `PageEditor.linkCompletion.test.jsx`, `PageEditor.uploads.test.jsx` if they mock `../api/client` with an explicit object) add `listTags: vi.fn().mockResolvedValue({ tags: [] })` to the api mock.

- [ ] **Step 8: Run GREEN** — `npx vitest run src/hooks/useTagSuggestions.test.js src/components/frontmatter src/components/PageEditor*.test.jsx` all pass; `npm run lint` clean.

- [ ] **Step 9: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/Widget.java \
  wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/FrontmatterSchema.java \
  wikantik-main/src/main/java/com/wikantik/frontmatter/schema/SchemaDrivenFrontmatterValidator.java \
  wikantik-main/src/test/java/com/wikantik/frontmatter/schema/SchemaDrivenFrontmatterValidatorTest.java \
  wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt \
  wikantik-frontend/src/api/client.js wikantik-frontend/src/hooks/useTagSuggestions.js \
  wikantik-frontend/src/hooks/useTagSuggestions.test.js \
  wikantik-frontend/src/components/frontmatter/FieldWidget.jsx \
  wikantik-frontend/src/components/frontmatter/FieldWidget.test.jsx \
  wikantik-frontend/src/components/frontmatter/FrontmatterEditor.jsx \
  wikantik-frontend/src/components/frontmatter/fieldLayout.js \
  wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor*.test.jsx
git commit -m "feat(frontmatter): aliases field (STRING_LIST) and tag autocomplete in the editor"
```

---

### Task 3: `PageTitleLookup` in the structural index; title/alias/fuzzy `?q=` ranking

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/pagegraph/PageTitleLookup.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/pagegraph/StructuralIndexService.java`
- Create: `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/PageTitleIndex.java`
- Test: `wikantik-main/src/test/java/com/wikantik/pagegraph/spine/PageTitleIndexTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/spine/DefaultStructuralIndexService.java`
- Test: `wikantik-main/src/test/java/com/wikantik/pagegraph/spine/DefaultStructuralIndexServiceTest.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageNameQuery.java`, `PageListResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/PageNameQueryTest.java` (create if absent; otherwise append), `PageListResourceTest.java`

**Interfaces:**
- Produces (wikantik-api):

```java
package com.wikantik.api.pagegraph;

public interface PageTitleLookup {
    /** One page's matchable phrases: de-CamelCased name, frontmatter title (if it differs), aliases. */
    record TitleEntry( String slug, String title, java.util.List< String > phrases ) {}

    /**
     * Ranks {@code names} against {@code query} using each page's name, de-CamelCased name, title and aliases.
     * Tiers (best key wins): exact, prefix, substring, subsequence — comparisons are case- and
     * whitespace-insensitive. Non-matching names are dropped; ties break by name (natural order).
     * A blank query returns all names in natural order.
     */
    java.util.List< String > rank( java.util.Collection< String > names, String query );

    /** Every indexed page, for phrase matching (mention scan). */
    java.util.List< TitleEntry > entries();
}
```

- Produces: `StructuralIndexService#titleLookup()` → `Optional<PageTitleLookup>`, default `Optional.empty()`; `DefaultStructuralIndexService` returns a lookup once its first rebuild completed.
- Produces: `PageTitleIndex.of( Collection<PageDescriptor> pages, Map<String,List<String>> aliasesBySlug )` (wikantik-main, implements `PageTitleLookup`); `PageTitleIndex.phraseOf( String slug )` → de-CamelCased name via `com.wikantik.util.TextUtil.beautifyString`.
- Produces: `PageNameQuery.rank( Collection<T> items, Function<T,String> nameOf, String q, Optional<PageTitleLookup> lookup )`.
- Consumed by: Task 6 (`entries()`), Task 7, Task 13 (`GET /api/pages?q=`).

- [ ] **Step 1: Write the failing `PageTitleIndexTest`**

```java
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.api.pagegraph.PageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PageTitleIndexTest {

    private static PageDescriptor page( final String slug, final String title ) {
        return new PageDescriptor( "id-" + slug, slug, title, PageType.ARTICLE, null, List.of(), List.of(),
                null, Instant.EPOCH, Optional.empty(), false );
    }

    private final PageTitleIndex index = PageTitleIndex.of(
            List.of( page( "LowCostIndexFundInvesting", "Low-Cost Index Fund Investing" ),
                     page( "IndexFundsHub", "IndexFundsHub" ),
                     page( "BondLadders", "Bond Ladders" ),
                     page( "Kubernetes", "Kubernetes" ) ),
            Map.of( "Kubernetes", List.of( "k8s" ) ) );

    private static final List< String > ALL =
            List.of( "LowCostIndexFundInvesting", "IndexFundsHub", "BondLadders", "Kubernetes" );

    @Test void exactBeatsPrefixBeatsSubstring() {
        final PageTitleIndex idx = PageTitleIndex.of(
                List.of( page( "Index", "Index" ), page( "IndexFundsHub", "IndexFundsHub" ),
                         page( "LowCostIndexFundInvesting", "x" ) ), Map.of() );
        assertEquals( List.of( "Index", "IndexFundsHub", "LowCostIndexFundInvesting" ),
                idx.rank( List.of( "LowCostIndexFundInvesting", "IndexFundsHub", "Index" ), "index" ) );
    }

    @Test void spacesInTheQueryAreIgnored() {
        assertEquals( List.of( "IndexFundsHub", "LowCostIndexFundInvesting" ), index.rank( ALL, "index fund" ) );
    }

    @Test void aliasMatchesFindThePage() {
        assertEquals( List.of( "Kubernetes" ), index.rank( ALL, "k8s" ) );
    }

    @Test void titleWithPunctuationIsMatchable() {
        assertEquals( List.of( "LowCostIndexFundInvesting" ), index.rank( ALL, "low-cost" ) );
    }

    @Test void subsequenceIsTheLastTier() {
        assertEquals( List.of( "LowCostIndexFundInvesting" ), index.rank( ALL, "lcifi" ) );
        final PageTitleIndex idx = PageTitleIndex.of(
                List.of( page( "Ladder", "Ladder" ), page( "LowAdDer", "Low Ad Der" ) ), Map.of() );
        // "ladd" is a prefix of Ladder and only a subsequence of lowadder
        assertEquals( List.of( "Ladder", "LowAdDer" ), idx.rank( List.of( "LowAdDer", "Ladder" ), "ladd" ) );
    }

    @Test void unknownNamesStillMatchOnTheirOwnName() {
        assertEquals( List.of( "NotIndexedYet" ), index.rank( List.of( "NotIndexedYet", "BondLadders" ), "notindexed" ) );
    }

    @Test void blankQueryReturnsAllInNaturalOrder() {
        assertEquals( List.of( "BondLadders", "IndexFundsHub", "Kubernetes", "LowCostIndexFundInvesting" ),
                index.rank( ALL, "  " ) );
    }

    @Test void entriesExposeDeCamelCasedNameTitleAndAliases() {
        final PageTitleLookup.TitleEntry k = index.entries().stream()
                .filter( e -> e.slug().equals( "Kubernetes" ) ).findFirst().orElseThrow();
        assertEquals( List.of( "Kubernetes", "k8s" ), k.phrases() );
        final PageTitleLookup.TitleEntry l = index.entries().stream()
                .filter( e -> e.slug().equals( "LowCostIndexFundInvesting" ) ).findFirst().orElseThrow();
        assertEquals( List.of( "Low Cost Index Fund Investing", "Low-Cost Index Fund Investing" ), l.phrases() );
    }
}
```

- [ ] **Step 2: Run RED** — `mvn -q -pl wikantik-api install -DskipTests` will fail until `PageTitleLookup` exists; create the interface file from the Interfaces block first (it is pure declaration), install wikantik-api, then `mvn test -pl wikantik-main -Dtest=PageTitleIndexTest -q` → fails to compile (`PageTitleIndex` missing). Expected RED.

- [ ] **Step 3: Implement `PageTitleIndex`**

```java
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable title/alias index over the structural projection: feeds {@code GET /api/pages?q=} ranking and
 * the unlinked-mention scan. Rebuilt lazily by {@link DefaultStructuralIndexService} whenever its projection
 * or alias map changes.
 */
public final class PageTitleIndex implements PageTitleLookup {

    private static final int EXACT = 0;
    private static final int PREFIX = 1;
    private static final int SUBSTRING = 2;
    private static final int SUBSEQUENCE = 3;
    private static final int NO_MATCH = Integer.MAX_VALUE;

    private final List< TitleEntry > entries;
    private final Map< String, List< String > > normalizedKeysBySlug;

    private PageTitleIndex( final List< TitleEntry > entries ) {
        this.entries = List.copyOf( entries );
        final Map< String, List< String > > keys = new HashMap<>();
        for ( final TitleEntry e : entries ) {
            keys.put( e.slug(), normalizedKeys( e.slug(), e.phrases() ) );
        }
        this.normalizedKeysBySlug = Map.copyOf( keys );
    }

    public static PageTitleIndex of( final Collection< PageDescriptor > pages,
                                     final Map< String, List< String > > aliasesBySlug ) {
        final List< TitleEntry > out = new ArrayList<>( pages.size() );
        for ( final PageDescriptor p : pages ) {
            final Set< String > phrases = new LinkedHashSet<>();
            phrases.add( phraseOf( p.slug() ) );
            if ( p.title() != null && !p.title().isBlank() && !p.title().equals( p.slug() ) ) {
                phrases.add( p.title().trim() );
            }
            for ( final String a : aliasesBySlug.getOrDefault( p.slug(), List.of() ) ) {
                if ( a != null && !a.isBlank() ) {
                    phrases.add( a.trim() );
                }
            }
            final String title = p.title() == null || p.title().isBlank() || p.title().equals( p.slug() )
                    ? phraseOf( p.slug() ) : p.title().trim();
            out.add( new TitleEntry( p.slug(), title, List.copyOf( phrases ) ) );
        }
        return new PageTitleIndex( out );
    }

    /** De-CamelCased page name, e.g. {@code LowCostIndexFundInvesting} → {@code Low Cost Index Fund Investing}. */
    public static String phraseOf( final String slug ) {
        return TextUtil.beautifyString( slug );
    }

    @Override
    public List< TitleEntry > entries() {
        return entries;
    }

    @Override
    public List< String > rank( final Collection< String > names, final String query ) {
        final Comparator< String > natural = Comparator.naturalOrder();
        final String needle = normalize( query == null ? "" : query );
        if ( needle.isEmpty() ) {
            return names.stream().sorted( natural ).toList();
        }
        final Map< String, Integer > tiers = new HashMap<>();
        for ( final String name : names ) {
            final List< String > keys = normalizedKeysBySlug.getOrDefault( name,
                    normalizedKeys( name, List.of( phraseOf( name ) ) ) );
            int best = NO_MATCH;
            for ( final String key : keys ) {
                best = Math.min( best, tier( key, needle ) );
            }
            if ( best != NO_MATCH ) {
                tiers.put( name, best );
            }
        }
        return tiers.keySet().stream()
                .sorted( Comparator.comparingInt( ( String n ) -> tiers.get( n ) ).thenComparing( natural ) )
                .toList();
    }

    private static List< String > normalizedKeys( final String slug, final List< String > phrases ) {
        final Set< String > keys = new LinkedHashSet<>();
        keys.add( normalize( slug ) );
        phrases.forEach( p -> keys.add( normalize( p ) ) );
        return List.copyOf( keys );
    }

    private static String normalize( final String s ) {
        return s.toLowerCase( Locale.ROOT ).replaceAll( "\\s+", "" );
    }

    private static int tier( final String key, final String needle ) {
        if ( key.equals( needle ) ) return EXACT;
        if ( key.startsWith( needle ) ) return PREFIX;
        if ( key.contains( needle ) ) return SUBSTRING;
        return isSubsequence( needle, key ) ? SUBSEQUENCE : NO_MATCH;
    }

    private static boolean isSubsequence( final String needle, final String key ) {
        int i = 0;
        for ( int j = 0; j < key.length() && i < needle.length(); j++ ) {
            if ( key.charAt( j ) == needle.charAt( i ) ) {
                i++;
            }
        }
        return i == needle.length();
    }
}
```

(The `entriesExposeDeCamelCasedNameTitleAndAliases` expectation assumes `TextUtil.beautifyString("Kubernetes")` is `Kubernetes` and the de-CamelCased phrase of `LowCostIndexFundInvesting` is `Low Cost Index Fund Investing`; if `beautifyString` behaves differently for a case, fix the test expectation to `beautifyString`'s documented output, not the other way round.)

- [ ] **Step 4: Run GREEN** — `mvn test -pl wikantik-main -Dtest=PageTitleIndexTest -q`.

- [ ] **Step 5: Failing structural-index tests** — append to `DefaultStructuralIndexServiceTest`:

```java
    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_isEmptyUntilTheFirstRebuild_thenServesTitlesAndAliases() throws Exception {
        assertTrue( svc.titleLookup().isEmpty(), "warming: no lookup before the first rebuild" );
        final Page k = fakePage( "Kubernetes",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8K8\ntitle: Kubernetes\naliases: [k8s, kube]", "body" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( k ) );
        svc.rebuild();
        assertEquals( List.of( "Kubernetes" ),
                svc.titleLookup().orElseThrow().rank( List.of( "Kubernetes", "Other" ), "kube" ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_followsSavesAndDeletes() throws Exception {
        final Page a = fakePage( "AlphaPage", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Alpha", "b" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        svc.rebuild();
        assertTrue( svc.titleLookup().orElseThrow().rank( List.of( "AlphaPage" ), "zeta" ).isEmpty() );

        final Page a2 = fakePage( "AlphaPage",
                "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8A1\ntitle: Alpha\naliases: [zeta]", "b" );
        when( pageManager.getPage( "AlphaPage" ) ).thenReturn( a2 );
        svc.onPageSaved( "AlphaPage" );
        assertEquals( List.of( "AlphaPage" ), svc.titleLookup().orElseThrow().rank( List.of( "AlphaPage" ), "zeta" ) );

        svc.onPageDeleted( "AlphaPage" );
        assertTrue( svc.titleLookup().orElseThrow().entries().stream().noneMatch( e -> e.slug().equals( "AlphaPage" ) ) );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void titleLookup_renameUnderTheSameCanonicalIdDropsTheOldSlug() throws Exception {
        final Page oldPage = fakePage( "OldName", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8R1\naliases: [legacy]", "b" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( oldPage ) );
        svc.rebuild();
        final Page renamed = fakePage( "NewName", "canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8R1\naliases: [legacy]", "b" );
        when( pageManager.getPage( "NewName" ) ).thenReturn( renamed );
        svc.onPageSaved( "NewName" );
        final var slugs = svc.titleLookup().orElseThrow().entries().stream()
                .map( com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry::slug ).toList();
        assertTrue( slugs.contains( "NewName" ), slugs.toString() );
        assertFalse( slugs.contains( "OldName" ), slugs.toString() );
    }
```

Run `mvn test -pl wikantik-main -Dtest=DefaultStructuralIndexServiceTest -q` → RED (`titleLookup()` returns empty / does not exist).

- [ ] **Step 6: Implement in `StructuralIndexService` and `DefaultStructuralIndexService`**

`StructuralIndexService` (wikantik-api) — add:

```java
    /**
     * Title/alias lookup over the current projection; empty while the index is still warming
     * (no rebuild has completed yet) or for implementations that don't maintain one.
     */
    default java.util.Optional< PageTitleLookup > titleLookup() {
        return java.util.Optional.empty();
    }
```

`DefaultStructuralIndexService` — add fields:

```java
    /** canonical_id → aliases, copy-on-write; swapped together with {@link #current} under this object's lock. */
    private volatile Map< String, List< String > > aliasesByCanonicalId = Map.of();
    /** True once a rebuild has completed — before that the title lookup is "warming". */
    private volatile boolean titlesReady;
    private volatile CachedTitles cachedTitles;

    private record CachedTitles( StructuralProjection projection, Map< String, List< String > > aliases,
                                 PageTitleIndex index ) {}
```

In `rebuild()`: declare `final Map< String, List< String > > nextAliases = new HashMap<>();` before the page loop; inside the `try`, after computing `canonicalId`, add `nextAliases.put( canonicalId, aliasesOf( fm ) );`; after `current.set( projection );` add `this.aliasesByCanonicalId = Map.copyOf( nextAliases ); this.titlesReady = true;`.

In `applyIncrementalUpdate(...)`: after computing `canonicalId`, before `current.set(...)`:

```java
        final Map< String, List< String > > nextAliases = new HashMap<>( aliasesByCanonicalId );
        nextAliases.put( canonicalId, aliasesOf( fm ) );
        this.aliasesByCanonicalId = Map.copyOf( nextAliases );
```

In `applyIncrementalDelete(...)`: after `current.set(...)`:

```java
        final Map< String, List< String > > nextAliases = new HashMap<>( aliasesByCanonicalId );
        nextAliases.remove( canonicalId );
        this.aliasesByCanonicalId = Map.copyOf( nextAliases );
```

Add:

```java
    @Override
    public Optional< PageTitleLookup > titleLookup() {
        if ( !titlesReady ) {
            return Optional.empty();
        }
        final StructuralProjection proj = current.get();
        final Map< String, List< String > > aliases = aliasesByCanonicalId;
        final CachedTitles cached = cachedTitles;
        if ( cached != null && cached.projection() == proj && cached.aliases() == aliases ) {
            return Optional.of( cached.index() );
        }
        final Map< String, List< String > > aliasesBySlug = new HashMap<>();
        for ( final PageDescriptor d : proj.allPages() ) {
            final List< String > a = aliases.get( d.canonicalId() );
            if ( a != null && !a.isEmpty() ) {
                aliasesBySlug.put( d.slug(), a );
            }
        }
        final PageTitleIndex index = PageTitleIndex.of( proj.allPages(), aliasesBySlug );
        cachedTitles = new CachedTitles( proj, aliases, index );
        return Optional.of( index );
    }

    private static List< String > aliasesOf( final Map< String, Object > fm ) {
        return stringList( fm.get( "aliases" ) ).stream()
                .map( String::trim ).filter( s -> !s.isEmpty() ).toList();
    }
```

(`stringList` already accepts a scalar as a one-element list, so `aliases: k8s` still indexes; the validator separately warns `aliases.list`.) Add the imports `com.wikantik.api.pagegraph.PageTitleLookup`, `java.util.HashMap` if absent.

- [ ] **Step 7: GREEN** — `mvn test -pl wikantik-main -Dtest='DefaultStructuralIndexService*Test,PageTitleIndexTest' -q`.

- [ ] **Step 8: Failing REST ranking tests** — append to `PageNameQueryTest` (create the class in `wikantik-rest/src/test/java/com/wikantik/rest/` if it does not exist; `PageNameQuery` is package-private so the test lives in `com.wikantik.rest`):

```java
    @Test
    void rankUsesTheTitleLookupWhenPresent() {
        final com.wikantik.api.pagegraph.PageTitleLookup lookup = new com.wikantik.api.pagegraph.PageTitleLookup() {
            @Override public java.util.List< String > rank( final java.util.Collection< String > names, final String q ) {
                return java.util.List.of( "Zed", "Alpha" ); // deliberately not alphabetical
            }
            @Override public java.util.List< TitleEntry > entries() { return java.util.List.of(); }
        };
        assertEquals( java.util.List.of( "Zed", "Alpha" ),
                PageNameQuery.rank( java.util.List.of( "Alpha", "Zed", "Unmatched" ), s -> s, "x",
                        java.util.Optional.of( lookup ) ) );
    }

    @Test
    void rankFallsBackToNameSubstringWhileWarming() {
        assertEquals( java.util.List.of( "Index", "IndexFunds", "LowIndex" ),
                PageNameQuery.rank( java.util.List.of( "LowIndex", "Index", "IndexFunds", "Other" ), s -> s, "index",
                        java.util.Optional.empty() ) );
    }
```

and to `PageListResourceTest` (uses the class's existing request helper; the TestEngine wires the real structural index, so a page with an alias saved in `setUp` is searchable by it once the index has rebuilt):

```java
    @Test
    void qMatchesAFrontmatterAlias() throws Exception {
        engine.saveText( "RestListAliasPage", "---\naliases: [zebra crossing]\n---\nBody." );
        try {
            // name-only ranking cannot match "zebra"; the alias can
            final String json = doGetList( null, null, "zebra crossing" );
            final JsonObject obj = gson.fromJson( json, JsonObject.class );
            final java.util.List< String > names = new java.util.ArrayList<>();
            obj.getAsJsonArray( "pages" ).forEach( p -> names.add( p.getAsJsonObject().get( "name" ).getAsString() ) );
            assertTrue( names.contains( "RestListAliasPage" ), names.toString() );
        } finally {
            engine.deleteQuietly( "RestListAliasPage" );
        }
    }
```

(Read `doGetList`'s parameter order in the test class first and pass `q` in the right slot. If the TestEngine's structural index has not completed its first rebuild when this test runs, call `getSubsystems`-equivalent `engine.getManager( StructuralIndexService.class ).rebuild()` in the test before the request — tests may call `getManager`; only production code is frozen.)

- [ ] **Step 9: Implement REST** — `PageNameQuery`:

```java
    /**
     * Ranks items for {@code q}: through the structural index's title lookup when it is ready (name, title,
     * aliases, fuzzy), else by name substring ({@link #rankBySubstring}). Blank {@code q} → alphabetical.
     */
    static < T > List< T > rank( final Collection< T > items, final Function< T, String > nameOf, final String q,
                                 final java.util.Optional< com.wikantik.api.pagegraph.PageTitleLookup > lookup ) {
        if ( q == null || q.isBlank() || lookup.isEmpty() ) {
            return rankBySubstring( items, nameOf, q );
        }
        final java.util.Map< String, T > byName = new java.util.LinkedHashMap<>();
        items.forEach( item -> byName.putIfAbsent( nameOf.apply( item ), item ) );
        return lookup.get().rank( byName.keySet(), q ).stream()
                .map( byName::get ).filter( java.util.Objects::nonNull ).toList();
    }
```

`PageListResource.doGet` — replace the `PageNameQuery.rankBySubstring(...)` call with `PageNameQuery.rank( <same filtered stream list>, Page::getName, q, titleLookup() )` and add:

```java
    private java.util.Optional< com.wikantik.api.pagegraph.PageTitleLookup > titleLookup() {
        try {
            final com.wikantik.api.pagegraph.StructuralIndexService idx =
                    getSubsystems().pageGraph().structuralIndexService();
            return idx == null ? java.util.Optional.empty() : idx.titleLookup();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Title lookup unavailable; ranking by page name only: {}", e.getMessage(), e );
            return java.util.Optional.empty();
        }
    }
```

(Use the class's existing logger field name; add one following the module's `LogManager.getLogger( PageListResource.class )` pattern if absent.)

- [ ] **Step 10: GREEN** — `mvn test -pl wikantik-rest -Dtest='PageNameQueryTest,PageListResourceTest' -q`.

- [ ] **Step 11: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/pagegraph/PageTitleLookup.java \
  wikantik-api/src/main/java/com/wikantik/api/pagegraph/StructuralIndexService.java \
  wikantik-main/src/main/java/com/wikantik/pagegraph/spine/PageTitleIndex.java \
  wikantik-main/src/main/java/com/wikantik/pagegraph/spine/DefaultStructuralIndexService.java \
  wikantik-main/src/test/java/com/wikantik/pagegraph/spine/PageTitleIndexTest.java \
  wikantik-main/src/test/java/com/wikantik/pagegraph/spine/DefaultStructuralIndexServiceTest.java \
  wikantik-rest/src/main/java/com/wikantik/rest/PageNameQuery.java \
  wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageNameQueryTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageListResourceTest.java
git commit -m "feat(search): title/alias/fuzzy page-name ranking via a structural-index title lookup"
```

---

### Task 4: `GET /api/pages/{name}/preview`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/export/HeadingSlugs.java` (add `sectionBody`)
- Test: `wikantik-main/src/test/java/com/wikantik/export/HeadingSlugsTest.java`
- Create: `wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java`
- Test: `wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java`

**Interfaces:**
- Produces: `HeadingSlugs.sectionBody( String markdownBody, String slug )` → `Optional<String>` — the Markdown source between the heading whose view anchor is `slug` and the next heading of the same or higher level (subsections included).
- Produces: `PageExcerpts.excerpt( String markdownBody, int maxChars )` → plain text; `PageExcerpts.MAX_CHARS = 280`.
- Produces: `GET /api/pages/{name}/preview[?section=slug]` → `200 {name, title, type?, cluster?, summary?, excerpt, lastModified}`; `404 {"error":true,"status":404,"message":"Page not found"}` for missing **and** not-viewable pages (ruling R7). Header `Cache-Control: private, max-age=60`.
- Consumed by: Task 16 (`api.getPagePreview`).

- [ ] **Step 1: Failing `HeadingSlugsTest` additions**

```java
    @Test void sectionBodyReturnsTheSectionUpToTheNextSameLevelHeading() {
        final String body = "# Top\n\nintro\n\n## Setup\n\nInstall it.\n\n### Detail\n\nmore\n\n## Usage\n\nRun it.\n";
        assertEquals( "Install it.\n\n### Detail\n\nmore",
                HeadingSlugs.sectionBody( body, "setup" ).orElseThrow().trim() );
        assertEquals( "Run it.", HeadingSlugs.sectionBody( body, "usage" ).orElseThrow().trim() );
    }

    @Test void sectionBodyUsesTheViewsDuplicateNumbering() {
        final String body = "## Setup\n\nfirst\n\n## Setup\n\nsecond\n";
        assertEquals( "second", HeadingSlugs.sectionBody( body, "setup-2" ).orElseThrow().trim() );
    }

    @Test void sectionBodyIsEmptyForAnUnknownSlug() {
        assertTrue( HeadingSlugs.sectionBody( "## A\n\nx\n", "nope" ).isEmpty() );
    }
```

- [ ] **Step 2: Failing `PageExcerptsTest`**

```java
package com.wikantik.preview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageExcerptsTest {
    @Test void reducesMarkdownToPlainProse() {
        final String md = "# Title\n\nSee [the hub](IndexFundsHub) and **bold** text.\n\n```java\ncode();\n```\n\n- item one\n";
        assertEquals( "See the hub and bold text. item one", PageExcerpts.excerpt( md, 280 ) );
    }

    @Test void dropsAclAndPluginMarkup() {
        final String md = "[{ALLOW view Admin}]\n\nBefore [{TableOfContents}] after.\n";
        assertEquals( "Before after.", PageExcerpts.excerpt( md, 280 ) );
    }

    @Test void cutsAtAWordBoundaryWithAnEllipsis() {
        final String md = "alpha beta gamma delta epsilon";
        assertEquals( "alpha beta…", PageExcerpts.excerpt( md, 14 ) );
    }

    @Test void emptyBodyGivesEmptyExcerpt() {
        assertEquals( "", PageExcerpts.excerpt( "", 280 ) );
        assertEquals( "", PageExcerpts.excerpt( "## Only a heading\n", 280 ) );
    }
}
```

- [ ] **Step 3: RED** — `mvn test -pl wikantik-main -Dtest='HeadingSlugsTest,PageExcerptsTest' -q` → compile failure.

- [ ] **Step 4: Implement `HeadingSlugs.sectionBody`** — refactor the anchor computation out of `headingsBySlug` so both use it:

```java
    /** Each heading's view anchor, in document order (h2/h3 own their numbered slugs; others only a free base slug). */
    private static Map< Heading, String > anchors( final Document doc ) {
        final Map< String, Integer > seen = new HashMap<>();
        final Map< Heading, String > anchored = new IdentityHashMap<>();
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h && ( h.getLevel() == 2 || h.getLevel() == 3 ) ) {
                final String base = slug( headingText( h ) );
                final int count = seen.merge( base, 1, Integer::sum ) - 1;
                anchored.put( h, count == 0 ? base : base + "-" + ( count + 1 ) );
            }
        }
        final Set< String > owned = new HashSet<>( anchored.values() );
        final Map< Heading, String > out = new LinkedHashMap<>();
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h ) {
                final String key = anchored.get( h );
                if ( key != null ) {
                    out.put( h, key );
                } else if ( !owned.contains( slug( headingText( h ) ) ) ) {
                    out.put( h, slug( headingText( h ) ) );
                }
            }
        }
        return out;
    }
```

Rewrite `headingsBySlug` as `anchors( PARSER.parse( markdownBody ) ).forEach( ( h, key ) -> out.putIfAbsent( key, headingText( h ) ) )` (behaviour unchanged — the existing tests prove it), then add:

```java
    /**
     * The Markdown source of the section whose view anchor is {@code slug}: everything after that heading up to
     * the next heading of the same or a higher level (deeper subsections are included).
     */
    public static java.util.Optional< String > sectionBody( final String markdownBody, final String slug ) {
        final Document doc = PARSER.parse( markdownBody );
        final java.util.List< Heading > all = new java.util.ArrayList<>();
        Heading target = null;
        for ( final Map.Entry< Heading, String > e : anchors( doc ).entrySet() ) {
            if ( target == null && e.getValue().equals( slug ) ) {
                target = e.getKey();
            }
        }
        if ( target == null ) {
            return java.util.Optional.empty();
        }
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h ) {
                all.add( h );
            }
        }
        int end = markdownBody.length();
        for ( int i = all.indexOf( target ) + 1; i < all.size(); i++ ) {
            if ( all.get( i ).getLevel() <= target.getLevel() ) {
                end = all.get( i ).getStartOffset();
                break;
            }
        }
        return java.util.Optional.of( markdownBody.substring( target.getEndOffset(), end ) );
    }
```

- [ ] **Step 5: Implement `PageExcerpts`**

```java
package com.wikantik.preview;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;

import java.util.regex.Pattern;

/** Plain-text excerpts of a page body for link-preview cards. Never renders HTML. */
public final class PageExcerpts {
    public static final int MAX_CHARS = 280;
    private static final Parser PARSER = Parser.builder().build();
    /** {@code [{ALLOW …}]}, {@code [{DENY …}]} and every other {@code [{Plugin …}]} span. */
    private static final Pattern PLUGIN = Pattern.compile( "\\[\\{.*?}]", Pattern.DOTALL );
    private static final Pattern SPACES = Pattern.compile( "\\s+" );

    private PageExcerpts() {}

    public static String excerpt( final String markdownBody, final int maxChars ) {
        if ( markdownBody == null || markdownBody.isBlank() ) {
            return "";
        }
        final String masked = PLUGIN.matcher( markdownBody ).replaceAll( " " );
        final StringBuilder text = new StringBuilder();
        for ( final Node block : PARSER.parse( masked ).getChildren() ) {
            if ( block instanceof Heading || block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock
                    || block instanceof HtmlBlock || block instanceof ThematicBreak ) {
                continue;
            }
            for ( final Node img : block.getDescendants() ) {
                if ( img instanceof Image ) {
                    img.unlink();
                }
            }
            text.append( ' ' ).append( new TextCollectingVisitor().collectAndGetText( block ) );
        }
        return truncate( SPACES.matcher( text ).replaceAll( " " ).trim(), maxChars );
    }

    static String truncate( final String s, final int max ) {
        if ( s.length() <= max ) {
            return s;
        }
        int cut = s.lastIndexOf( ' ', max - 1 );
        if ( cut < max / 2 ) {
            cut = max - 1;
        }
        return s.substring( 0, cut ).trim() + "…";
    }
}
```

(Mutating `getDescendants()` while iterating can throw; if it does, collect the `Image` nodes into a list first and unlink them after the loop.)

- [ ] **Step 6: GREEN** — `mvn test -pl wikantik-main -Dtest='HeadingSlugsTest,PageExcerptsTest' -q`.

- [ ] **Step 7: Failing `PageResourceTest` additions** — add `"RestPreviewPage"` and `"RestPreviewBare"` to the `@AfterEach` `deleteQuietly(...)` list, then:

```java
    @Test
    void previewReturnsTitleTypeClusterSummaryAndExcerpt() throws Exception {
        engine.saveText( "RestPreviewPage", "---\ntitle: Preview Page\ntype: reference\ncluster: finance\n"
                + "summary: A short summary that is long enough to be a real summary of the page.\n---\n"
                + "# Preview Page\n\nFirst paragraph with [a link](Other).\n\n## Usage\n\nRun it.\n" );
        final JsonObject obj = gson.fromJson( doGet( "RestPreviewPage/preview" ), JsonObject.class );
        assertEquals( "RestPreviewPage", obj.get( "name" ).getAsString() );
        assertEquals( "Preview Page", obj.get( "title" ).getAsString() );
        assertEquals( "reference", obj.get( "type" ).getAsString() );
        assertEquals( "finance", obj.get( "cluster" ).getAsString() );
        assertTrue( obj.get( "summary" ).getAsString().startsWith( "A short summary" ) );
        assertEquals( "First paragraph with a link. Run it.", obj.get( "excerpt" ).getAsString() );
    }

    @Test
    void previewSectionParamExcerptsThatSection() throws Exception {
        engine.saveText( "RestPreviewPage", "# T\n\nIntro.\n\n## Usage\n\nRun it.\n" );
        final JsonObject obj = gson.fromJson(
                doGetWithParams( "RestPreviewPage/preview", Map.of( "section", "usage" ) ), JsonObject.class );
        assertEquals( "Run it.", obj.get( "excerpt" ).getAsString() );
    }

    @Test
    void previewTitleFallsBackToTheDeCamelCasedName() throws Exception {
        engine.saveText( "RestPreviewBare", "Just text." );
        final JsonObject obj = gson.fromJson( doGet( "RestPreviewBare/preview" ), JsonObject.class );
        assertEquals( "Rest Preview Bare", obj.get( "title" ).getAsString() );
        assertFalse( obj.has( "summary" ) );
    }

    @Test
    void previewOfAMissingPageAndOfAnUnviewablePageAreIndistinguishable() throws Exception {
        engine.saveText( "RestPreviewPage", "Secret." );
        final PageResource spy = Mockito.spy( servlet );
        Mockito.doReturn( false ).when( spy ).hasPagePermission( Mockito.any(), Mockito.eq( "RestPreviewPage" ),
                Mockito.eq( "view" ) );

        final HttpServletResponse hidden = HttpMockFactory.createHttpResponse();
        final StringWriter hiddenBody = new StringWriter();
        Mockito.doReturn( new PrintWriter( hiddenBody ) ).when( hidden ).getWriter();
        spy.doGet( createRequest( "RestPreviewPage/preview" ), hidden );

        final HttpServletResponse missing = HttpMockFactory.createHttpResponse();
        final StringWriter missingBody = new StringWriter();
        Mockito.doReturn( new PrintWriter( missingBody ) ).when( missing ).getWriter();
        servlet.doGet( createRequest( "RestPreviewNoSuchPage/preview" ), missing );

        Mockito.verify( hidden ).setStatus( 404 );
        Mockito.verify( missing ).setStatus( 404 );
        assertEquals( missingBody.toString(), hiddenBody.toString() );
        assertFalse( hiddenBody.toString().contains( "Secret" ) );
    }
```

(The class's `servlet` field must be visible to the spy — it is a static field of type `PageResource` initialised via `RestTestSupport.initServlet`.) RED: `mvn test -pl wikantik-rest -Dtest=PageResourceTest -q` → the `/preview` path is treated as a page name.

- [ ] **Step 8: Implement in `PageResource.doGet`** — directly after the `/similar` branch:

```java
        // GET /api/pages/{name}/preview — link-preview card data; missing and unviewable are the same 404
        if ( pathParam.endsWith( "/preview" ) ) {
            handlePreview( request, response, pathParam.substring( 0, pathParam.length() - "/preview".length() ) );
            return;
        }
```

and the handler:

```java
    private void handlePreview( final HttpServletRequest request, final HttpServletResponse response,
                                final String pageName ) throws IOException {
        final PageManager pm = getSubsystems().page().pages();
        final Page page = pm.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION );
        if ( page == null || !hasPagePermission( request, pageName, "view" ) ) {
            sendError( response, HttpServletResponse.SC_NOT_FOUND, "Page not found" );
            return;
        }
        final ParsedPage parsed = FrontmatterParser.parse( pm.getPureText( pageName, PageProvider.LATEST_VERSION ) );
        final Map< String, Object > fm = parsed.metadata();
        String body = parsed.body();
        final String section = request.getParameter( "section" );
        if ( section != null && !section.isBlank() ) {
            body = HeadingSlugs.sectionBody( body, section ).orElse( body );
        }
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "name", pageName );
        final Object title = fm.get( "title" );
        out.put( "title", title != null && !title.toString().isBlank()
                ? title.toString().trim() : TextUtil.beautifyString( pageName ) );
        putIfText( out, "type", fm.get( "type" ) );
        final Object cluster = fm.get( "cluster" );
        putIfText( out, "cluster", cluster instanceof List< ? > l && !l.isEmpty() ? l.get( 0 ) : cluster );
        putIfText( out, "summary", fm.get( "summary" ) );
        out.put( "excerpt", PageExcerpts.excerpt( body, PageExcerpts.MAX_CHARS ) );
        out.put( "lastModified", page.getLastModified() );
        response.setHeader( "Cache-Control", "private, max-age=60" );
        sendJson( response, out );
    }

    private static void putIfText( final Map< String, Object > out, final String key, final Object value ) {
        if ( value != null && !value.toString().isBlank() ) {
            out.put( key, value.toString().trim() );
        }
    }
```

Imports as needed: `com.wikantik.api.providers.PageProvider` (or wherever `LATEST_VERSION` already comes from in this file), `com.wikantik.api.frontmatter.{FrontmatterParser,ParsedPage}`, `com.wikantik.export.HeadingSlugs`, `com.wikantik.preview.PageExcerpts`, `com.wikantik.util.TextUtil`, `java.util.{LinkedHashMap,List,Map}`. `hasPagePermission` must be overridable for the spy test — it is `protected` in `RestServletBase`; do not make it final.

- [ ] **Step 9: GREEN** — `mvn test -pl wikantik-rest -Dtest=PageResourceTest -q`, plus `mvn test -pl wikantik-main -Dtest='HeadingSlugsTest,PageExcerptsTest' -q`.

- [ ] **Step 10: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/export/HeadingSlugs.java \
  wikantik-main/src/test/java/com/wikantik/export/HeadingSlugsTest.java \
  wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java \
  wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java \
  wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java
git commit -m "feat(rest): GET /api/pages/{name}/preview for link-preview cards"
```

---

### Task 5: Type-aware page templates + `GET /api/page-templates`

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/PageTemplates.java`
- Create: `wikantik-main/src/test/java/com/wikantik/frontmatter/schema/PageTemplatesValidityTest.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/PageTemplatesResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/PageTemplatesResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

**Interfaces:**
- Produces: `PageTemplates.all()` → `List<PageTemplates.Template>`; `record Template( String type, String label, String description, Map<String,Object> metadata, String body )`; `PageTemplates.forType( String )` → `Optional<Template>`. Body placeholders: `{{title}}` only (the client also substitutes `{{date}}` for forward compatibility). Metadata never contains `date` or `cluster` — the client adds them (as the modal does today).
- Produces: `GET /api/page-templates` → `{ "templates": [ {type, label, description, metadata, body} ] }`, `Cache-Control: public, max-age=300`.
- Consumed by: Task 12.

- [ ] **Step 1: Failing validity test** (wikantik-main, where the validator lives)

```java
package com.wikantik.frontmatter.schema;

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.PageTemplates;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PageTemplatesValidityTest {

    private final SchemaDrivenFrontmatterValidator validator =
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );

    @Test
    void everySchemaTypeHasExactlyOneTemplate() {
        final Set< String > schemaTypes = Set.copyOf(
                FrontmatterSchema.defaultSchema().field( "type" ).orElseThrow().canonicalValues() );
        final List< String > templateTypes = PageTemplates.all().stream().map( PageTemplates.Template::type ).toList();
        assertEquals( schemaTypes, Set.copyOf( templateTypes ) );
        assertEquals( templateTypes.size(), Set.copyOf( templateTypes ).size(), "no duplicate templates" );
    }

    @Test
    void everyTemplateValidatesWithNoErrorsAndNoWarnings() {
        for ( final PageTemplates.Template t : PageTemplates.all() ) {
            final Map< String, Object > m = new HashMap<>( t.metadata() );
            m.put( "date", "2026-09-30" );                    // what the client adds
            if ( "hub".equals( t.type() ) ) {
                m.put( "cluster", "example-cluster" );         // the modal requires one for hubs
            }
            final List< FieldViolation > vs = validator.validate( m, ValidationCtx.lenient() );
            assertTrue( vs.isEmpty(), t.type() + " → " + vs.stream().map( FieldViolation::code )
                    .collect( Collectors.joining( ", " ) ) );
        }
    }

    @Test
    void bodiesStartWithTheTitleHeadingAndUseOnlyKnownPlaceholders() {
        for ( final PageTemplates.Template t : PageTemplates.all() ) {
            assertTrue( t.body().startsWith( "# {{title}}\n" ), t.type() );
            assertFalse( t.body().replace( "{{title}}", "" ).contains( "{{" ), t.type() );
            assertFalse( t.metadata().containsKey( "date" ) || t.metadata().containsKey( "cluster" ), t.type() );
        }
    }
}
```

- [ ] **Step 2: RED** — `mvn -q -pl wikantik-api install -DskipTests` fails until `PageTemplates` exists → create it in Step 3 then run `mvn test -pl wikantik-main -Dtest=PageTemplatesValidityTest -q`. Capture the RED by first committing a stub `all()` returning `List.of()` locally (do not commit the stub) and showing `everySchemaTypeHasExactlyOneTemplate` fail.

- [ ] **Step 3: Implement `PageTemplates`**

```java
package com.wikantik.api.frontmatter.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in page templates, one per {@code type} in {@link FrontmatterSchema}. Versioned with the schema so a
 * template can never be invalid (pinned by {@code PageTemplatesValidityTest}). Bodies use the {@code {{title}}}
 * placeholder; the client fills it and adds {@code date} and {@code cluster} to the metadata.
 */
public final class PageTemplates {

    public record Template( String type, String label, String description,
                            Map< String, Object > metadata, String body ) {
        public Template {
            metadata = java.util.Collections.unmodifiableMap( new LinkedHashMap<>( metadata ) );
        }
    }

    private static final List< Template > ALL = List.of(
            new Template( "article", "Article", "A general page: an explanation, guide or essay.",
                    meta( "article" ), "# {{title}}\n\n" ),
            new Template( "reference", "Reference", "Facts to look up: definitions, specifications, lists.",
                    meta( "reference" ), "# {{title}}\n\n## Summary\n\n## Details\n\n## See also\n" ),
            new Template( "design", "Design", "A proposal or decision record for something to be built.",
                    meta( "design" ),
                    "# {{title}}\n\n## Context\n\n## Goals\n\n## Non-goals\n\n## Design\n\n"
                            + "## Alternatives considered\n\n## Status\n" ),
            new Template( "runbook", "Runbook", "A step-by-step procedure for an operational task.",
                    runbookMeta(), "# {{title}}\n\n## Notes\n" ),
            new Template( "hub", "Hub", "The entry page that declares and introduces a cluster.",
                    meta( "hub" ), "# {{title}}\n\n## Overview\n\n## Start here\n" ) );

    private PageTemplates() {}

    public static List< Template > all() {
        return ALL;
    }

    public static Optional< Template > forType( final String type ) {
        return ALL.stream().filter( t -> t.type().equals( type ) ).findFirst();
    }

    private static Map< String, Object > meta( final String type ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "type", type );
        m.put( "status", "active" );
        return m;
    }

    private static Map< String, Object > runbookMeta() {
        final Map< String, Object > m = meta( "runbook" );
        final Map< String, Object > block = new LinkedHashMap<>();
        block.put( "when_to_use", List.of( "Describe the situation that calls for this runbook." ) );
        block.put( "steps", List.of( "First step.", "Second step." ) );
        block.put( "pitfalls", List.of( "(none known)" ) );
        m.put( "runbook", block );
        return m;
    }
}
```

(Ruling R2: the runbook prompts are the minimum the runbook validator accepts.)

- [ ] **Step 4: GREEN** — `mvn -q -pl wikantik-api install -DskipTests && mvn test -pl wikantik-main -Dtest=PageTemplatesValidityTest -q`. If a template draws a warning, fix the template, not the test.

- [ ] **Step 5: Failing resource test** — model on `FrontmatterSchemaResourceTest`:

```java
package com.wikantik.rest;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PageTemplatesResourceTest {
    @Test
    @SuppressWarnings( "unchecked" )
    void payloadListsEveryTemplateWithItsFields() {
        final Map< String, Object > payload = PageTemplatesResource.templatesPayload();
        final List< Map< String, Object > > templates = ( List< Map< String, Object > > ) payload.get( "templates" );
        assertEquals( 5, templates.size() );
        final Map< String, Object > runbook = templates.stream()
                .filter( t -> "runbook".equals( t.get( "type" ) ) ).findFirst().orElseThrow();
        assertEquals( "Runbook", runbook.get( "label" ) );
        assertTrue( ( ( Map< String, Object > ) runbook.get( "metadata" ) ).containsKey( "runbook" ) );
        assertTrue( ( ( String ) runbook.get( "body" ) ).startsWith( "# {{title}}" ) );
    }
}
```

RED: `mvn test -pl wikantik-rest -Dtest=PageTemplatesResourceTest -q` → class missing.

- [ ] **Step 6: Implement `PageTemplatesResource`**

```java
package com.wikantik.rest;

import com.wikantik.api.frontmatter.schema.PageTemplates;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /api/page-templates} — the built-in per-type page templates. Read-only and cacheable. */
public class PageTemplatesResource extends RestServletBase {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        response.setHeader( "Cache-Control", "public, max-age=300" );
        sendJson( response, templatesPayload() );
    }

    static Map< String, Object > templatesPayload() {
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final PageTemplates.Template t : PageTemplates.all() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "type", t.type() );
            m.put( "label", t.label() );
            m.put( "description", t.description() );
            m.put( "metadata", t.metadata() );
            m.put( "body", t.body() );
            out.add( m );
        }
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "templates", out );
        return payload;
    }
}
```

`web.xml` — beside the `FrontmatterSchemaResource` blocks add:

```xml
    <servlet>
        <servlet-name>PageTemplatesResource</servlet-name>
        <servlet-class>com.wikantik.rest.PageTemplatesResource</servlet-class>
    </servlet>
```

```xml
    <servlet-mapping>
        <servlet-name>PageTemplatesResource</servlet-name>
        <url-pattern>/api/page-templates</url-pattern>
    </servlet-mapping>
```

- [ ] **Step 7: GREEN** — `mvn test -pl wikantik-rest -Dtest=PageTemplatesResourceTest -q`; `mvn test -pl wikantik-war -q -Dtest='*WebXml*,SecurityHeaderRegistrationTest'` (any web.xml-parsing test in that module must still pass; use `-Dsurefire.failIfNoSpecifiedTests=false`).

- [ ] **Step 8: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/frontmatter/schema/PageTemplates.java \
  wikantik-main/src/test/java/com/wikantik/frontmatter/schema/PageTemplatesValidityTest.java \
  wikantik-rest/src/main/java/com/wikantik/rest/PageTemplatesResource.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageTemplatesResourceTest.java \
  wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): built-in per-type page templates at GET /api/page-templates"
```

---

### Task 6: `MentionScanner` (outgoing unlinked mentions, pure)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java` (extract `structuralOptions()`)
- Create: `wikantik-main/src/main/java/com/wikantik/mentions/Mention.java`
- Create: `wikantik-main/src/main/java/com/wikantik/mentions/MentionScanner.java`
- Test: `wikantik-main/src/test/java/com/wikantik/mentions/MentionScannerTest.java`

**Interfaces:**
- Consumes: `PageTitleLookup.TitleEntry( slug, title, phrases )` (Task 3).
- Produces: `public record Mention( String target, String title, String phrase, int from, int to, int line, String context, int more )`.
- Produces: `MentionScanner.scan( String text, String selfPage, List<PageTitleLookup.TitleEntry> entries )` → `List<Mention>` — every match, document order (uncapped: the REST layer drops targets the caller can't view and only then caps at `MentionScanner.MAX_RESULTS = 50`, so a restricted page can never push a viewable one out). Offsets are UTF-16 code-unit indices into `text`; `line` is 1-based.
- Produces: `MarkdownDocument.structuralOptions()` → `MutableDataSet` with every context-free option and stateless extension `options(...)` uses (ruling R8); `options(...)` now starts from it.

- [ ] **Step 1: Failing tests** — `MentionScannerTest`:

```java
package com.wikantik.mentions;

import com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MentionScannerTest {

    private static final List< TitleEntry > ENTRIES = List.of(
            new TitleEntry( "IndexFundsHub", "Index Funds Hub", List.of( "Index Funds Hub", "index fund" ) ),
            new TitleEntry( "LowCostIndexFundInvesting", "Low-Cost Index Fund Investing",
                    List.of( "Low Cost Index Fund Investing", "Low-Cost Index Fund Investing" ) ),
            new TitleEntry( "ExpenseRatio", "Expense Ratio", List.of( "Expense Ratio" ) ),
            new TitleEntry( "Design", "Design", List.of( "Design" ) ),
            new TitleEntry( "Go", "Go", List.of( "Go" ) ),
            new TitleEntry( "SelfPage", "Self Page", List.of( "Self Page" ) ) );

    private static List< Mention > scan( final String text ) {
        return MentionScanner.scan( text, "SelfPage", ENTRIES );
    }

    @Test void findsAPhraseInProseWithExactOffsets() {
        final String text = "Buy a broad index fund today.\n";
        final Mention m = scan( text ).get( 0 );
        assertEquals( "IndexFundsHub", m.target() );
        assertEquals( "index fund", m.phrase() );
        assertEquals( "index fund", text.substring( m.from(), m.to() ) );
        assertEquals( 1, m.line() );
    }

    @Test void longestPhraseWins() {
        final List< Mention > ms = scan( "Read about low-cost index fund investing first.\n" );
        assertEquals( 1, ms.size() );
        assertEquals( "LowCostIndexFundInvesting", ms.get( 0 ).target() );
        assertEquals( "low-cost index fund investing", ms.get( 0 ).phrase() );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "## The index fund heading\n",
            "Use `index fund` in code.\n",
            "```\nindex fund\n```\n",
            "Already [an index fund](Somewhere) linked.\n",
            "See https://example.com/index-fund-guide now.\n",
            "Math $index fund$ here.\n",
            "![index fund](pic.png)\n",
            "Before [{Plugin text='index fund'}] after.\n",
            "<div>index fund</div>\n",
            "---\ntitle: index fund\n---\nbody\n" } )
    void ineligibleContextsAreSkipped( final String text ) {
        assertTrue( scan( text ).stream().noneMatch( m -> m.target().equals( "IndexFundsHub" ) ), text );
    }

    @Test void listItemsTableCellsAndBlockquotesAreEligible() {
        assertEquals( 3, scan( "- an index fund\n\n| a |\n|---|\n| expense ratio |\n\n> low-cost index fund investing\n" ).size() );
    }

    @Test void selfAndAlreadyLinkedTargetsAreExcluded() {
        assertTrue( scan( "This Self Page mentions itself.\n" ).isEmpty() );
        assertTrue( scan( "An index fund. Also [hub](IndexFundsHub).\n" ).isEmpty() );
    }

    @Test void commonWordsAndShortPhrasesAreIgnored() {
        assertTrue( scan( "The design is good. Go now.\n" ).isEmpty() );
    }

    @Test void onlyTheFirstOccurrencePerTargetIsReportedWithACountOfTheRest() {
        final List< Mention > ms = scan( "index fund one.\n\nindex fund two.\n\nindex fund three.\n" );
        assertEquals( 1, ms.size() );
        assertEquals( 1, ms.get( 0 ).line() );
        assertEquals( 2, ms.get( 0 ).more() );
    }

    @Test void offsetsAreUtf16CodeUnitsAcrossEmojiAndCrlf() {
        final String text = "🎉 Party\r\n\r\nThen an expense ratio 😀 matters.\r\n";
        final Mention m = scan( text ).get( 0 );
        assertEquals( "expense ratio", text.substring( m.from(), m.to() ) );
        assertEquals( 3, m.line() );
    }

    @Test void contextShowsTheSurroundingLine() {
        final Mention m = scan( "Some words before an expense ratio and after.\n" ).get( 0 );
        assertTrue( m.context().contains( "an expense ratio and" ), m.context() );
    }

    @Test void resultsAreInDocumentOrderAndUncapped() {
        final StringBuilder sb = new StringBuilder();
        final java.util.List< TitleEntry > many = new java.util.ArrayList<>();
        for ( int i = 0; i < 60; i++ ) {
            many.add( new TitleEntry( "Topic" + i, "Topic " + i, List.of( "zebra topic " + i ) ) );
            sb.append( "zebra topic " ).append( i ).append( ".\n\n" );
        }
        final List< Mention > ms = MentionScanner.scan( sb.toString(), "X", many );
        assertEquals( 60, ms.size(), "the REST layer caps after permission filtering, not the scanner" );
        for ( int i = 1; i < ms.size(); i++ ) {
            assertTrue( ms.get( i ).from() > ms.get( i - 1 ).from() );
        }
    }
}
```

RED: `mvn test -pl wikantik-main -Dtest=MentionScannerTest -q` → classes missing.

- [ ] **Step 2: Extract `MarkdownDocument.structuralOptions()`** — move every `options.set(...)` in `options(...)` except `HtmlRenderer.ESCAPE_HTML` and the `Parser.EXTENSIONS` list into:

```java
    /**
     * The context-free parser/renderer options and stateless extensions every Wikantik Markdown parse uses.
     * {@link #options} layers the context-bound extension and the HTML-escape policy on top; the mention scan
     * parses with this alone so its AST has the same node kinds as the rendered page.
     */
    public static MutableDataSet structuralOptions() {
        final MutableDataSet options = new MutableDataSet();
        options.setFrom( ParserEmulationProfile.COMMONMARK );
        // … the AttributesExtension / FootnoteExtension / all GitLabExtension settings, moved verbatim …
        options.set( Parser.EXTENSIONS, Arrays.asList( ATTRIBUTES_EXT, DEFINITION_EXT, FOOTNOTE_EXT,
                                                       GITLAB_EXT, TABLES_EXT, TOC_EXT ) );
        return options;
    }
```

and make `options(...)` start with `final MutableDataSet options = structuralOptions();`, set `ESCAPE_HTML`, and set `Parser.EXTENSIONS` to the list with `new MarkdownForWikantikExtension(...)` first followed by the same stateless extensions (unchanged order). Run `mvn test -pl wikantik-main -Dtest='Markdown*Test' -q` to prove rendering is unchanged before continuing.

- [ ] **Step 3: Implement `Mention` and `MentionScanner`**

```java
package com.wikantik.mentions;

/** One outgoing unlinked mention: the first eligible occurrence of a page's phrase in a draft. */
public record Mention( String target, String title, String phrase, int from, int to, int line,
                       String context, int more ) {}
```

```java
package com.wikantik.mentions;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInlineBase;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.gitlab.GitLabInlineMath;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.api.parser.MarkdownLinkScanner;
import com.wikantik.api.pagegraph.PageTitleLookup.TitleEntry;
import com.wikantik.markdown.extensions.math.InlineMathParser;
import com.wikantik.parser.markdown.MarkdownDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds phrases in a draft that name another page (its de-CamelCased name, title or an alias) but are not
 * linked. Only plain prose is eligible: text in paragraphs, list items, blockquotes and table cells, never in
 * headings, code, math, links, images, HTML, plugin markup, bare URLs or frontmatter. Matching is whole-word,
 * case-insensitive, over word tokens, with the longest phrase winning. Pure: no wiki state.
 */
public final class MentionScanner {

    public static final int MAX_RESULTS = 50;
    static final int MIN_PHRASE_CHARS = 4;
    private static final int CONTEXT_CHARS = 30;

    /** Single words too generic to suggest as links on their own. */
    static final Set< String > COMMON_WORDS = Set.of(
            "about", "api", "architecture", "change", "changes", "config", "configuration", "content", "data",
            "design", "editor", "example", "examples", "faq", "glossary", "guide", "help", "history", "home",
            "index", "install", "installation", "introduction", "issue", "issues", "links", "list", "lists", "main",
            "model", "models", "notes", "overview", "page", "pages", "performance", "plan", "plans", "problem",
            "process", "project", "projects", "reference", "release", "releases", "resources", "roadmap", "search",
            "security", "setup", "solution", "status", "summary", "system", "systems", "task", "tasks", "test",
            "testing", "tests", "tool", "tools", "update", "updates", "usage" );

    private static final Parser PARSER = Parser.builder( MarkdownDocument.structuralOptions() )
            .customInlineParserExtensionFactory( new InlineMathParser.Factory() )
            .build();
    private static final Pattern PLUGIN = Pattern.compile( "\\[\\{.*?}]", Pattern.DOTALL );
    private static final Pattern BARE_URL = Pattern.compile( "\\b(?:https?://|www\\.)\\S+" );
    private static final Pattern TOKEN = Pattern.compile( "[\\p{L}\\p{N}]+" );
    private static final Set< Class< ? extends Node > > INELIGIBLE = Set.of(
            Heading.class, Code.class, FencedCodeBlock.class, IndentedCodeBlock.class, HtmlBlock.class,
            Link.class, LinkRef.class, Image.class, ImageRef.class, AutoLink.class, MailLink.class,
            GitLabInlineMath.class );

    private MentionScanner() {}

    /** A phrase trie keyed by lowercase word tokens; terminals name the target page. */
    private static final class Trie {
        final Map< String, Trie > next = new HashMap<>();
        TitleEntry target;
    }

    public static List< Mention > scan( final String text, final String selfPage, final List< TitleEntry > entries ) {
        if ( text == null || text.isBlank() ) {
            return List.of();
        }
        final Set< String > linked = MarkdownLinkScanner.findLocalLinks( text ).stream()
                .map( s -> s.toLowerCase( Locale.ROOT ) ).collect( Collectors.toSet() );
        final Trie root = buildTrie( entries, selfPage, linked );
        final String masked = mask( text );

        final Map< String, Mention > firstByTarget = new LinkedHashMap<>();
        final Map< String, Integer > extra = new HashMap<>();
        for ( final Node n : PARSER.parse( masked ).getDescendants() ) {
            if ( n instanceof Text t && eligible( t ) ) {
                matchSegment( text, t.getStartOffset(), t.getEndOffset(), root, firstByTarget, extra );
            }
        }
        return firstByTarget.values().stream()
                .map( m -> new Mention( m.target(), m.title(), m.phrase(), m.from(), m.to(), m.line(), m.context(),
                        extra.getOrDefault( m.target(), 0 ) ) )
                .sorted( Comparator.comparingInt( Mention::from ) )
                .toList();
    }

    private static Trie buildTrie( final List< TitleEntry > entries, final String selfPage, final Set< String > linked ) {
        final Trie root = new Trie();
        final List< TitleEntry > sorted = entries.stream()
                .sorted( Comparator.comparing( TitleEntry::slug ) ).toList();  // deterministic winner on collisions
        for ( final TitleEntry e : sorted ) {
            if ( e.slug().equalsIgnoreCase( selfPage ) || linked.contains( e.slug().toLowerCase( Locale.ROOT ) ) ) {
                continue;
            }
            for ( final String phrase : e.phrases() ) {
                final List< String > tokens = tokens( phrase );
                if ( tokens.isEmpty() || phrase.trim().length() < MIN_PHRASE_CHARS
                        || ( tokens.size() == 1 && COMMON_WORDS.contains( tokens.get( 0 ) ) ) ) {
                    continue;
                }
                Trie node = root;
                for ( final String tok : tokens ) {
                    node = node.next.computeIfAbsent( tok, k -> new Trie() );
                }
                if ( node.target == null ) {
                    node.target = e;
                }
            }
        }
        return root;
    }

    /**
     * Replaces frontmatter, plugin spans and bare URLs with spaces (newlines kept) so they can't match, while
     * every offset and line number stays identical to {@code text}.
     */
    static String mask( final String text ) {
        final char[] chars = text.toCharArray();
        final int fmEnd = frontmatterEnd( text );
        for ( int i = 0; i < fmEnd; i++ ) {
            blank( chars, i );
        }
        for ( final Pattern p : List.of( PLUGIN, BARE_URL ) ) {
            final Matcher m = p.matcher( text );
            while ( m.find() ) {
                for ( int i = m.start(); i < m.end(); i++ ) {
                    blank( chars, i );
                }
            }
        }
        return new String( chars );
    }

    private static void blank( final char[] chars, final int i ) {
        if ( chars[ i ] != '\n' && chars[ i ] != '\r' ) {
            chars[ i ] = ' ';
        }
    }

    /** Offset just past a leading {@code ---} … {@code ---} frontmatter block, or 0 when there is none. */
    static int frontmatterEnd( final String text ) {
        if ( !text.startsWith( "---" ) ) {
            return 0;
        }
        final Matcher close = Pattern.compile( "\\r?\\n---[ \\t]*(\\r?\\n|$)" ).matcher( text );
        return close.find( 3 ) ? close.end() : 0;
    }

    private static boolean eligible( final Text t ) {
        boolean inProse = false;
        for ( Node p = t.getParent(); p != null; p = p.getParent() ) {
            if ( INELIGIBLE.contains( p.getClass() ) || p instanceof HtmlInlineBase ) {
                return false;
            }
            if ( p instanceof Paragraph || p instanceof TableCell ) {
                inProse = true;
            }
        }
        return inProse;
    }

    private static void matchSegment( final String text, final int start, final int end, final Trie root,
                                      final Map< String, Mention > firstByTarget, final Map< String, Integer > extra ) {
        final List< int[] > spans = new ArrayList<>();   // [from, to] per token
        final Matcher m = TOKEN.matcher( text ).region( start, end );
        while ( m.find() ) {
            spans.add( new int[]{ m.start(), m.end() } );
        }
        int i = 0;
        while ( i < spans.size() ) {
            Trie node = root;
            TitleEntry hit = null;
            int hitEnd = -1;
            for ( int j = i; j < spans.size(); j++ ) {
                node = node.next.get( text.substring( spans.get( j )[ 0 ], spans.get( j )[ 1 ] ).toLowerCase( Locale.ROOT ) );
                if ( node == null ) {
                    break;
                }
                if ( node.target != null ) {
                    hit = node.target;
                    hitEnd = j;
                }
            }
            if ( hit == null ) {
                i++;
                continue;
            }
            final int from = spans.get( i )[ 0 ];
            final int to = spans.get( hitEnd )[ 1 ];
            if ( firstByTarget.containsKey( hit.slug() ) ) {
                extra.merge( hit.slug(), 1, Integer::sum );
            } else {
                firstByTarget.put( hit.slug(), new Mention( hit.slug(), hit.title(), text.substring( from, to ),
                        from, to, lineOf( text, from ), context( text, from, to ), 0 ) );
            }
            i = hitEnd + 1;
        }
    }

    private static List< String > tokens( final String phrase ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = TOKEN.matcher( phrase );
        while ( m.find() ) {
            out.add( m.group().toLowerCase( Locale.ROOT ) );
        }
        return out;
    }

    private static int lineOf( final String text, final int offset ) {
        int line = 1;
        for ( int i = 0; i < offset; i++ ) {
            if ( text.charAt( i ) == '\n' ) {
                line++;
            }
        }
        return line;
    }

    private static String context( final String text, final int from, final int to ) {
        final int lineStart = text.lastIndexOf( '\n', from - 1 ) + 1;
        int lineEnd = text.indexOf( '\n', to );
        if ( lineEnd < 0 ) {
            lineEnd = text.length();
        }
        final int a = Math.max( lineStart, from - CONTEXT_CHARS );
        final int b = Math.min( lineEnd, to + CONTEXT_CHARS );
        return ( a > lineStart ? "…" : "" ) + text.substring( a, b ).strip() + ( b < lineEnd ? "…" : "" );
    }
}
```

Notes for the implementer:
- `Text#getStartOffset()/getEndOffset()` index into the parsed (masked) string, which has the same length and line structure as `text`, so slicing `text` with them is exact.
- If `TableCell` text is not a direct `Text` descendant in Flexmark 0.64.8 (e.g. wrapped in `Paragraph`), the `Paragraph` branch covers it — keep both.
- `INELIGIBLE` uses exact classes; if a test shows a subclass slipping through (e.g. a `Link` subtype), switch that entry to an `instanceof` check rather than widening the set.

- [ ] **Step 4: GREEN** — `mvn test -pl wikantik-main -Dtest='MentionScannerTest,Markdown*Test' -q`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java \
  wikantik-main/src/main/java/com/wikantik/mentions/Mention.java \
  wikantik-main/src/main/java/com/wikantik/mentions/MentionScanner.java \
  wikantik-main/src/test/java/com/wikantik/mentions/MentionScannerTest.java
git commit -m "feat(mentions): scan a draft for unlinked mentions of other pages"
```

---

### Task 7: `POST /api/mentions/scan` + ACL integration test (preview and mentions)

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/MentionScanResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/MentionScanResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/EditorWorkspaceAclIT.java`

**Interfaces:**
- Consumes: `MentionScanner.scan(...)`, `Mention`, `MentionScanner.MAX_RESULTS` (Task 6); `StructuralIndexService#titleLookup()` (Task 3).
- Produces: `POST /api/mentions/scan` body `{ "page": "<current page>", "text": "<draft>" }` →
  - `200 { "mentions": [ {target, title, phrase, from, to, line, context, more} ] }` — only targets the caller can view, at most 50, document order;
  - `400` missing/blank `text` or malformed JSON; `413` body over 1 MiB (`"Draft too large to scan (limit 1 MB)"`); `503` `"Title index is warming up"` while the structural index has not completed its first rebuild.
- **Ruling R11:** the spec's `503 {warming: true}` is delivered as the standard `sendError` body (`{error, status: 503, message}`) because every new error must go through `RestServletBase.sendError`; the client keys "warming" off status 503.
- Consumed by: Task 17 (`api.scanMentions`).

- [ ] **Step 1: Failing unit tests** — `MentionScanResourceTest` (spies stub the two collaborators so no structural index is needed):

```java
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.pagegraph.PageTitleLookup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MentionScanResourceTest {
    private static TestEngine engine;
    private static MentionScanResource servlet;
    private final Gson gson = new Gson();

    private static final PageTitleLookup LOOKUP = new PageTitleLookup() {
        @Override public List< String > rank( final Collection< String > names, final String q ) { return List.of(); }
        @Override public List< TitleEntry > entries() {
            return List.of( new TitleEntry( "ExpenseRatio", "Expense Ratio", List.of( "Expense Ratio" ) ),
                            new TitleEntry( "SecretTopic", "Secret Topic", List.of( "Secret Topic" ) ) );
        }
    };

    @BeforeAll static void start() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( MentionScanResource::new, engine );
    }

    @AfterAll static void stop() { engine.stop(); }

    private record Result( HttpServletResponse response, String body ) {}

    private Result post( final MentionScanResource target, final String json ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/mentions/scan" );
        Mockito.doReturn( new BufferedReader( new StringReader( json ) ) ).when( req ).getReader();
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();
        target.doPost( req, resp );
        return new Result( resp, sw.toString() );
    }

    private MentionScanResource ready( final Set< String > viewable ) {
        final MentionScanResource spy = Mockito.spy( servlet );
        Mockito.doReturn( Optional.of( LOOKUP ) ).when( spy ).titleLookup();
        Mockito.doAnswer( inv -> {
            final Collection< String > names = inv.getArgument( 1 );
            return new java.util.HashSet<>( names.stream().filter( viewable::contains ).toList() );
        } ).when( spy ).filterViewable( Mockito.any(), Mockito.any() );
        return spy;
    }

    @Test void returnsViewableMentionsOnly() throws Exception {
        final Result r = post( ready( Set.of( "ExpenseRatio" ) ),
                "{\"page\":\"Draft\",\"text\":\"The expense ratio and the secret topic.\"}" );
        final JsonObject obj = gson.fromJson( r.body(), JsonObject.class );
        assertEquals( 1, obj.getAsJsonArray( "mentions" ).size() );
        final JsonObject m = obj.getAsJsonArray( "mentions" ).get( 0 ).getAsJsonObject();
        assertEquals( "ExpenseRatio", m.get( "target" ).getAsString() );
        assertEquals( 4, m.get( "from" ).getAsInt() );
        assertEquals( 17, m.get( "to" ).getAsInt() );
        assertFalse( r.body().contains( "SecretTopic" ) );
    }

    @Test void warmingIndexAnswers503() throws Exception {
        final MentionScanResource spy = Mockito.spy( servlet );
        Mockito.doReturn( Optional.empty() ).when( spy ).titleLookup();
        Mockito.verify( post( spy, "{\"text\":\"x\"}" ).response() ).setStatus( 503 );
    }

    @Test void missingTextIs400() throws Exception {
        Mockito.verify( post( ready( Set.of() ), "{\"page\":\"P\"}" ).response() ).setStatus( 400 );
    }

    @Test void malformedJsonIs400() throws Exception {
        Mockito.verify( post( ready( Set.of() ), "{not json" ).response() ).setStatus( 400 );
    }

    @Test void oversizedBodyIs413() throws Exception {
        final String big = "{\"text\":\"" + "a".repeat( MentionScanResource.MAX_CHARS ) + "\"}";
        Mockito.verify( post( ready( Set.of() ), big ).response() ).setStatus( 413 );
    }
}
```

RED: `mvn test -pl wikantik-rest -Dtest=MentionScanResourceTest -q` → class missing.

- [ ] **Step 2: Implement `MentionScanResource`**

```java
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.mentions.Mention;
import com.wikantik.mentions.MentionScanner;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code POST /api/mentions/scan} — outgoing unlinked mentions in an editor draft: phrases naming another page
 * (name, title or alias) that the draft doesn't link yet. Only pages the caller can view are reported.
 */
public class MentionScanResource extends RestServletBase {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( MentionScanResource.class );

    /** 1 MiB of request characters. */
    static final int MAX_CHARS = 1 << 20;

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String raw = readBounded( request );
        if ( raw == null ) {
            sendError( response, 413, "Draft too large to scan (limit 1 MB)" );
            return;
        }
        final JsonObject body;
        try {
            body = JsonParser.parseString( raw ).getAsJsonObject();
        } catch ( final JsonParseException | IllegalStateException e ) {
            LOG.info( "Rejecting malformed mention-scan body: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Request body must be a JSON object" );
            return;
        }
        final String text = getJsonString( body, "text" );
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required" );
            return;
        }
        final Optional< PageTitleLookup > lookup = titleLookup();
        if ( lookup.isEmpty() ) {
            sendError( response, 503, "Title index is warming up" );
            return;
        }
        final String page = Optional.ofNullable( getJsonString( body, "page" ) ).orElse( "" );
        final List< Mention > all = MentionScanner.scan( text, page, lookup.get().entries() );
        final Set< String > viewable = filterViewable( request, all.stream().map( Mention::target ).distinct().toList() );
        final List< Map< String, Object > > out = all.stream()
                .filter( m -> viewable.contains( m.target() ) )
                .limit( MentionScanner.MAX_RESULTS )
                .map( MentionScanResource::toJson )
                .toList();
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "mentions", out );
        sendJson( response, payload );
    }

    /** Overridable for tests; production reads the structural index's title lookup. */
    protected Optional< PageTitleLookup > titleLookup() {
        try {
            final StructuralIndexService idx = getSubsystems().pageGraph().structuralIndexService();
            return idx == null ? Optional.empty() : idx.titleLookup();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Title lookup unavailable for mention scan: {}", e.getMessage(), e );
            return Optional.empty();
        }
    }

    /** The body as a string, or {@code null} when it exceeds {@link #MAX_CHARS}. */
    private static String readBounded( final HttpServletRequest request ) throws IOException {
        final BufferedReader reader = request.getReader();
        final StringBuilder sb = new StringBuilder();
        final char[] buf = new char[ 8192 ];
        int n;
        while ( ( n = reader.read( buf ) ) != -1 ) {
            sb.append( buf, 0, n );
            if ( sb.length() > MAX_CHARS ) {
                return null;
            }
        }
        return sb.toString();
    }

    private static Map< String, Object > toJson( final Mention m ) {
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "target", m.target() );
        o.put( "title", m.title() );
        o.put( "phrase", m.phrase() );
        o.put( "from", m.from() );
        o.put( "to", m.to() );
        o.put( "line", m.line() );
        o.put( "context", m.context() );
        o.put( "more", m.more() );
        return o;
    }
}
```

(`filterViewable` is `protected` in `RestServletBase`; the spy in the test stubs it. If it is declared `final`, remove `final` — it must remain overridable.)

`web.xml` — add a servlet block `MentionScanResource` → `com.wikantik.rest.MentionScanResource` and a mapping to `/api/mentions/scan`, beside the Task 5 blocks.

- [ ] **Step 3: GREEN** — `mvn test -pl wikantik-rest -Dtest=MentionScanResourceTest -q`.

- [ ] **Step 4: ACL integration test** — `EditorWorkspaceAclIT`, modelled on `WikiPageFormatAclIT` (copy its `@BeforeAll` client construction, `secureCookieOverHttp()`, `loginAsAdmin()`/`logoutAdmin()`, `put(...)` and `getAnonymous(...)` helpers verbatim, then add an anonymous POST helper):

```java
    private static HttpResponse< String > postAnonymous( final String path, final String json ) throws Exception {
        final HttpClient anon = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).build();
        return anon.send( HttpRequest.newBuilder( URI.create( baseUrl + path ) )
                        .header( "Content-Type", "application/json" )
                        .header( "Accept", "application/json" )
                        .POST( HttpRequest.BodyPublishers.ofString( json ) ).build(),
                HttpResponse.BodyHandlers.ofString() );
    }

    @Test
    void restrictedPagesStayInvisibleToPreviewAndMentionScan() throws Exception {
        loginAsAdmin();
        assertEquals( 200, put( "/api/pages/EwAclSecretTopic",
                "{\"content\":\"[{ALLOW view Admin}]\\n\\nSecret body EWSECRET.\",\"changeNote\":\"it\"}" ).statusCode() );
        assertEquals( 200, put( "/api/pages/EwAclPublicTopic",
                "{\"content\":\"Public body.\",\"changeNote\":\"it\"}" ).statusCode() );
        logoutAdmin();

        final HttpResponse< String > secret = getAnonymous( "/api/pages/EwAclSecretTopic/preview", "application/json" );
        final HttpResponse< String > missing = getAnonymous( "/api/pages/EwAclNoSuchTopic/preview", "application/json" );
        assertEquals( 404, secret.statusCode() );
        assertEquals( 404, missing.statusCode() );
        assertEquals( missing.body(), secret.body() );
        assertFalse( secret.body().contains( "EWSECRET" ) );
        assertEquals( 200, getAnonymous( "/api/pages/EwAclPublicTopic/preview", "application/json" ).statusCode() );

        final String draft = "{\"page\":\"Scratch\",\"text\":\"About the ew acl secret topic and the ew acl public topic.\"}";
        HttpResponse< String > scan = postAnonymous( "/api/mentions/scan", draft );
        for ( int i = 0; i < 30 && scan.statusCode() == 503; i++ ) {   // structural index still warming
            Thread.sleep( 1000 );
            scan = postAnonymous( "/api/mentions/scan", draft );
        }
        assertEquals( 200, scan.statusCode(), scan.body() );
        assertTrue( scan.body().contains( "EwAclPublicTopic" ), scan.body() );
        assertFalse( scan.body().contains( "EwAclSecretTopic" ), scan.body() );
    }
```

(If the CSRF filter rejects the anonymous POST, read `CsrfProtectionFilter.isRestApiEndpoint()` / its natural-protection condition and add exactly the header it requires — e.g. `X-Requested-With` — mirroring what the SPA's `request()` helper sends.)

- [ ] **Step 5: Run the IT** — `bin/run-tests.sh --module rest` if supported, else `mvn -q install -DskipTests -T 1C` followed by `mvn verify -pl wikantik-it-tests/wikantik-it-test-rest -Pintegration-tests -Dit.test=EditorWorkspaceAclIT -Dtest=ZZZ_NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false` through `bin/agent-build.sh start ewacl -- …` (poll with `bin/agent-build.sh status ewacl`). Expect PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/MentionScanResource.java \
  wikantik-rest/src/test/java/com/wikantik/rest/MentionScanResourceTest.java \
  wikantik-war/src/main/webapp/WEB-INF/web.xml \
  wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/EditorWorkspaceAclIT.java
git commit -m "feat(rest): POST /api/mentions/scan; ACL IT for preview and mention scan"
```

---

### Task 8: Callouts — server (Flexmark extension, sanitizer, shared fixture)

**Files:**
- Create: `wikantik-frontend/src/utils/__fixtures__/callouts.json`
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/callouts/CalloutTypes.java`
- Create: `.../callouts/CalloutBlock.java`, `.../callouts/CalloutTitle.java`, `.../callouts/CalloutPostProcessor.java`, `.../callouts/CalloutNodeRenderer.java`, `.../callouts/CalloutExtension.java`
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/callouts/CalloutExtensionTest.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java` (add `CALLOUT_EXT` to the stateless list in `structuralOptions()`)
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/WikantikHtmlSanitizer.java` (+ its test)

**Interfaces:**
- Produces: `CalloutTypes.styleOf( String rawType )` → one of `note, abstract, info, todo, tip, success, question, warning, failure, danger, bug, example, quote` (unknown → `note`); `CalloutTypes.defaultTitle( String rawType )` → first letter upper-cased, rest as written.
- Produces HTML (no fold marker):

```html
<div class="callout callout-warning" data-callout="warning">
<div class="callout-title"><span class="callout-icon" aria-hidden="true"></span><span class="callout-title-inner">Title</span></div>
<div class="callout-content">
…blocks…
</div>
</div>
```

  With `-`: outer `<details class="callout callout-…" data-callout="…">` and `<summary class="callout-title">…</summary>`; with `+`: `<details … open="">`. The title inner span holds the title's rendered inline Markdown.
- Produces: `wikantik-frontend/src/utils/__fixtures__/callouts.json` — the parity contract consumed by Task 9.

- [ ] **Step 1: Write the shared fixture** `callouts.json` (an array; `expected` lists the top-level callouts in order; `title` is text content; `titleTags` are the element tag names inside `.callout-title-inner`; `content` is the whitespace-collapsed text content of `.callout-content` *excluding* nested callouts; `children` are nested callouts):

```json
[
  { "name": "basic note", "markdown": "> [!note]\n> Body text.\n",
    "expected": [ { "tag": "div", "style": "note", "open": false, "title": "Note", "titleTags": [], "content": "Body text.", "children": [] } ] },
  { "name": "custom title with inline markdown", "markdown": "> [!warning] Watch **out**\n> Careful.\n",
    "expected": [ { "tag": "div", "style": "warning", "open": false, "title": "Watch out", "titleTags": ["strong"], "content": "Careful.", "children": [] } ] },
  { "name": "alias maps to its style", "markdown": "> [!faq] Questions\n> Q.\n",
    "expected": [ { "tag": "div", "style": "question", "open": false, "title": "Questions", "titleTags": [], "content": "Q.", "children": [] } ] },
  { "name": "unknown type renders as note with its own name", "markdown": "> [!recipe]\n> Mix.\n",
    "expected": [ { "tag": "div", "style": "note", "open": false, "title": "Recipe", "titleTags": [], "content": "Mix.", "children": [] } ] },
  { "name": "case-insensitive type keeps its written case in the title", "markdown": "> [!WARNING]\n> x\n",
    "expected": [ { "tag": "div", "style": "warning", "open": false, "title": "WARNING", "titleTags": [], "content": "x", "children": [] } ] },
  { "name": "collapsed fold", "markdown": "> [!tip]- Hidden\n> Inside.\n",
    "expected": [ { "tag": "details", "style": "tip", "open": false, "title": "Hidden", "titleTags": [], "content": "Inside.", "children": [] } ] },
  { "name": "expanded fold", "markdown": "> [!tip]+ Shown\n> Inside.\n",
    "expected": [ { "tag": "details", "style": "tip", "open": true, "title": "Shown", "titleTags": [], "content": "Inside.", "children": [] } ] },
  { "name": "title only", "markdown": "> [!info] Just a title\n",
    "expected": [ { "tag": "div", "style": "info", "open": false, "title": "Just a title", "titleTags": [], "content": "", "children": [] } ] },
  { "name": "multi-paragraph body", "markdown": "> [!example]\n> First.\n>\n> Second.\n",
    "expected": [ { "tag": "div", "style": "example", "open": false, "title": "Example", "titleTags": [], "content": "First. Second.", "children": [] } ] },
  { "name": "nested callout", "markdown": "> [!note] Outer\n> outer body\n>\n> > [!tip] Inner\n> > inner body\n",
    "expected": [ { "tag": "div", "style": "note", "open": false, "title": "Outer", "titleTags": [], "content": "outer body",
                    "children": [ { "tag": "div", "style": "tip", "open": false, "title": "Inner", "titleTags": [], "content": "inner body", "children": [] } ] } ] },
  { "name": "plain blockquote is untouched", "markdown": "> Just a quote.\n", "expected": [] },
  { "name": "empty marker is not a callout", "markdown": "> [!]\n> x\n", "expected": [] },
  { "name": "marker must open the blockquote", "markdown": "> intro\n> [!note]\n", "expected": [] }
]
```

- [ ] **Step 2: Failing `CalloutExtensionTest`** — renders with `MarkdownDocument.structuralOptions()` (no wiki context needed), checks every fixture case structurally from the AST and checks the HTML shape:

```java
package com.wikantik.markdown.extensions.callouts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.wikantik.parser.markdown.MarkdownDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CalloutExtensionTest {
    private static final MutableDataSet OPTIONS = MarkdownDocument.structuralOptions();
    private static final Parser PARSER = Parser.builder( OPTIONS ).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder( OPTIONS ).build();

    @Test
    void matchesTheSharedFrontendFixture() throws Exception {
        final JsonArray cases = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "callouts.json" ) ) ).getAsJsonArray();
        assertFalse( cases.isEmpty() );
        for ( final JsonElement e : cases ) {
            final JsonObject c = e.getAsJsonObject();
            final Document doc = PARSER.parse( c.get( "markdown" ).getAsString() );
            assertEquals( c.getAsJsonArray( "expected" ), describe( topLevel( doc ) ), c.get( "name" ).getAsString() );
        }
    }

    private static List< CalloutBlock > topLevel( final Node root ) {
        final List< CalloutBlock > out = new ArrayList<>();
        for ( final Node n : root.getDescendants() ) {
            if ( n instanceof CalloutBlock cb && nearestCallout( cb.getParent() ) == nearestCallout( root ) ) {
                out.add( cb );
            }
        }
        return out;
    }

    private static Node nearestCallout( final Node n ) {
        for ( Node p = n; p != null; p = p.getParent() ) {
            if ( p instanceof CalloutBlock ) return p;
        }
        return null;
    }

    private static JsonArray describe( final List< CalloutBlock > callouts ) {
        final JsonArray arr = new JsonArray();
        for ( final CalloutBlock cb : callouts ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "tag", cb.fold() == CalloutBlock.Fold.NONE ? "div" : "details" );
            o.addProperty( "style", cb.style() );
            o.addProperty( "open", cb.fold() == CalloutBlock.Fold.EXPANDED );
            o.addProperty( "title", cb.titleText() );
            final JsonArray tags = new JsonArray();
            cb.titleTagNames().forEach( tags::add );
            o.add( "titleTags", tags );
            o.addProperty( "content", cb.contentTextExcludingNested() );
            o.add( "children", describe( topLevel( cb ) ) );
            arr.add( o );
        }
        return arr;
    }

    @Test
    void rendersTheDocumentedHtmlShape() {
        final String html = RENDERER.render( PARSER.parse( "> [!warning] Watch **out**\n> Careful.\n" ) );
        assertTrue( html.contains( "<div class=\"callout callout-warning\" data-callout=\"warning\">" ), html );
        assertTrue( html.contains( "<div class=\"callout-title\"><span class=\"callout-icon\" aria-hidden=\"true\"></span>"
                + "<span class=\"callout-title-inner\">Watch <strong>out</strong></span></div>" ), html );
        assertTrue( html.contains( "<div class=\"callout-content\">" ) && html.contains( "<p>Careful.</p>" ), html );
        assertFalse( html.contains( "<blockquote" ), html );
    }

    @Test
    void foldedCalloutsUseDetailsAndSummary() {
        final String collapsed = RENDERER.render( PARSER.parse( "> [!tip]- Hidden\n> Inside.\n" ) );
        assertTrue( collapsed.contains( "<details class=\"callout callout-tip\" data-callout=\"tip\">" ), collapsed );
        assertTrue( collapsed.contains( "<summary class=\"callout-title\">" ), collapsed );
        final String expanded = RENDERER.render( PARSER.parse( "> [!tip]+ Shown\n> Inside.\n" ) );
        assertTrue( expanded.contains( "<details class=\"callout callout-tip\" data-callout=\"tip\" open=\"\">" ), expanded );
    }

    @Test
    void escapesTheTypeIntoTheAttribute() {
        final String html = RENDERER.render( PARSER.parse( "> [!no\"te]\n> x\n" ) );
        assertFalse( html.contains( "data-callout=\"no\"te\"" ), html );
    }

    @Test
    void typeVocabulary() {
        assertEquals( "question", CalloutTypes.styleOf( "FAQ" ) );
        assertEquals( "danger", CalloutTypes.styleOf( "error" ) );
        assertEquals( "note", CalloutTypes.styleOf( "recipe" ) );
        assertEquals( "Recipe", CalloutTypes.defaultTitle( "recipe" ) );
        assertEquals( "WARNING", CalloutTypes.defaultTitle( "WARNING" ) );
    }
}
```

(`TextCollectingVisitor` import is used by the helper methods on `CalloutBlock`; drop it from the test if unused.) RED: `mvn test -pl wikantik-main -Dtest=CalloutExtensionTest -q` → classes missing.

- [ ] **Step 3: Implement**

`CalloutTypes.java`:

```java
package com.wikantik.markdown.extensions.callouts;

import java.util.Locale;
import java.util.Map;

/** Obsidian callout types and aliases → the 13 visual styles. */
public final class CalloutTypes {
    private static final Map< String, String > STYLE = Map.ofEntries(
            Map.entry( "note", "note" ),
            Map.entry( "abstract", "abstract" ), Map.entry( "summary", "abstract" ), Map.entry( "tldr", "abstract" ),
            Map.entry( "info", "info" ),
            Map.entry( "todo", "todo" ),
            Map.entry( "tip", "tip" ), Map.entry( "hint", "tip" ), Map.entry( "important", "tip" ),
            Map.entry( "success", "success" ), Map.entry( "check", "success" ), Map.entry( "done", "success" ),
            Map.entry( "question", "question" ), Map.entry( "help", "question" ), Map.entry( "faq", "question" ),
            Map.entry( "warning", "warning" ), Map.entry( "caution", "warning" ), Map.entry( "attention", "warning" ),
            Map.entry( "failure", "failure" ), Map.entry( "fail", "failure" ), Map.entry( "missing", "failure" ),
            Map.entry( "danger", "danger" ), Map.entry( "error", "danger" ),
            Map.entry( "bug", "bug" ),
            Map.entry( "example", "example" ),
            Map.entry( "quote", "quote" ), Map.entry( "cite", "quote" ) );

    private CalloutTypes() {}

    public static String styleOf( final String rawType ) {
        return STYLE.getOrDefault( rawType.toLowerCase( Locale.ROOT ), "note" );
    }

    public static String defaultTitle( final String rawType ) {
        return rawType.isEmpty() ? "" : Character.toUpperCase( rawType.charAt( 0 ) ) + rawType.substring( 1 );
    }
}
```

`CalloutTitle.java` — an inline container node:

```java
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;

/** Holds the callout title's inline nodes (empty when the default title applies). */
public class CalloutTitle extends Node {
    @Override
    public BasedSequence[] getSegments() {
        return EMPTY_SEGMENTS;
    }
}
```

`CalloutBlock.java`:

```java
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.sequence.BasedSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A blockquote that opened with {@code [!type]}: first child is its {@link CalloutTitle}, the rest its body. */
public class CalloutBlock extends Block {
    public enum Fold { NONE, COLLAPSED, EXPANDED }

    private final String rawType;
    private final String style;
    private final Fold fold;

    public CalloutBlock( final BasedSequence chars, final String rawType, final Fold fold ) {
        super( chars );
        this.rawType = rawType;
        this.style = CalloutTypes.styleOf( rawType );
        this.fold = fold;
    }

    public String rawType() { return rawType; }
    public String style() { return style; }
    public Fold fold() { return fold; }

    public CalloutTitle title() {
        return ( CalloutTitle ) getFirstChild();
    }

    public String titleText() {
        final CalloutTitle t = title();
        final String text = t.hasChildren() ? new TextCollectingVisitor().collectAndGetText( t ).trim() : "";
        return text.isEmpty() ? CalloutTypes.defaultTitle( rawType ) : text;
    }

    /** Tag names the title's inline nodes render as (e.g. {@code strong}), for the parity fixture. */
    public List< String > titleTagNames() {
        final List< String > out = new ArrayList<>();
        for ( final Node n : title().getDescendants() ) {
            final String simple = n.getClass().getSimpleName();
            switch ( simple ) {
                case "StrongEmphasis" -> out.add( "strong" );
                case "Emphasis" -> out.add( "em" );
                case "Code" -> out.add( "code" );
                case "Link" -> out.add( "a" );
                default -> { }
            }
        }
        return out;
    }

    public String contentTextExcludingNested() {
        final StringBuilder sb = new StringBuilder();
        for ( Node c = title().getNext(); c != null; c = c.getNext() ) {
            if ( !( c instanceof CalloutBlock ) ) {
                sb.append( ' ' ).append( new TextCollectingVisitor().collectAndGetText( c ) );
            }
        }
        return sb.toString().replaceAll( "\\s+", " ").trim();
    }

    @Override
    public BasedSequence[] getSegments() {
        return EMPTY_SEGMENTS;
    }

    @Override
    public String toString() {
        return "CalloutBlock[" + style + "/" + rawType.toLowerCase( Locale.ROOT ) + "/" + fold + "]";
    }
}
```

`CalloutPostProcessor.java`:

```java
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites {@code > [!type][+|-] Title} blockquotes into {@link CalloutBlock}s. Never throws on odd input. */
public class CalloutPostProcessor extends NodePostProcessor {

    /** {@code [!type]} then an optional fold marker, then the optional title (rest of the first line). */
    static final Pattern MARKER = Pattern.compile( "^\\[!([A-Za-z][A-Za-z0-9_-]*)]([+-]?)[ \\t]*" );

    @Override
    public void process( final NodeTracker state, final Node node ) {
        if ( !( node instanceof BlockQuote bq ) || !( bq.getFirstChild() instanceof Paragraph p ) ) {
            return;
        }
        final String paragraph = p.getChars().toString();
        final Matcher m = MARKER.matcher( paragraph );
        if ( !m.find() ) {
            return;
        }
        final CalloutBlock.Fold fold = switch ( m.group( 2 ) ) {
            case "-" -> CalloutBlock.Fold.COLLAPSED;
            case "+" -> CalloutBlock.Fold.EXPANDED;
            default -> CalloutBlock.Fold.NONE;
        };
        final CalloutBlock callout = new CalloutBlock( bq.getChars(), m.group( 1 ), fold );
        final CalloutTitle title = new CalloutTitle();
        callout.appendChild( title );

        // Move the first line's inline nodes (minus the marker) into the title.
        final int titleStart = p.getStartOffset() + m.end();
        final List< Node > firstLine = new ArrayList<>();
        Node lineBreak = null;
        for ( Node c = p.getFirstChild(); c != null; c = c.getNext() ) {
            if ( c instanceof SoftLineBreak || c instanceof HardLineBreak ) {
                lineBreak = c;
                break;
            }
            firstLine.add( c );
        }
        for ( final Node c : firstLine ) {
            if ( c.getEndOffset() <= titleStart ) {
                c.unlink();
            } else if ( c.getStartOffset() < titleStart ) {
                if ( c instanceof Text t ) {
                    t.setChars( t.getChars().subSequence( titleStart - t.getStartOffset() ) );
                    title.appendChild( t );   // appendChild unlinks from the paragraph
                } else {
                    c.unlink();
                }
            } else {
                title.appendChild( c );
            }
        }
        if ( lineBreak != null ) {
            lineBreak.unlink();
        }
        if ( !p.hasChildren() ) {
            p.unlink();
        }

        // Body: everything left in the blockquote, in order.
        while ( bq.getFirstChild() != null ) {
            callout.appendChild( bq.getFirstChild() );
        }
        bq.insertBefore( callout );
        bq.unlink();
        state.nodeRemoved( bq );
        state.nodeAddedWithChildren( callout );
    }

    public static class Factory extends NodePostProcessorFactory {
        public Factory() {
            super( false );
            addNodes( BlockQuote.class );
        }

        @Override
        public NodePostProcessor apply( final Document document ) {
            return new CalloutPostProcessor();
        }
    }
}
```

`CalloutNodeRenderer.java`:

```java
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;

import java.util.Set;

/** Renders {@link CalloutBlock}/{@link CalloutTitle} as the shared callout HTML (see the plan / fixture). */
public class CalloutNodeRenderer implements NodeRenderer {

    @Override
    public Set< NodeRenderingHandler< ? > > getNodeRenderingHandlers() {
        return Set.of( new NodeRenderingHandler<>( CalloutBlock.class, this::render ),
                       new NodeRenderingHandler<>( CalloutTitle.class, ( n, ctx, html ) -> ctx.renderChildren( n ) ) );
    }

    private void render( final CalloutBlock node, final NodeRendererContext context, final HtmlWriter html ) {
        final boolean folded = node.fold() != CalloutBlock.Fold.NONE;
        final String tag = folded ? "details" : "div";
        html.line();
        html.attr( "class", "callout callout-" + node.style() ).attr( "data-callout", node.style() );
        if ( node.fold() == CalloutBlock.Fold.EXPANDED ) {
            html.attr( "open", "" );
        }
        html.withAttr().tag( tag ).line();
        final String titleTag = folded ? "summary" : "div";
        html.attr( "class", "callout-title" ).withAttr().tag( titleTag );
        html.raw( "<span class=\"callout-icon\" aria-hidden=\"true\"></span><span class=\"callout-title-inner\">" );
        if ( node.title().hasChildren() ) {
            context.renderChildren( node.title() );
        } else {
            html.text( CalloutTypes.defaultTitle( node.rawType() ) );
        }
        html.raw( "</span>" ).closeTag( titleTag ).line();
        html.attr( "class", "callout-content" ).withAttr().tag( "div" ).line();
        for ( Node c = node.title().getNext(); c != null; c = c.getNext() ) {
            context.render( c );
        }
        html.closeTag( "div" ).line();
        html.closeTag( tag ).line();
    }

    public static class Factory implements NodeRendererFactory {
        @Override
        public NodeRenderer apply( final DataHolder options ) {
            return new CalloutNodeRenderer();
        }
    }
}
```

`CalloutExtension.java`:

```java
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;

/** Obsidian-style {@code > [!type]} callouts. Stateless: safe to share like the other stock extensions. */
public final class CalloutExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

    private CalloutExtension() {}

    public static CalloutExtension create() {
        return new CalloutExtension();
    }

    @Override public void parserOptions( final MutableDataHolder options ) { }
    @Override public void rendererOptions( final MutableDataHolder options ) { }

    @Override
    public void extend( final Parser.Builder parserBuilder ) {
        parserBuilder.postProcessorFactory( new CalloutPostProcessor.Factory() );
    }

    @Override
    public void extend( final HtmlRenderer.Builder rendererBuilder, final String rendererType ) {
        rendererBuilder.nodeRendererFactory( new CalloutNodeRenderer.Factory() );
    }
}
```

`MarkdownDocument` — `private static final com.vladsch.flexmark.util.misc.Extension CALLOUT_EXT = CalloutExtension.create();` and append `CALLOUT_EXT` to the stateless list in **both** `structuralOptions()` and `options(...)`'s extension list.

The `escapesTheTypeIntoTheAttribute` case: `MARKER` only accepts `[A-Za-z][A-Za-z0-9_-]*`, so `[!no"te]` is not a callout at all — the test passes because no attribute is emitted; keep it as the guard that no future regex widening leaks a quote into an attribute.

Flexmark API notes: if the 0.64.8 signatures differ (e.g. `NodePostProcessorFactory#apply` vs `create`, `NodeRenderingHandler` constructor generics, `HtmlWriter#closeTag` return type for chaining), follow the compiler and the patterns in `com.wikantik.markdown.extensions.wikilinks` / `WikantikNodeRendererFactory` — the fixture is the contract, not these exact lines.

- [ ] **Step 4: Sanitizer** — failing test in the existing `WikantikHtmlSanitizerTest` (create beside the class if absent):

```java
    @Test
    void keepsCalloutMarkup() {
        final String html = "<details class=\"callout callout-tip\" data-callout=\"tip\" open=\"\">"
                + "<summary class=\"callout-title\">T</summary><div class=\"callout-content\"><p>x</p></div></details>";
        final String out = WikantikHtmlSanitizer.sanitize( html );
        assertTrue( out.contains( "data-callout=\"tip\"" ), out );
        assertTrue( out.contains( "open" ), out );
        assertTrue( out.contains( "class=\"callout callout-tip\"" ), out );
    }

    @Test
    void rejectsNonWordCalloutAttributeValues() {
        final String out = WikantikHtmlSanitizer.sanitize( "<div data-callout=\"x onload=alert(1)\">y</div>" );
        assertFalse( out.contains( "data-callout" ), out );
    }
```

Implement: in the policy builder add

```java
            .allowAttributes( "data-callout" ).matching( java.util.regex.Pattern.compile( "[a-z]+" ) ).onElements( "div", "details" )
            .allowAttributes( "open" ).onElements( "details" )
```

- [ ] **Step 5: GREEN** — `mvn test -pl wikantik-main -Dtest='CalloutExtensionTest,WikantikHtmlSanitizerTest,Markdown*Test,MentionScannerTest' -q`.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/utils/__fixtures__/callouts.json \
  wikantik-main/src/main/java/com/wikantik/markdown/extensions/callouts \
  wikantik-main/src/test/java/com/wikantik/markdown/extensions/callouts \
  wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java \
  wikantik-main/src/main/java/com/wikantik/parser/markdown/WikantikHtmlSanitizer.java \
  wikantik-main/src/test/java/com/wikantik/parser/markdown/WikantikHtmlSanitizerTest.java
git commit -m "feat(render): Obsidian callouts in the server Markdown renderer"
```

---

### Task 9: Callouts — editor preview and styling

**Files:**
- Create: `wikantik-frontend/src/utils/remarkCallouts.js`, `remarkCallouts.test.jsx`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (add the plugin to the preview's `remarkPlugins`)
- Modify: `wikantik-frontend/src/styles/article.css` (callout styles), `wikantik-frontend/src/styles/globals.css` (callout tokens)

**Interfaces:**
- Consumes: `src/utils/__fixtures__/callouts.json` (Task 8).
- Produces: default export `remarkCallouts` (no options) — rewrites mdast `blockquote` nodes into the Task 8 HTML structure via `data.hName`/`hProperties`.

- [ ] **Step 1: Failing test** — `remarkCallouts.test.jsx` renders each fixture case through `react-markdown` (the real preview path) and extracts the same structure from the DOM:

```jsx
import { render } from '@testing-library/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { describe, it, expect } from 'vitest';
import remarkCallouts from './remarkCallouts';
import cases from './__fixtures__/callouts.json';

function describeCallouts(container, parentCallout = null) {
  return [...container.querySelectorAll('.callout')]
    .filter((el) => (el.parentElement.closest('.callout') || null) === parentCallout)
    .map((el) => {
      const inner = el.querySelector(':scope > .callout-title .callout-title-inner');
      const content = el.querySelector(':scope > .callout-content');
      const clone = content.cloneNode(true);
      clone.querySelectorAll('.callout').forEach((n) => n.remove());
      return {
        tag: el.tagName.toLowerCase(),
        style: el.getAttribute('data-callout'),
        open: el.hasAttribute('open'),
        title: inner.textContent.trim(),
        titleTags: [...inner.querySelectorAll('*')].map((n) => n.tagName.toLowerCase()),
        content: clone.textContent.replace(/\s+/g, ' ').trim(),
        children: describeCallouts(content, el),
      };
    });
}

describe('remarkCallouts', () => {
  it.each(cases.map((c) => [c.name, c]))('matches the shared server fixture: %s', (_name, c) => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkCallouts]}>{c.markdown}</ReactMarkdown>,
    );
    expect(describeCallouts(container)).toEqual(c.expected);
  });

  it('emits the documented classes and the icon span', () => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[remarkCallouts]}>{'> [!danger] Stop\n> Now.\n'}</ReactMarkdown>,
    );
    const el = container.querySelector('div.callout.callout-danger');
    expect(el).not.toBeNull();
    expect(el.querySelector(':scope > .callout-title > .callout-icon').getAttribute('aria-hidden')).toBe('true');
    expect(container.querySelector('blockquote')).toBeNull();
  });
});
```

RED: `npx vitest run src/utils/remarkCallouts.test.jsx` → module missing.

- [ ] **Step 2: Implement `remarkCallouts.js`**

```js
// Obsidian callouts in the editor preview. Mirrors the server's CalloutExtension exactly — the shared
// fixture (__fixtures__/callouts.json) pins the two together.
import { visit } from 'unist-util-visit';

const STYLE = {
  note: 'note', abstract: 'abstract', summary: 'abstract', tldr: 'abstract', info: 'info', todo: 'todo',
  tip: 'tip', hint: 'tip', important: 'tip', success: 'success', check: 'success', done: 'success',
  question: 'question', help: 'question', faq: 'question', warning: 'warning', caution: 'warning',
  attention: 'warning', failure: 'failure', fail: 'failure', missing: 'failure', danger: 'danger',
  error: 'danger', bug: 'bug', example: 'example', quote: 'quote', cite: 'quote',
};
const MARKER = /^\[!([A-Za-z][A-Za-z0-9_-]*)\]([+-]?)[ \t]*/;

export function styleOf(rawType) {
  return STYLE[rawType.toLowerCase()] || 'note';
}

export function defaultTitle(rawType) {
  return rawType ? rawType[0].toUpperCase() + rawType.slice(1) : '';
}

/** Splits the paragraph's first line (after the marker) off as title inline nodes. */
function takeTitle(paragraph, markerLength) {
  const title = [];
  const children = paragraph.children;
  const first = children[0];
  first.value = first.value.slice(markerLength);
  while (children.length > 0) {
    const node = children[0];
    if (node.type === 'text' && node.value.includes('\n')) {
      const at = node.value.indexOf('\n');
      const before = node.value.slice(0, at);
      node.value = node.value.slice(at + 1);
      if (before) title.push({ type: 'text', value: before });
      if (!node.value) children.shift();
      return title;
    }
    if (node.type === 'break') { children.shift(); return title; }
    title.push(children.shift());
  }
  return title;
}

export default function remarkCallouts() {
  return (tree) => {
    visit(tree, 'blockquote', (node) => {
      const paragraph = node.children[0];
      if (!paragraph || paragraph.type !== 'paragraph') return;
      const first = paragraph.children[0];
      if (!first || first.type !== 'text') return;
      const m = MARKER.exec(first.value);
      if (!m) return;

      const [, rawType, foldMarker] = m;
      const style = styleOf(rawType);
      const folded = foldMarker === '+' || foldMarker === '-';
      let titleNodes = takeTitle(paragraph, m[0].length);
      titleNodes = titleNodes.filter((n) => !(n.type === 'text' && n.value.trim() === ''));
      if (titleNodes.length > 0 && titleNodes[titleNodes.length - 1].type === 'text') {
        titleNodes[titleNodes.length - 1].value = titleNodes[titleNodes.length - 1].value.replace(/\s+$/, '');
      }
      if (paragraph.children.length === 0) node.children.shift();

      const titleNode = {
        type: 'paragraph',
        data: { hName: folded ? 'summary' : 'div', hProperties: { className: ['callout-title'] } },
        children: [
          { type: 'emphasis', data: { hName: 'span', hProperties: { className: ['callout-icon'], ariaHidden: 'true' } }, children: [] },
          {
            type: 'emphasis',
            data: { hName: 'span', hProperties: { className: ['callout-title-inner'] } },
            children: titleNodes.length > 0 ? titleNodes : [{ type: 'text', value: defaultTitle(rawType) }],
          },
        ],
      };
      const contentNode = {
        type: 'blockquote',
        data: { hName: 'div', hProperties: { className: ['callout-content'] } },
        children: node.children,
      };
      node.data = {
        hName: folded ? 'details' : 'div',
        hProperties: {
          className: ['callout', `callout-${style}`],
          dataCallout: style,
          ...(foldMarker === '+' ? { open: true } : {}),
        },
      };
      node.children = [titleNode, contentNode];
    });
  };
}
```

`visit` revisits the (now re-typed) content `blockquote`; it has no marker paragraph so it is left alone, while a nested `> > [!tip]` blockquote inside it is converted — that is what makes nesting work. If a fixture case fails because the inner content wrapper itself matches (first child paragraph starting with `[!`), return `visit.SKIP`-free but check `node.data?.hName === 'div' && node.data.hProperties?.className?.includes('callout-content')` and skip it.

- [ ] **Step 3: Wire into the preview** — in `PageEditor.jsx` add `import remarkCallouts from '../utils/remarkCallouts';` and insert `remarkCallouts` after `remarkMath` in the preview's `remarkPlugins` array.

- [ ] **Step 4: Styles** — `globals.css`: under `:root` add

```css
  --callout-note: #4a76c9; --callout-abstract: #2aa6a6; --callout-info: #4a76c9; --callout-todo: #4a76c9;
  --callout-tip: #1f9e89; --callout-success: #3a9a4a; --callout-question: #c98a1f; --callout-warning: #d9822b;
  --callout-failure: #d14b4b; --callout-danger: #c93c3c; --callout-bug: #c93c3c; --callout-example: #8a5cc9;
  --callout-quote: #8a8f98; --callout-bg-mix: 8%;
```

and under `[data-theme="dark"]`

```css
  --callout-note: #7aa2f7; --callout-abstract: #5fd1d1; --callout-info: #7aa2f7; --callout-todo: #7aa2f7;
  --callout-tip: #4fd1b5; --callout-success: #6cc77a; --callout-question: #e6b450; --callout-warning: #f0a35e;
  --callout-failure: #f07a7a; --callout-danger: #f06c6c; --callout-bug: #f06c6c; --callout-example: #b392f0;
  --callout-quote: #a0a6b0; --callout-bg-mix: 14%;
```

`article.css` — append:

```css
.article-prose .callout {
  --c: var(--callout-note);
  border-left: 4px solid var(--c);
  background: color-mix(in srgb, var(--c) var(--callout-bg-mix), transparent);
  border-radius: var(--radius-md, 6px);
  padding: 0.6rem 0.9rem;
  margin: 1rem 0;
}
.article-prose .callout-abstract { --c: var(--callout-abstract); }
.article-prose .callout-info { --c: var(--callout-info); }
.article-prose .callout-todo { --c: var(--callout-todo); }
.article-prose .callout-tip { --c: var(--callout-tip); }
.article-prose .callout-success { --c: var(--callout-success); }
.article-prose .callout-question { --c: var(--callout-question); }
.article-prose .callout-warning { --c: var(--callout-warning); }
.article-prose .callout-failure { --c: var(--callout-failure); }
.article-prose .callout-danger { --c: var(--callout-danger); }
.article-prose .callout-bug { --c: var(--callout-bug); }
.article-prose .callout-example { --c: var(--callout-example); }
.article-prose .callout-quote { --c: var(--callout-quote); }
.article-prose .callout-title { display: flex; align-items: center; gap: 0.4rem; font-weight: 600; color: var(--c); }
.article-prose summary.callout-title { cursor: pointer; list-style: none; }
.article-prose summary.callout-title::-webkit-details-marker { display: none; }
.article-prose summary.callout-title::after { content: '▸'; margin-left: auto; transition: transform 0.15s; }
.article-prose details.callout[open] > summary.callout-title::after { transform: rotate(90deg); }
.article-prose .callout-icon::before { content: 'ℹ'; }
.article-prose .callout-tip .callout-icon::before,
.article-prose .callout-success .callout-icon::before { content: '✓'; }
.article-prose .callout-question .callout-icon::before { content: '?'; }
.article-prose .callout-warning .callout-icon::before,
.article-prose .callout-danger .callout-icon::before,
.article-prose .callout-failure .callout-icon::before,
.article-prose .callout-bug .callout-icon::before { content: '!'; }
.article-prose .callout-example .callout-icon::before { content: '☰'; }
.article-prose .callout-quote .callout-icon::before { content: '“'; }
.article-prose .callout-todo .callout-icon::before { content: '☐'; }
.article-prose .callout-abstract .callout-icon::before { content: '≡'; }
.article-prose .callout-content > :first-child { margin-top: 0.4rem; }
.article-prose .callout-content > :last-child { margin-bottom: 0; }
```

(Confirm `--radius-md` exists in `globals.css`; if the radius token has another name, use it.)

- [ ] **Step 5: GREEN** — `npx vitest run src/utils/remarkCallouts.test.jsx src/components/PageEditor.test.jsx`; `npm run lint`.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/utils/remarkCallouts.js wikantik-frontend/src/utils/remarkCallouts.test.jsx \
  wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/styles/article.css \
  wikantik-frontend/src/styles/globals.css
git commit -m "feat(editor): callouts in the editor preview, styled for light and dark themes"
```

---

### Task 10: In-app unsaved-changes guard

**Files:**
- Create: `wikantik-frontend/src/navigation/navigationGuard.js`, `navigationGuard.test.js`
- Create: `wikantik-frontend/src/navigation/NavigationGuardProvider.jsx`, `NavigationGuardProvider.test.jsx`
- Modify: `wikantik-frontend/src/App.jsx` (mount the provider)
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (register while dirty; Cancel uses the guard; delete the hand-rolled discard modal and `showDiscardConfirm`)
- Modify: `wikantik-frontend/src/components/PageEditor.test.jsx` (+ the other `PageEditor.*.test.jsx`): wrap `renderEditor` in the provider; update discard tests to the new dialog
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/StructuredFrontmatterEditorIT.java` (the "Discard" click → `[data-testid=guard-leave]`)

**Interfaces:**
- Produces: `interceptableHref( event, location = window.location, base = window.__WIKANTIK_BASE__ || '' )` → in-app path string (`/wiki/X?y#z`, basename stripped) or `null` when the click must not be intercepted.
- Produces: `<NavigationGuardProvider>` (inside the router); `useNavigationGuard( active: boolean )`; `useGuardedNavigate()` → `(to, options?) => void` (opens the dialog when any guard is active, else navigates; outside a provider it is plain `useNavigate()`).
- Dialog test ids: `guard-dialog`, `guard-stay`, `guard-leave`.
- Consumed by: Tasks 11, 12, 13 (all in-app navigation they trigger goes through `useGuardedNavigate`).

- [ ] **Step 1: Failing `navigationGuard.test.js`**

```js
import { describe, it, expect } from 'vitest';
import { interceptableHref } from './navigationGuard';

const loc = { href: 'http://w.test/edit/Page', origin: 'http://w.test', pathname: '/edit/Page', search: '' };

function clickOn(html, init = {}) {
  const host = document.createElement('div');
  host.innerHTML = html;
  const a = host.querySelector('a') || host.firstElementChild;
  return { target: a.querySelector('span') || a, button: 0, metaKey: false, ctrlKey: false, shiftKey: false,
           altKey: false, defaultPrevented: false, ...init };
}

describe('interceptableHref', () => {
  it.each([
    ['plain in-app link', '<a href="/wiki/Other">x</a>', {}, '/wiki/Other'],
    ['click on a child element of the link', '<a href="/wiki/Other"><span>x</span></a>', {}, '/wiki/Other'],
    ['keeps query and hash', '<a href="/search?q=a#r">x</a>', {}, '/search?q=a#r'],
    ['relative href resolves against the current page', '<a href="Other">x</a>', {}, '/edit/Other'],
    ['ctrl-click opens a tab', '<a href="/wiki/Other">x</a>', { ctrlKey: true }, null],
    ['cmd-click opens a tab', '<a href="/wiki/Other">x</a>', { metaKey: true }, null],
    ['shift-click', '<a href="/wiki/Other">x</a>', { shiftKey: true }, null],
    ['middle button', '<a href="/wiki/Other">x</a>', { button: 1 }, null],
    ['target=_blank', '<a href="/wiki/Other" target="_blank">x</a>', {}, null],
    ['download link', '<a href="/attach/Page/f.pdf" download>x</a>', {}, null],
    ['other origin', '<a href="https://example.com/">x</a>', {}, null],
    ['same-page hash jump', '<a href="#section">x</a>', {}, null],
    ['already handled', '<a href="/wiki/Other">x</a>', { defaultPrevented: true }, null],
    ['not a link', '<button>x</button>', {}, null],
  ])('%s', (_name, html, init, expected) => {
    expect(interceptableHref(clickOn(html, init), loc, '')).toBe(expected);
  });

  it('strips a non-root basename', () => {
    const based = { ...loc, href: 'http://w.test/wk/edit/Page', pathname: '/wk/edit/Page' };
    expect(interceptableHref(clickOn('<a href="/wk/wiki/Other">x</a>'), based, '/wk')).toBe('/wiki/Other');
  });
});
```

- [ ] **Step 2: Failing `NavigationGuardProvider.test.jsx`**

```jsx
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, Link, useLocation } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { NavigationGuardProvider, useNavigationGuard, useGuardedNavigate } from './NavigationGuardProvider';

function Where() { return <div data-testid="where">{useLocation().pathname}</div>; }

function Dirty({ dirty }) {
  useNavigationGuard(dirty);
  const go = useGuardedNavigate();
  return (
    <>
      <Link to="/wiki/B" data-testid="link">B</Link>
      <button type="button" onClick={() => go('/wiki/C')}>go</button>
    </>
  );
}

function setup(dirty) {
  return render(
    <MemoryRouter initialEntries={['/edit/A']}>
      <NavigationGuardProvider>
        <Routes><Route path="*" element={<><Dirty dirty={dirty} /><Where /></>} /></Routes>
      </NavigationGuardProvider>
    </MemoryRouter>,
  );
}

describe('NavigationGuardProvider', () => {
  it('lets links through when nothing is dirty', () => {
    setup(false);
    fireEvent.click(screen.getByTestId('link'));
    expect(screen.getByTestId('where').textContent).toBe('/wiki/B');
  });

  it('holds a link click behind the dialog while dirty; Stay keeps you here', () => {
    setup(true);
    fireEvent.click(screen.getByTestId('link'));
    expect(screen.getByTestId('guard-dialog')).toHaveTextContent(
      "You have unsaved changes. Your draft is kept in this browser but isn't saved to the wiki.");
    expect(screen.getByTestId('where').textContent).toBe('/edit/A');
    fireEvent.click(screen.getByTestId('guard-stay'));
    expect(screen.queryByTestId('guard-dialog')).toBeNull();
    expect(screen.getByTestId('where').textContent).toBe('/edit/A');
  });

  it('Leave without saving completes the navigation', () => {
    setup(true);
    fireEvent.click(screen.getByTestId('link'));
    fireEvent.click(screen.getByTestId('guard-leave'));
    expect(screen.getByTestId('where').textContent).toBe('/wiki/B');
  });

  it('guards programmatic navigation too', () => {
    setup(true);
    fireEvent.click(screen.getByText('go'));
    expect(screen.getByTestId('guard-dialog')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('guard-leave'));
    expect(screen.getByTestId('where').textContent).toBe('/wiki/C');
  });

  it('never intercepts a ctrl-click', () => {
    setup(true);
    fireEvent.click(screen.getByTestId('link'), { ctrlKey: true });
    expect(screen.queryByTestId('guard-dialog')).toBeNull();
  });
});
```

RED: `npx vitest run src/navigation` → modules missing.

- [ ] **Step 3: Implement `navigationGuard.js`**

```js
// Decides whether a document click is an in-app navigation the unsaved-changes guard should hold.
// Returns the router path to navigate to (basename stripped), or null to let the browser handle it.
export function interceptableHref(e, loc = window.location, base = window.__WIKANTIK_BASE__ || '') {
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return null;
  const anchor = e.target?.closest?.('a[href]');
  if (!anchor) return null;
  const target = anchor.getAttribute('target');
  if ((target && target !== '_self') || anchor.hasAttribute('download')) return null;
  const url = new URL(anchor.getAttribute('href'), loc.href);
  if (url.origin !== loc.origin) return null;
  if (url.pathname === loc.pathname && url.search === loc.search) return null; // same page (incl. #hash jumps)
  let path = url.pathname;
  if (base && base !== '/' && path.startsWith(base)) path = path.slice(base.length) || '/';
  return path + url.search + url.hash;
}
```

- [ ] **Step 4: Implement `NavigationGuardProvider.jsx`**

```jsx
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Modal from '../components/ui/Modal';
import { interceptableHref } from './navigationGuard';

const GuardContext = createContext(null);

export function NavigationGuardProvider({ children }) {
  const navigate = useNavigate();
  const active = useRef(0);
  const [pending, setPending] = useState(null); // { to, options }

  const register = useCallback(() => {
    active.current += 1;
    return () => { active.current -= 1; };
  }, []);

  const request = useCallback((to, options) => {
    if (active.current > 0) { setPending({ to, options }); return; }
    navigate(to, options);
  }, [navigate]);

  useEffect(() => {
    // Capture phase on document runs before React Router's <Link> handler, so cancelling here stops it.
    const onClick = (e) => {
      if (active.current === 0) return;
      const to = interceptableHref(e);
      if (!to) return;
      e.preventDefault();
      e.stopPropagation();
      setPending({ to });
    };
    document.addEventListener('click', onClick, true);
    return () => document.removeEventListener('click', onClick, true);
  }, []);

  const value = useMemo(() => ({ register, request }), [register, request]);
  const stay = () => setPending(null);
  const leave = () => {
    const p = pending;
    setPending(null);
    navigate(p.to, p.options);
  };

  return (
    <GuardContext.Provider value={value}>
      {children}
      {pending && (
        <Modal isOpen onClose={stay} labelledBy="guard-dialog-title" testId="guard-dialog">
          <h2 id="guard-dialog-title">Unsaved changes</h2>
          <p>You have unsaved changes. Your draft is kept in this browser but isn&apos;t saved to the wiki.</p>
          <div className="modal-actions">
            <button type="button" className="btn" data-testid="guard-stay" onClick={stay} autoFocus>Stay</button>
            <button type="button" className="btn btn-danger" data-testid="guard-leave" onClick={leave}>
              Leave without saving
            </button>
          </div>
        </Modal>
      )}
    </GuardContext.Provider>
  );
}

/** Holds in-app navigation behind the guard dialog while {@code active} is true. */
export function useNavigationGuard(active) {
  const ctx = useContext(GuardContext);
  useEffect(() => {
    if (!active || !ctx) return undefined;
    return ctx.register();
  }, [active, ctx]);
}

/** navigate() that asks first when a guard is active. Outside a provider it is plain navigate(). */
export function useGuardedNavigate() {
  const ctx = useContext(GuardContext);
  const navigate = useNavigate();
  return ctx ? ctx.request : navigate;
}
```

(Check `Modal`'s prop names — the survey reports `{ isOpen, onClose, children, labelledBy, className, testId, style }`; if `testId` lands on the overlay rather than the dialog, the `toHaveTextContent` assertion still holds because the text is inside it.)

- [ ] **Step 5: Mount and use** — `App.jsx`: wrap the existing `<div className="app-layout">…</div>` and the overlay mount in `<NavigationGuardProvider>` (inside `ToastProvider`). `PageEditor.jsx`:
  - `import { useNavigationGuard, useGuardedNavigate } from '../navigation/NavigationGuardProvider';`
  - after `isDirty` is computed: `useNavigationGuard(isDirty);` and `const guardedNavigate = useGuardedNavigate();`
  - `handleCancel` becomes `const handleCancel = () => guardedNavigate(`/wiki/${name}`);`
  - delete `showDiscardConfirm` state and the discard modal JSX (lines ~880–913).
  - In `PageEditor*.test.jsx`, wrap the router children of `renderEditor` in `<NavigationGuardProvider>`, and change discard tests to expect `guard-dialog` and click `guard-leave` / `guard-stay`.
  - `StructuredFrontmatterEditorIT`: replace `$$( "button" ).findBy( text( "Discard" ) )…click()` with `$( "[data-testid=guard-leave]" ).shouldBe( visible, ASYNC_WAIT ).click();` and update the two comments above it.

- [ ] **Step 6: GREEN** — `npx vitest run src/navigation src/components/PageEditor*.test.jsx src/App.test.jsx`; `npm run lint`.

- [ ] **Step 7: Commit**

```bash
git add wikantik-frontend/src/navigation wikantik-frontend/src/App.jsx \
  wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor*.test.jsx \
  wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/StructuredFrontmatterEditorIT.java
git commit -m "feat(editor): ask before in-app navigation discards an unsaved draft"
```

---

### Task 11: Command registry, global hotkeys, global commands

**Files:**
- Create: `wikantik-frontend/src/commands/registry.js`, `registry.test.js`
- Create: `wikantik-frontend/src/commands/useCommands.js` (`useRegisterCommands`, `useCommands`, `useRunCommand`), `useCommands.test.jsx`
- Create: `wikantik-frontend/src/commands/useGlobalCommands.js`, `useGlobalCommands.test.jsx`
- Modify: `wikantik-frontend/src/hooks/useGlobalHotkeys.js`, `useGlobalHotkeys.test.js`
- Modify: `wikantik-frontend/src/App.jsx` (overlay mode state; register global commands)

**Interfaces:**
- Command shape: `{ id: string, title: string, section: 'Navigate'|'Page'|'Editor'|'Insert'|'View', keys?: string /* display, e.g. 'Mod-B' */, slash?: boolean, slashLabel?: string, keywords?: string[], run: () => void|Promise<void> }`.
- Produces (`registry.js`): `registerCommands(list) → unregister()`, `getCommands() → Command[]` (stable snapshot), `subscribe(listener) → unsubscribe`, `getCommand(id)`, `runCommand(id, { onError })` → `Promise<boolean>` (catches, `console.warn('[commands] … failed', id, err)`, calls `onError(cmd, err)`, resolves `false`).
- Produces (`useCommands.js`): `useRegisterCommands(list, deps)`; `useCommands()` → current list (re-renders on change); `useRunCommand()` → `(id) => Promise<boolean>` that toasts `Command failed: {title}` on error.
- Produces: `useGlobalHotkeys({ onOpenOverlay })` — Mod-K and Mod-O call `onOpenOverlay('pages')`, Mod-P calls `onOpenOverlay('commands')`; events with `defaultPrevented === true` are ignored.
- Produces: `useGlobalCommands({ openOverlay, toggleSidebar })` registering: `go-to-page` (Go to page, keys `Mod-O`), `search-full-text` (Search full text → `/search`), `edit-page` (Edit this page; only while on `/wiki/:name`), `page-history` (Page history → `/diff/:name`; while on `/wiki/:name` or `/edit/:name`), `recent-changes` (Recent changes → `/wiki/RecentChanges`), `toggle-sidebar` (Toggle sidebar). All navigation through `useGuardedNavigate`.
- App state: `overlayMode` ∈ `null | 'pages' | 'commands'`, `openOverlay(mode)`; Task 13 mounts the overlay with it.

- [ ] **Step 1: Failing tests**

`registry.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { registerCommands, getCommands, getCommand, runCommand, subscribe, __resetRegistryForTest } from './registry';

describe('command registry', () => {
  beforeEach(() => __resetRegistryForTest());

  it('registers and unregisters a batch', () => {
    const off = registerCommands([{ id: 'a', title: 'A', run: () => {} }, { id: 'b', title: 'B', run: () => {} }]);
    expect(getCommands().map((c) => c.id)).toEqual(['a', 'b']);
    off();
    expect(getCommands()).toEqual([]);
  });

  it('a later registration of the same id wins, and the earlier unregister does not remove it', () => {
    const off1 = registerCommands([{ id: 'a', title: 'old', run: () => {} }]);
    registerCommands([{ id: 'a', title: 'new', run: () => {} }]);
    off1();
    expect(getCommand('a').title).toBe('new');
  });

  it('notifies subscribers and keeps a stable snapshot between changes', () => {
    const l = vi.fn();
    const unsub = subscribe(l);
    const before = getCommands();
    expect(getCommands()).toBe(before);
    registerCommands([{ id: 'x', title: 'X', run: () => {} }]);
    expect(l).toHaveBeenCalledTimes(1);
    unsub();
  });

  it('runCommand reports failures instead of throwing', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const onError = vi.fn();
    registerCommands([{ id: 'boom', title: 'Boom', run: () => { throw new Error('x'); } }]);
    await expect(runCommand('boom', { onError })).resolves.toBe(false);
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ id: 'boom' }), expect.any(Error));
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('runCommand resolves true on success and false for an unknown id', async () => {
    registerCommands([{ id: 'ok', title: 'OK', run: async () => {} }]);
    await expect(runCommand('ok')).resolves.toBe(true);
    await expect(runCommand('nope')).resolves.toBe(false);
  });
});
```

Replace the whole body of `useGlobalHotkeys.test.js` with:

```js
import { renderHook } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useGlobalHotkeys } from './useGlobalHotkeys';

const press = (init) => {
  const e = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  window.dispatchEvent(e);
  return e;
};

describe('useGlobalHotkeys', () => {
  it.each([
    [{ key: 'k', ctrlKey: true }, 'pages'],
    [{ key: 'k', metaKey: true }, 'pages'],
    [{ key: 'o', ctrlKey: true }, 'pages'],
    [{ key: 'p', metaKey: true }, 'commands'],
  ])('%o opens the overlay in %s mode', (init, mode) => {
    const onOpenOverlay = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    const e = press(init);
    expect(onOpenOverlay).toHaveBeenCalledWith(mode);
    expect(e.defaultPrevented).toBe(true);
  });

  it('ignores a key the editor already handled (Mod-K inserts a link in CodeMirror)', () => {
    const onOpenOverlay = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    const e = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true, cancelable: true });
    e.preventDefault();
    window.dispatchEvent(e);
    expect(onOpenOverlay).not.toHaveBeenCalled();
  });

  it('ignores unmodified keys and other letters', () => {
    const onOpenOverlay = vi.fn();
    renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    press({ key: 'k' });
    press({ key: 'x', ctrlKey: true });
    expect(onOpenOverlay).not.toHaveBeenCalled();
  });

  it('detaches on unmount', () => {
    const onOpenOverlay = vi.fn();
    const { unmount } = renderHook(() => useGlobalHotkeys({ onOpenOverlay }));
    unmount();
    press({ key: 'k', ctrlKey: true });
    expect(onOpenOverlay).not.toHaveBeenCalled();
  });
});
```

`useCommands.test.jsx`:

```jsx
import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { __resetRegistryForTest, getCommands } from './registry';

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() };
vi.mock('../hooks/useToast', () => ({ useToast: () => toast }));
import { useRegisterCommands, useCommands, useRunCommand } from './useCommands';

describe('useCommands', () => {
  beforeEach(() => { __resetRegistryForTest(); vi.clearAllMocks(); });

  it('registers while mounted and unregisters on unmount', () => {
    const { unmount } = renderHook(() => useRegisterCommands([{ id: 'x', title: 'X', run: () => {} }], []));
    expect(getCommands().map((c) => c.id)).toEqual(['x']);
    unmount();
    expect(getCommands()).toEqual([]);
  });

  it('useCommands re-renders when the registry changes', async () => {
    const { result } = renderHook(() => useCommands());
    expect(result.current).toEqual([]);
    renderHook(() => useRegisterCommands([{ id: 'y', title: 'Y', run: () => {} }], []));
    await waitFor(() => expect(result.current.map((c) => c.id)).toEqual(['y']));
  });

  it('useRunCommand toasts a failure with the command title', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderHook(() => useRegisterCommands([{ id: 'z', title: 'Zap', run: () => { throw new Error('no'); } }], []));
    const { result } = renderHook(() => useRunCommand());
    await act(async () => { await result.current('z'); });
    expect(toast.error).toHaveBeenCalledWith('Command failed: Zap');
  });
});
```

`useGlobalCommands.test.jsx` — render the hook inside `MemoryRouter initialEntries={['/wiki/Alpha']}` with `NavigationGuardProvider`, then assert `getCommands()` ids include `go-to-page, search-full-text, edit-page, page-history, recent-changes, toggle-sidebar`; that running `go-to-page` calls the `openOverlay` spy with `'pages'`; that running `edit-page` navigates to `/edit/Alpha` (observe via a `useLocation` probe); and that on `/search` the `edit-page` command is absent.

RED: `npx vitest run src/commands src/hooks/useGlobalHotkeys.test.js`.

- [ ] **Step 2: Implement `registry.js`**

```js
// Single command registry. Components register contextual commands while mounted; the overlay, slash menu,
// toolbar and key bindings all read from here so they can never drift apart.
const commands = new Map();
const listeners = new Set();
let snapshot = [];

function emit() {
  snapshot = [...commands.values()];
  listeners.forEach((l) => l());
}

export function registerCommands(list) {
  list.forEach((c) => commands.set(c.id, c));
  emit();
  return () => {
    let changed = false;
    list.forEach((c) => {
      if (commands.get(c.id) === c) { commands.delete(c.id); changed = true; }
    });
    if (changed) emit();
  };
}

export const getCommands = () => snapshot;
export const getCommand = (id) => commands.get(id);

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export async function runCommand(id, { onError } = {}) {
  const cmd = commands.get(id);
  if (!cmd) {
    console.warn('[commands] unknown command', id);
    return false;
  }
  try {
    await cmd.run();
    return true;
  } catch (err) {
    console.warn('[commands] command failed', id, err);
    onError?.(cmd, err);
    return false;
  }
}

export function __resetRegistryForTest() {
  commands.clear();
  emit();
}
```

`useCommands.js`:

```js
import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { registerCommands, getCommands, subscribe, runCommand } from './registry';
import { useToast } from '../hooks/useToast';

/** Registers {@code list} for as long as the calling component is mounted (re-registers when deps change). */
export function useRegisterCommands(list, deps) {
  // eslint-disable-next-line react-hooks/exhaustive-deps -- caller-supplied deps, like useMemo
  useEffect(() => registerCommands(list), deps);
}

export function useCommands() {
  return useSyncExternalStore(subscribe, getCommands, getCommands);
}

export function useRunCommand() {
  const toast = useToast();
  return useCallback((id) => runCommand(id, {
    onError: (cmd) => toast.error(`Command failed: ${cmd.title}`),
  }), [toast]);
}
```

`useGlobalHotkeys.js`:

```js
import { useEffect } from 'react';

const MODES = { k: 'pages', o: 'pages', p: 'commands' };

/**
 * App-wide shortcuts: Mod-K / Mod-O open the quick overlay on pages, Mod-P on commands. A keydown something
 * else already handled (e.g. CodeMirror's Mod-K "insert link") is ignored.
 */
export function useGlobalHotkeys({ onOpenOverlay } = {}) {
  useEffect(() => {
    const handler = (e) => {
      if (e.defaultPrevented || !(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      const mode = MODES[e.key?.toLowerCase()];
      if (!mode) return;
      e.preventDefault();
      onOpenOverlay?.(mode);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onOpenOverlay]);
}
```

`useGlobalCommands.js`:

```js
import { useMemo } from 'react';
import { matchPath, useLocation } from 'react-router-dom';
import { useRegisterCommands } from './useCommands';
import { useGuardedNavigate } from '../navigation/NavigationGuardProvider';

export function useGlobalCommands({ openOverlay, toggleSidebar }) {
  const { pathname } = useLocation();
  const go = useGuardedNavigate();
  const viewing = matchPath('/wiki/:name', pathname)?.params.name;
  const editing = matchPath('/edit/:name', pathname)?.params.name;
  const page = viewing || editing;

  const list = useMemo(() => {
    const cmds = [
      { id: 'go-to-page', title: 'Go to page', section: 'Navigate', keys: 'Mod-O', run: () => openOverlay('pages') },
      { id: 'search-full-text', title: 'Search full text', section: 'Navigate', run: () => go('/search') },
      { id: 'recent-changes', title: 'Recent changes', section: 'Navigate', run: () => go('/wiki/RecentChanges') },
      { id: 'toggle-sidebar', title: 'Toggle sidebar', section: 'View', run: () => toggleSidebar() },
    ];
    if (viewing) cmds.push({ id: 'edit-page', title: 'Edit this page', section: 'Page', run: () => go(`/edit/${viewing}`) });
    if (page) cmds.push({ id: 'page-history', title: 'Page history', section: 'Page', run: () => go(`/diff/${page}`) });
    return cmds;
  }, [openOverlay, toggleSidebar, go, viewing, page]);

  useRegisterCommands(list, [list]);
}
```

`App.jsx`: replace `searchOpen` state with `const [overlayMode, setOverlayMode] = useState(null); const openOverlay = useCallback((mode) => setOverlayMode(mode), []);`; `useGlobalHotkeys({ onOpenOverlay: openOverlay })`; `Sidebar`'s `onOpenSearch={() => openOverlay('pages')}`; call `useGlobalCommands({ openOverlay, toggleSidebar: () => setSidebarCollapsed((c) => !c) })` from a small child component rendered **inside** `NavigationGuardProvider` (the hook needs the guard context), e.g. `function GlobalCommands(props) { useGlobalCommands(props); return null; }`. Keep the existing `<SearchOverlay>` mount for now, driven by `overlayMode !== null` (`onClose={() => setOverlayMode(null)}`); Task 13 replaces it. Update `App.test.jsx` only where it asserted the old `onSearch` wiring.

- [ ] **Step 3: GREEN** — `npx vitest run src/commands src/hooks/useGlobalHotkeys.test.js src/App.test.jsx`; `npm run lint`.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/commands wikantik-frontend/src/hooks/useGlobalHotkeys.js \
  wikantik-frontend/src/hooks/useGlobalHotkeys.test.js wikantik-frontend/src/App.jsx wikantik-frontend/src/App.test.jsx
git commit -m "feat(frontend): command registry, Ctrl-K/O/P hotkeys that respect editor shortcuts"
```

---

### Task 12: Self-contained new-page dialog with templates (`NewPageProvider`)

**Files:**
- Create: `wikantik-frontend/src/utils/pageTemplates.js`, `pageTemplates.test.js`
- Create: `wikantik-frontend/src/newpage/NewPageProvider.jsx`, `NewPageProvider.test.jsx`
- Modify: `wikantik-frontend/src/components/NewArticleModal.jsx`, `NewArticleModal.test.jsx`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx` (+ test) — open via the provider; stop rendering the modal
- Modify: `wikantik-frontend/src/api/client.js` — `getPageTemplates`, `listClusters`
- Modify: `wikantik-frontend/src/App.jsx` — mount `NewPageProvider` inside `NavigationGuardProvider`

**Interfaces:**
- Consumes: `GET /api/page-templates` (Task 5); `GET /api/structure/clusters` (existing, `{clusters:[{name,…}]}` after envelope unwrap); `api.listPages({ names })`; `useGuardedNavigate` (Task 10); `useRegisterCommands` (Task 11).
- Produces: `api.getPageTemplates()` → `{ templates: [...] }`; `api.listClusters()` → `{ clusters: [...] }`.
- Produces: `fillTemplate(text, { title, date })`; `buildInitialPage({ template, title, type, cluster, today })` → `{ initialMetadata, initialContent }`.
- Produces: `<NewPageProvider>`; `useNewPage()` → `{ openNewPage(initialTitle?: string) }`; registers command `new-page` ("New page", section `Page`).
- `NewArticleModal` props become `{ isOpen, onClose, initialTitle = '' }`.
- Consumed by: Task 13 ("Create page" row).

- [ ] **Step 1: Failing `pageTemplates.test.js`**

```js
import { describe, it, expect } from 'vitest';
import { fillTemplate, buildInitialPage } from './pageTemplates';

describe('pageTemplates', () => {
  it('fills title and date placeholders everywhere', () => {
    expect(fillTemplate('# {{title}}\n{{date}} {{title}}', { title: 'T', date: '2026-09-30' }))
      .toBe('# T\n2026-09-30 T');
  });

  it('builds metadata from the template plus date and cluster', () => {
    const template = { type: 'runbook', metadata: { type: 'runbook', status: 'active', runbook: { steps: ['a', 'b'] } },
                       body: '# {{title}}\n\n## Notes\n' };
    const { initialMetadata, initialContent } = buildInitialPage({
      template, title: 'Restart X', type: 'runbook', cluster: 'ops', today: '2026-09-30' });
    expect(initialMetadata).toEqual({ type: 'runbook', status: 'active', runbook: { steps: ['a', 'b'] },
                                      date: '2026-09-30', cluster: 'ops' });
    expect(initialContent).toBe('# Restart X\n\n## Notes\n');
  });

  it('falls back to a bare title page without a template and omits a blank cluster', () => {
    const { initialMetadata, initialContent } = buildInitialPage({
      template: null, title: 'Plain', type: 'article', cluster: '  ', today: '2026-09-30' });
    expect(initialMetadata).toEqual({ type: 'article', status: 'active', date: '2026-09-30' });
    expect(initialContent).toBe('# Plain\n\n');
  });

  it('does not let the template mutate between uses', () => {
    const template = { type: 'article', metadata: { type: 'article', status: 'active' }, body: '# {{title}}\n\n' };
    buildInitialPage({ template, title: 'A', type: 'article', cluster: 'c', today: 'd' });
    expect(template.metadata).toEqual({ type: 'article', status: 'active' });
  });
});
```

- [ ] **Step 2: Failing `NewArticleModal.test.jsx`** — replace the file's api/router setup with: `vi.mock('../api/client', () => ({ api: { getPageTemplates: vi.fn(), listClusters: vi.fn(), listPages: vi.fn() } }))`; `const mockNavigate = vi.fn(); vi.mock('../navigation/NavigationGuardProvider', () => ({ useGuardedNavigate: () => mockNavigate }))`; templates fixture with all five types (hub/runbook/design/reference/article, bodies as in Task 5). Tests (keep the existing slug/title tests that still apply, rewriting their props to `{ isOpen, onClose, initialTitle }`):

```jsx
  it('offers all five types and previews the selected template', async () => {
    renderModal();
    for (const label of ['Article', 'Reference', 'Design', 'Runbook', 'Hub']) {
      expect(await screen.findByRole('button', { name: label })).toBeInTheDocument();
    }
    fireEvent.click(screen.getByRole('button', { name: 'Design' }));
    fireEvent.change(screen.getByLabelText(/title/i), { target: { value: 'New Engine' } });
    expect(screen.getByTestId('template-preview')).toHaveTextContent('# New Engine');
    expect(screen.getByTestId('template-preview')).toHaveTextContent('## Alternatives considered');
  });

  it('prefills the title it was opened with', async () => {
    renderModal({ initialTitle: 'Index Fund' });
    expect(await screen.findByDisplayValue('Index Fund')).toBeInTheDocument();
    expect(screen.getByDisplayValue('IndexFund')).toBeInTheDocument(); // derived slug
  });

  it('requires a cluster for a hub', async () => {
    renderModal({ initialTitle: 'Finance' });
    fireEvent.click(await screen.findByRole('button', { name: 'Hub' }));
    expect(screen.getByRole('button', { name: /create/i })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/cluster/i), { target: { value: 'finance' } });
    expect(screen.getByRole('button', { name: /create/i })).toBeEnabled();
  });

  it('navigates to the editor with the filled template', async () => {
    renderModal({ initialTitle: 'Restart X' });
    fireEvent.click(await screen.findByRole('button', { name: 'Runbook' }));
    fireEvent.click(screen.getByRole('button', { name: /create/i }));
    expect(mockNavigate).toHaveBeenCalledWith('/edit/RestartX', { state: {
      initialMetadata: expect.objectContaining({ type: 'runbook', runbook: expect.any(Object) }),
      initialContent: '# Restart X\n\n## Notes\n' } });
  });

  it('detects an existing page by asking the server, not a client-side page list', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'Existing' }] });
    renderModal({ initialTitle: 'Existing' });
    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    expect(api.listPages).toHaveBeenCalledWith({ names: ['Existing'], limit: 1 });
  });

  it('falls back and says so when templates cannot be loaded', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.getPageTemplates.mockRejectedValue(new Error('down'));
    renderModal({ initialTitle: 'Plain' });
    expect(await screen.findByText('Templates unavailable')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /create/i }));
    expect(mockNavigate).toHaveBeenCalledWith('/edit/Plain', { state: expect.objectContaining({ initialContent: '# Plain\n\n' }) });
  });
```

(Keep the duplicate-warning copy the existing modal uses; read it before writing the `already exists` matcher. `renderModal(props)` renders `<NewArticleModal isOpen onClose={vi.fn()} {...props} />`; `beforeEach` sets `api.getPageTemplates.mockResolvedValue({ templates: FIVE })`, `api.listClusters.mockResolvedValue({ clusters: [{ name: 'finance' }] })`, `api.listPages.mockResolvedValue({ pages: [] })`.)

`NewPageProvider.test.jsx`: a consumer button calling `openNewPage('From Overlay')` opens the modal with that title; the `new-page` command is registered while the provider is mounted (`getCommands()` contains it) and running it opens the modal empty.

RED: `npx vitest run src/utils/pageTemplates.test.js src/components/NewArticleModal.test.jsx src/newpage`.

- [ ] **Step 3: Implement**

`utils/pageTemplates.js`:

```js
export function fillTemplate(text, { title, date }) {
  return text.replaceAll('{{title}}', title).replaceAll('{{date}}', date);
}

export function buildInitialPage({ template, title, type, cluster, today }) {
  const initialMetadata = structuredClone(template?.metadata ?? { type, status: 'active' });
  initialMetadata.type = type;
  initialMetadata.date = today;
  if (cluster && cluster.trim()) initialMetadata.cluster = cluster.trim();
  const initialContent = fillTemplate(template?.body ?? '# {{title}}\n\n', { title, date: today });
  return { initialMetadata, initialContent };
}
```

`api/client.js`:

```js
  getPageTemplates: () => request('/api/page-templates'),
  listClusters: () => request('/api/structure/clusters'),
```

`NewArticleModal.jsx` — rewrite around these rules (keep its existing markup/classes for title, slug, cluster datalist and buttons):
- props `{ isOpen, onClose, initialTitle = '' }`; on open, reset state with `title = initialTitle`, `slug = titleToSlug(initialTitle)`, type `article`.
- on open: `api.getPageTemplates()` → `templates` (on failure: `console.warn('[new-page] templates unavailable', err)`, `templatesError = true`); `api.listClusters()` → `clusters.map(c => c.name)` for the datalist (on failure: warn, empty list).
- type buttons: one per template (`label`, `title={description}`), in server order; when templates failed, the five schema types `['article','reference','design','runbook','hub']` with capitalised labels.
- duplicate check: 300 ms after `slug` settles and `isValidSlug(slug)`, `api.listPages({ names: [slug], limit: 1 })`; `isDuplicate = pages.length > 0` (failure: warn, treat as not duplicate). Ignore stale responses (cancel flag in the effect cleanup).
- `<pre className="template-preview" data-testid="template-preview">{fillTemplate(template?.body ?? '# {{title}}\n\n', { title: title || 'Title', date: today })}</pre>`; `{templatesError && <p className="info-banner">Templates unavailable</p>}`.
- `isValid = isValidSlug(slug) && title.trim() && (articleType !== 'hub' || cluster.trim())`.
- submit: `const { initialMetadata, initialContent } = buildInitialPage({ template, title, type: articleType, cluster, today }); guardedNavigate('/edit/' + slug, { state: { initialMetadata, initialContent } }); onClose();` (duplicate → keep today's "Open Editor" behaviour: `guardedNavigate('/edit/' + slug)` with no state).

`newpage/NewPageProvider.jsx`:

```jsx
import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import NewArticleModal from '../components/NewArticleModal';
import { useRegisterCommands } from '../commands/useCommands';

const NewPageContext = createContext({ openNewPage: () => {} });

export function NewPageProvider({ children }) {
  const [state, setState] = useState(null); // { initialTitle }
  const openNewPage = useCallback((initialTitle = '') => setState({ initialTitle }), []);
  const commands = useMemo(() => [
    { id: 'new-page', title: 'New page', section: 'Page', run: () => openNewPage('') },
  ], [openNewPage]);
  useRegisterCommands(commands, [commands]);
  const value = useMemo(() => ({ openNewPage }), [openNewPage]);
  return (
    <NewPageContext.Provider value={value}>
      {children}
      {state && <NewArticleModal isOpen initialTitle={state.initialTitle} onClose={() => setState(null)} />}
    </NewPageContext.Provider>
  );
}

export const useNewPage = () => useContext(NewPageContext);
```

`Sidebar.jsx`: delete `newArticleOpen` state and the `<NewArticleModal …>` block; `onNewArticle={() => openNewPage()}` with `const { openNewPage } = useNewPage();`. Keep `existingPageNames`/`existingClusters` only where Sidebar itself still uses them (cluster listing); drop what becomes unused. Update `Sidebar.test.jsx` to assert the provider's `openNewPage` is called (mock `../newpage/NewPageProvider`).

`App.jsx`: wrap the layout in `<NewPageProvider>` inside `<NavigationGuardProvider>`.

- [ ] **Step 4: GREEN** — `npx vitest run src/utils/pageTemplates.test.js src/components/NewArticleModal.test.jsx src/newpage src/components/Sidebar.test.jsx src/App.test.jsx`; `npm run lint`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/pageTemplates.js wikantik-frontend/src/utils/pageTemplates.test.js \
  wikantik-frontend/src/newpage wikantik-frontend/src/components/NewArticleModal.jsx \
  wikantik-frontend/src/components/NewArticleModal.test.jsx wikantik-frontend/src/components/Sidebar.jsx \
  wikantik-frontend/src/components/Sidebar.test.jsx wikantik-frontend/src/api/client.js wikantik-frontend/src/App.jsx
git commit -m "feat(frontend): new-page dialog with per-type templates and a server-side duplicate check"
```

---

### Task 13: `QuickOverlay` — switcher, full-text, create, command mode

**Files:**
- Create: `wikantik-frontend/src/utils/fuzzy.js`, `fuzzy.test.js`
- Create: `wikantik-frontend/src/components/QuickOverlay.jsx`, `QuickOverlay.test.jsx`
- Delete: `wikantik-frontend/src/components/SearchOverlay.jsx`, `SearchOverlay.test.jsx` (`git rm`)
- Modify: `wikantik-frontend/src/App.jsx` (mount `QuickOverlay` with `overlayMode`)
- Modify: `wikantik-frontend/src/styles/globals.css` (section headers, kbd hints, error row)

**Interfaces:**
- Consumes: `api.listPages({ q, limit })` (Task 3 ranking), `api.search(q, limit, { typeahead: true, signal })`, `api.getRecentChanges(limit)` → `{ changes: [{ name, … }] }`, `useRecentlyViewed({ login, enabled })`, `useAuth()`, `useCommands()` / `useRunCommand()` (Task 11), `useGuardedNavigate()` (Task 10), `useNewPage()` (Task 12).
- Produces: `fuzzyRank(text, query)` → `0` prefix, `1` substring, `2` subsequence, `-1` no match (case-insensitive; whitespace ignored in the query).
- Produces: `<QuickOverlay mode="pages"|"commands" onClose />`. Test ids kept for existing ITs: container `search-overlay`, input `search-overlay-input`; rows `quick-row` with `data-kind` ∈ `page|fulltext|search|create|command|error` and `data-page-name` / `data-command-id`.

- [ ] **Step 1: Failing `fuzzy.test.js`**

```js
import { describe, it, expect } from 'vitest';
import { fuzzyRank } from './fuzzy';

describe('fuzzyRank', () => {
  it.each([
    ['Fold all headings', 'fold', 0],
    ['Unfold all headings', 'fold', 1],
    ['Insert table', 'itbl', 2],
    ['Insert table', 'zzz', -1],
    ['Toggle sidebar', 'TOGGLE side', 0],
    ['anything', '', 0],
  ])('%s / %s → %i', (text, q, rank) => expect(fuzzyRank(text, q)).toBe(rank));
});
```

- [ ] **Step 2: Failing `QuickOverlay.test.jsx`** — mocks: `../api/client` (`listPages`, `search`, `getRecentChanges`), `../hooks/useAuth` (`{ user: { loginPrincipal: 'me' } }`), `../hooks/useRecentlyViewed` (`{ items: [{ slug: 'RecentOne', title: 'Recent One' }], record: vi.fn() }`), `../navigation/NavigationGuardProvider` (`useGuardedNavigate: () => mockNavigate`), `../newpage/NewPageProvider` (`useNewPage: () => ({ openNewPage })`), `../hooks/useToast`. Commands come from the real registry (`registerCommands` in the test, `__resetRegistryForTest` in `beforeEach`). Use `vi.useFakeTimers({ shouldAdvanceTime: true })` and `await act(() => vi.advanceTimersByTimeAsync(300))` after typing.

```jsx
  it('shows recently viewed pages for an empty query', () => {
    renderOverlay();
    expect(screen.getByText('Recent One')).toBeInTheDocument();
  });

  it('lists ranked page matches, then full-text matches, then the search and create rows', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'IndexFundsHub' }, { name: 'LowCostIndexFundInvesting' }] });
    api.search.mockResolvedValue({ results: [{ name: 'BondLadders' }, { name: 'IndexFundsHub' }] });
    renderOverlay();
    type('index fund');
    await settle();
    const rows = screen.getAllByTestId('quick-row').map((r) => `${r.dataset.kind}:${r.dataset.pageName ?? ''}`);
    expect(rows).toEqual(['page:IndexFundsHub', 'page:LowCostIndexFundInvesting', 'fulltext:BondLadders',
                          'search:', 'create:']);
    expect(api.listPages).toHaveBeenCalledWith({ q: 'index fund', limit: 8 });
    expect(screen.getByText('Search full text for “index fund”')).toBeInTheDocument();
    expect(screen.getByText('Create page “index fund”')).toBeInTheDocument();
  });

  it('omits the create row when a page name matches exactly', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'IndexFund' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('IndexFund');
    await settle();
    expect(screen.queryByText(/Create page/)).toBeNull();
  });

  it('Enter opens the first page; Ctrl-Enter opens it in a new tab', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    api.listPages.mockResolvedValue({ pages: [{ name: 'Alpha' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('alp');
    await settle();
    fireEvent.keyDown(input(), { key: 'Enter', ctrlKey: true });
    expect(open).toHaveBeenCalledWith('/wiki/Alpha', '_blank', 'noopener');
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/Alpha');
    open.mockRestore();
  });

  it('arrow keys move the selection and Enter activates it', async () => {
    api.listPages.mockResolvedValue({ pages: [{ name: 'A1' }, { name: 'A2' }] });
    api.search.mockResolvedValue({ results: [] });
    renderOverlay();
    type('a');
    await settle();
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    fireEvent.keyDown(input(), { key: 'ArrowDown' });
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(mockNavigate).toHaveBeenCalledWith('/wiki/A2');
  });

  it('create row closes the overlay and opens the new-page dialog with the query', async () => {
    api.listPages.mockResolvedValue({ pages: [] });
    api.search.mockResolvedValue({ results: [] });
    const onClose = vi.fn();
    renderOverlay({ onClose });
    type('Brand New');
    await settle();
    fireEvent.click(screen.getByText('Create page “Brand New”'));
    expect(onClose).toHaveBeenCalled();
    expect(openNewPage).toHaveBeenCalledWith('Brand New');
  });

  it('shows "Page search failed" but keeps full-text results when the title search fails', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    api.listPages.mockRejectedValue(new Error('x'));
    api.search.mockResolvedValue({ results: [{ name: 'Still' }] });
    renderOverlay();
    type('st');
    await settle();
    expect(screen.getByText('Page search failed')).toBeInTheDocument();
    expect(screen.getByText('Still')).toBeInTheDocument();
  });

  it('command mode lists commands by fuzzy title and runs the chosen one after closing', async () => {
    const run = vi.fn();
    registerCommands([{ id: 'fold-all', title: 'Fold all headings', keys: 'Ctrl-Alt-[', run },
                      { id: 'unfold-all', title: 'Unfold all headings', run: vi.fn() }]);
    const onClose = vi.fn();
    renderOverlay({ mode: 'commands', onClose });
    expect(input().value).toBe('>');
    type('>fold');
    const ids = screen.getAllByTestId('quick-row').map((r) => r.dataset.commandId);
    expect(ids).toEqual(['fold-all', 'unfold-all']);
    expect(screen.getByText('Ctrl-Alt-[')).toBeInTheDocument();
    fireEvent.keyDown(input(), { key: 'Enter' });
    expect(onClose).toHaveBeenCalled();
    await waitFor(() => expect(run).toHaveBeenCalled());
  });

  it('typing ">" switches a page query into command mode', () => {
    registerCommands([{ id: 'x', title: 'Toggle sidebar', run: vi.fn() }]);
    renderOverlay();
    type('>tog');
    expect(screen.getAllByTestId('quick-row')[0].dataset.commandId).toBe('x');
    expect(api.listPages).not.toHaveBeenCalled();
  });

  it('Escape closes', () => {
    const onClose = vi.fn();
    renderOverlay({ onClose });
    fireEvent.keyDown(input(), { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
```

(Helpers in the test file: `renderOverlay(props)` → `render(<MemoryRouter><QuickOverlay mode="pages" onClose={vi.fn()} {...props} /></MemoryRouter>)`; `input = () => screen.getByTestId('search-overlay-input')`; `type = (v) => fireEvent.change(input(), { target: { value: v } })`; `settle = () => act(() => vi.advanceTimersByTimeAsync(300))`. Stale responses: the component must ignore a response for an older query — add a test that resolves an older `listPages` promise after a newer one and asserts the newer rows win.)

RED: `npx vitest run src/utils/fuzzy.test.js src/components/QuickOverlay.test.jsx`.

- [ ] **Step 3: Implement `fuzzy.js`**

```js
export function fuzzyRank(text, query) {
  const q = (query || '').toLowerCase().replace(/\s+/g, '');
  if (!q) return 0;
  const t = text.toLowerCase().replace(/\s+/g, '');
  if (t.startsWith(q)) return 0;
  if (t.includes(q)) return 1;
  let i = 0;
  for (const ch of t) { if (ch === q[i]) i += 1; if (i === q.length) return 2; }
  return -1;
}
```

- [ ] **Step 4: Implement `QuickOverlay.jsx`** (reuses the `search-overlay` / `search-dialog` / `search-input` / `search-result-item` CSS):

```jsx
import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../hooks/useAuth';
import { useRecentlyViewed } from '../hooks/useRecentlyViewed';
import { useCommands, useRunCommand } from '../commands/useCommands';
import { useGuardedNavigate } from '../navigation/NavigationGuardProvider';
import { useNewPage } from '../newpage/NewPageProvider';
import { fuzzyRank } from '../utils/fuzzy';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

export default function QuickOverlay({ mode = 'pages', onClose }) {
  const [query, setQuery] = useState(mode === 'commands' ? '>' : '');
  const [pages, setPages] = useState([]);
  const [pageError, setPageError] = useState(false);
  const [fullText, setFullText] = useState([]);
  const [recentChanges, setRecentChanges] = useState([]);
  const [focused, setFocused] = useState(0);
  const inputRef = useRef(null);
  const go = useGuardedNavigate();
  const { openNewPage } = useNewPage();
  const runCommand = useRunCommand();
  const commands = useCommands();
  const { user } = useAuth();
  const login = user?.loginPrincipal || null;
  const { items: recentlyViewed } = useRecentlyViewed({ login, enabled: !!login });

  const isCommand = query.startsWith('>');
  const term = (isCommand ? query.slice(1) : query).trim();

  useEffect(() => { inputRef.current?.focus(); }, []);

  useEffect(() => {
    if (login || isCommand) return undefined;
    let cancelled = false;
    api.getRecentChanges(8)
      .then((d) => { if (!cancelled) setRecentChanges((d?.changes || []).map((c) => c.name)); })
      .catch((err) => console.warn('[quick-overlay] recent changes unavailable', err?.message || err));
    return () => { cancelled = true; };
  }, [login, isCommand]);

  useEffect(() => {
    if (isCommand || !term) return undefined; // rows ignore pages when there is no term — no state reset needed
    let cancelled = false;
    const id = setTimeout(() => {
      api.listPages({ q: term, limit: 8 })
        .then((d) => { if (!cancelled) { setPages((d?.pages || []).map((p) => p.name)); setPageError(false); } })
        .catch((err) => {
          console.warn('[quick-overlay] page search failed', err?.message || err);
          if (!cancelled) { setPages([]); setPageError(true); }
        });
    }, 80);
    return () => { cancelled = true; clearTimeout(id); };
  }, [term, isCommand]);

  useEffect(() => {
    if (isCommand || !term) return undefined;
    const ctl = new AbortController();
    const id = setTimeout(() => {
      api.search(term, 8, { typeahead: true, signal: ctl.signal })
        .then((d) => setFullText((d?.results || []).map((r) => r.name)))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          console.warn('[quick-overlay] full-text search failed', err?.message || err);
          setFullText([]);
        });
    }, 200);
    return () => { ctl.abort(); clearTimeout(id); };
  }, [term, isCommand]);

  const rows = useMemo(() => {
    if (isCommand) {
      return commands
        .map((c) => ({ c, r: fuzzyRank(c.title, term) }))
        .filter((x) => x.r >= 0)
        .sort((a, b) => a.r - b.r || a.c.title.localeCompare(b.c.title))
        .map(({ c }) => ({ kind: 'command', key: `c:${c.id}`, command: c }));
    }
    if (!term) {
      const recent = login
        ? recentlyViewed.map((i) => ({ kind: 'page', key: `r:${i.slug}`, name: i.slug, label: i.title || i.slug }))
        : recentChanges.map((n) => ({ kind: 'page', key: `r:${n}`, name: n, label: n }));
      return recent;
    }
    const out = [];
    if (pageError) out.push({ kind: 'error', key: 'err', label: 'Page search failed' });
    pages.forEach((n) => out.push({ kind: 'page', key: `p:${n}`, name: n, label: n }));
    fullText.filter((n) => !pages.includes(n))
      .forEach((n) => out.push({ kind: 'fulltext', key: `f:${n}`, name: n, label: n }));
    out.push({ kind: 'search', key: 'search', label: `Search full text for “${term}”` });
    const exact = pages.some((n) => n.toLowerCase() === term.replace(/\s+/g, '').toLowerCase());
    if (!exact) out.push({ kind: 'create', key: 'create', label: `Create page “${term}”` });
    return out;
  }, [isCommand, commands, term, login, recentlyViewed, recentChanges, pages, fullText, pageError]);

  const selectable = rows.filter((r) => r.kind !== 'error');
  const current = selectable[Math.min(focused, selectable.length - 1)];

  const activate = (row, { newTab = false } = {}) => {
    if (!row) return;
    if (row.kind === 'page' || row.kind === 'fulltext') {
      if (newTab) { window.open(`${BASE}/wiki/${row.name}`, '_blank', 'noopener'); return; }
      onClose();
      go(`/wiki/${row.name}`);
    } else if (row.kind === 'search') {
      onClose();
      go(`/search?q=${encodeURIComponent(term)}`);
    } else if (row.kind === 'create') {
      onClose();
      openNewPage(term);
    } else if (row.kind === 'command') {
      onClose();
      runCommand(row.command.id);
    }
  };

  const onKeyDown = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); onClose(); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); setFocused((f) => Math.min(f + 1, selectable.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setFocused((f) => Math.max(f - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); activate(current, { newTab: e.ctrlKey || e.metaKey }); }
  };

  return (
    <div className="search-overlay" data-testid="search-overlay"
         onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="search-dialog quick-overlay" role="dialog" aria-label="Go to page or run a command">
        <input ref={inputRef} className="search-input" data-testid="search-overlay-input" type="text"
               placeholder="Go to page…  (type > for commands)" value={query}
               onChange={(e) => { setQuery(e.target.value); setFocused(0); }} onKeyDown={onKeyDown}
               aria-controls="quick-overlay-rows" />
        <div className="search-results" id="quick-overlay-rows" role="listbox">
          {rows.map((row) => (
            row.kind === 'error'
              ? <div key={row.key} className="search-empty quick-row-error" data-testid="quick-row" data-kind="error">{row.label}</div>
              : (
                <button key={row.key} type="button" role="option"
                        aria-selected={row === current}
                        className={`search-result-item quick-row-${row.kind}${row === current ? ' focused' : ''}`}
                        data-testid="quick-row" data-kind={row.kind}
                        data-page-name={row.name} data-command-id={row.command?.id}
                        onMouseEnter={() => setFocused(selectable.indexOf(row))}
                        onClick={() => activate(row)}>
                  <span>{row.kind === 'command' ? row.command.title : row.label}</span>
                  {row.command?.keys && <kbd className="search-view-all-kbd">{row.command.keys}</kbd>}
                </button>
              )
          ))}
        </div>
      </div>
    </div>
  );
}
```

`data-page-name` must be absent (not `"undefined"`) on non-page rows — React omits `undefined` attributes, which is what the test's `?? ''` relies on.

`App.jsx`: replace the `SearchOverlay` import/mount with `{overlayMode && <QuickOverlay mode={overlayMode} onClose={() => setOverlayMode(null)} />}` placed inside `NewPageProvider` (it uses `useNewPage`). `git rm` `SearchOverlay.jsx` and `SearchOverlay.test.jsx`. Any remaining import of `SearchOverlay` (grep `-a`) is updated.

`globals.css` — append:

```css
.quick-overlay .quick-row-search, .quick-overlay .quick-row-create { color: var(--text-secondary); }
.quick-overlay .quick-row-fulltext::before { content: '🔍'; margin-right: 0.4rem; opacity: 0.6; }
.quick-overlay .quick-row-error { color: var(--text-muted); font-style: italic; }
```

- [ ] **Step 5: GREEN** — `npx vitest run src/utils/fuzzy.test.js src/components/QuickOverlay.test.jsx src/App.test.jsx`; `npm run lint`. Grep the Selenide tests for `search-overlay-result` and `search-overlay-view-all`; if any IT uses them, update it to `[data-testid=quick-row][data-kind=page]` / `[data-kind=search]` in this task.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/utils/fuzzy.js wikantik-frontend/src/utils/fuzzy.test.js \
  wikantik-frontend/src/components/QuickOverlay.jsx wikantik-frontend/src/components/QuickOverlay.test.jsx \
  wikantik-frontend/src/App.jsx wikantik-frontend/src/styles/globals.css
git rm wikantik-frontend/src/components/SearchOverlay.jsx wikantik-frontend/src/components/SearchOverlay.test.jsx
git commit -m "feat(frontend): quick overlay — title switcher, full-text, create page and command palette"
```

---

### Task 14: Editor commands, registry-driven toolbar, slash menu

**Files:**
- Modify: `wikantik-frontend/src/utils/markdownFormat.js` (+ test) — `setHeading`, `insertCallout`, `insertMathBlock`, `insertRule`
- Create: `wikantik-frontend/src/utils/editorCommands.js`, `editorCommands.test.js`
- Create: `wikantik-frontend/src/utils/slashComplete.js`, `slashComplete.test.js`
- Modify: `wikantik-frontend/src/components/EditorToolbar.jsx` (+ test) — buttons run command ids
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx` (+ test) — accept a `slashSource` completion source
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (+ tests) — register editor commands, preview toggle, image picker, pass `slashSource`
- Modify: `wikantik-frontend/src/styles/globals.css` — `.editor-container.preview-hidden`
- Modify: `wikantik-frontend/package.json` — declare `@codemirror/language`

**Interfaces:**
- Consumes: registry + `useRegisterCommands`/`useRunCommand`/`getCommands` (Task 11), `fuzzyRank` (Task 13).
- Produces (`markdownFormat.js`, same `{text, selStart, selEnd}` contract as the existing helpers): `setHeading(state, level)`, `insertCallout(state, type)` → `> [!type] ` + newline + `> ` block with the cursor at the end of the first line, `insertMathBlock(state)` → `$$\n\n$$` block with the cursor on the empty line, `insertRule(state)` → `---` on its own line.
- Produces: `buildEditorCommands({ format, save, pickImage, togglePreview, toggleRail, foldAll, unfoldAll })` → Command[] with ids: `editor-save` (Save, `Mod-S`), `format-bold` (Bold, `Mod-B`), `format-italic` (Italic, `Mod-I`), `format-code` (Inline code), `format-list` (Bulleted list), `insert-link` (Insert link, `Mod-K`, slash), `heading-1|2|3` (Heading 1/2/3, slash), `callout-note|tip|warning|danger|info` (Insert callout: Note/Tip/Warning/Danger/Info, slash), `insert-table` (slash), `code-block` (slash), `math-block` (slash), `horizontal-rule` (slash), `insert-image` (slash), `fold-all`, `unfold-all`, `toggle-preview`, `toggle-rail`. `format(name)` names: `bold, italic, code, list, link, h1, h2, h3, table, codeblock, mathblock, rule, callout:<type>`.
- Produces: `createSlashSource(getCommands, run)` → CodeMirror `CompletionSource`; `slashAllowed(state, slashPos)` → boolean.
- Produces: `EditorToolbar` props `{ onRun(commandId) }`; buttons map `B→format-bold, I→format-italic, H→heading-2, ≡→format-list, `→format-code, {}→code-block, ▦→insert-table, ⌘K→insert-link`.
- Produces: `CodeEditor` prop `slashSource` (optional); the existing link source and it are both in `autocompletion({ override: [...] })`.
- Consumed by: Task 15 (`fold-all`/`unfold-all` call handle methods it adds — until then they call `editorRef.current?.foldAll?.()` and are harmless no-ops).

- [ ] **Step 1: Failing tests**

`markdownFormat.test.js` additions:

```js
describe('block inserts for slash commands', () => {
  it('setHeading replaces any existing heading prefix on the line', () => {
    expect(setHeading({ text: '## Old', selStart: 3, selEnd: 3 }, 1).text).toBe('# Old');
    expect(setHeading({ text: 'plain', selStart: 0, selEnd: 0 }, 3).text).toBe('### plain');
  });
  it('insertCallout starts a callout block on its own line with the cursor after the marker', () => {
    const r = insertCallout({ text: 'para', selStart: 4, selEnd: 4 }, 'warning');
    expect(r.text).toBe('para\n\n> [!warning] \n> ');
    expect(r.text.slice(0, r.selStart)).toBe('para\n\n> [!warning] ');
  });
  it('insertMathBlock puts the cursor on the empty line inside $$', () => {
    const r = insertMathBlock({ text: '', selStart: 0, selEnd: 0 });
    expect(r.text).toBe('$$\n\n$$\n');
    expect(r.selStart).toBe(3);
  });
  it('insertRule adds a thematic break on its own line', () => {
    expect(insertRule({ text: 'a', selStart: 1, selEnd: 1 }).text).toBe('a\n\n---\n');
  });
});
```

(Use the module's existing private `blockLead(text, pos)` for the leading blank line — it is what produces `'\n\n'` after `para` in `insertTable`; if its output differs, align the expectations to `blockLead`, which is the established convention.)

`editorCommands.test.js`:

```js
import { describe, it, expect, vi } from 'vitest';
import { buildEditorCommands } from './editorCommands';

const deps = () => ({ format: vi.fn(), save: vi.fn(), pickImage: vi.fn(), togglePreview: vi.fn(),
                      toggleRail: vi.fn(), foldAll: vi.fn(), unfoldAll: vi.fn() });

describe('buildEditorCommands', () => {
  it('declares the editor command set with stable ids', () => {
    const ids = buildEditorCommands(deps()).map((c) => c.id);
    expect(ids).toEqual(expect.arrayContaining([
      'editor-save', 'format-bold', 'format-italic', 'format-code', 'format-list', 'insert-link',
      'heading-1', 'heading-2', 'heading-3', 'callout-note', 'callout-tip', 'callout-warning', 'callout-danger',
      'callout-info', 'insert-table', 'code-block', 'math-block', 'horizontal-rule', 'insert-image',
      'fold-all', 'unfold-all', 'toggle-preview', 'toggle-rail']));
  });

  it('marks only insert commands as slash commands', () => {
    const slash = buildEditorCommands(deps()).filter((c) => c.slash).map((c) => c.id).sort();
    expect(slash).toEqual(['callout-danger', 'callout-info', 'callout-note', 'callout-tip', 'callout-warning',
      'code-block', 'heading-1', 'heading-2', 'heading-3', 'horizontal-rule', 'insert-image', 'insert-link',
      'insert-table', 'math-block'].sort());
  });

  it('routes to the right dependency', () => {
    const d = deps();
    const byId = Object.fromEntries(buildEditorCommands(d).map((c) => [c.id, c]));
    byId['callout-warning'].run();
    byId['heading-2'].run();
    byId['editor-save'].run();
    byId['insert-image'].run();
    expect(d.format).toHaveBeenCalledWith('callout:warning');
    expect(d.format).toHaveBeenCalledWith('h2');
    expect(d.save).toHaveBeenCalled();
    expect(d.pickImage).toHaveBeenCalled();
  });
});
```

`slashComplete.test.js` (real CodeMirror state, no DOM):

```js
import { describe, it, expect, vi } from 'vitest';
import { EditorState } from '@codemirror/state';
import { CompletionContext } from '@codemirror/autocomplete';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { createSlashSource, slashAllowed } from './slashComplete';

const COMMANDS = [
  { id: 'heading-2', title: 'Heading 2', slash: true, run: vi.fn() },
  { id: 'callout-warning', title: 'Insert callout: Warning', slash: true, run: vi.fn() },
  { id: 'fold-all', title: 'Fold all headings', run: vi.fn() },
];

function complete(doc, pos = doc.length) {
  const state = EditorState.create({ doc, extensions: [markdown()] });
  ensureSyntaxTree(state, state.doc.length, 5000);
  const source = createSlashSource(() => COMMANDS, vi.fn());
  return source(new CompletionContext(state, pos, false));
}

describe('slash completion', () => {
  it('offers slash commands at line start', () => {
    const r = complete('/');
    expect(r.options.map((o) => o.label)).toEqual(['Heading 2', 'Insert callout: Warning']);
    expect(r.from).toBe(0);
  });

  it('filters by the typed query and keeps non-slash commands out', () => {
    expect(complete('text /warn').options.map((o) => o.label)).toEqual(['Insert callout: Warning']);
    expect(complete('/fold')).toBeNull();
  });

  it.each([
    ['mid-word', 'and/or'],
    ['URL path', 'see https://example.com/he'],
    ['inline code', 'run `ls /he'],
    ['fenced code', '```\n/he'],
    ['inline math', 'value $a /he'],
    ['math block', '$$\n/he'],
    ['frontmatter', '---\ntitle: x\n/he'],
  ])('does not trigger in %s', (_name, doc) => {
    expect(complete(doc)).toBeNull();
  });

  it('applying an option deletes the typed /query and runs the command', () => {
    const run = vi.fn();
    const state = EditorState.create({ doc: 'a /hea', extensions: [markdown()] });
    const r = createSlashSource(() => COMMANDS, run)(new CompletionContext(state, 6, false));
    const dispatch = vi.fn();
    r.options[0].apply({ dispatch, state }, r.options[0], r.from, 6);
    expect(dispatch).toHaveBeenCalledWith({ changes: { from: 2, to: 6, insert: '' } });
    expect(run).toHaveBeenCalledWith('heading-2');
  });
});
```

`EditorToolbar.test.jsx` — replace the `onCommand` assertions with `onRun` ids, e.g. clicking the `B` button (mousedown) calls `onRun('format-bold')`, `▦` calls `onRun('insert-table')`.

RED: `npx vitest run src/utils/markdownFormat.test.js src/utils/editorCommands.test.js src/utils/slashComplete.test.js src/components/EditorToolbar.test.jsx`.

- [ ] **Step 2: Implement `markdownFormat.js` additions**

```js
/** Sets the current line's heading level (replacing any existing #-prefix). */
export function setHeading(state, level) {
  const { text, selStart } = state;
  const lineStart = text.lastIndexOf('\n', selStart - 1) + 1;
  const lineEndRaw = text.indexOf('\n', selStart);
  const lineEnd = lineEndRaw === -1 ? text.length : lineEndRaw;
  const line = text.slice(lineStart, lineEnd).replace(/^#{1,6}\s+/, '');
  const prefix = '#'.repeat(level) + ' ';
  const newText = text.slice(0, lineStart) + prefix + line + text.slice(lineEnd);
  const cursor = lineStart + prefix.length + line.length;
  return { text: newText, selStart: cursor, selEnd: cursor };
}

function insertBlockAt(state, block, cursorOffset) {
  const { text, selStart, selEnd } = state;
  const lead = blockLead(text, selStart);
  const newText = text.slice(0, selStart) + lead + block + text.slice(selEnd);
  const cursor = selStart + lead.length + cursorOffset;
  return { text: newText, selStart: cursor, selEnd: cursor };
}

export function insertCallout(state, type) {
  const first = `> [!${type}] `;
  return insertBlockAt(state, `${first}\n> `, first.length);
}

export function insertMathBlock(state) {
  return insertBlockAt(state, '$$\n\n$$\n', 3);
}

export function insertRule(state) {
  return insertBlockAt(state, '---\n', 4);
}
```

- [ ] **Step 3: Implement `editorCommands.js`**

```js
const CALLOUTS = [['note', 'Note'], ['tip', 'Tip'], ['warning', 'Warning'], ['danger', 'Danger'], ['info', 'Info']];

export function buildEditorCommands({ format, save, pickImage, togglePreview, toggleRail, foldAll, unfoldAll }) {
  const cmd = (id, title, section, run, extra = {}) => ({ id, title, section, run, ...extra });
  return [
    cmd('editor-save', 'Save', 'Editor', save, { keys: 'Mod-S' }),
    cmd('format-bold', 'Bold', 'Editor', () => format('bold'), { keys: 'Mod-B' }),
    cmd('format-italic', 'Italic', 'Editor', () => format('italic'), { keys: 'Mod-I' }),
    cmd('format-code', 'Inline code', 'Editor', () => format('code')),
    cmd('format-list', 'Bulleted list', 'Editor', () => format('list')),
    cmd('insert-link', 'Insert link', 'Insert', () => format('link'), { keys: 'Mod-K', slash: true }),
    ...[1, 2, 3].map((n) => cmd(`heading-${n}`, `Heading ${n}`, 'Insert', () => format(`h${n}`), { slash: true })),
    ...CALLOUTS.map(([type, label]) => cmd(`callout-${type}`, `Insert callout: ${label}`, 'Insert',
      () => format(`callout:${type}`), { slash: true, keywords: ['callout', type] })),
    cmd('insert-table', 'Insert table', 'Insert', () => format('table'), { slash: true }),
    cmd('code-block', 'Code block', 'Insert', () => format('codeblock'), { slash: true }),
    cmd('math-block', 'Math block', 'Insert', () => format('mathblock'), { slash: true }),
    cmd('horizontal-rule', 'Horizontal rule', 'Insert', () => format('rule'), { slash: true }),
    cmd('insert-image', 'Insert image', 'Insert', pickImage, { slash: true }),
    cmd('fold-all', 'Fold all headings', 'View', foldAll, { keys: 'Ctrl-Alt-[' }),
    cmd('unfold-all', 'Unfold all headings', 'View', unfoldAll, { keys: 'Ctrl-Alt-]' }),
    cmd('toggle-preview', 'Toggle preview', 'View', togglePreview),
    cmd('toggle-rail', 'Toggle rail', 'View', toggleRail),
  ];
}
```

- [ ] **Step 4: Implement `slashComplete.js`**

```js
import { syntaxTree } from '@codemirror/language';
import { fuzzyRank } from './fuzzy';

const CODE_NODES = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'CodeText', 'CodeMark', 'URL', 'Autolink']);

/** True when a slash at {@code slashPos} may open the menu: not in frontmatter, code, URLs or math. */
export function slashAllowed(state, slashPos) {
  const doc = state.doc;
  if (doc.line(1).text.trim() === '---') {
    let closed = false;
    for (let n = 2; n <= doc.lines; n += 1) {
      const line = doc.line(n);
      if (line.from > slashPos) break;
      if (line.text.trim() === '---') { closed = true; if (line.to >= slashPos) return false; break; }
    }
    if (!closed) return false;
  }
  for (let node = syntaxTree(state).resolveInner(slashPos, 1); node; node = node.parent) {
    if (CODE_NODES.has(node.name)) return false;
  }
  const line = doc.lineAt(slashPos);
  const before = line.text.slice(0, slashPos - line.from);
  if (((before.match(/(?<!\\)\$/g) || []).length % 2) === 1) return false;          // inside $…$
  if (/(^|[\s`])`[^`]*$/.test(before)) return false;                               // unterminated inline code
  let mathFences = 0;
  for (let n = 1; n < line.number; n += 1) if (doc.line(n).text.trim() === '$$') mathFences += 1;
  return mathFences % 2 === 0;                                                      // inside $$ … $$
}

export function createSlashSource(getCommands, run) {
  return (ctx) => {
    const m = ctx.matchBefore(/(?:^|\s)\/[\w-]*$/);
    if (!m) return null;
    const slashPos = m.text.startsWith('/') ? m.from : m.from + 1;
    if (!slashAllowed(ctx.state, slashPos)) return null;
    const query = ctx.state.sliceDoc(slashPos + 1, ctx.pos);
    const options = getCommands()
      .filter((c) => c.slash)
      .map((c) => ({ c, r: fuzzyRank(c.slashLabel || c.title, query) }))
      .filter((x) => x.r >= 0)
      .sort((a, b) => a.r - b.r || (a.c.slashLabel || a.c.title).localeCompare(b.c.slashLabel || b.c.title))
      .map(({ c }) => ({
        label: c.slashLabel || c.title,
        detail: c.keys,
        apply: (view, _completion, _from, to) => {
          view.dispatch({ changes: { from: slashPos, to, insert: '' } });
          run(c.id);
        },
      }));
    return options.length ? { from: slashPos, options, filter: false } : null;
  };
}
```

(`fuzzyRank` with the query `hea` ranks `Heading 2` first; `/fold` returns `null` because `fold-all` is not a slash command. If the fenced-code case still triggers because the unterminated fence parses as `FencedCode` only after more text, the `CODE_NODES` walk with `resolveInner(slashPos, 1)` is the intended guard — debug with `syntaxTree(state).toString()` rather than adding regexes.)

- [ ] **Step 5: Wire into the editor**
  - `package.json`: add `"@codemirror/language": "^6.12.4"` to `dependencies` (then `npm install` to refresh the lockfile; commit both).
  - `CodeEditor.jsx`: add prop `slashSource`; keep it in a ref like `linkCompletion`; change the completion extension to `autocompletion({ override: [linkSource, (ctx) => slashSourceRef.current?.(ctx) ?? null] })`. Add a `CodeEditor.test.jsx` case asserting the component accepts the prop without error (the CodeMirror stub doesn't run completions; the source itself is covered by `slashComplete.test.js`).
  - `EditorToolbar.jsx`: entries gain `id` (the command ids above) and call `onRun(id)` on mousedown; prop renamed `onRun`.
  - `PageEditor.jsx`:
    - extend `applyFormat`'s switch: `h1`/`h2`/`h3` → `setHeading(state, n)`; `mathblock` → `insertMathBlock`; `rule` → `insertRule`; any `callout:<type>` → `insertCallout(state, type)` (handle before the switch with `command.startsWith('callout:')`).
    - `const [previewOpen, setPreviewOpen] = useState(true);` and add `preview-hidden` to the `editor-container` className when false; render the preview pane only when `previewOpen`.
    - hidden `<input type="file" ref={imageInputRef} hidden accept="image/*" multiple onChange={(e) => { handleFiles([...e.target.files], editorRef.current?.getSelection().selStart ?? bodyRef.current.length, { pasted: false }); e.target.value = ''; }} />`.
    - `const runCommand = useRunCommand();` and
      ```js
      const editorCommands = useMemo(() => buildEditorCommands({
        format: applyFormat,
        save: () => { if (!savingRef.current) latestSaveRef.current?.(); },
        pickImage: () => imageInputRef.current?.click(),
        togglePreview: () => setPreviewOpen((v) => !v),
        toggleRail,
        foldAll: () => editorRef.current?.foldAll?.(),
        unfoldAll: () => editorRef.current?.unfoldAll?.(),
      }), [applyFormat, toggleRail]);
      useRegisterCommands(editorCommands, [editorCommands]);
      const slashSource = useMemo(() => createSlashSource(getCommands, (id) => runCommand(id)), [runCommand]);
      ```
    - `<EditorToolbar onRun={runCommand} />`; pass `slashSource={slashSource}` to `<CodeEditor>`.
    - In the PageEditor test files, mock `../hooks/useToast` already exists; add nothing else unless a test renders the toolbar and asserts on `onCommand` — update those to the new ids.
  - `globals.css`: `.editor-container.preview-hidden { grid-template-columns: 1fr; }` (match however `.editor-container` lays out its two panes — if it is flex, use `.editor-container.preview-hidden .editor-pane { flex: 1 1 100%; }`).

- [ ] **Step 6: GREEN** — `npx vitest run src/utils src/components/EditorToolbar.test.jsx src/components/CodeEditor.test.jsx src/components/PageEditor*.test.jsx`; `npm run lint`.

- [ ] **Step 7: Commit**

```bash
git add wikantik-frontend/package.json wikantik-frontend/package-lock.json \
  wikantik-frontend/src/utils/markdownFormat.js wikantik-frontend/src/utils/markdownFormat.test.js \
  wikantik-frontend/src/utils/editorCommands.js wikantik-frontend/src/utils/editorCommands.test.js \
  wikantik-frontend/src/utils/slashComplete.js wikantik-frontend/src/utils/slashComplete.test.js \
  wikantik-frontend/src/components/EditorToolbar.jsx wikantik-frontend/src/components/EditorToolbar.test.jsx \
  wikantik-frontend/src/components/CodeEditor.jsx wikantik-frontend/src/components/CodeEditor.test.jsx \
  wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/components/PageEditor*.test.jsx \
  wikantik-frontend/src/styles/globals.css
git commit -m "feat(editor): editor commands in the palette, registry-driven toolbar, slash menu"
```

---

### Task 15: Heading folding and the reveal rule

**Files:**
- Create: `wikantik-frontend/src/utils/markdownFold.js`, `markdownFold.test.js`
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx` (+ test) — fold gutter on, frontmatter fold, `foldAll`/`unfoldAll` handle methods, reveal before every cursor/scroll jump

**Interfaces:**
- Produces: `frontmatterFoldRange(state, lineStart)` → `{ from, to } | null` (exported for tests) and `frontmatterFold` (a `foldService` extension using it); `revealEffects(state, pos)` → `StateEffect[]` (one `unfoldEffect` per folded range containing `pos`).
- Produces (CodeEditor handle): `foldAll()`, `unfoldAll()`; `setSelection`, `scrollToLine`, `jumpToLineAligned` first dispatch `revealEffects(...)` for their target (ruling R9).

- [ ] **Step 1: Failing `markdownFold.test.js`**

```js
import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { codeFolding, foldEffect, foldedRanges, unfoldEffect } from '@codemirror/language';
import { frontmatterFoldRange, revealEffects } from './markdownFold';

describe('frontmatterFoldRange', () => {
  it('folds a leading frontmatter block from the end of the opening fence to the closing fence', () => {
    const state = EditorState.create({ doc: '---\ntitle: x\ntags: [a]\n---\nbody' });
    expect(frontmatterFoldRange(state, 0)).toEqual({ from: 3, to: state.doc.line(4).to });
  });
  it('offers nothing for other lines or unclosed blocks', () => {
    expect(frontmatterFoldRange(EditorState.create({ doc: '---\ntitle: x\n---' }), 4)).toBeNull();
    expect(frontmatterFoldRange(EditorState.create({ doc: '---\nnever closed' }), 0)).toBeNull();
    expect(frontmatterFoldRange(EditorState.create({ doc: 'text\n---\n' }), 0)).toBeNull();
  });
});

describe('revealEffects', () => {
  it('returns an unfold effect for each fold containing the position', () => {
    let state = EditorState.create({ doc: '# A\none\ntwo\n# B\nthree', extensions: [markdown(), codeFolding()] });
    state = state.update({ effects: foldEffect.of({ from: 3, to: 11 }) }).state;
    const effects = revealEffects(state, 6);
    expect(effects).toHaveLength(1);
    expect(effects[0].is(unfoldEffect)).toBe(true);
    const after = state.update({ effects }).state;
    expect(foldedRanges(after).size).toBe(0);
  });
  it('returns nothing outside folds or without the fold state', () => {
    expect(revealEffects(EditorState.create({ doc: 'x' }), 0)).toEqual([]);
  });
});
```

RED: `npx vitest run src/utils/markdownFold.test.js`.

- [ ] **Step 2: Implement `markdownFold.js`**

```js
import { foldService, foldState, foldedRanges, unfoldEffect } from '@codemirror/language';

export function frontmatterFoldRange(state, lineStart) {
  const first = state.doc.line(1);
  if (lineStart !== first.from || first.text.trim() !== '---') return null;
  for (let n = 2; n <= state.doc.lines; n += 1) {
    const line = state.doc.line(n);
    if (line.text.trim() === '---') return { from: first.to, to: line.to };
  }
  return null;
}

export const frontmatterFold = foldService.of((state, lineStart) => frontmatterFoldRange(state, lineStart));

/** Unfold effects that make {@code pos} visible (empty when nothing hides it). */
export function revealEffects(state, pos) {
  if (state.field(foldState, false) === undefined) return [];
  const effects = [];
  foldedRanges(state).between(pos, pos, (from, to) => {
    if (from <= pos && pos <= to) effects.push(unfoldEffect.of({ from, to }));
  });
  return effects;
}
```

- [ ] **Step 3: Wire into `CodeEditor.jsx`**
  - `basicSetup`: `foldGutter: true` (the fold keymap is part of `basicSetup` already — verify `foldKeymap` isn't disabled).
  - add `frontmatterFold` to `extensions`.
  - handle:
    ```js
    foldAll: () => { const v = viewRef.current; if (v) foldAll(v); },
    unfoldAll: () => { const v = viewRef.current; if (v) unfoldAll(v); },
    ```
    (`foldAll`/`unfoldAll` imported from `@codemirror/language`).
  - a local `reveal(view, pos)` that dispatches `revealEffects(view.state, pos)` when non-empty, guarded by `typeof view.state.field === 'function'` so the textarea test stub keeps working; call it at the top of `setSelection` (with `selStart`), `scrollToLine` (with `doc.line(line).from`) and `jumpToLineAligned` (same).
  - `CodeEditor.test.jsx`: extend the stub view's `state` with `field: () => undefined` only if a test needs it; add a test that `setSelection` still works with the stub (reveal is a no-op without fold state).

- [ ] **Step 4: GREEN** — `npx vitest run src/utils/markdownFold.test.js src/components/CodeEditor.test.jsx src/components/PageEditor*.test.jsx`; `npm run lint`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/markdownFold.js wikantik-frontend/src/utils/markdownFold.test.js \
  wikantik-frontend/src/components/CodeEditor.jsx wikantik-frontend/src/components/CodeEditor.test.jsx
git commit -m "feat(editor): fold headings and frontmatter; jumps unfold their target first"
```

---

### Task 16: Link hover previews and Ctrl-click

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` — `getPagePreview`
- Create: `wikantik-frontend/src/hooks/usePagePreview.js`, `usePagePreview.test.js`
- Create: `wikantik-frontend/src/components/LinkPreviewCard.jsx`, `LinkPreviewCard.test.jsx`
- Create: `wikantik-frontend/src/hooks/useLinkPreview.js`, `useLinkPreview.test.jsx`
- Create: `wikantik-frontend/src/utils/linkInteraction.js`, `linkInteraction.test.js`
- Modify: `wikantik-frontend/src/components/PageView.jsx` (+ test), `PageEditor.jsx` (+ test), `CodeEditor.jsx` (prop `onLinkHover`)
- Modify: `wikantik-frontend/src/styles/globals.css` — card styles, `.cm-mod-held` link cursor

**Interfaces:**
- Consumes: `GET /api/pages/{name}/preview[?section=]` (Task 4); `wikiLinkTarget` (`utils/wikiLinkTargets.js`).
- Produces: `api.getPagePreview(name, { section, signal } = {})`.
- Produces (`usePagePreview.js`): `loadPreview(name, section, signal)` → `Promise<{ status: 'ok', data } | { status: 'missing' }>` (LRU cache of 200 keyed `name#section`; 404 cached as missing; other errors reject and are not cached); `evictPreview(name)` (drops every section of `name`); `__resetPreviewCacheForTest()`.
- Produces: `<LinkPreviewCard target rect result />` where `result` ∈ `null` (loading) | `{status:'ok',data}` | `{status:'missing'}`; test id `link-preview-card`.
- Produces: `previewTargetOf(anchor)` → `{ name, section, missing } | null`; `useLinkPreview(containerRef)` → `{ card }` (a React element or `null`) handling hover/focus delegation, 400 ms open, 200 ms grace, Escape and scroll.
- Produces (`linkInteraction.js`): `linkAt(state, pos)` → `{ url, from, to } | null` (Markdown `Link`/`Autolink` URL via the syntax tree); `hrefFor(url)` → absolute in-app/external URL or `null`; `linkInteraction({ onHover(url|null, rect|null) })` → CodeMirror extension (Ctrl/Cmd-click opens `hrefFor(url)` in a new tab with `noopener`; Ctrl/Cmd-mousemove reports the link under the pointer; adds `cm-mod-held` to the editor DOM while Ctrl/Cmd is down).

- [ ] **Step 1: Failing tests** (write all, then RED together)

`usePagePreview.test.js`:

```js
import { describe, it, expect, vi, beforeEach } from 'vitest';
vi.mock('../api/client', () => ({ api: { getPagePreview: vi.fn() } }));
import { api } from '../api/client';
import { loadPreview, evictPreview, __resetPreviewCacheForTest } from './usePagePreview';

describe('page preview cache', () => {
  beforeEach(() => { vi.clearAllMocks(); __resetPreviewCacheForTest(); });

  it('fetches once and serves the cache afterwards', async () => {
    api.getPagePreview.mockResolvedValue({ name: 'A', title: 'A', excerpt: 'x' });
    expect(await loadPreview('A', null)).toEqual({ status: 'ok', data: { name: 'A', title: 'A', excerpt: 'x' } });
    await loadPreview('A', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(1);
  });

  it('caches a 404 as missing', async () => {
    api.getPagePreview.mockRejectedValue(Object.assign(new Error('nf'), { status: 404 }));
    expect(await loadPreview('Gone', null)).toEqual({ status: 'missing' });
    expect(await loadPreview('Gone', null)).toEqual({ status: 'missing' });
    expect(api.getPagePreview).toHaveBeenCalledTimes(1);
  });

  it('does not cache other failures', async () => {
    api.getPagePreview.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }))
      .mockResolvedValueOnce({ name: 'B' });
    await expect(loadPreview('B', null)).rejects.toThrow('boom');
    expect((await loadPreview('B', null)).status).toBe('ok');
  });

  it('evictPreview drops every section of a page', async () => {
    api.getPagePreview.mockResolvedValue({ name: 'C' });
    await loadPreview('C', null);
    await loadPreview('C', 'usage');
    evictPreview('C');
    await loadPreview('C', null);
    expect(api.getPagePreview).toHaveBeenCalledTimes(3);
  });

  it('passes the section through', async () => {
    api.getPagePreview.mockResolvedValue({ name: 'D' });
    await loadPreview('D', 'setup');
    expect(api.getPagePreview).toHaveBeenCalledWith('D', expect.objectContaining({ section: 'setup' }));
  });
});
```

`linkInteraction.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { markdown } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { linkAt, hrefFor } from './linkInteraction';

const at = (doc, pos) => {
  const state = EditorState.create({ doc, extensions: [markdown()] });
  ensureSyntaxTree(state, doc.length, 5000);
  return linkAt(state, pos);
};

describe('linkAt', () => {
  it('finds the link under the position, in its text or its URL', () => {
    expect(at('see [the hub](IndexFundsHub) now', 7).url).toBe('IndexFundsHub');
    expect(at('see [the hub](IndexFundsHub) now', 16).url).toBe('IndexFundsHub');
    expect(at('go <https://example.com> x', 8).url).toBe('https://example.com');
  });
  it('returns null off a link', () => {
    expect(at('plain text', 3)).toBeNull();
  });
});

describe('hrefFor', () => {
  it('maps wiki targets into the app and keeps external URLs', () => {
    expect(hrefFor('IndexFundsHub')).toBe('/wiki/IndexFundsHub');
    expect(hrefFor('Page#setup')).toBe('/wiki/Page#setup');
    expect(hrefFor('https://example.com/a')).toBe('https://example.com/a');
    expect(hrefFor('javascript:alert(1)')).toBeNull();
    expect(hrefFor('#local')).toBeNull();
  });
});
```

`useLinkPreview.test.jsx` (fake timers; container with anchors; `../hooks/usePagePreview` mocked so `loadPreview` resolves `{status:'ok', data:{ title:'Hub', type:'hub', cluster:'finance', summary:'About index funds.' }}`):

```jsx
  it('shows the card 400 ms after hovering an internal link', async () => { /* mouseover a[href="/wiki/IndexFundsHub"];
     advance 399 → no card; advance 1 + flush → card with "Hub" and "About index funds." */ });
  it('keeps the card while the pointer moves into it within 200 ms, closes after leaving both', async () => { });
  it('shows "Not created yet" for a createpage link without fetching', async () => {
     /* a.createpage[href="/wiki/Nope"] → card text "Not created yet"; loadPreview not called */ });
  it('ignores external, attachment and same-page anchors', async () => {
     /* https://x, /attach/P/f.png, #sec → no card after 500 ms */ });
  it('opens on keyboard focus and closes on Escape', async () => { });
  it('closes on scroll', async () => { });
  it('extracts the section from a hash', () => {
     expect(previewTargetOf(anchor('/wiki/Page#usage'))).toEqual({ name: 'Page', section: 'usage', missing: false });
     expect(previewTargetOf(anchor('Page#usage'))).toEqual({ name: 'Page', section: 'usage', missing: false });
  });
```

Write each body fully in the test file following the comments (render a `<div ref>` host component that calls `useLinkPreview(ref)` and renders `{card}`; drive events with `fireEvent.mouseOver/mouseOut/focusIn/keyDown/scroll` and `act(() => vi.advanceTimersByTimeAsync(n))`).

`LinkPreviewCard.test.jsx`: renders title, type badge, cluster and summary; falls back to excerpt when no summary; `null` result shows a loading line; `{status:'missing'}` shows `Not created yet`; text with `<b>` markup is rendered as literal text (no HTML injection).

RED: `npx vitest run src/hooks/usePagePreview.test.js src/hooks/useLinkPreview.test.jsx src/utils/linkInteraction.test.js src/components/LinkPreviewCard.test.jsx`.

- [ ] **Step 2: Implement**

`api/client.js`:

```js
  getPagePreview: (name, { section, signal } = {}) =>
    request(`/api/pages/${encodeURIComponent(name)}/preview${section ? `?section=${encodeURIComponent(section)}` : ''}`,
      { signal }),
```

`hooks/usePagePreview.js`:

```js
import { api } from '../api/client';

const MAX = 200;
const cache = new Map(); // key → result; insertion order = LRU order

const keyOf = (name, section) => `${name}#${section || ''}`;

export async function loadPreview(name, section, signal) {
  const key = keyOf(name, section);
  if (cache.has(key)) {
    const hit = cache.get(key);
    cache.delete(key);
    cache.set(key, hit);
    return hit;
  }
  let result;
  try {
    result = { status: 'ok', data: await api.getPagePreview(name, { section, signal }) };
  } catch (err) {
    if (err?.status !== 404) throw err;
    result = { status: 'missing' };
  }
  cache.set(key, result);
  if (cache.size > MAX) cache.delete(cache.keys().next().value);
  return result;
}

export function evictPreview(name) {
  for (const key of [...cache.keys()]) if (key.startsWith(`${name}#`)) cache.delete(key);
}

export function __resetPreviewCacheForTest() { cache.clear(); }
```

`components/LinkPreviewCard.jsx`:

```jsx
/** Text-only preview of a wiki page, positioned under the hovered link. Never renders Markdown or HTML. */
export default function LinkPreviewCard({ rect, result, onMouseEnter, onMouseLeave }) {
  if (!rect) return null;
  const style = { position: 'fixed', top: Math.round(rect.bottom + 6), left: Math.round(Math.max(8, rect.left)) };
  let body;
  if (!result) body = <p className="link-preview-loading">Loading…</p>;
  else if (result.status === 'missing') body = <p className="link-preview-missing">Not created yet</p>;
  else {
    const d = result.data;
    body = (
      <>
        <div className="link-preview-head">
          <strong className="link-preview-title">{d.title || d.name}</strong>
          {d.type && <span className="link-preview-type">{d.type}</span>}
        </div>
        {d.cluster && <div className="link-preview-cluster">{d.cluster}</div>}
        {(d.summary || d.excerpt) && <p className="link-preview-text">{d.summary || d.excerpt}</p>}
      </>
    );
  }
  return (
    <div className="link-preview-card" role="tooltip" data-testid="link-preview-card" style={style}
         onMouseEnter={onMouseEnter} onMouseLeave={onMouseLeave}>
      {body}
    </div>
  );
}
```

`hooks/useLinkPreview.js`:

```js
import { createElement, useCallback, useEffect, useRef, useState } from 'react';
import LinkPreviewCard from '../components/LinkPreviewCard';
import { loadPreview } from './usePagePreview';
import { wikiLinkTarget } from '../utils/wikiLinkTargets';

const OPEN_MS = 400;
const GRACE_MS = 200;
const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

export function previewTargetOf(anchor) {
  const href = anchor?.getAttribute?.('href');
  if (!href) return null;
  const missing = anchor.classList.contains('createpage');
  const local = href.startsWith(`${BASE}/wiki/`) ? href.slice(BASE.length) : href;
  const wiki = /^\/wiki\/([^?#]+)(?:\?[^#]*)?(?:#(.*))?$/.exec(local);
  if (wiki) return { name: decodeURIComponent(wiki[1]), section: wiki[2] || null, missing };
  const name = wikiLinkTarget(href);
  if (!name) return null;
  const hash = href.includes('#') ? href.slice(href.indexOf('#') + 1) : '';
  return { name, section: hash || null, missing };
}

/** Delegated hover/focus previews for every wiki link inside {@code containerRef}. */
export function useLinkPreview(containerRef) {
  const [state, setState] = useState(null); // { rect, result }
  const openTimer = useRef(null);
  const closeTimer = useRef(null);
  const ctl = useRef(null);

  const cancelTimers = () => { clearTimeout(openTimer.current); clearTimeout(closeTimer.current); };
  const close = useCallback(() => {
    cancelTimers();
    ctl.current?.abort();
    setState(null);
  }, []);
  const scheduleClose = useCallback(() => {
    clearTimeout(closeTimer.current);
    closeTimer.current = setTimeout(close, GRACE_MS);
  }, [close]);

  const open = useCallback((anchor) => {
    const target = previewTargetOf(anchor);
    if (!target) return;
    cancelTimers();
    openTimer.current = setTimeout(() => {
      const rect = anchor.getBoundingClientRect();
      if (target.missing) { setState({ rect, result: { status: 'missing' } }); return; }
      ctl.current?.abort();
      ctl.current = new AbortController();
      setState({ rect, result: null });
      loadPreview(target.name, target.section, ctl.current.signal)
        .then((result) => setState((s) => (s ? { ...s, result } : s)))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          console.warn('[link-preview] preview failed', target.name, err?.message || err);
          setState(null);
        });
    }, OPEN_MS);
  }, []);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return undefined;
    const over = (e) => { const a = e.target.closest?.('a[href]'); if (a && el.contains(a)) open(a); };
    const out = (e) => { if (e.target.closest?.('a[href]')) scheduleClose(); };
    const key = (e) => { if (e.key === 'Escape') close(); };
    el.addEventListener('mouseover', over);
    el.addEventListener('mouseout', out);
    el.addEventListener('focusin', over);
    el.addEventListener('focusout', out);
    window.addEventListener('keydown', key);
    window.addEventListener('scroll', close, true);
    return () => {
      el.removeEventListener('mouseover', over);
      el.removeEventListener('mouseout', out);
      el.removeEventListener('focusin', over);
      el.removeEventListener('focusout', out);
      window.removeEventListener('keydown', key);
      window.removeEventListener('scroll', close, true);
      cancelTimers();
      ctl.current?.abort();
    };
  }, [containerRef, open, close, scheduleClose]);

  const card = state ? createElement(LinkPreviewCard, {
    rect: state.rect, result: state.result,
    onMouseEnter: () => clearTimeout(closeTimer.current),
    onMouseLeave: scheduleClose,
  }) : null;
  return { card };
}
```

`utils/linkInteraction.js`:

```js
import { EditorView, ViewPlugin } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import { wikiLinkTarget } from './wikiLinkTargets';

const BASE = (typeof window !== 'undefined' && window.__WIKANTIK_BASE__) || '';

export function linkAt(state, pos) {
  for (let node = syntaxTree(state).resolveInner(pos, 1); node; node = node.parent) {
    if (node.name === 'Link' || node.name === 'Autolink') {
      const url = node.getChild('URL');
      if (!url) return null;
      return { url: state.sliceDoc(url.from, url.to), from: node.from, to: node.to };
    }
  }
  return null;
}

export function hrefFor(url) {
  if (!url) return null;
  if (/^https?:\/\//i.test(url)) return url;
  const name = wikiLinkTarget(url);
  if (!name) return null;
  const hash = url.includes('#') ? url.slice(url.indexOf('#')) : '';
  return `${BASE}/wiki/${encodeURIComponent(name)}${hash}`;
}

/** Ctrl/Cmd-hover reports the link under the pointer; Ctrl/Cmd-click opens it in a new tab. */
export function linkInteraction({ onHover }) {
  const modHeld = ViewPlugin.fromClass(class {
    constructor(view) {
      this.view = view;
      this.sync = (e) => view.dom.classList.toggle('cm-mod-held', e.ctrlKey || e.metaKey);
      window.addEventListener('keydown', this.sync);
      window.addEventListener('keyup', this.sync);
    }
    destroy() {
      window.removeEventListener('keydown', this.sync);
      window.removeEventListener('keyup', this.sync);
    }
  });
  const handlers = EditorView.domEventHandlers({
    mousedown(e, view) {
      if (!(e.ctrlKey || e.metaKey) || e.button !== 0) return false;
      const pos = view.posAtCoords({ x: e.clientX, y: e.clientY });
      const link = pos == null ? null : linkAt(view.state, pos);
      const href = link && hrefFor(link.url);
      if (!href) return false;
      e.preventDefault();
      window.open(href, '_blank', 'noopener');
      return true;
    },
    mousemove(e, view) {
      if (!(e.ctrlKey || e.metaKey)) { onHover(null, null); return false; }
      const pos = view.posAtCoords({ x: e.clientX, y: e.clientY });
      const link = pos == null ? null : linkAt(view.state, pos);
      if (!link) { onHover(null, null); return false; }
      const start = view.coordsAtPos(link.from);
      const end = view.coordsAtPos(link.to);
      onHover(link.url, start && end
        ? { left: start.left, top: start.top, bottom: Math.max(start.bottom, end.bottom), right: end.right }
        : null);
      return false;
    },
    mouseleave() { onHover(null, null); return false; },
  });
  return [modHeld, handlers];
}
```

Wiring:
- `CodeEditor.jsx`: new prop `onLinkHover`; keep it in a ref; add `linkInteraction({ onHover: (url, rect) => onLinkHoverRef.current?.(url, rect) })` to the memoised extensions (built once).
- `PageEditor.jsx`:
  - preview pane: `const previewArticleRef = useRef(null); const { card: previewCard } = useLinkPreview(previewArticleRef);` — put the ref on the preview `<article>` and render `{previewCard}` after it.
  - source editor: `const [sourceHover, setSourceHover] = useState(null);` and an effect that, when `sourceHover?.url` maps through `wikiLinkTarget` to a page, loads `loadPreview(name, section)` into `{ rect, result }` (aborting the previous), rendering `<LinkPreviewCard rect={…} result={…} />`; `onLinkHover={(url, rect) => setSourceHover(url ? { url, rect } : null)}` on `<CodeEditor>`.
  - after a successful save in `saveContent`, call `evictPreview(name)`.
- `PageView.jsx`: `const { card } = useLinkPreview(articleRef);` and render `{card}` next to the memoised `articleEl` (do not put it inside the memoised element — React 19 would re-apply `dangerouslySetInnerHTML`).
- `globals.css`:

```css
.link-preview-card { z-index: 1000; max-width: 22rem; padding: 0.6rem 0.75rem; background: var(--bg-elevated);
  color: var(--text); border: 1px solid var(--border); border-radius: 6px; box-shadow: var(--shadow-strong);
  font-size: 0.85rem; line-height: 1.4; pointer-events: auto; }
.link-preview-head { display: flex; gap: 0.5rem; align-items: baseline; }
.link-preview-type { font-size: 0.7rem; text-transform: uppercase; color: var(--text-muted); }
.link-preview-cluster { color: var(--text-secondary); font-size: 0.75rem; }
.link-preview-text { margin: 0.35rem 0 0; color: var(--text-secondary); }
.link-preview-missing, .link-preview-loading { margin: 0; color: var(--text-muted); font-style: italic; }
.cm-mod-held .cm-content .tok-link, .cm-mod-held .cm-content .tok-url { cursor: pointer; text-decoration: underline; }
```

(Check which CodeMirror highlight classes the Markdown link/URL tokens carry in this theme by inspecting the rendered DOM in the browser pass; adjust the selectors to match.)

- `PageView.test.jsx`: one test — hovering an internal link in the rendered article shows `link-preview-card` after timers advance (mock `../hooks/usePagePreview`).

- [ ] **Step 3: GREEN** — `npx vitest run src/hooks src/utils/linkInteraction.test.js src/components/LinkPreviewCard.test.jsx src/components/PageView.test.jsx src/components/PageEditor*.test.jsx src/components/CodeEditor.test.jsx`; `npm run lint`.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/hooks/usePagePreview.js \
  wikantik-frontend/src/hooks/usePagePreview.test.js wikantik-frontend/src/hooks/useLinkPreview.js \
  wikantik-frontend/src/hooks/useLinkPreview.test.jsx wikantik-frontend/src/components/LinkPreviewCard.jsx \
  wikantik-frontend/src/components/LinkPreviewCard.test.jsx wikantik-frontend/src/utils/linkInteraction.js \
  wikantik-frontend/src/utils/linkInteraction.test.js wikantik-frontend/src/components/PageView.jsx \
  wikantik-frontend/src/components/PageView.test.jsx wikantik-frontend/src/components/PageEditor.jsx \
  wikantik-frontend/src/components/PageEditor*.test.jsx wikantik-frontend/src/components/CodeEditor.jsx \
  wikantik-frontend/src/styles/globals.css
git commit -m "feat(frontend): link hover previews and Ctrl-click to open links from the editor"
```

---

### Task 17: Unlinked mentions in the editor rail

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` — `scanMentions`
- Modify: `wikantik-frontend/src/utils/wikiLinkComplete.js` — export `escapeLinkText`
- Create: `wikantik-frontend/src/utils/mentionLink.js`, `mentionLink.test.js`
- Create: `wikantik-frontend/src/hooks/useUnlinkedMentions.js`, `useUnlinkedMentions.test.js`
- Create: `wikantik-frontend/src/components/editor/UnlinkedMentionsPanel.jsx`, `UnlinkedMentionsPanel.test.jsx`
- Modify: `wikantik-frontend/src/components/editor/EditorRail.jsx` (+ test) — render `children` as extra sections
- Modify: `wikantik-frontend/src/components/CodeEditor.jsx` (+ test) — handle `replaceRange(from, to, text)`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx` (+ tests; the `./CodeEditor` stub in `PageEditor.linkCompletion.test.jsx` gains `replaceRange`)
- Modify: `wikantik-frontend/src/styles/globals.css`

**Interfaces:**
- Consumes: `POST /api/mentions/scan` (Task 7): `200 {mentions:[…]}`, `503` while warming.
- Produces: `api.scanMentions({ page, text, signal })`.
- Produces: `locatePhrase(text, mention)` → `{ from, to } | null` — the recorded span when it still reads `phrase`, else the nearest whole-word occurrence of the phrase (case-insensitive), else `null`; `linkMarkup(phrase, target)` → `` `[${escapeLinkText(phrase)}](${target})` ``.
- Produces: `useUnlinkedMentions({ page, text, enabled })` → `{ status: 'idle'|'loading'|'ok'|'warming'|'error', mentions, rescan }` (1.5 s debounce, aborts superseded scans, warming retries automatically after 5 s).
- Produces: `<UnlinkedMentionsPanel status mentions onLink onIgnore onJump onRetry />` (test ids `mentions-panel`, `mention-row`, `mention-link`, `mention-ignore`, `mention-retry`).
- Produces: CodeEditor handle `replaceRange(from, to, text)` → `boolean` (one non-External transaction; `false` without a view).

- [ ] **Step 1: Failing tests**

`mentionLink.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { locatePhrase, linkMarkup } from './mentionLink';

const m = (phrase, from) => ({ phrase, from, to: from + phrase.length, target: 'T' });

describe('locatePhrase', () => {
  it('uses the recorded span when it still matches', () => {
    expect(locatePhrase('an index fund here', m('index fund', 3))).toEqual({ from: 3, to: 13 });
  });
  it('relocates to the nearest occurrence after the text shifted', () => {
    const text = 'NEW TEXT. an index fund here, another index fund';
    expect(locatePhrase(text, m('index fund', 3))).toEqual({ from: 13, to: 23 });
  });
  it('requires whole words and ignores case', () => {
    expect(locatePhrase('reindex fundamentals; Index Fund', m('index fund', 0))).toEqual({ from: 22, to: 32 });
  });
  it('returns null when the phrase is gone', () => {
    expect(locatePhrase('nothing left', m('index fund', 0))).toBeNull();
  });
  it('handles astral characters before the phrase (UTF-16 offsets)', () => {
    const text = '😀 an expense ratio';
    expect(locatePhrase(text, m('expense ratio', 6))).toEqual({ from: 6, to: 19 });
  });
});

describe('linkMarkup', () => {
  it('keeps the author casing and escapes brackets', () => {
    expect(linkMarkup('Index [x] Fund', 'IndexFundsHub')).toBe('[Index \\[x\\] Fund](IndexFundsHub)');
  });
});
```

`useUnlinkedMentions.test.js` (fake timers; mock `../api/client`): no request while `enabled` is false; one request 1500 ms after the last text change (not before); a newer change aborts the earlier request (assert the first call's `signal.aborted`); `503` → `status: 'warming'` and a retry fires after 5000 ms; other errors → `status: 'error'` and `console.warn`; `rescan()` triggers an immediate request.

`UnlinkedMentionsPanel.test.jsx`: lists rows with phrase → title, `line N` and context; **Link** / **Ignore** call their handlers with the mention; clicking the context calls `onJump`; empty `ok` shows `No unlinked mentions`; `warming` shows `Mentions available shortly`; `error` shows `Couldn't scan for mentions` with a retry button; the header shows the count badge.

`CodeEditor.test.jsx`: `replaceRange(4, 8, 'X')` dispatches `{ changes: { from: 4, to: 8, insert: 'X' } }` on the stub view and returns `true`; returns `false` before the view exists.

`PageEditor` test (new file `PageEditor.mentions.test.jsx`, modelled on `PageEditor.uploads.test.jsx`'s CodeEditor stub with `replaceRange` recorded): with `api.scanMentions` resolving one mention for the loaded body, after timers the rail shows it; clicking **Link** calls `replaceRange(from, to, '[phrase](Target)')`; when the body changed so the phrase is gone, clicking **Link** calls `toast.info('Text changed — rescanned')` and `api.scanMentions` again; **Ignore** hides the row.

RED: `npx vitest run src/utils/mentionLink.test.js src/hooks/useUnlinkedMentions.test.js src/components/editor src/components/CodeEditor.test.jsx src/components/PageEditor.mentions.test.jsx`.

- [ ] **Step 2: Implement**

`api/client.js`:

```js
  scanMentions: ({ page, text, signal }) =>
    request('/api/mentions/scan', { method: 'POST', body: JSON.stringify({ page, text }), signal }),
```

`wikiLinkComplete.js`: change `const escapeLinkText = …` to `export const escapeLinkText = …`.

`utils/mentionLink.js`:

```js
import { escapeLinkText } from './wikiLinkComplete';

const WORD = /[\p{L}\p{N}]/u;
function wholeWordAt(text, from, len) {
  const before = from === 0 || !WORD.test(text[from - 1]);
  const after = from + len >= text.length || !WORD.test(text[from + len]);
  return before && after;
}

export function locatePhrase(text, mention) {
  const { phrase, from, to } = mention;
  if (text.slice(from, to) === phrase) return { from, to };
  const hay = text.toLowerCase();
  const needle = phrase.toLowerCase();
  let best = null;
  for (let i = hay.indexOf(needle); i !== -1; i = hay.indexOf(needle, i + 1)) {
    if (wholeWordAt(text, i, needle.length) && (best === null || Math.abs(i - from) < Math.abs(best - from))) best = i;
  }
  return best === null ? null : { from: best, to: best + phrase.length };
}

export function linkMarkup(phrase, target) {
  return `[${escapeLinkText(phrase)}](${target})`;
}
```

`hooks/useUnlinkedMentions.js`:

```js
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

const DEBOUNCE_MS = 1500;
const WARMING_RETRY_MS = 5000;

export function useUnlinkedMentions({ page, text, enabled }) {
  const [state, setState] = useState({ status: 'idle', mentions: [] });
  const [nonce, setNonce] = useState(0);
  const rescan = useCallback(() => setNonce((n) => n + 1), []);
  const latest = useRef(nonce);

  useEffect(() => {
    if (!enabled || !text) return undefined;
    const ctl = new AbortController();
    const immediate = nonce !== latest.current;
    latest.current = nonce;
    let retry;
    const id = setTimeout(() => {
      setState((s) => ({ ...s, status: 'loading' }));
      api.scanMentions({ page, text, signal: ctl.signal })
        .then((d) => setState({ status: 'ok', mentions: d?.mentions || [] }))
        .catch((err) => {
          if (err?.name === 'AbortError') return;
          if (err?.status === 503) {
            setState({ status: 'warming', mentions: [] });
            retry = setTimeout(() => setNonce((n) => n + 1), WARMING_RETRY_MS);
            return;
          }
          console.warn('[mentions] scan failed', err?.message || err);
          setState({ status: 'error', mentions: [] });
        });
    }, immediate ? 0 : DEBOUNCE_MS);
    return () => { ctl.abort(); clearTimeout(id); clearTimeout(retry); };
  }, [page, text, enabled, nonce]);

  return { ...state, rescan };
}
```

`components/editor/UnlinkedMentionsPanel.jsx`:

```jsx
export default function UnlinkedMentionsPanel({ status, mentions, onLink, onIgnore, onJump, onRetry }) {
  let body;
  if (status === 'warming') body = <p className="editor-rail-empty">Mentions available shortly</p>;
  else if (status === 'error') body = (
    <p className="editor-rail-empty">Couldn&apos;t scan for mentions{' '}
      <button type="button" className="link-button" data-testid="mention-retry" onClick={onRetry}>Retry</button></p>
  );
  else if (status === 'ok' && mentions.length === 0) body = <p className="editor-rail-empty">No unlinked mentions</p>;
  else if (mentions.length === 0) body = <p className="editor-rail-empty">Scanning…</p>;
  else body = (
    <ul className="mentions-list">
      {mentions.map((m) => (
        <li key={m.target} className="mention-row" data-testid="mention-row">
          <div className="mention-head">
            <span className="mention-phrase">“{m.phrase}”</span> → <span className="mention-title">{m.title}</span>
          </div>
          <button type="button" className="mention-context" onClick={() => onJump(m)}>
            line {m.line}: {m.context}
          </button>
          <div className="mention-actions">
            <button type="button" data-testid="mention-link" onClick={() => onLink(m)}>Link</button>
            <button type="button" data-testid="mention-ignore" onClick={() => onIgnore(m)}>Ignore</button>
          </div>
        </li>
      ))}
    </ul>
  );
  return (
    <section className="editor-rail-section" data-testid="mentions-panel">
      <h4 className="editor-rail-heading">
        Unlinked mentions{mentions.length > 0 && <span className="editor-rail-badge">{mentions.length}</span>}
      </h4>
      {body}
    </section>
  );
}
```

`EditorRail.jsx`: accept `children` and render them after the Backlinks section inside the open rail.

`CodeEditor.jsx` handle:

```js
    replaceRange: (from, to, text) => {
      const view = viewRef.current;
      if (!view) return false;
      const len = view.state.doc.length;
      view.dispatch({ changes: { from: Math.min(from, len), to: Math.min(to, len), insert: text } });
      return true;
    },
```

`PageEditor.jsx`:

```js
  const [ignoredMentions, setIgnoredMentions] = useState(() => new Set());
  const mentionScan = useUnlinkedMentions({ page: name, text: body, enabled: railOpen && loaded });
  const visibleMentions = mentionScan.mentions.filter((m) => !ignoredMentions.has(m.target));

  const linkMention = useCallback((m) => {
    const loc = locatePhrase(bodyRef.current, m);
    if (!loc) {
      toast.info('Text changed — rescanned');
      mentionScan.rescan();
      return;
    }
    const markup = linkMarkup(bodyRef.current.slice(loc.from, loc.to), m.target);
    if (!editorRef.current?.replaceRange(loc.from, loc.to, markup)) {
      setBody((prev) => prev.slice(0, loc.from) + markup + prev.slice(loc.to));
    }
    setIgnoredMentions((s) => new Set(s).add(m.target)); // hidden until the next scan drops it as linked
  }, [toast, mentionScan]);

  const jumpToMention = useCallback((m) => {
    editorRef.current?.setSelection(m.from, m.to);
    editorRef.current?.scrollToLine(m.line);
  }, []);
```

and render inside `<EditorRail …>`:

```jsx
  <UnlinkedMentionsPanel status={mentionScan.status} mentions={visibleMentions}
    onLink={linkMention} onIgnore={(m) => setIgnoredMentions((s) => new Set(s).add(m.target))}
    onJump={jumpToMention} onRetry={mentionScan.rescan} />
```

(`mentionScan` is a new object each render; depend on `mentionScan.rescan` — stable — instead of the whole object in `useCallback` deps to keep lint quiet.) Add `scanMentions: vi.fn().mockResolvedValue({ mentions: [] })` to every PageEditor test's api mock.

`globals.css`: `.mention-row`, `.mention-context` (button reset, muted, small, left-aligned, ellipsis), `.mention-actions` (small buttons), `.editor-rail-badge` (pill using `--accent` / `--bg-elevated` tokens).

- [ ] **Step 3: GREEN** — `npx vitest run src/utils/mentionLink.test.js src/hooks/useUnlinkedMentions.test.js src/components/editor src/components/CodeEditor.test.jsx src/components/PageEditor*.test.jsx`; `npm run lint`.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/api/client.js wikantik-frontend/src/utils/wikiLinkComplete.js \
  wikantik-frontend/src/utils/mentionLink.js wikantik-frontend/src/utils/mentionLink.test.js \
  wikantik-frontend/src/hooks/useUnlinkedMentions.js wikantik-frontend/src/hooks/useUnlinkedMentions.test.js \
  wikantik-frontend/src/components/editor wikantik-frontend/src/components/CodeEditor.jsx \
  wikantik-frontend/src/components/CodeEditor.test.jsx wikantik-frontend/src/components/PageEditor.jsx \
  wikantik-frontend/src/components/PageEditor*.test.jsx wikantik-frontend/src/styles/globals.css
git commit -m "feat(editor): unlinked mentions in the rail, linked in place as an undoable edit"
```

---

### Task 18: Browser IT, docs, CHANGELOG

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/EditorWorkspaceIT.java`
- Modify: `CHANGELOG.md` (Unreleased), `CLAUDE.md` (servlet counts in the agent-surface table), `README.md` (~line 424 count), `docs/wikantik-pages/WikantikArchitecture.md` (~line 78 count), `docs/superpowers/specs/2026-09-30-editor-workspace-design.md` (status line)

- [ ] **Step 1: Browser IT** — model the class on an existing SPA IT that logs in and edits (e.g. `StructuredFrontmatterEditorIT`: same base class / login helper / waits). Two tests:
  1. **Switcher:** open `/wiki/Main` (a startup fixture — index-dependent ITs must not rely on freshly created pages), press Ctrl-O (`actions().keyDown(Keys.CONTROL).sendKeys("o").keyUp(Keys.CONTROL).perform()`), assert `[data-testid=search-overlay]` is visible, type a known fixture page name, wait for a `[data-testid=quick-row][data-kind=page]` whose `data-page-name` equals it, press Enter, assert the URL ends with `/wiki/<name>` and `[data-testid=page-view]` is visible.
  2. **Callout:** as the logged-in user create a page through the REST API (`PUT /api/pages/EwCalloutIT` with `> [!warning] Mind the gap\n> Careful.\n`) using the IT's existing HTTP helper, open `/wiki/EwCalloutIT`, assert `.callout.callout-warning .callout-title-inner` has text `Mind the gap` and `.callout-content` has text `Careful.`.

- [ ] **Step 2: Run** the browser IT through the canonical path (it is part of the default IT modules): `bin/agent-build.sh start ewit -- bin/run-tests.sh --parallel 4` and poll `bin/agent-build.sh status ewit` until it reports SUCCESS or FAILED; on failure read `bin/agent-build.sh tail ewit 80`.

- [ ] **Step 3: Docs**
  - `CHANGELOG.md` `## [Unreleased]`:
    - `### Added` — quick overlay (Ctrl-K/Ctrl-O pages with title/alias/fuzzy ranking, Ctrl-P commands, full-text and create rows); command palette shared with the toolbar and a `/` slash menu; link hover previews (read view and editor) and Ctrl/Cmd-click to open links from the editor; type-aware page templates for all five page types; `aliases:` frontmatter field; outgoing unlinked mentions in the editor rail; heading/frontmatter folding; tag autocomplete; Obsidian callouts (`> [!type]`, foldable `+`/`-`) in pages and the editor preview; endpoints `GET /api/pages/{name}/preview`, `GET /api/page-templates`, `POST /api/mentions/scan`; `GET /api/pages?q=` ranks titles, aliases and fuzzy matches.
    - `### Changed` — in-app navigation away from an unsaved draft now asks first (the editor's Cancel uses the same dialog).
    - `### Fixed` — removing a field in the structured frontmatter editor now removes it on save (`replaceMetadata` was dropped by the client); the new-page dialog detects existing pages beyond the first 500; Ctrl/Cmd-K inside the editor no longer also opens the search overlay.
  - Servlet counts: two new servlets under `/api/*` (`PageTemplatesResource`, `MentionScanResource`) — bump the distinct-servlet and url-pattern counts in `CLAUDE.md`'s `/api/*` row (and mention the two endpoints there), `README.md:424` and `WikantikArchitecture.md:78` by 2 each, re-deriving the numbers from `web.xml` rather than adding blindly.
  - Spec status line → `**Status:** implemented on main (2026-09-30), not yet released.`

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/EditorWorkspaceIT.java \
  CHANGELOG.md CLAUDE.md README.md docs/wikantik-pages/WikantikArchitecture.md \
  docs/superpowers/specs/2026-09-30-editor-workspace-design.md
git commit -m "test(it): editor workspace browser IT; docs and changelog for the editor workspace"
```

---

## Final verification (controller, after Task 18)

- `cd wikantik-frontend && npm run lint && npx vitest run` — all green.
- `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4` → SUCCESS.
- `bin/agent-build.sh start pmd -- mvn -q pmd:check -Pcomplexity-gate` → SUCCESS (no baseline additions).
- `bin/agent-build.sh start cov -- mvn clean install -Pcoverage -DskipITs` → SUCCESS (module floors hold; frontend vitest coverage thresholds in `vite.config.js` hold).
- Manual browser pass against `bin/redeploy.sh`: every feature in the spec, light and dark theme, narrow viewport.
- Publish status to the wiki page `EditorWorkspaceDesign` (status active, implemented on main).
