# Obsidian-Compatible Content Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated user pick a slice of the wiki (clusters + filters + link hops), preview its size, and download it as a zip that opens directly as an Obsidian vault.

**Architecture:** Pure, engine-free units in `wikantik-main` `com.wikantik.export` (selection resolver, page converter, vault writer) behind two small ports (`ExportCatalog`, `ExportLinkContext`); an `ExportService` adapts them onto the engine subsystems; a thin `ExportResource` in `wikantik-rest` exposes `GET /api/export{,/preview,/options}` and streams a `ZipOutputStream`; a React `ExportDialog` drives it. Gated by a new `export` wiki permission granted to Authenticated.

**Tech Stack:** Java 25, flexmark (bare CommonMark parser + tables ext for AST spans), SnakeYAML (via `FrontmatterParser`), `java.util.zip`, Jakarta Servlet, JUnit 5 + Mockito, PostgresTestDb, React 19 + vitest.

**Spec:** `docs/superpowers/specs/2026-09-29-obsidian-export-design.md`

## Global Constraints

- Every new `.java` file starts with the Apache license header used across the repo (copy from `wikantik-main/src/main/java/com/wikantik/citation/CitationMarkupParser.java:1-18`).
- Code style: spaces inside parens/generics as in surrounding code (`foo( a, b )`, `List< String >`), `final` on params/locals.
- Never swallow exceptions: every `catch` logs at least `LOG.warn(...)` with context (CLAUDE.md rule) — except client-disconnect `IOException` during streaming, which is logged at DEBUG per spec §8.
- TDD: each test is run and seen to FAIL before the implementation is written.
- New code in wikantik-main must not call `WikiEngine.getManager(...)` (DecompositionArchTest R-2); take dependencies through constructors / `WikiSubsystems`.
- REST errors go through `RestServletBase.sendError(...)`, never `response.sendError(...)`.
- Stage files by name; never `git add -A`. Commit messages 1–3 lines ending with the two attribution lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg
  ```
- Work directly on `main` (sole developer). Agent worktrees base on `origin/main` — if using `isolation: worktree`, first `git reset --hard main` inside it.
- Unset `WIKANTIK_*` env vars when running Maven (`env -u ...` or `bin/agent-build.sh`, which does it).
- Module-scoped test runs: `mvn test -pl wikantik-main -Dtest=ClassName -q` (add `-am` only if an upstream module changed, then `-Dsurefire.failIfNoSpecifiedTests=false`). After signature changes run `mvn test-compile -pl <module>`.
- Config key: `wikantik.export.maxPages`, int, default `2000`.
- Wire params: `cluster` (repeatable), `subclusters=true|false` (default true), `tag` (repeatable), `type`, `status`, `hops` (0–2, default 0), `unresolved=keep|url` (default keep).

## Deviations from the spec (decided while planning)

1. **Seed set comes from `StructuralIndexService.sitemap().pages()`**, filtered in memory, not `listPagesByFilter` — the latter clamps to 1000 rows and ignores its `cursor`, which would silently truncate a whole-corpus export (~1200 pages). `status` is not in `PageDescriptor`, so it is filtered from each candidate's frontmatter.
2. **`GET /api/export/options`** is added (clusters + tags for the dialog pickers) — the SPA has no general cluster/tag listing endpoint.
3. **Frontmatter is patched textually, not re-serialised** — SnakeYAML round-tripping turns `date: 2026-01-15` into a timestamp, which breaks Obsidian date properties. Only `related`/`aliases` lines are replaced; `wikantik_url`/`wikantik_version` are appended.
4. **No rate-limit tier change.** The expensive tier (3/s) matches by prefix and would 429 the debounced preview; the default tier (25/s) already covers `/api/*`.

## File map

**wikantik-main** `src/main/java/com/wikantik/export/`
| File | Responsibility |
|---|---|
| `UnresolvedLinkMode.java` | enum KEEP / URL + `fromWire` |
| `ExportSelection.java` | selection record + validation |
| `ExportCatalog.java` | port: page universe, status, outbound links, ACL filter |
| `ExportSelectionResolver.java` | seed → hops → ACL, pure |
| `HeadingSlugs.java` | GitHub-style heading slug + heading extraction |
| `FrontmatterPatcher.java` | textual frontmatter patching |
| `ExportLinkContext.java` | port the converter asks about link targets |
| `ConvertedPage.java`, `AttachmentRef.java` | converter output records |
| `ObsidianPageConverter.java` | body rewrite by AST/regex spans |
| `VaultLayout.java` | page + attachment paths, sanitising, collisions |
| `ExportManifest.java` | manifest records + JSON |
| `ObsidianVaultWriter.java` | ZipOutputStream wrapper, sha256, readme |
| `ExportTooLargeException.java` | cap exceeded |
| `ExportPreview.java`, `ExportOptions.java` | preview/options DTOs |
| `ExportService.java` | orchestration on engine services |
| `EngineExportCatalog.java` | `ExportCatalog` over engine services |

**Other**: `WikiPermission.java`, `PolicyRoleTable.java`, `AdminPolicyResource.java`, both `wikantik.policy`, `postgresql-test-seed.sql`, `it-test-seed.sql`, `bin/db/migrations/V060__wiki_export_permission.sql`, `ini/wikantik.properties`, generated config docs, `wikantik-rest/.../ExportResource.java`, `web.xml`, frontend (`api/client.js`, `components/ExportDialog.jsx`, `PersonalZone.jsx`, `Sidebar.jsx`, `PageView.jsx`, `admin/PolicyGrantFormModal.jsx`, `styles/globals.css`), IT `ExportIT.java`, `CLAUDE.md` servlet count.

## Execution order

T1, T2, T3, T4, T5 have disjoint files → dispatch in parallel. T6 needs T2–T5. T7 needs T6. T8 needs only the wire contract (can run alongside T7). T9 needs T7. T10 last.

---

### Task 1: `export` wiki permission

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/permissions/WikiPermission.java` (constants L44-84, `createMask` L240-264)
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/subsystem/verify/PolicyRoleTable.java:148`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminPolicyResource.java:66` (valid wiki actions whitelist)
- Modify: `wikantik-frontend/src/components/admin/PolicyGrantFormModal.jsx:7`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/wikantik.policy` and `wikantik-main/src/test/resources/wikantik.policy` (Authenticated block: `"createPages,createGroups,export"`)
- Modify: `wikantik-main/src/test/resources/postgresql-test-seed.sql` and `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql` — the Authenticated `wiki` row gains `export`
- Create: `bin/db/migrations/V060__wiki_export_permission.sql` (confirm V060 is still the next free number: `ls bin/db/migrations | tail -1`)
- Modify: `bin/db/migrations/README.md` "currently up to V0NN" line → V060
- Test: `wikantik-main/src/test/java/com/wikantik/auth/permissions/WikiPermissionTest.java`
- Create test: `wikantik-main/src/test/java/com/wikantik/auth/WikiExportPermissionMigrationTest.java`

**Interfaces — Produces:** `WikiPermission.EXPORT_ACTION = "export"`, `WikiPermission.EXPORT_MASK = 0x20`, `public static final WikiPermission EXPORT`.

- [ ] **Step 1: failing permission tests** — add to `WikiPermissionTest`:

```java
@Test
public void testExportMask() {
    Assertions.assertEquals( 32, WikiPermission.createMask( "export" ) );
    Assertions.assertEquals( 34, WikiPermission.createMask( "createPages,export" ) );
}

@Test
public void testExportImplication() {
    final WikiPermission authed = new WikiPermission( "*", "createPages,createGroups,export" );
    Assertions.assertTrue( authed.implies( WikiPermission.EXPORT ) );
    final WikiPermission noExport = new WikiPermission( "*", "createPages,createGroups" );
    Assertions.assertFalse( noExport.implies( WikiPermission.EXPORT ) );
    Assertions.assertEquals( "export", WikiPermission.EXPORT.getActions() );
}
```

- [ ] **Step 2:** `mvn test -pl wikantik-main -Dtest=WikiPermissionTest -q` → FAIL (`EXPORT` undefined / "Unrecognized action: export").

- [ ] **Step 3: implement** in `WikiPermission`:

```java
/** Action for bulk-exporting content the caller can view (Obsidian vault zip). */
public static final String         EXPORT_ACTION           = "export";
...
static final int         EXPORT_MASK             = 0x20;
...
/** A static instance of the export permission. */
public static final WikiPermission EXPORT                  = new WikiPermission( WILDCARD, EXPORT_ACTION );
```
and in `createMask` before the final `else`:
```java
} else if (EXPORT_ACTION.equalsIgnoreCase(action)) {
    mask |= EXPORT_MASK;
```
Update the javadoc at L135 listing actions. `export` is NOT implied by any other action (`impliedMask` unchanged).

- [ ] **Step 4:** re-run → PASS.

- [ ] **Step 5: enumeration sites.** `PolicyRoleTable` `wikiPerms` → append `"export"`. `AdminPolicyResource.java:66` list → append `"export"`. `PolicyGrantFormModal.jsx:7` → `wiki: ['createPages', 'createGroups', 'editPreferences', 'editProfile', 'login', 'export'],`. Both `wikantik.policy` Authenticated lines → `"createPages,createGroups,export"`. Seeds: in both seed SQL files change the Authenticated wiki actions to `'createPages,createGroups,export'`. Then run `mvn test -pl wikantik-rest -Dtest='AdminPolicyResource*' -q` and `grep -rn "createPages,createGroups\"" --include=*Test.java .` — fix any test that asserts the exact old action list.

- [ ] **Step 6: failing migration test** — create `WikiExportPermissionMigrationTest` modelled on `wikantik-main/src/test/java/com/wikantik/auth/PolicyGrantConvergenceMigrationTest.java` (copy its `repoRoot()`, `exec`, `insert` helpers verbatim):

```java
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class WikiExportPermissionMigrationTest {
    private DataSource ds;
    private String migrationSql;

    @BeforeAll
    void setUp() throws Exception {
        ds = PostgresTestDb.createDataSource();
        migrationSql = Files.readString( repoRoot().resolve( "bin/db/migrations/V060__wiki_export_permission.sql" ) );
    }

    @BeforeEach
    void clean() throws Exception { exec( "DELETE FROM policy_grants" ); }

    @Test
    void defaultAuthenticatedRowGainsExport() throws Exception {
        insert( "role", "Authenticated", "wiki", "*", "createPages,createGroups" );
        runMigration();
        assertEquals( "createPages,createGroups,export", authenticatedWikiActions() );
    }

    @Test
    void customisedRowIsLeftAlone() throws Exception {
        insert( "role", "Authenticated", "wiki", "*", "createPages" );
        runMigration();
        assertEquals( "createPages", authenticatedWikiActions() );
    }

    @Test
    void rerunIsNoOp() throws Exception {
        insert( "role", "Authenticated", "wiki", "*", "createPages,createGroups" );
        runMigration();
        runMigration();
        assertEquals( "createPages,createGroups,export", authenticatedWikiActions() );
    }

    @Test
    void missingRowIsNotCreated() throws Exception {
        runMigration();
        assertNull( authenticatedWikiActions() );
    }

    private String authenticatedWikiActions() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( "SELECT actions FROM policy_grants WHERE principal_type='role' "
                      + "AND principal_name='Authenticated' AND permission_type='wiki' AND target='*'" ) ) {
            return rs.next() ? rs.getString( 1 ) : null;
        }
    }
    // runMigration / exec / insert / repoRoot: copy from PolicyGrantConvergenceMigrationTest
}
```
(Direct JDBC in a test is fine — J-1 `JdbcAccessArchTest` covers production code only; confirm by checking that the copied test already uses `ds.getConnection()`.)

- [ ] **Step 7:** `mvn test -pl wikantik-main -Dtest=WikiExportPermissionMigrationTest -q` → FAIL (file not found).

- [ ] **Step 8: migration**:

```sql
-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: grant the new 'export' wiki permission (Obsidian vault export) to
-- Authenticated by default.
--
-- Conservative and idempotent, modelled on V043: the Authenticated wiki row is
-- only touched while it still holds V003's stock actions, so an operator who has
-- customised the row is never silently granted export. Re-running finds the row
-- no longer equal to the default and does nothing. Revoke via /admin/security.
UPDATE policy_grants
   SET actions = 'createPages,createGroups,export'
 WHERE principal_type = 'role' AND principal_name = 'Authenticated'
   AND permission_type = 'wiki' AND target = '*'
   AND actions = 'createPages,createGroups';
