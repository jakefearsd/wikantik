# Wiki Syntax to Markdown Converter

## Context

Wikantik migrated from JSPWiki's legacy wiki syntax to Markdown, but pages imported from older JSPWiki instances still exist as `.txt` files with legacy markup (headings with `!`, bold with `__`, links with `[text|url]`, etc.). The old parser was fully removed (commit `a9e5ea071`), so these pages cannot be rendered correctly. Users need a way to convert them to Markdown from within the editor.

## Requirements

1. **Detect legacy wiki syntax pages** — both by file extension (`.txt` = wiki) and by content heuristic (scoring patterns like `!!!heading`, `''italic''`, `[{Plugin}]`)
2. **Show a conversion banner in the editor** — when editing a wiki syntax page, display: "This page uses legacy wiki syntax. Convert to Markdown?" with a Convert button
3. **Convert server-side in Java** — REST endpoint accepts wiki text, returns Markdown with warnings
4. **Core syntax + best-effort plugins** — reliably convert headings, formatting, links, lists, tables, code blocks, horizontal rules; preserve plugin/ACL/variable constructs as `[{...}]()` form; flag unconvertible constructs with HTML comments
5. **Migrate file on save** — after conversion, save as `.md` and remove the `.txt` file

## Architecture

### Component Overview

```
PageEditor.jsx                    PageResource.java
  |                                  |
  |-- GET /api/pages/Name ---------> | returns markupSyntax: "wiki"|"markdown"|"likely-wiki"
  |                                  |
  | [shows banner if wiki]           |
  |                                  |
  |-- POST /api/convert/wiki-to-md-> ConvertResource.java
  |                                  |  calls WikiToMarkdownConverter.convert()
  |<-- { markdown, warnings } -------|
  |                                  |
  | [user reviews, edits, saves]     |
  |                                  |
  |-- PUT /api/pages/Name ---------->| saves as .md, deletes .txt
     (markupSyntax: "markdown")      |
```

### 1. Bug Fix: markup.syntax Inference

**Problem:** `FileSystemProvider.getPageInfo()` lines 142 and 156 set `markup.syntax = "markdown"` for `.txt` files. Both else-branches should set `"wiki"`.

**Files:**
- `wikantik-main/src/main/java/com/wikantik/providers/FileSystemProvider.java` — lines 142, 156
- `wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java` — equivalent else-branch

### 2. Wiki Syntax Detection

**File-extension-based (definitive):** `.txt` → `markupSyntax = "wiki"`, `.md` → `markupSyntax = "markdown"`. Already handled by the provider once the bug is fixed.

**Content heuristic (for `.md` pages that may contain unconverted wiki syntax):**

New static method `WikiToMarkdownConverter.isLikelyWikiSyntax(String content)` scores the content:

| Pattern | Points | Rationale |
|---------|--------|-----------|
| `^!{1,3}\s` at line start | 2 | Wiki headings — Markdown uses `#` |
| `''[^']+''` | 2 | Wiki italic — extremely rare in Markdown |
| `\[([^\]|]+)\|([^\]]+)\]` | 2 | Pipe-separated wiki links |
| `\[\{[^}]+\}\]` (without trailing `()`) | 2 | Unconverted plugin syntax |
| `\{\{\{` | 1 | Wiki code block delimiters |
| `^#{1,3}\s+\S` where `#` is used as ordered list | 1 | Wiki ordered lists (context-dependent) |

Threshold: score >= 3 → `"likely-wiki"` (threshold is a constant in the converter class, tunable if false positive rates need adjustment). The heuristic runs in `PageResource.doGet()` when `markupSyntax` is `"markdown"` and is a lightweight pass (regex scan, no full parse).

### 3. WikiToMarkdownConverter

**New file:** `wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java`

**Public API:**
```java
public final class WikiToMarkdownConverter {
    public record ConversionResult(String markdown, List<String> warnings) {}

    public static ConversionResult convert(String wikiText) { ... }
    public static boolean isLikelyWikiSyntax(String content) { ... }
}
```

