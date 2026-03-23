# ADR-001: Extract Manager Interfaces to wikantik-api

**Status:** Implemented (Phases 1-5 complete, Phase 6 partially complete)
**Date:** 2026-03-22 (proposed) → 2026-03-23 (implemented)
**Deciders:** jakefear

## Context

The MCP module (`wikantik-mcp`) depended directly on manager interfaces and utility types defined in `wikantik-main`. This meant MCP couldn't compile without the full engine implementation, preventing alternative engine implementations, lightweight testing, and clean module boundaries.

## What was done

### Phase 1-4: Manager interfaces extracted (complete)

8 types moved from wikantik-main to wikantik-api with backward-compatibility shims:

| Type | API Location | Shim in Main |
|------|-------------|-------------|
| `InternalModule` | `com.wikantik.api.modules` | `com.wikantik.modules` |
| `SystemPageRegistry` | `com.wikantik.api.managers` | `com.wikantik.content` |
| `PageManager` | `com.wikantik.api.managers` | `com.wikantik.pages` |
| `ReferenceManager` | `com.wikantik.api.managers` | `com.wikantik.references` |
| `AttachmentManager` | `com.wikantik.api.managers` | `com.wikantik.attachment` |
| `DynamicAttachment` | `com.wikantik.api.attachment` | `com.wikantik.attachment` |
| `DynamicAttachmentProvider` | `com.wikantik.api.attachment` | `com.wikantik.attachment` |
| `PageLock` | `com.wikantik.api.pages` | `com.wikantik.pages` |

The shim approach meant zero import changes were needed in existing wikantik-main code. The ADR originally estimated 20-30 hours; actual time was ~3 hours thanks to the shim pattern.

### Phase 5: Utility types and concrete classes moved (complete)

7 additional types moved directly (no shims — import updates applied via sed):

| Type | API Location | Notes |
|------|-------------|-------|
| `FrontmatterParser` | `com.wikantik.api.frontmatter` | Pure utility, only SnakeYAML deps |
| `FrontmatterWriter` | `com.wikantik.api.frontmatter` | Pure utility, only SnakeYAML deps |
| `ParsedPage` | `com.wikantik.api.frontmatter` | Record, no deps |
| `MarkdownLinkScanner` | `com.wikantik.api.parser` | Pure regex, no deps |
| `PageSaveHelper` | `com.wikantik.api.pages` | All imports already from API |
| `SaveOptions` | `com.wikantik.api.pages` | Record, no deps |
| `VersionConflictException` | `com.wikantik.api.pages` | Extends WikiException (API) |

### Phase 6: MCP decoupling from WikiEngine (partially complete)

13 MCP tool constructors refactored to accept managers directly instead of WikiEngine. Tools now receive `PageSaveHelper`, `PageManager`, etc. as constructor params. `McpToolRegistry` resolves all managers and creates the `PageSaveHelper` once.

MCP source imports from wikantik-main reduced from **15 to 5**:
- `WikiEngine` — bootstrapping in McpServerInitializer
- `PageRenamer` — used by RenamePageTool
- `DifferenceManager` — used by DiffPageTool
- `SearchManager` — used by SearchPagesTool
- `SitemapServlet` — one constant reference

### Test infrastructure decoupled (complete)

Created lightweight test stubs that don't require WikiEngine:
- `StubPageManager` — in-memory PageManager with locking, version history
- `StubSystemPageRegistry` — configurable system page registry
- `StubReferenceManager` — in-memory reference tracking with addReferences()
- `StubPageSaveHelper` — extends PageSaveHelper, saves to StubPageManager

29 of 36 MCP tool test classes converted from TestEngine to stubs. Test execution time for converted tests: 2-27ms each (previously 1-3 seconds).

## Remaining work

### Would further reduce MCP→main coupling (diminishing returns)
- Extract `PageRenamer`, `DifferenceManager`, `SearchManager` interfaces to API (same shim pattern, ~2 hours)
- Replace `SitemapServlet.PROP_SITEMAP_BASE_URL` constant with string literal (~5 minutes)
- After that, only `WikiEngine` import remains (irreducible — needed for bootstrapping)

### Would further improve test infrastructure
- Convert 7 remaining TestEngine-dependent MCP tests (need Engine for Context creation)
- Create `StubAttachmentManager` for attachment tool tests
- Reduce TestEngine startup cost for wikantik-main tests (JDBC tests are the real bottleneck at 34 seconds)

## Key insight

The backward-compatibility shim pattern made this dramatically easier than expected. Instead of updating 200+ imports, each interface move was: create in API, leave `@Deprecated` shim in old location, done. The shim extends the API version, so all existing code continues to work unchanged. Import migration can happen gradually.

For concrete types (records, classes), direct moves with sed-based import updates worked well. 33 files updated in seconds.

## Module dependency graph (current)

```
wikantik-api ← wikantik-event, wikantik-util, snakeyaml
  Contains: Engine, Page, Context, PageProvider, AttachmentProvider,
            PageManager, ReferenceManager, AttachmentManager, SystemPageRegistry,
            InternalModule, PageLock, PageSaveHelper, SaveOptions,
            FrontmatterParser/Writer/ParsedPage, MarkdownLinkScanner

wikantik-main ← wikantik-api, wikantik-event, wikantik-cache, wikantik-http
  Contains: WikiEngine, DefaultPageManager, DefaultReferenceManager, etc.
  Shims: PageManager, ReferenceManager, AttachmentManager, etc.

wikantik-mcp ← wikantik-api (primary), wikantik-main (5 imports remaining)
```