```

- [ ] **Step 9:** re-run → PASS (needs Docker; if it SKIPs, say so — do not claim PASS). Also run `mvn test -pl wikantik-main -Dtest='WikiPermission*,PolicyRoleTable*,DatabasePolicyTest,AuthorizationManagerTest' -q` → PASS. Run `cd wikantik-frontend && npx vitest run src/components/admin/PolicyGrantFormModal` → PASS.

- [ ] **Step 10: commit** all files above: `feat(auth): add 'export' wiki permission, granted to Authenticated by default`.

---

### Task 2: `HeadingSlugs` and `FrontmatterPatcher`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/export/HeadingSlugs.java`
- Create: `wikantik-main/src/main/java/com/wikantik/export/FrontmatterPatcher.java`
- Test: `wikantik-main/src/test/java/com/wikantik/export/HeadingSlugsTest.java`, `FrontmatterPatcherTest.java`

**Interfaces — Produces:**
- `static String HeadingSlugs.slug( String headingText )`
- `static Map< String, String > HeadingSlugs.headingsBySlug( String markdownBody )` — slug → heading text, first occurrence wins, insertion-ordered
- `static String FrontmatterPatcher.patch( String rawPageText, Map< String, Object > replaceOrAdd )` — returns the full page text with the frontmatter block patched; values are `String` or `List< String >`

- [ ] **Step 1: failing tests**

```java
class HeadingSlugsTest {
    @Test void githubStyleSlug() {
        assertEquals( "2-advanced-portfolio-optimization-hrp-2025",
                HeadingSlugs.slug( "2. Advanced Portfolio Optimization (HRP, 2025)" ) );
        assertEquals( "case-study-the-2026-iran-war-shock", HeadingSlugs.slug( "Case Study: The 2026 Iran War Shock" ) );
        assertEquals( "a--b", HeadingSlugs.slug( "A & B" ) );
    }
    @Test void headingsBySlugIgnoresCodeBlocks() {
        final String body = "# Top\n\n```\n# not a heading\n```\n\n## Second Part\n";
        final Map< String, String > m = HeadingSlugs.headingsBySlug( body );
        assertEquals( List.of( "top", "second-part" ), List.copyOf( m.keySet() ) );
        assertEquals( "Second Part", m.get( "second-part" ) );
    }
    @Test void inlineMarkupStrippedFromHeadingText() {
        assertEquals( "Using Foo", HeadingSlugs.headingsBySlug( "## Using `Foo`\n" ).get( "using-foo" ) );
    }
}

class FrontmatterPatcherTest {
    @Test void replacesRelatedAndAppendsNewKeysPreservingOtherLines() {
        final String raw = "---\ntype: article\ndate: 2026-01-15\nrelated:\n  - Foo\n  - Bar\ntags: [a, b]\n---\n# Body\n";
        final Map< String, Object > patch = new LinkedHashMap<>();
        patch.put( "related", List.of( "[[Foo]]", "[[Bar]]" ) );
        patch.put( "wikantik_url", "https://w.example/wiki/Page" );
        final String out = FrontmatterPatcher.patch( raw, patch );
        assertEquals( "---\ntype: article\ndate: 2026-01-15\ntags: [a, b]\n"
                + "related:\n  - \"[[Foo]]\"\n  - \"[[Bar]]\"\n"
                + "wikantik_url: \"https://w.example/wiki/Page\"\n---\n# Body\n", out );
    }
    @Test void inlineListValueReplaced() {
        final String out = FrontmatterPatcher.patch( "---\nrelated: [Foo]\n---\nx", Map.of( "related", List.of( "[[Foo]]" ) ) );
        assertEquals( "---\nrelated:\n  - \"[[Foo]]\"\n---\nx", out );
    }
    @Test void noFrontmatterCreatesBlock() {
        assertEquals( "---\nwikantik_version: \"3\"\n---\nbody", FrontmatterPatcher.patch( "body", Map.of( "wikantik_version", "3" ) ) );
    }
    @Test void quotesEscaped() {
        assertTrue( FrontmatterPatcher.patch( "x", Map.of( "aliases", List.of( "Say \"hi\"" ) ) )
                .contains( "  - \"Say \\\"hi\\\"\"\n" ) );
    }
}
```

- [ ] **Step 2:** `mvn test -pl wikantik-main -Dtest='HeadingSlugsTest,FrontmatterPatcherTest' -q` → FAIL (classes missing).

- [ ] **Step 3: implement**

```java
package com.wikantik.export;

/** GitHub-compatible heading slugs, matching the anchors wiki pages link with ({@code Page#slug}). */
public final class HeadingSlugs {
    private static final Parser PARSER = Parser.builder().build();
    private HeadingSlugs() {}

    public static String slug( final String headingText ) {
        final String lower = headingText.trim().toLowerCase( Locale.ROOT );
        final StringBuilder sb = new StringBuilder( lower.length() );
        for ( int i = 0; i < lower.length(); i++ ) {
            final char c = lower.charAt( i );
            if ( Character.isLetterOrDigit( c ) || c == '-' || c == '_' ) { sb.append( c ); }
            else if ( c == ' ' ) { sb.append( '-' ); }
        }
        return sb.toString();
    }

    public static Map< String, String > headingsBySlug( final String markdownBody ) {
        final Map< String, String > out = new LinkedHashMap<>();
        final Document doc = PARSER.parse( markdownBody );
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h ) {
                final String text = TextCollectingVisitor.collectAndGetText( h ).trim();  // com.vladsch.flexmark.util.ast.TextCollectingVisitor
                out.putIfAbsent( slug( text ), text );
            }
        }
        return out;
    }
}
```
(If `TextCollectingVisitor.collectAndGetText` is not static in the flexmark version on the classpath, use `new TextCollectingVisitor().collectAndGetText( h )`. Check with `mvn dependency:tree -pl wikantik-main | grep flexmark`.)

```java
package com.wikantik.export;

/**
 * Patches a page's YAML frontmatter block textually. Re-serialising through SnakeYAML would
 * rewrite scalars (dates become timestamps), which breaks Obsidian properties — so every line
 * we do not own is copied verbatim; owned top-level keys are removed and re-appended.
 */
public final class FrontmatterPatcher {
    private FrontmatterPatcher() {}

    public static String patch( final String raw, final Map< String, Object > replaceOrAdd ) {
        final String text = raw == null ? "" : raw;
        final List< String > kept = new ArrayList<>();
        String body = text;
        if ( text.startsWith( "---\n" ) || text.startsWith( "---\r\n" ) ) {
            final int firstNl = text.indexOf( '\n' );
            final int close = findClose( text, firstNl + 1 );
            if ( close >= 0 ) {
                final String block = text.substring( firstNl + 1, close );
                final int afterClose = text.indexOf( '\n', close );
                body = afterClose < 0 ? "" : text.substring( afterClose + 1 );
                boolean skipping = false;
                for ( final String line : block.split( "\r?\n", -1 ) ) {
                    if ( line.isEmpty() && kept.isEmpty() ) { continue; }
                    final boolean topLevel = !line.isEmpty() && !Character.isWhitespace( line.charAt( 0 ) ) && !line.startsWith( "- " );
                    if ( topLevel ) {
                        final int colon = line.indexOf( ':' );
                        final String key = colon > 0 ? line.substring( 0, colon ).trim() : "";
                        skipping = replaceOrAdd.containsKey( key );
                    }
                    if ( !skipping ) { kept.add( line ); }
                }
                while ( !kept.isEmpty() && kept.get( kept.size() - 1 ).isEmpty() ) { kept.remove( kept.size() - 1 ); }
            }
        }
        final StringBuilder sb = new StringBuilder( "---\n" );
        for ( final String line : kept ) { sb.append( line ).append( '\n' ); }
        for ( final Map.Entry< String, Object > e : replaceOrAdd.entrySet() ) {
            if ( e.getValue() instanceof List< ? > list ) {
                sb.append( e.getKey() ).append( ":\n" );
                for ( final Object item : list ) { sb.append( "  - " ).append( quote( String.valueOf( item ) ) ).append( '\n' ); }
            } else {
                sb.append( e.getKey() ).append( ": " ).append( quote( String.valueOf( e.getValue() ) ) ).append( '\n' );
            }
        }
        return sb.append( "---\n" ).append( body ).toString();
    }

    private static int findClose( final String text, final int from ) {
        int idx = from;
        while ( idx < text.length() ) {
            final int nl = text.indexOf( '\n', idx );
            final String line = ( nl < 0 ? text.substring( idx ) : text.substring( idx, nl ) ).stripTrailing();
            if ( line.equals( "---" ) ) { return idx; }
            if ( nl < 0 ) { return -1; }
            idx = nl + 1;
        }
        return -1;
    }