**Processing model:** Line-by-line with a state machine for multi-line constructs.

**States:**
- `NORMAL` — apply line-by-line regex transformations
- `CODE_BLOCK` — inside `{{{ ... }}}`, emit lines verbatim within fenced code block
- `TABLE` — accumulating table rows, flush as Markdown table on non-table line

**Conversion rules (processing order within each line):**

1. **Code block boundaries:** `{{{` → enter CODE_BLOCK, emit `` ``` ``; `}}}` → exit, emit `` ``` ``
2. **Skip processing in CODE_BLOCK state** — emit lines verbatim
3. **Headings:** `^!!!(.*)` → `# $1`, `^!!(.*)` → `## $1`, `^!(.*)` → `### $1`
4. **Horizontal rules:** `^-{4,}$` → `---`
5. **Unordered lists:** `^(\*{1,})\s+(.*)` → indentation + `* ` (each `*` beyond first = 2 spaces indent)
6. **Ordered lists:** `^(#{1,})\s+(.*)` → indentation + `1. ` (each `#` beyond first = 3 spaces indent)
7. **Table rows:** Lines starting with `||` (header) or `|` (data) → accumulate in table buffer
8. **Flush table** on non-table line → emit Markdown table with `| --- |` separator
9. **Plugin/ACL/variable:** `\[\{([^}]*)\}\]` → `[{$1}]()` — **before** link conversion
10. **Wiki links with text:** `\[([^|\]]+)\|([^\]]+)\]` → `[$1]($2)`
11. **Bare wiki links:** `\[([^\]\[|]+)\]` → `[$1]()` — must not match already-converted patterns
12. **Bold:** `__([^_]+)__` → `**$1**`
13. **Italic:** `''([^']+)''` → `*$1*`
14. **Inline code:** `\{\{([^}]+)\}\}` → `` `$1` `` — must not match `{{{`
15. **Line breaks:** `\\\\` → two trailing spaces + newline

**Warnings:** Unconvertible constructs (e.g., `{style:...}`, deeply nested structures) emit `<!-- WIKI-CONVERT: description -->` inline and add a message to the warnings list.

### 4. REST Endpoint

**New file:** `wikantik-rest/src/main/java/com/wikantik/rest/ConvertResource.java`

Extends `RestServletBase`, mapped to `/api/convert/*`.

**`POST /api/convert/wiki-to-markdown`:**
- Request: `{ "content": "wiki syntax text" }`
- Response: `{ "markdown": "converted text", "warnings": ["..."] }`
- Auth: requires authenticated user (not guest)

**Registration:** Add servlet + mapping in `wikantik-war/src/main/webapp/WEB-INF/web.xml`.

**Modification to `PageResource.doGet()`:** Add `markupSyntax` field to JSON response:
```java
String markupSyntax = page.getAttribute(Page.MARKUP_SYNTAX);
if ("markdown".equals(markupSyntax) && WikiToMarkdownConverter.isLikelyWikiSyntax(content)) {
    markupSyntax = "likely-wiki";
}
result.put("markupSyntax", markupSyntax != null ? markupSyntax : "markdown");
```

### 5. Frontend Changes

**Files modified:**
- `wikantik-frontend/src/components/PageEditor.jsx`
- `wikantik-frontend/src/api/client.js`
- `wikantik-frontend/src/styles/globals.css` (or `admin.css`)

**PageEditor.jsx changes:**

New state:
```javascript
const [markupSyntax, setMarkupSyntax] = useState('markdown');
const [converting, setConverting] = useState(false);
const [conversionWarnings, setConversionWarnings] = useState([]);
```

On page load, capture `page.markupSyntax` from the API response.

Conversion handler calls `api.convertWikiToMarkdown(content)`, replaces editor content with result, sets `markupSyntax` to `'markdown'`, shows warnings.