    private static String quote( final String s ) {
        return "\"" + s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" ) + "\"";
    }
}
```
Note the `noFrontmatterCreatesBlock` expectation: with no frontmatter the body is the whole text (`"body"`), output `---\nwikantik_version: "3"\n---\nbody`.

- [ ] **Step 4:** re-run → PASS. Add a test for a malformed/unclosed `---` block (treated as no frontmatter: the original text becomes the body) and make it pass.

- [ ] **Step 5: commit** `feat(export): heading slugs and textual frontmatter patcher`.

---

### Task 3: `ObsidianPageConverter`

**Depends on:** Task 2 (`HeadingSlugs`, `FrontmatterPatcher`). Run in the same agent as Task 2 or after it.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/export/{UnresolvedLinkMode,ExportLinkContext,AttachmentRef,ConvertedPage,ObsidianPageConverter}.java`
- Test: `wikantik-main/src/test/java/com/wikantik/export/ObsidianPageConverterTest.java`

**Interfaces — Produces:**

```java
public enum UnresolvedLinkMode {
    KEEP, URL;
    /** null/blank → KEEP; case-insensitive; otherwise IllegalArgumentException. */
    public static UnresolvedLinkMode fromWire( final String raw ) { ... }
}

/** What the converter needs to know about link targets. Implemented by ExportService; faked in tests. */
public interface ExportLinkContext {
    boolean inExport( String pageName );
    /** Heading text on {@code pageName} whose slug is {@code slug}, if any. */
    Optional< String > headingText( String pageName, String slug );
    /** Obsidian link target for an attachment ("file.png" or "_attachments/Page/file.png"); empty if not an attachment. */
    Optional< String > attachmentTarget( String pageName, String fileName );
    Optional< String > slugForCanonicalId( String canonicalId );
    String liveUrl( String pageName );
    UnresolvedLinkMode unresolvedMode();
}

public record AttachmentRef( String pageName, String fileName ) {}
public record ConvertedPage( String markdown, List< AttachmentRef > attachments, List< String > warnings ) {}

public final class ObsidianPageConverter {
    /** @param extraFrontmatter keys replaced/added via FrontmatterPatcher (related, aliases, wikantik_url, wikantik_version) */
    public ConvertedPage convert( String pageName, String rawText, Map< String, Object > extraFrontmatter, ExportLinkContext ctx );
}
```

Rules (spec §5):

| Input (outside code) | Output |
|---|---|
| `[T](P)`, P in export | `[[P\|T]]`; `[[P]]` when T equals P |
| `[T](P#slug)` | `[[P#Heading\|T]]` if slug resolves, else `[[P\|T]]` |
| `[T](#slug)` | `[[#Heading\|T]]` if resolves, else unchanged |
| P not in export, mode KEEP | `[[P\|T]]` (same form as in-export) |
| P not in export, mode URL | `[T](<liveUrl(P)>)` |
| `[T](P/f.ext)` or `[T](f.ext)` (attachment of current page) | image ext → `![[target]]`, else `[[target\|T]]`; `AttachmentRef` recorded |
| `![alt](P/f.png)` attachment | `![[target]]` |
| `[claim](cite://<canonicalId>/<encoded heading path> "span")` | `[[Slug#Heading\|claim]][^cN]` + `[^cN]: span` appended at end of body; unresolvable id → claim text + footnote only, warning |
| `[{ALLOW …}]`, `[{DENY …}]`, `[{SET …}]`, `[{$var}]`, `[{TableOfContents …}]` (with or without trailing `()`) | removed (a line left empty by removal is dropped) |
| `[{InsertPage page=X …}]` (also `page='X'`, `page="X"`) | `![[X]]` |
| other `[{Name …}]` alone on its line | `> [!note] Wiki plugin omitted: Name — [view on the wiki](<liveUrl(page)>)` |
| other `[{Name …}]` inline | `*(wiki plugin omitted: Name)*` |
| external URLs (`scheme:`), code spans/blocks, math, tables | unchanged |
| Link inside a table cell | the alias pipe is written `\|` |
| Alias containing `[`, `]` or `\|` | those characters are removed from the alias |

Image extensions: `png jpg jpeg gif svg webp bmp avif`.

- [ ] **Step 1: failing tests** — use a fake context:

```java
class ObsidianPageConverterTest {
    private final ObsidianPageConverter conv = new ObsidianPageConverter();

    private static final class FakeCtx implements ExportLinkContext {
        Set< String > pages = Set.of( "Foo", "Bar", "Here" );
        Map< String, Map< String, String > > headings = Map.of(
                "Foo", Map.of( "setup-steps", "Setup Steps" ),
                "Here", Map.of( "intro", "Intro" ) );
        Set< String > attachments = Set.of( "Here/chart.png", "Here/report.pdf", "Foo/diagram.svg" );
        UnresolvedLinkMode mode = UnresolvedLinkMode.KEEP;
        public boolean inExport( String p ) { return pages.contains( p ); }
        public Optional< String > headingText( String p, String s ) { return Optional.ofNullable( headings.getOrDefault( p, Map.of() ).get( s ) ); }
        public Optional< String > attachmentTarget( String p, String f ) { return attachments.contains( p + "/" + f ) ? Optional.of( f ) : Optional.empty(); }
        public Optional< String > slugForCanonicalId( String id ) { return "01FOO".equals( id ) ? Optional.of( "Foo" ) : Optional.empty(); }
        public String liveUrl( String p ) { return "https://w.example/wiki/" + p; }
        public UnresolvedLinkMode unresolvedMode() { return mode; }
    }

    private String body( final String raw ) { return body( raw, new FakeCtx() ); }
    private String body( final String raw, final FakeCtx ctx ) {
        final String md = conv.convert( "Here", raw, Map.of(), ctx ).markdown();
        return md.substring( md.indexOf( "---\n", 4 ) + 4 );   // strip the generated frontmatter block
    }

    @Test void internalLink() { assertEquals( "See [[Foo|the foo]].\n", body( "See [the foo](Foo).\n" ) ); }
    @Test void internalLinkSameText() { assertEquals( "[[Foo]]\n", body( "[Foo](Foo)\n" ) ); }
    @Test void anchorResolved() { assertEquals( "[[Foo#Setup Steps|go]]\n", body( "[go](Foo#setup-steps)\n" ) ); }
    @Test void anchorUnresolvedFallsBack() { assertEquals( "[[Foo|go]]\n", body( "[go](Foo#nope)\n" ) ); }
    @Test void samePageAnchor() { assertEquals( "[[#Intro|up]]\n", body( "[up](#intro)\n" ) ); }
    @Test void outOfScopeKeep() { assertEquals( "[[Elsewhere|x]]\n", body( "[x](Elsewhere)\n" ) ); }
    @Test void outOfScopeUrl() {
        final FakeCtx c = new FakeCtx(); c.mode = UnresolvedLinkMode.URL;
        assertEquals( "[x](https://w.example/wiki/Elsewhere)\n", body( "[x](Elsewhere)\n", c ) );
    }
    @Test void externalUntouched() { assertEquals( "[g](https://google.com)\n", body( "[g](https://google.com)\n" ) ); }
    @Test void linkInsideCodeUntouched() {
        final String raw = "`[a](Foo)`\n\n```\n[b](Foo)\n[{ALLOW view Admin}]\n```\n";
        assertEquals( raw, body( raw ) );
    }
    @Test void imageAttachment() {
        final ConvertedPage p = conv.convert( "Here", "![c](Here/chart.png)\n", Map.of(), new FakeCtx() );
        assertTrue( p.markdown().endsWith( "![[chart.png]]\n" ) );
        assertEquals( List.of( new AttachmentRef( "Here", "chart.png" ) ), p.attachments() );
    }
    @Test void bareAttachmentOfCurrentPage() { assertEquals( "[[report.pdf|Report]]\n", body( "[Report](report.pdf)\n" ) ); }
    @Test void imageLinkSyntaxToAttachment() { assertEquals( "![[diagram.svg]]\n", body( "[d](Foo/diagram.svg)\n" ) ); }
    @Test void citation() {
        final String out = body( "Claim [it works](cite://01FOO/Setup%20Steps \"exact span\") ok.\n" );
        assertEquals( "Claim [[Foo#Setup Steps|it works]][^c1] ok.\n\n[^c1]: exact span\n", out );
    }
    @Test void aclAndSetRemoved() {
        assertEquals( "Text\n", body( "[{ALLOW view Admin}]\n[{SET foo=bar}]()\nText\n" ) );
    }
    @Test void tocRemoved() { assertEquals( "# H\n", body( "[{TableOfContents}]\n# H\n" ) ); }
    @Test void insertPageEmbeds() { assertEquals( "![[Bar]]\n", body( "[{InsertPage page='Bar'}]\n" ) ); }
    @Test void otherPluginOwnLineBecomesCallout() {
        assertEquals( "> [!note] Wiki plugin omitted: Relationships — [view on the wiki](https://w.example/wiki/Here)\n",
                body( "[{Relationships depth=2}]\n" ) );
    }
    @Test void otherPluginInline() { assertEquals( "Now *(wiki plugin omitted: CurrentTimePlugin)* ok\n", body( "Now [{CurrentTimePlugin}] ok\n" ) ); }
    @Test void tableCellPipeEscaped() {
        final String raw = "| a | b |\n|---|---|\n| [x](Foo) | y |\n";
        assertEquals( "| a | b |\n|---|---|\n| [[Foo\\|x]] | y |\n", body( raw ) );
    }
    @Test void mathUntouched() {
        final String raw = "Inline $x_1$ and\n\n$$\n\\mathbb{E}[X]\n$$\n";
        assertEquals( raw, body( raw ) );
    }
    @Test void untouchedBytesIdentical() {
        final String raw = "Some *emphasis*,  double  spaces,\ttabs and a [x](Foo).\n\n- list\n";
        assertEquals( "Some *emphasis*,  double  spaces,\ttabs and a [[Foo|x]].\n\n- list\n", body( raw ) );
    }
    @Test void aliasBracketsStripped() { assertEquals( "[[Foo|a b]]\n", body( "[a [b]](Foo)\n" ) ); }
    @Test void frontmatterPreservedAndExtended() {
        final String raw = "---\ntype: article\ndate: 2026-01-15\n---\nx\n";
        final String md = conv.convert( "Here", raw, Map.of( "wikantik_version", "4" ), new FakeCtx() ).markdown();
        assertEquals( "---\ntype: article\ndate: 2026-01-15\nwikantik_version: \"4\"\n---\nx\n", md );
    }
}
```

- [ ] **Step 2:** `mvn test -pl wikantik-main -Dtest=ObsidianPageConverterTest -q` → FAIL.

- [ ] **Step 3: implement.** Algorithm:
  1. `FrontmatterParser.parse( rawText )` → `body` (use its `body()`; the frontmatter text itself is re-derived by `FrontmatterPatcher.patch( rawText, extra )` at the end, which replaces the *body* portion — so compute `prefix = rawText.substring(0, rawText.length() - body.length())` only for sanity; final output = `FrontmatterPatcher.patch( rawText-with-new-body )`. Simplest: build `newBody`, then `FrontmatterPatcher.patch( frontmatterPrefix + newBody, extra )` where `frontmatterPrefix = rawText.substring( 0, rawText.lastIndexOf( body ) )`).
  2. Parse `body` with a flexmark `Parser` built with `TablesExtension` only (static final; tables so cells are `TableCell` nodes). Collect **code ranges** from `Code`, `FencedCodeBlock`, `IndentedCodeBlock` (`getStartOffset()`/`getEndOffset()`), same as `DefaultAclManager.java:284-286`.
  3. Collect edits `record Edit( int start, int end, String replacement )`:
     - **Plugins** first: regex `\[\{([^}]*)\}\](\(\))?` over `body`; skip matches starting inside a code range. Name = first token of group 1 (strip leading whitespace). Dispatch per table above. "Alone on its line" = the text between the previous `\n` (or 0) and next `\n` (or end) outside the match is blank. For removals whose line becomes blank, extend the edit to swallow the trailing `\n`.
     - **Links/Images**: iterate `doc.getDescendants()` for `Link` and `Image`; skip if inside a code range or overlapping a plugin edit, skip if `getText()` starts with `{`. `url = getUrl().toString()`; classify: `cite://` → citation; matches `^[A-Za-z][A-Za-z0-9+.-]*:` → external (skip); starts with `#` → same-page anchor; contains `/` → split at last `/` into (page, file) and try `ctx.attachmentTarget`; no `/` and has a file extension → try `ctx.attachmentTarget( currentPage, url )`; otherwise page link with optional `#slug` (URL-decode the page part with `URLDecoder.decode( …, UTF_8 )`). Edit span = node `getStartOffset()`..`getEndOffset()`.
     - Table-cell detection: walk `node.getParent()` looking for `TableCell`.
  4. Sort edits by start descending and apply to a `StringBuilder` of `body`. Append citation footnotes (`\n[^cN]: span` lines, preceded by one blank line; ensure body ends with `\n` first).
  5. Record `AttachmentRef`s (distinct, first-seen order) and warnings (e.g. `"Here: citation target 01XYZ not found"`).

  Keep `convert` under ~40 lines by extracting `collectPluginEdits`, `collectLinkEdits`, `renderPageLink`, `renderAttachment`, `renderCitation` private methods (PMD complexity gate: `mvn pmd:check -Pcomplexity-gate -pl wikantik-main` must stay green).

- [ ] **Step 4:** re-run → all PASS. Then `mvn pmd:check -Pcomplexity-gate -pl wikantik-main -q` → PASS (refactor if a new violation appears; never add to the baseline).

- [ ] **Step 5: commit** `feat(export): Obsidian page converter (links, anchors, attachments, citations, plugins)`.

---

### Task 4: `ExportSelection` and `ExportSelectionResolver`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/export/{ExportSelection,ExportCatalog,ExportSelectionResolver}.java`
- Test: `wikantik-main/src/test/java/com/wikantik/export/{ExportSelectionTest,ExportSelectionResolverTest}.java`

**Interfaces — Consumes:** `PageDescriptor` (slug, clusters, tags, type), `PageType`, `ClusterPath.isSelfOrDescendant( candidate, ancestor )` (all `com.wikantik.api.pagegraph`); `UnresolvedLinkMode` from Task 3 (if Task 3 has not landed yet, create `UnresolvedLinkMode` here exactly as specified in Task 3 and have Task 3 reuse it — coordinate: whichever lands first creates it).

**Produces:**

```java
public record ExportSelection( List< String > clusters, boolean includeSubClusters, List< String > tags,
                               Optional< PageType > type, Optional< String > status, int hops,
                               UnresolvedLinkMode unresolved ) {
    public static final int MAX_HOPS = 2;
    // compact ctor: null lists → List.of(), trims + drops blanks, copies; hops outside 0..MAX_HOPS → IllegalArgumentException("hops must be 0-2")
    // null type/status → Optional.empty(), null unresolved → KEEP
    public boolean hasFilters() { return !clusters.isEmpty() || !tags.isEmpty() || type.isPresent() || status.isPresent(); }
}

public interface ExportCatalog {
    List< PageDescriptor > allPages();
    /** Frontmatter {@code status} of the page, if any. */
    Optional< String > status( String slug );
    /** Outbound page links (attachments and nonexistent pages already excluded). */
    Collection< String > outboundPages( String slug );
    /** Subset of {@code slugs} the caller may view. */
    Set< String > viewable( Collection< String > slugs );
}

public record ResolvedSelection( List< PageDescriptor > pages, int seedCount, int hopAdded, int aclDropped ) {}

public final class ExportSelectionResolver {
    public ResolvedSelection resolve( ExportSelection selection, ExportCatalog catalog );
}
```
Result `pages` sorted by slug.

- [ ] **Step 1: failing tests**

```java
class ExportSelectionResolverTest {
    private static PageDescriptor page( String slug, List< String > clusters, List< String > tags, PageType type ) {
        return new PageDescriptor( "ID-" + slug, slug, slug, type, clusters.isEmpty() ? null : clusters.get( 0 ),
                clusters, tags, "", Instant.EPOCH, Optional.empty(), false );
    }
    private final List< PageDescriptor > all = List.of(
            page( "FinHub", List.of( "finance" ), List.of(), PageType.HUB ),
            page( "Roth", List.of( "finance/retirement" ), List.of( "tax" ), PageType.ARTICLE ),
            page( "Stocks", List.of( "finance" ), List.of( "equity" ), PageType.ARTICLE ),
            page( "Financial", List.of( "financials" ), List.of(), PageType.ARTICLE ),   // startsWith trap
            page( "Secret", List.of( "finance" ), List.of(), PageType.ARTICLE ),
            page( "Math", List.of( "math" ), List.of(), PageType.ARTICLE ),
            page( "Deep", List.of( "math" ), List.of(), PageType.ARTICLE ) );

    private final ExportCatalog catalog = new ExportCatalog() {
        public List< PageDescriptor > allPages() { return all; }
        public Optional< String > status( String s ) { return "Stocks".equals( s ) ? Optional.of( "draft" ) : Optional.empty(); }
        public Collection< String > outboundPages( String s ) {
            return switch ( s ) { case "Stocks" -> List.of( "Math" ); case "Math" -> List.of( "Deep" ); default -> List.of(); };
        }
        public Set< String > viewable( Collection< String > s ) { return s.stream().filter( n -> !"Secret".equals( n ) ).collect( Collectors.toSet() ); }
    };
    private final ExportSelectionResolver resolver = new ExportSelectionResolver();

    private List< String > slugs( ExportSelection sel ) { return resolver.resolve( sel, catalog ).pages().stream().map( PageDescriptor::slug ).toList(); }
    private ExportSelection sel( List< String > clusters, boolean sub, List< String > tags, PageType type, String status, int hops ) {
        return new ExportSelection( clusters, sub, tags, Optional.ofNullable( type ), Optional.ofNullable( status ), hops, UnresolvedLinkMode.KEEP );
    }

    @Test void clusterWithSubClustersIsSegmentAware() {
        assertEquals( List.of( "FinHub", "Roth", "Stocks" ), slugs( sel( List.of( "finance" ), true, List.of(), null, null, 0 ) ) );
    }
    @Test void clusterWithoutSubClusters() {
        assertEquals( List.of( "FinHub", "Stocks" ), slugs( sel( List.of( "finance" ), false, List.of(), null, null, 0 ) ) );
    }
    @Test void clustersAreOred() {
        assertEquals( List.of( "Deep", "FinHub", "Math", "Roth", "Stocks" ), slugs( sel( List.of( "finance", "math" ), true, List.of(), null, null, 0 ) ) );
    }
    @Test void tagsTypeStatusNarrow() {
        assertEquals( List.of( "Roth" ), slugs( sel( List.of( "finance" ), true, List.of( "tax" ), null, null, 0 ) ) );
        assertEquals( List.of( "FinHub" ), slugs( sel( List.of( "finance" ), true, List.of(), PageType.HUB, null, 0 ) ) );
        assertEquals( List.of( "Stocks" ), slugs( sel( List.of(), true, List.of(), null, "draft", 0 ) ) );
    }
    @Test void hopsExpandOutboundOnlyAndAreBounded() {
        assertEquals( List.of( "Math", "Stocks" ), slugs( sel( List.of(), true, List.of( "equity" ), null, null, 1 ) ) );
        assertEquals( List.of( "Deep", "Math", "Stocks" ), slugs( sel( List.of(), true, List.of( "equity" ), null, null, 2 ) ) );
    }
    @Test void noFiltersMeansEverythingViewable() {
        final ResolvedSelection r = resolver.resolve( sel( List.of(), true, List.of(), null, null, 0 ), catalog );
        assertEquals( 6, r.pages().size() );
        assertFalse( r.pages().stream().anyMatch( p -> p.slug().equals( "Secret" ) ) );
        assertEquals( 1, r.aclDropped() );
    }
    @Test void hopDoesNotReintroduceRestrictedPage() {
        // even if something links to Secret, it never appears
        assertFalse( slugs( sel( List.of( "finance" ), true, List.of(), null, null, 2 ) ).contains( "Secret" ) );
    }
}
```
Plus `ExportSelectionTest`: hops −1 and 3 throw; nulls normalised; blank cluster entries dropped.

- [ ] **Step 2:** run → FAIL.

- [ ] **Step 3: implement** `resolve`:
  1. Seed = `allPages()` filtered by: clusters empty OR any membership `m` matches any selected `c` where match = `includeSubClusters ? ClusterPath.isSelfOrDescendant( m, c ) : m.equals( c )`; tags → `p.tags().containsAll( tags )`; type → equality; status → `catalog.status( slug )` equals (case-insensitive).
  2. BFS: `frontier = seed slugs`; for `h` in `1..hops`: next = union of `outboundPages( s )` for s in frontier, minus already included, restricted to slugs present in `allPages()` (index by slug); add; frontier = next.
  3. `viewable( included )` → drop the rest, count `aclDropped`.
  4. Return pages sorted by slug.