Info banner (between toolbar and editor):
```jsx
{(markupSyntax === 'wiki' || markupSyntax === 'likely-wiki') && (
  <div className="info-banner">
    <span>This page uses legacy wiki syntax. Convert to Markdown?</span>
    <button onClick={handleConvert} disabled={converting}>
      {converting ? 'Converting...' : 'Convert to Markdown'}
    </button>
  </div>
)}
```

Warning banner (below info banner, after conversion):
```jsx
{conversionWarnings.length > 0 && (
  <div className="warning-banner">
    <strong>Conversion notes:</strong>
    <ul>{conversionWarnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
  </div>
)}
```

**client.js addition:**
```javascript
convertWikiToMarkdown: (content) =>
  request('/api/convert/wiki-to-markdown', {
    method: 'POST',
    body: JSON.stringify({ content }),
  }),
```

**savePage modification:** Add `markupSyntax` to the destructured params and JSON body.

**CSS:** Add `.info-banner` and `.warning-banner` styles with dark mode variants.

### 6. File Extension Migration on Save

When saving a page with `markupSyntax: "markdown"` and the existing file is `.txt`:

1. `PageResource.doPut()` extracts `markupSyntax` from the JSON body, passes to `SaveOptions`
2. `PageSaveHelper.saveText()` sets `page.setAttribute(Page.MARKUP_SYNTAX, "markdown")` (already implemented)
3. `AbstractFileProvider.putPageText()` checks: if `markup.syntax` attribute is `"markdown"` and current file is `.txt`, rename to `.md` before writing
4. Update `fileExtensionCache` after rename
5. Delete old `.txt` file (clean replacement, no backup — version history preserves old content)

## Files to Create

| File | Purpose |
|------|---------|
| `wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java` | Converter + heuristic detection |
| `wikantik-main/src/test/java/com/wikantik/content/WikiToMarkdownConverterTest.java` | Comprehensive unit tests |
| `wikantik-rest/src/main/java/com/wikantik/rest/ConvertResource.java` | REST endpoint |
| `wikantik-rest/src/test/java/com/wikantik/rest/ConvertResourceTest.java` | Endpoint tests |

## Files to Modify

| File | Change |
|------|--------|
| `wikantik-main/.../providers/FileSystemProvider.java` | Fix markup.syntax bug (lines 142, 156) |
| `wikantik-main/.../providers/VersioningFileProvider.java` | Fix markup.syntax bug |
| `wikantik-main/.../providers/AbstractFileProvider.java` | Add file extension migration in `putPageText()` |
| `wikantik-rest/.../rest/PageResource.java` | Add `markupSyntax` to GET response; extract from PUT body |
| `wikantik-war/.../WEB-INF/web.xml` | Register ConvertResource servlet |
| `wikantik-frontend/src/api/client.js` | Add `convertWikiToMarkdown()`, add `markupSyntax` to `savePage()` |
| `wikantik-frontend/src/components/PageEditor.jsx` | Banner, conversion flow, warnings |
| `wikantik-frontend/src/styles/globals.css` | `.info-banner`, `.warning-banner` styles |

## Verification

1. **Unit tests:** `mvn test -Dtest=WikiToMarkdownConverterTest` — 20+ test cases covering every syntax construct, edge cases, combined constructs, and the heuristic detector
2. **REST tests:** `mvn test -Dtest=ConvertResourceTest` — endpoint returns correct JSON for valid input, handles empty input, requires authentication
3. **Provider tests:** `mvn test -Dtest=FileSystemProviderTest` — `.txt` pages return `markup.syntax=wiki`; saving with `markupSyntax=markdown` migrates `.txt` to `.md`
4. **Manual test:**
   - Place a `.txt` file with wiki syntax in `docs/wikantik-pages/`
   - Open the page in the editor → verify banner appears
   - Click Convert → verify Markdown appears in editor with correct preview
   - Save → verify file on disk is now `.md`, content is Markdown
   - Re-open editor → verify no banner (now Markdown)
5. **Full build:** `mvn clean install -T 1C -DskipITs` — all modules build successfully