- [ ] **Step 4:** run → PASS.
- [ ] **Step 5: commit** `feat(export): export selection resolver (clusters, filters, hops, ACL)`.

---

### Task 5: `VaultLayout`, `ExportManifest`, `ObsidianVaultWriter`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/export/{VaultLayout,ExportManifest,ObsidianVaultWriter}.java`
- Test: `wikantik-main/src/test/java/com/wikantik/export/{VaultLayoutTest,ObsidianVaultWriterTest}.java`

**Interfaces — Consumes:** `PageDescriptor.slug()`, `PageDescriptor.cluster()` (primary; may be null), `AttachmentRef` (Task 3 — if not landed, create it exactly as `public record AttachmentRef( String pageName, String fileName ) {}`).

**Produces:**

```java
public final class VaultLayout {
    public static final String ATTACHMENTS_DIR = "_attachments";
    public static final String UNCLUSTERED_DIR = "_unclustered";
    public static VaultLayout plan( List< PageDescriptor > pages, Collection< AttachmentRef > attachments );
    public String pagePath( String slug );                 // e.g. "finance/retirement/Roth.md"
    /** Extra alias to add when the file basename differs from the page name (collision suffix / sanitising). */
    public Optional< String > aliasFor( String slug );
    public String attachmentPath( AttachmentRef ref );    // "_attachments/Roth/chart.png"
    /** Obsidian link target: bare file name, or the full path when the basename is ambiguous vault-wide. */
    public String attachmentLinkTarget( AttachmentRef ref );
    static String sanitize( String name );                 // replaces : * ? " < > | \ and control chars with '_'
}

public record ExportManifest( int formatVersion, String serverBaseUrl, String exportedAt, Map< String, Object > selection,
                              List< PageEntry > pages, List< AttachmentEntry > attachments, List< String > warnings ) {
    public static final int FORMAT_VERSION = 1;
    public record PageEntry( String name, String canonicalId, int version, String path, String sha256 ) {}
    public record AttachmentEntry( String page, String name, String path, String sha256 ) {}
    public String toJson();   // new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}

public final class ObsidianVaultWriter implements Closeable {
    public static final String README_PATH = "Wikantik Export.md";
    public static final String MANIFEST_PATH = ".wikantik/manifest.json";
    public ObsidianVaultWriter( OutputStream out );
    /** Writes the entry and returns its lowercase hex SHA-256. */
    public String writeText( String path, String content ) throws IOException;
    public String writeStream( String path, InputStream in ) throws IOException;
    /** Writes readme + manifest and finishes the zip (does not close the underlying stream). */
    public void finish( ExportManifest manifest, String readme ) throws IOException;
    public void close() throws IOException;  // finishes if not finished
}
```

- [ ] **Step 1: failing tests**

```java
class VaultLayoutTest {
    private static PageDescriptor p( String slug, String cluster ) {
        return new PageDescriptor( "ID-" + slug, slug, slug, PageType.ARTICLE, cluster,
                cluster == null ? List.of() : List.of( cluster ), List.of(), "", Instant.EPOCH, Optional.empty(), false );
    }
    @Test void pathsFollowPrimaryCluster() {
        final VaultLayout l = VaultLayout.plan( List.of( p( "Roth", "finance/retirement" ), p( "Loose", null ) ), List.of() );
        assertEquals( "finance/retirement/Roth.md", l.pagePath( "Roth" ) );
        assertEquals( "_unclustered/Loose.md", l.pagePath( "Loose" ) );
    }
    @Test void caseInsensitiveCollisionSuffixedWithAlias() {
        final VaultLayout l = VaultLayout.plan( List.of( p( "FooBar", "a" ), p( "Foobar", "b" ) ), List.of() );
        assertEquals( "a/FooBar.md", l.pagePath( "FooBar" ) );
        assertEquals( "b/Foobar~2.md", l.pagePath( "Foobar" ) );
        assertEquals( Optional.of( "Foobar" ), l.aliasFor( "Foobar" ) );
        assertEquals( Optional.empty(), l.aliasFor( "FooBar" ) );
    }
    @Test void illegalCharactersSanitised() {
        assertEquals( "What_ Why_", VaultLayout.sanitize( "What: Why?" ) );
    }
    @Test void attachmentTargetsQualifiedOnlyWhenAmbiguous() {
        final AttachmentRef a = new AttachmentRef( "A", "chart.png" ), b = new AttachmentRef( "B", "Chart.png" ), c = new AttachmentRef( "A", "only.pdf" );
        final VaultLayout l = VaultLayout.plan( List.of( p( "A", "x" ), p( "B", "x" ) ), List.of( a, b, c ) );
        assertEquals( "_attachments/A/chart.png", l.attachmentPath( a ) );
        assertEquals( "_attachments/A/chart.png", l.attachmentLinkTarget( a ) );
        assertEquals( "_attachments/B/Chart.png", l.attachmentLinkTarget( b ) );
        assertEquals( "only.pdf", l.attachmentLinkTarget( c ) );
    }
}

class ObsidianVaultWriterTest {
    @Test void writesEntriesManifestAndReadmeWithMatchingHashes() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final String sha;
        try ( ObsidianVaultWriter w = new ObsidianVaultWriter( bos ) ) {
            sha = w.writeText( "a/Page.md", "hello" );
            w.writeStream( "_attachments/Page/x.bin", new ByteArrayInputStream( new byte[]{ 1, 2, 3 } ) );
            w.finish( new ExportManifest( 1, "https://w", "2026-09-29T00:00:00Z", Map.of(),
                    List.of( new ExportManifest.PageEntry( "Page", "01X", 3, "a/Page.md", sha ) ), List.of(), List.of( "warn one" ) ),
                    "# readme" );
        }
        final Map< String, byte[] > entries = unzip( bos.toByteArray() );
        assertEquals( Set.of( "a/Page.md", "_attachments/Page/x.bin", "Wikantik Export.md", ".wikantik/manifest.json" ), entries.keySet() );
        assertEquals( "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha ); // sha256("hello")
        final String manifest = new String( entries.get( ".wikantik/manifest.json" ), StandardCharsets.UTF_8 );
        assertTrue( manifest.contains( "\"sha256\": \"" + sha + "\"" ) );
        assertTrue( manifest.contains( "warn one" ) );
    }
    @Test void duplicatePathRejected() throws Exception {
        try ( ObsidianVaultWriter w = new ObsidianVaultWriter( new ByteArrayOutputStream() ) ) {
            w.writeText( "a.md", "1" );
            assertThrows( IllegalStateException.class, () -> w.writeText( "a.md", "2" ) );
        }
    }
    static Map< String, byte[] > unzip( byte[] zip ) throws IOException {
        final Map< String, byte[] > out = new LinkedHashMap<>();
        try ( ZipInputStream zin = new ZipInputStream( new ByteArrayInputStream( zip ) ) ) {
            for ( ZipEntry e; ( e = zin.getNextEntry() ) != null; ) { out.put( e.getName(), zin.readAllBytes() ); }
        }
        return out;
    }
}
```

- [ ] **Step 2:** run `mvn test -pl wikantik-main -Dtest='VaultLayoutTest,ObsidianVaultWriterTest' -q` → FAIL.

- [ ] **Step 3: implement.**
  - `VaultLayout.plan`: iterate pages sorted by slug; folder = `cluster()` segments each sanitised, joined by `/`, or `_unclustered`; base = `sanitize( slug )`; track lowercase basenames in a `Map< String, Integer >` — on a repeat, append `~N` (N = count+1) and record alias = slug; also record alias when `sanitize( slug )` differs from `slug`. Attachments: path `_attachments/<sanitize(page)>/<sanitize(file)>`; count lowercase file names across all refs; ambiguous (count > 1) → link target = full path, else file name.
  - `ObsidianVaultWriter`: wraps `new ZipOutputStream( out, UTF_8 )`; `Set< String > written`; each write: reject duplicates with `IllegalStateException( "duplicate zip entry: " + path )`, `putNextEntry( new ZipEntry( path ) )`, copy through a `DigestOutputStream`-style `MessageDigest.getInstance( "SHA-256" )` update, `closeEntry()`, return `HexFormat.of().formatHex( digest )`. `finish` writes `README_PATH` then `MANIFEST_PATH`, then `zip.finish()`. `close()` calls `finish`-equivalent `zip.finish()` if not finished, then `zip.close()`? — **No**: the servlet owns the response stream; `close()` must call `zip.finish()` only. Document that.

- [ ] **Step 4:** run → PASS.
- [ ] **Step 5: commit** `feat(export): vault layout, manifest and zip writer`.

---

### Task 6: `ExportService` + engine adapter + config key

**Depends on:** Tasks 2–5.

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/export/{ExportService,EngineExportCatalog,ExportTooLargeException,ExportPreview,ExportOptions}.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (new `# [Export]` section)
- Regenerate: `bin/config-reference.sh --write` (updates `docs/ConfigurationReference.md` + `docs/wikantik-pages/WikantikConfigurationReference.md`)
- Test: `wikantik-main/src/test/java/com/wikantik/export/ExportServiceTest.java`

**Interfaces — Consumes:** everything from Tasks 2–5; `StructuralIndexService` (`sitemap().pages()`, `listClusters()` → `ClusterSummary.name()`, `listTags( int )` → `TagSummary.tag()`, `resolveSlugFromCanonicalId`); `PageManager` (`getPage( String )`, `getPureText( Page )`, `pageExists( String )`); `ReferenceManager.findRefersTo( String )` (includes attachment names `Page/file` — exclude names containing `/`, and names where `pageExists` is false); `AttachmentManager` (`listAttachments( Page )`, `getAttachmentStream( Attachment )`, `Attachment.getFileName()`, `getSize()`); `PermissionFilter.filterViewableQuietly( Session, Collection< String > )`; `FrontmatterParser.parse( text ).metadata()`.

**Produces:**

```java
public record ExportPreview( int pages, int attachments, long estimatedBytes, int unresolvedLinks,
                             int cap, boolean overCap, List< String > sample ) {}
public record ExportOptions( List< String > clusters, List< String > tags ) {}
public final class ExportTooLargeException extends Exception {
    public ExportTooLargeException( int count, int cap ) { super( "Export matches " + count + " pages; the limit is " + cap ); ... }
    public int count(); public int cap();
}

public final class ExportService {
    public static final String PROP_MAX_PAGES = "wikantik.export.maxPages";
    public static final int DEFAULT_MAX_PAGES = 2000;

    public ExportService( StructuralIndexService index, PageManager pages, ReferenceManager refs,
                          AttachmentManager attachments, PermissionFilter permissions,
                          String baseUrl, int maxPages, Clock clock );

    /** Reads wikantik.baseURL and wikantik.export.maxPages from the engine properties. */
    public static ExportService fromSubsystems( Engine engine, WikiSubsystems subs );

    public ExportOptions options();
    public ExportPreview preview( Session session, ExportSelection selection );
    /** All work that can fail with a status code happens here, before a byte is streamed. */
    public PreparedExport prepare( Session session, ExportSelection selection ) throws ExportTooLargeException;
    /** Streams the zip. Never throws for per-page/attachment problems (they become warnings). */
    public void stream( PreparedExport export, OutputStream out ) throws IOException;

    public record PreparedExport( ExportSelection selection, List< PageDescriptor > pages, String fileName ) {}
}
```

Behaviour:
- `EngineExportCatalog( StructuralIndexService, PageManager, ReferenceManager, PermissionFilter, Session )` implements `ExportCatalog` (status from frontmatter `status` via `FrontmatterParser.parse( pages.getPureText( page ) )`; cache parsed metadata per slug in a `HashMap` for the life of the request).
- `prepare`: resolve; if `pages.size() > maxPages` → throw; fileName = `"wikantik-export-" + DateTimeFormatter.ofPattern( "yyyyMMdd-HHmm" ).withZone( ZoneOffset.UTC ).format( clock.instant() ) + ".zip"`.
- `stream`:
  1. For each page: raw text, `listAttachments` → build `AttachmentRef`s for files actually referenced (known after conversion; so convert first with a context whose `attachmentTarget` answers from the page's attachment list, then plan the layout from all refs, then re-render? — **avoid double conversion**: plan the layout from *all* attachments of included pages (cheap, list only), converter context uses `layout.attachmentLinkTarget`, and only the refs the converter returns are written to the zip).
  2. Context: `inExport` = slug in set; `headingText` = `HeadingSlugs.headingsBySlug( body of target )` cached per page (target may be out of export — still look it up if it exists and is viewable; otherwise empty); `slugForCanonicalId` = `index.resolveSlugFromCanonicalId`, but only return it if that slug is viewable (treat restricted as unknown); `liveUrl` = `baseUrl + "/wiki/" + URLEncoder.encode( slug, UTF_8 )`.
  3. Extra frontmatter per page: `wikantik_url`, `wikantik_version` (`String.valueOf( page.getVersion() )`), `aliases` = distinct non-null of { title if ≠ slug, `layout.aliasFor( slug )` } (omit key when empty), `related` rewritten to `"[[X]]"` strings when the page has a `related` list (from frontmatter metadata; scalar → single).
  4. On `RuntimeException` converting a page: `LOG.warn( "Export: converting {} failed, writing raw markdown: {}", slug, e.getMessage(), e )`, write raw text, add warning.
  5. Attachments: stream via `getAttachmentStream`; on `IOException | ProviderException` → `LOG.warn`, warning, skip. **But** an `IOException` from writing to the zip (client gone) must propagate — distinguish by wrapping the *read* side: open the input stream in its own try; failures opening/reading the source are warnings; failures in `writer.writeStream` propagate.
  6. `finish( manifest, readme )`. Readme content: title, exported-at, server, selection summary, counts, a "What was changed" list (links rewritten, ACL/SET/TOC stripped, other plugins replaced by callouts), the ACL caveat sentence from spec §5, and a `## Warnings` list when non-empty.
- `preview`: resolve; `estimatedBytes` = Σ raw text UTF-8 length + Σ attachment `getSize()` (only if ≤ cap, otherwise skip the size pass and report 0); `unresolvedLinks` = count of distinct outbound targets (via catalog) not in the set; `sample` = first 50 slugs; `overCap` = pages > cap.
- `options`: clusters = `listClusters()` names sorted; tags = `listTags( 2 )` tags in returned order, limited to 300.

- [ ] **Step 1: failing test** — `ExportServiceTest` with a real `TestEngine` (pattern: `wikantik-main/src/test/java/com/wikantik/pagegraph/references/ReferenceManagerTest.java:35-46`):

```java
class ExportServiceTest {
    private TestEngine engine;
    private WikiSubsystems subs;
    private ExportService service;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "FinanceHub", "---\ntype: hub\ncluster: finance\ncanonical_id: 01HUBFINANCE0000000000000A\n---\n# Finance\n\nSee [Roth](RothConversions) and [Math](MathPage).\n" );
        engine.saveText( "RothConversions", "---\ntype: article\ncluster: finance/retirement\nstatus: active\ncanonical_id: 01ROTH00000000000000000000\nrelated: [FinanceHub]\n---\n# Roth\n\n## Setup Steps\n\n![c](RothConversions/chart.png)\n" );
        engine.addAttachment( "RothConversions", "chart.png", new byte[]{ 1, 2, 3 } );
        engine.saveText( "MathPage", "---\ntype: article\ncluster: math\ncanonical_id: 01MATH00000000000000000000\n---\n# Math\n" );
        engine.saveText( "SecretFinance", "---\ntype: article\ncluster: finance\ncanonical_id: 01SECRET000000000000000000\n---\n[{ALLOW view Admin}]\n# Secret\n" );
        // structural index rebuild is async; force it
        subs = ( WikiSubsystems ) engine.getServletContext().getAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE );
        subs.pageGraph().structuralIndexService().rebuild();   // initial rebuild is async; force a synchronous one
        service = ExportService.fromSubsystems( engine, subs );
    }

    @AfterEach void tearDown() { engine.stop(); }

    private Session guest() { return WikiSession.guestSession( engine ); }
    private ExportSelection finance( int hops ) {
        return new ExportSelection( List.of( "finance" ), true, List.of(), Optional.empty(), Optional.empty(), hops, UnresolvedLinkMode.KEEP );
    }

    @Test void previewCountsAndHidesRestricted() {
        final ExportPreview p = service.preview( guest(), finance( 0 ) );
        assertEquals( 2, p.pages() );
        assertEquals( 1, p.attachments() );
        assertFalse( p.sample().contains( "SecretFinance" ) );
        assertEquals( 1, p.unresolvedLinks() );   // MathPage
    }

    @Test void zipIsAWorkingVault() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        service.stream( service.prepare( guest(), finance( 1 ) ), bos );
        final Map< String, byte[] > z = ObsidianVaultWriterTest.unzip( bos.toByteArray() );
        assertTrue( z.containsKey( "finance/FinanceHub.md" ) );
        assertTrue( z.containsKey( "finance/retirement/RothConversions.md" ) );
        assertTrue( z.containsKey( "math/MathPage.md" ) );                       // pulled in by hops=1
        assertTrue( z.containsKey( "_attachments/RothConversions/chart.png" ) );
        assertFalse( z.keySet().stream().anyMatch( k -> k.contains( "SecretFinance" ) ) );
        final String hub = new String( z.get( "finance/FinanceHub.md" ), StandardCharsets.UTF_8 );
        assertTrue( hub.contains( "[[RothConversions|Roth]]" ), hub );
        final String roth = new String( z.get( "finance/retirement/RothConversions.md" ), StandardCharsets.UTF_8 );
        assertTrue( roth.contains( "![[chart.png]]" ), roth );
        assertTrue( roth.contains( "  - \"[[FinanceHub]]\"" ), roth );
        assertTrue( roth.contains( "wikantik_version:" ), roth );
        assertTrue( z.containsKey( ".wikantik/manifest.json" ) );
        assertTrue( z.containsKey( "Wikantik Export.md" ) );
    }

    @Test void overCapThrows() {
        final ExportService tiny = new ExportService( subs.pageGraph().structuralIndexService(), subs.page().pages(),
                subs.pageGraph().referenceManager(), subs.page().attachments(), new PermissionFilter( engine ),
                "https://w.example", 1, Clock.systemUTC() );
        final ExportTooLargeException e = assertThrows( ExportTooLargeException.class, () -> tiny.prepare( guest(), finance( 0 ) ) );
        assertEquals( 2, e.count() );
    }
}
```
`WikiEngine.java:756` stores the bundle under `WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE`; if the `TestEngine` servlet context returns null for it, fall back to the bridges `RestServletBase.getSubsystems()` uses (`RestServletBase.java:141-175`). `referenceManager()` is null until the async page scan finishes — if so, poll `subs.pageGraph().referenceManager()` for up to 10 s. Also verify that the `[{ALLOW view Admin}]` page is really denied to a guest session (the test would be vacuous otherwise: assert `new PermissionFilter( engine ).canAccessQuietly( guest(), "SecretFinance", "view" )` is false first).

- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement as specified. Keep each method ≤ ~40 lines (PMD gate).
- [ ] **Step 4:** add a test: a converter failure (e.g. inject a page whose text makes a `RuntimeException` — simplest: construct `ExportService` with a `PageManager` spy that throws from `getPureText` for one slug) still yields a complete zip whose manifest `warnings` names that page. Run → PASS.
- [ ] **Step 5: config key.** In `wikantik-main/src/main/resources/ini/wikantik.properties`, add near the other feature sections:

```
# [Export]
#
#  Maximum number of pages a single Obsidian export (GET /api/export) may contain.
#  A selection that resolves to more pages is refused with HTTP 413 and the real
#  count, never silently truncated.
#
#  Type: int
wikantik.export.maxPages = 2000
```
Run `bin/config-reference.sh --write`, then `mvn test -pl wikantik-war -Dtest=ConfigSurfaceDriftTest -q` and `mvn test -pl wikantik-extract-cli -Dtest=ConfigReferenceRegressionTest -q` → PASS (`-am` may be needed; if so add `-Dsurefire.failIfNoSpecifiedTests=false`).
- [ ] **Step 6:** `mvn test -pl wikantik-main -Dtest='com.wikantik.export.*Test' -q` → PASS; `mvn test -pl wikantik-main -Dtest='DecompositionArchTest,*ArchTest' -q` → PASS (if the ArchUnit freeze store changes on a red run, restore it from git before retrying).
- [ ] **Step 7: commit** service, adapter, DTOs, properties, both regenerated docs: `feat(export): ExportService orchestration + wikantik.export.maxPages`.

---

### Task 7: `ExportResource` REST surface

**Depends on:** Task 6.

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/ExportResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet + mappings `/api/export` and `/api/export/*`, placed next to `BundleResource`)
- Modify: `CLAUDE.md` `/api/*` row: `32 distinct servlets (33 url-patterns …)` → `33 distinct servlets (35 url-patterns — SelfApiKeysResource and ExportResource carry two each)` and mention `GET /api/export` (Obsidian vault zip, `export`-gated)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/ExportResourceTest.java`

**Interfaces — Consumes:** `ExportService` (Task 6), `ExportSelection`, `UnresolvedLinkMode.fromWire`, `PageType.fromFrontmatter`, `ExportTooLargeException`, `WikiPermission.EXPORT`.

**Wire contract (T8 depends on this):**
- `GET /api/export/options` → `200 {"clusters":[…],"tags":[…]}`
- `GET /api/export/preview?…` → `200 {"pages":N,"attachments":N,"estimatedBytes":N,"unresolvedLinks":N,"cap":N,"overCap":bool,"sample":[…]}`
- `GET /api/export?…` → `200 application/zip`, `Content-Disposition: attachment; filename="wikantik-export-yyyyMMdd-HHmm.zip"`
- Errors (JSON `{"error":…}`): 401 not logged in, 403 lacks `export`, 400 bad param (message names it) or empty selection (`"Selection matches no pages"`), 413 over cap (`{"error":…,"count":N,"cap":N}`), 404 unknown sub-path.

Implementation outline:

```java
public class ExportResource extends RestServletBase {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ExportResource.class );

    /** Seam for tests. */
    protected ExportService exportService() { return ExportService.fromSubsystems( getEngine(), getSubsystems() ); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final Session session = Wiki.session().find( getEngine(), req );
        if ( !session.isAuthenticated() ) { sendError( resp, 401, "Login required to export" ); return; }
        final AuthorizationManager auth = AuthSubsystemBridge.fromLegacyEngine( getEngine() ).authorization();
        if ( !auth.checkPermission( session, WikiPermission.EXPORT ) ) { sendError( resp, 403, "Forbidden: export permission required" ); return; }
        final String sub = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        switch ( sub ) {
            case "", "/" -> download( req, resp, session );
            case "/preview" -> preview( req, resp, session );
            case "/options" -> sendJson( resp, exportService().options() );
            default -> sendNotFound( resp, "Unknown export path: " + sub );
        }
    }

    static ExportSelection parseSelection( final HttpServletRequest req ) { /* IllegalArgumentException with param name */ }
}
```
- `parseSelection`: `cluster`/`tag` via `getParameterValues` (null → empty); `subclusters` default true (`"false"` → false, other non-blank non-`true` → 400); `type` via `PageType.fromFrontmatter` — `UNKNOWN` for a non-blank value → 400 `"Unknown type: x"`; `status` blank → empty; `hops` via `Integer.parseInt` → 400 on `NumberFormatException` or out of range; `unresolved` via `UnresolvedLinkMode.fromWire`.
- `download`: `prepare` (catch `ExportTooLargeException` → 413 with count/cap body written via `RestJson`-style JSON using `GSON`; empty pages → 400), then `resp.setStatus( 200 )`, `setContentType( "application/zip" )`, `setHeader( "Content-Disposition", "attachment; filename=\"" + p.fileName() + "\"" )`, `setHeader( "Cache-Control", "no-store" )`, then `exportService().stream( p, resp.getOutputStream() )`. Catch `IOException` from stream: `LOG.debug( "Export stream aborted (client disconnect?): {}", e.getMessage() )` and return — status can no longer change.
- `com.wikantik.api.core.Session.isAuthenticated()` exists (Session.java:86). Put the permission check in `protected boolean canExport( final Session session )` and call it from `doGet`. `TestEngine.janneSession()` (TestEngine.java:131) confirms janne's session registers under the shared mock id the same way `adminSession()` does; verify this before relying on it.

- [ ] **Step 1: failing tests** (pattern: `DerivedIngestResourceTest.java:62-102` for real-engine session states; `BundleResourceTest` for the service seam):

```java
class ExportResourceTest {
    private static TestEngine engine;
    private static ExportService stubService = mock( ExportService.class );

    @BeforeAll static void start() throws Exception { engine = new TestEngine( TestEngine.getTestProperties() ); }
    @AfterAll static void stop() { engine.stop(); }
    @BeforeEach void anon() { SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID ); reset( stubService ); }

    private ExportResource servlet() throws Exception {
        final ExportResource r = new ExportResource() { @Override protected ExportService exportService() { return stubService; } };
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( engine.getServletContext() ).when( cfg ).getServletContext();
        r.init( cfg );
        return r;
    }
    private HttpServletRequest get( String pathInfo, Map< String, String[] > params ) {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/export" + ( pathInfo == null ? "" : pathInfo ) );
        doReturn( pathInfo ).when( req ).getPathInfo();
        params.forEach( ( k, v ) -> { doReturn( v ).when( req ).getParameterValues( k ); doReturn( v[ 0 ] ).when( req ).getParameter( k ); } );
        return req;
    }
    private record Resp( HttpServletResponse mock, StringWriter body, ByteArrayOutputStream bytes ) {}
    private Resp resp() throws Exception { /* HttpMockFactory.createHttpResponse() + getWriter → StringWriter, getOutputStream → ServletOutputStream over a BAOS */ }

    @Test void anonymousGets401() throws Exception { final Resp r = resp(); servlet().doGet( get( "/preview", Map.of() ), r.mock() ); verify( r.mock() ).setStatus( 401 ); }
    @Test void authenticatedWithoutGrantGets403() throws Exception {
        engine.janneSession();   // authenticated, registers under the shared mock-session id
        final ExportResource r = new ExportResource() {
            @Override protected ExportService exportService() { return stubService; }
            @Override protected boolean canExport( final Session s ) { return false; }
        };
        final ServletConfig cfg = mock( ServletConfig.class );
        doReturn( engine.getServletContext() ).when( cfg ).getServletContext();
        r.init( cfg );
        final Resp resp = resp();
        r.doGet( get( "/preview", Map.of() ), resp.mock() );
        verify( resp.mock() ).setStatus( 403 );
        verifyNoInteractions( stubService );
    }
    @Test void adminPreviewReturnsJson() throws Exception {
        engine.adminSession();
        when( stubService.preview( any(), any() ) ).thenReturn( new ExportPreview( 2, 1, 100, 0, 2000, false, List.of( "A", "B" ) ) );
        final Resp r = resp();
        servlet().doGet( get( "/preview", Map.of( "cluster", new String[]{ "finance" }, "hops", new String[]{ "1" } ) ), r.mock() );
        final JsonObject o = JsonParser.parseString( r.body().toString() ).getAsJsonObject();
        assertEquals( 2, o.get( "pages" ).getAsInt() );
        final ArgumentCaptor< ExportSelection > sel = ArgumentCaptor.forClass( ExportSelection.class );
        verify( stubService ).preview( any(), sel.capture() );
        assertEquals( List.of( "finance" ), sel.getValue().clusters() );
        assertEquals( 1, sel.getValue().hops() );
    }
    @Test void badHopsIs400() { /* hops=9 → setStatus(400), body mentions "hops" */ }
    @Test void badTypeIs400() { /* type=nonsense → 400 */ }
    @Test void overCapIs413WithCount() { /* prepare throws ExportTooLargeException(5000, 2000) → 413, body has count 5000 */ }
    @Test void downloadSetsZipHeaders() { /* prepare returns PreparedExport(..., "wikantik-export-20260929-1412.zip"); stream writes "PK"; verify content type application/zip and Content-Disposition filename */ }
    @Test void unknownSubPathIs404() { /* "/nope" → 404 */ }
}
```
The implementer writes the bodies of the commented tests in full (same shape as `adminPreviewReturnsJson`) and adds a `protected boolean canExport( Session )` seam in `ExportResource` (default = the `AuthorizationManager` check) to test 403 without depending on policy fixtures. Also add one real-policy test: with `engine.adminSession()` and no override, `/options` returns 200 (Admin has AllPermission).

- [ ] **Step 2:** `mvn test -pl wikantik-rest -Dtest=ExportResourceTest -q` → FAIL (install wikantik-main first: `mvn install -pl wikantik-main -DskipTests -q`).
- [ ] **Step 3:** implement resource + `web.xml`:

```xml
<servlet>
    <servlet-name>ExportResource</servlet-name>
    <servlet-class>com.wikantik.rest.ExportResource</servlet-class>
</servlet>
...
<servlet-mapping>
    <servlet-name>ExportResource</servlet-name>
    <url-pattern>/api/export</url-pattern>
    <url-pattern>/api/export/*</url-pattern>
</servlet-mapping>
```
- [ ] **Step 4:** run → PASS. Then `mvn test -pl wikantik-war -q` (web.xml-parsing tests) → PASS.
- [ ] **Step 5:** update CLAUDE.md row; commit resource, test, web.xml, CLAUDE.md: `feat(rest): GET /api/export (Obsidian vault zip) + preview/options`.

---

### Task 8: Frontend `ExportDialog`

**Depends on:** Task 7 wire contract only.

**Files:**
- Modify: `wikantik-frontend/src/api/client.js` — add `exportVault` group
- Create: `wikantik-frontend/src/components/ExportDialog.jsx`, `ExportDialog.test.jsx`
- Modify: `wikantik-frontend/src/components/PersonalZone.jsx` (+ its test), `Sidebar.jsx` (mount dialog, pass `onExport`), `PageView.jsx` (hub button + dialog mount) (+ `PageView.test.jsx`)
- Modify: `wikantik-frontend/src/styles/globals.css` — `.export-dialog*` rules

**API client** (next to `listPages`, client.js:112):

```js
exportVault: {
  params: ({ clusters = [], subclusters = true, tags = [], type = '', status = '', hops = 0, unresolved = 'keep' } = {}) => {
    const p = new URLSearchParams();
    clusters.forEach((c) => p.append('cluster', c));
    tags.forEach((t) => p.append('tag', t));
    if (!subclusters) p.set('subclusters', 'false');
    if (type) p.set('type', type);
    if (status) p.set('status', status);
    if (hops) p.set('hops', String(hops));
    if (unresolved !== 'keep') p.set('unresolved', unresolved);
    return p;
  },
  options: () => request('/api/export/options'),
  preview: (sel, { signal } = {}) => request(`/api/export/preview?${api.exportVault.params(sel)}`, { signal }),
  downloadUrl: (sel) => `${BASE}/api/export?${api.exportVault.params(sel)}`,
},
```
(Check whether the object is named `api` and whether `BASE` is in scope at that point in `client.js`; adapt.) Note `request()` dispatches `wikantik:auth-required` on 403 — for the preview call that is harmless (it re-probes `/api/auth/user`), but confirm it does not log the user out.

**Component** — `ExportDialog({ isOpen, onClose, initialCluster = '' })`:
- Reset state on open with the store-previous-and-compare pattern (NewArticleModal.jsx:16-26): `clusters = initialCluster ? [initialCluster] : []`, subclusters true, tags [], type '', status '', hops 0, unresolved 'keep'.
- Load options once per open with the promise-chain + `cancelled` flag pattern (BacklinksPanel.jsx:13-20).
- Preview: every control change calls `schedulePreview(nextSel)` → clears `debounceRef`, sets a 300 ms `setTimeout` that aborts the previous `AbortController`, calls `api.exportVault.preview(nextSel, { signal })`, sets `preview` / `error`. A 403 sets `denied = true`. Clear the timer on unmount (`useEffect(() => () => clearTimeout(debounceRef.current), [])`). Trigger an initial preview when the dialog opens (from the open-transition branch, via the same scheduler).
- Controls: clusters — `TagInput` with `suggestions={options.clusters}`; "Include sub-clusters" checkbox; tags — `TagInput` with `suggestions={options.tags}`; type — `Select` options `['', 'hub','article','reference','runbook','design']` (label "Any type"); status — text input; hops — three buttons 0/1/2 using the NewArticleModal button-group pattern (`aria-pressed`); unresolved — two radio inputs "Keep as [[links]]" / "Link to the wiki".
- Summary line (`data-testid="export-preview-summary"`): `"{pages} pages · {attachments} attachments · ~{MB} MB · {unresolvedLinks} links unresolved"`; sample list (first 10 of `sample`, then "and N more").
- Download: `<a className="btn btn-primary" href={api.exportVault.downloadUrl(sel)} download data-testid="export-download">` rendered as a disabled `<button>` with a reason (`data-testid="export-disabled-reason"`) when `pages === 0` ("Nothing matches this selection"), `overCap` ("{pages} pages exceeds the limit of {cap} — narrow the selection"), or while loading.
- Denied state: render only `"Export is disabled for your account."` (`data-testid="export-denied"`) and a Close button.
- Modal: `<Modal isOpen onClose labelledBy="export-dialog-title" className="search-dialog export-dialog" testId="export-dialog">`, `<h2 id="export-dialog-title">Export to Obsidian</h2>`.

**Entry points:**
- `PersonalZone`: add a `onExport` prop (default no-op) and a `btn btn-ghost btn-block` "Export to Obsidian…" button under "+ New Article" (`data-testid="personal-export"`). `Sidebar.jsx`: `const [exportOpen, setExportOpen] = useState(false)`, pass `onExport={() => setExportOpen(true)}`, mount `<ExportDialog isOpen={exportOpen} onClose={() => setExportOpen(false)} />` next to `NewArticleModal`.
- `PageView.jsx` action row (L481-497): when `useAuth().user?.authenticated` and `String(page.metadata?.type||'').toLowerCase()==='hub'` and a cluster is known (`page.cluster_status?.path || page.metadata?.cluster`), render `<button className="btn btn-ghost" data-testid="export-cluster-button">Export this cluster</button>` opening `<ExportDialog initialCluster={cluster} …/>`.

- [ ] **Step 1: failing tests** — `ExportDialog.test.jsx` (mock pattern from BacklinksPanel.test.jsx:5-10):

```jsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';

vi.mock('../api/client', () => ({
  api: { exportVault: { options: vi.fn(), preview: vi.fn(), downloadUrl: vi.fn(() => '/api/export?cluster=finance'), params: vi.fn() } },
}));
import ExportDialog from './ExportDialog';
import { api } from '../api/client';

const PREVIEW = { pages: 12, attachments: 3, estimatedBytes: 2_500_000, unresolvedLinks: 4, cap: 2000, overCap: false, sample: ['A', 'B'] };

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  api.exportVault.options.mockResolvedValue({ clusters: ['finance', 'math'], tags: ['tax'] });
  api.exportVault.preview.mockResolvedValue(PREVIEW);
});
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });

const open = (props = {}) => render(<ExportDialog isOpen onClose={vi.fn()} {...props} />);

describe('ExportDialog', () => {
  it('prefills the cluster and previews it', async () => {
    open({ initialCluster: 'finance' });
    await act(() => vi.advanceTimersByTimeAsync(350));
    await waitFor(() => expect(api.exportVault.preview).toHaveBeenCalled());
    expect(api.exportVault.preview.mock.calls.at(-1)[0].clusters).toEqual(['finance']);
    expect(await screen.findByTestId('export-preview-summary')).toHaveTextContent('12 pages · 3 attachments · ~2.4 MB · 4 links unresolved');
    expect(screen.getByTestId('export-download')).toHaveAttribute('href', '/api/export?cluster=finance');
  });
  it('debounces rapid changes into one preview call', async () => {
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    api.exportVault.preview.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '1' }));
    fireEvent.click(screen.getByRole('button', { name: '2' }));
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(api.exportVault.preview).toHaveBeenCalledTimes(1);
    expect(api.exportVault.preview.mock.calls[0][0].hops).toBe(2);
  });
  it('disables download over the cap with a reason', async () => {
    api.exportVault.preview.mockResolvedValue({ ...PREVIEW, pages: 2500, overCap: true });
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-disabled-reason')).toHaveTextContent('2500 pages exceeds the limit of 2000');
    expect(screen.queryByTestId('export-download')).toBeNull();
  });
  it('disables download for an empty selection', async () => { /* pages: 0 → reason 'Nothing matches this selection' */ });
  it('shows the denied message on 403', async () => {
    api.exportVault.preview.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
    open();
    await act(() => vi.advanceTimersByTimeAsync(350));
    expect(await screen.findByTestId('export-denied')).toHaveTextContent('Export is disabled for your account.');
  });
});
```
The implementer writes the empty-selection test body in full. Add to `PersonalZone.test.jsx`: clicking `personal-export` calls `onExport`. Add to `PageView.test.jsx`: a hub page with an authenticated user shows `export-cluster-button`; a non-hub page does not (follow that file's existing mocking of `useAuth` and the page payload).

- [ ] **Step 2:** `cd wikantik-frontend && npx vitest run src/components/ExportDialog src/components/PersonalZone src/components/PageView` → FAIL.
- [ ] **Step 3:** implement component, client, entry points, CSS (`.export-dialog-controls { display:grid; gap: var(--space-md) }`, `.export-dialog-summary`, `.export-dialog-sample` — use only CSS tokens already defined in `globals.css`; grep before using any `var(--x)`).
- [ ] **Step 4:** re-run → PASS; `npx eslint src/components/ExportDialog.jsx src/components/PersonalZone.jsx src/components/PageView.jsx src/components/Sidebar.jsx src/api/client.js` → 0 errors, no new warnings; full `npx vitest run` → PASS (if an unrelated test flakes under concurrency, re-run it alone and report).
- [ ] **Step 5: commit** `feat(frontend): Export to Obsidian dialog (user menu + hub pages)`.

---

### Task 9: REST integration test

**Depends on:** Task 7 (and Task 1 seed change).

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ExportIT.java`

Pattern: copy the HTTP client, cookie handling (`secureCookieOverHttp`), `loginAsAdmin` (`janne` / `myP@5sw0rd`) and `logoutAdmin` helpers from `DerivedIngestIT.java:68-142,242-259`. Find how that module creates a page over REST (`grep -rn "PUT\|/api/pages" wikantik-it-tests/wikantik-it-test-rest/src/test/java | head`) and use it.

```java
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class ExportIT {
    private static final String CLUSTER = "export-it-" + UUID.randomUUID().toString().substring( 0, 8 );
    // @BeforeAll: login as admin; create ExportItHub (type: hub, cluster: CLUSTER, links [Child](ExportItChild))
    //             and ExportItChild (cluster: CLUSTER); logout.
    // @AfterAll: login; delete both pages; logout.

    @Test @Order( 1 ) void anonymousIsRejected() throws Exception {
        final HttpResponse< String > r = getString( "/api/export/preview?cluster=" + CLUSTER );
        assertEquals( 401, r.statusCode(), r.body() );
    }

    @Test @Order( 2 ) void previewAndDownloadProduceAWorkingVault() throws Exception {
        loginAsAdmin();
        try {
            final JsonObject p = JsonParser.parseString( getString( "/api/export/preview?cluster=" + CLUSTER ).body() ).getAsJsonObject();
            assertEquals( 2, p.get( "pages" ).getAsInt() );
            final HttpResponse< byte[] > zip = getBytes( "/api/export?cluster=" + CLUSTER );
            assertEquals( 200, zip.statusCode() );
            assertEquals( "application/zip", zip.headers().firstValue( "Content-Type" ).orElse( "" ) );
            final Map< String, byte[] > entries = unzip( zip.body() );
            final String hub = new String( entries.get( CLUSTER + "/ExportItHub.md" ), StandardCharsets.UTF_8 );
            final Matcher m = Pattern.compile( "\\[\\[([^\\]|#]+)" ).matcher( hub );
            assertTrue( m.find(), hub );
            final String target = m.group( 1 );
            assertTrue( entries.keySet().stream().anyMatch( k -> k.endsWith( "/" + target + ".md" ) ),
                    "wikilink [[" + target + "]] must resolve to a file in the vault: " + entries.keySet() );
            assertTrue( entries.containsKey( ".wikantik/manifest.json" ) );
        } finally { logoutAdmin(); }
    }
}
```
The structural index may lag page creation (memory: `reference_it_structural_index_seed_lag`) — poll the preview up to 30 s until `pages == 2` before asserting, failing with the last body if it never converges.

- [ ] **Step 1:** write the test with Task 7 already merged → it should PASS; to demonstrate it detects breakage, temporarily change the expected cluster folder to a wrong value, run, see FAIL, revert. Run with `bin/run-tests.sh --module rest` (check the flag name in `bin/run-tests.sh --help`) via `bin/agent-build.sh start export-it -- bin/run-tests.sh --module rest` and poll `status`/`wait`.
- [ ] **Step 2: commit** `test(it): Obsidian export end-to-end`.

---

### Task 10: Final verification and review

- [ ] **Step 1:** `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll `bin/agent-build.sh wait gate 540` until SUCCESS/FAILED. Prove freshness via the log's `CMD:` line. Any red is fixed in-session (no "pre-existing" deferrals).
- [ ] **Step 2:** `mvn pmd:check -Pcomplexity-gate -q` and `cd wikantik-frontend && npx eslint src` → clean.
- [ ] **Step 3:** coverage: `mvn clean install -Pcoverage -DskipITs` via agent-build — wikantik-main and wikantik-rest floors must hold.
- [ ] **Step 4:** superpowers:requesting-code-review over the whole feature diff (`git diff <commit-before-T1>..HEAD`).
- [ ] **Step 5:** update the wiki page `ObsidianExportDesign` status line to "implemented" via MCP `update_page`.
