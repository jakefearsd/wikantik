# Native Wikilinks & Embeds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Obsidian-style `[[Page]]`, `[[Page|Alias]]`, `[[Page#Heading]]`, `[[#Heading]]`, `![[Page]]`, `![[Page#Heading]]` and `![[Owner/file.png|300]]` first-class stored syntax — rendered by the server and the editor preview, and understood by every subsystem that reads links.

**Architecture:** One pure, regex-based syntax module (`WikiLinkSyntax`, wikantik-api) defines what a wikilink token is and how it splits; every consumer (scanner, renamer, mentions, `?format=md`, excerpts, export) uses it, and the frontend mirrors it in `wikiLinkSyntax.js`. Flexmark's `flexmark-ext-wikilink` only *locates* tokens during rendering (code exclusion for free); a new post-processor re-parses each token with `WikiLinkSyntax`, resolves the target with `WikiLinkResolver` (exact → case-insensitive → title/alias, via the structural index), and emits the same `<a>` markup the existing local-link path produces. Page embeds are rendered by `WikiEmbedRenderer` (ACL-checked, cycle/depth-limited, viewer-sensitive) both inline in the server render and through `GET /api/pages/{name}/embed` for the editor preview.

**Tech Stack:** Java 25, Flexmark 0.64.8 (+ `flexmark-ext-wikilink`), JUnit 5 + Mockito + `TestEngine`; React 19, react-markdown/remark (mdast), CodeMirror 6, vitest.

**Spec:** `docs/superpowers/specs/2026-10-01-native-wikilinks-design.md` (binding; read it before your task).

## Global Constraints

- TDD: every behaviour change starts with a test that fails for the right reason; run it red before implementing.
- Never swallow an exception: every `catch` logs at least `LOG.warn( "...{}...", context, e.getMessage() )` with the page names involved.
- Stage files by name (`git add <path> …`), never `git add -A` / `git add .`. Work on `main`.
- Every commit message ends with exactly these two trailer lines (after a blank line):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg
  ```
- New config keys are declared in `wikantik-main/src/main/resources/ini/wikantik.properties` (section, description, `Type:`, explicit default equal to the Java constant) and docs regenerated with `bin/config-reference.sh --write` (it rewrites `docs/ConfigurationReference.md` and the `WikantikConfigurationReference` wiki page — stage both). `ConfigSurfaceDriftTest` (wikantik-war) and `ConfigReferenceRegressionTest` (wikantik-extract-cli) must pass.
- `mvn pmd:check -Pcomplexity-gate` must pass with **no** additions to `build-support/pmd-complexity-baseline.properties`. Keep new methods small (≤ ~10 branches, ≤ ~40 lines); split dispatch into helpers.
- Frontend: `cd wikantik-frontend && npx vitest run` and `npm run lint` both clean for every frontend task.
- No new MCP tool in this work. `/api/pages/{name}/embed` is a sub-path of `PageResource` (`/api/pages/*` is already mapped at `wikantik-war/src/main/webapp/WEB-INF/web.xml:822`) — **no web.xml change and no servlet-count change**; Task 12 only extends the CLAUDE.md `/api/*` row text.
- When a task changes an upstream module (wikantik-api, wikantik-main) and then tests a downstream one, build with `-am`: `mvn test -pl <module> -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. After any signature change run `mvn test-compile -pl <module> -am -q` (plain `compile` skips tests).
- Use `grep -a` when searching (the shell `grep` is ugrep and silently skips some files).
- Do not edit `docs/wikantik-pages/Main.md`; do not add News.md entries. `CHANGELOG.md` `[Unreleased]` entries are written in Task 12 only.
- Both link syntaxes are first-class forever: never convert or rewrite existing `[Text](Page)` links, and never change their resolution (exact + plural only).

## Rulings (spec contradicted by code or silent — closest faithful approach)

- **R1 — heading anchors.** Ruling: wikilink anchors are `#` + `HeadingSlugs.slug(H)` (e.g. `/wiki/T#my-heading`), not the legacy `#section-T-…` form — the page view assigns plain `slugify` ids to h2/h3 (`PageView.jsx:255`) and no element carries a `section-…` id, so the legacy form would not scroll.
- **R2 — parser split.** Ruling: `flexmark-ext-wikilink` (options exactly as the spec lists) is used only to find tokens; the target/heading/alias split is re-done from `node.getChars()` by `WikiLinkSyntax.parse` — verified against 0.64.8: in a table cell `[[T\|A]]` yields link `T\|A` (no alias split) and `WikiImage` never splits `#` (link `Page#H`).
- **R3 — client hrefs.** Ruling: in the editor preview `remarkWikiLinks` emits the preview's existing relative scheme (`Name`, `Name#slug`, `#slug`) rather than `/wiki/Name`, because `wikiLinkTarget`, `remarkMissingLinks` and `useLinkPreview` only recognise relative targets in the preview; the parity fixture compares canonical decoded `Page#slug` hrefs.
- **R4 — HTML cache.** Ruling: `DefaultRenderingManager.textToHTML`'s final `CACHE_HTML` put (line 448) gains the missing `!isViewerSensitive( context )` guard — the page view (`PageResource:270`) and SSR (`SpaRoutingFilter:570`) render through `textToHTML`, so without it an embed page would be cached across viewers regardless of the flag.
- **R5 — inline page embeds.** Ruling: a page embed that shares its paragraph with other text renders as a plain wikilink (a `<div>` cannot sit inside `<p>`); a paragraph consisting only of page embeds (separated by line breaks/whitespace) becomes a sequence of embed blocks. Client mirrors this.
- **R6 — attachment URLs.** Ruling: `?format=md` and the client use the real attachment servlet path `/attach/O/f` (`web.xml:802`), not the spec's illustrative `/attachments/`.
- **R7 — embed endpoint.** Ruling: the JSON `html` is the embed *body* (state paragraphs included); the client renders the title. A caller without view gets 403 (spec §7) which the client shows as its restricted state; `restricted` is true only when the renderer itself withheld a body.
- **R8 — rename.** Ruling: besides exact / case-insensitive / `cleanLink`-equal targets, a target equal (case- and whitespace-insensitively) to the de-CamelCased old name (`TextUtil.beautifyString( from )`) is rewritten, because that phrase does not survive the rename; frontmatter title/alias matches are left alone as the spec says.
- **R9 — resolution data.** Ruling: resolver steps 2–3 both read `PageTitleLookup.entries()` (slug = page name; phrases = de-CamelCased name, title, aliases); the de-CamelCased phrase is part of the index's phrase data, so it participates in step 3.
- **R10 — parity scope.** Ruling: the shared `wikilinks.json` fixture covers exact-name, missing, heading, alias, table, code and leading-space cases only — the unit `TestEngine` wires no structural index, so case/alias resolution is pinned by `WikiLinkResolverTest` and `PageListResourceTest` instead.
- **R11 — export.** Ruling: the Obsidian export remaps native targets by exact page name (`ExportLinkContext` has no resolver); unmatched targets are kept verbatim (Obsidian resolves case-insensitively itself).
- **R12 — client missing styling.** Ruling: `remarkWikiLinks` applies the `createpage` class itself from the `resolve=true` map (`useMissingPages` cannot see `[[ ]]`); `remarkMissingLinks` is unchanged.

## Review Focus

1. Legacy pages with `[[` as prose or a bash conditional outside code (`[[ -f "$x" ]]`) must stay literal text — pinned by Task 1 (`parse` rejects leading whitespace), Task 3 (render test + fixture case) and Task 9 (client fixture).
2. `[[T]]` where `T` exists only as an alias of two pages — deterministic lowest page name, no exception — pinned by Task 2 `aliasSharedByTwoPagesResolvesToTheLexicographicallyLowest`.
3. A page that embeds itself, or A↔B mutual embeds — "Embed loop stopped", no stack overflow — pinned by Task 5 `selfEmbedStopsTheLoop` / `mutualEmbedsStopTheLoop`.
4. Restricted embedded page viewed by a guest then an admin — no cache cross-talk — pinned by Task 5 `textToHTMLDoesNotCacheAViewerSensitiveRender` + `aRenderWithAnEmbedIsFlaggedViewerSensitive`, and end-to-end by Task 12 `restrictedEmbedIsNotSharedBetweenViewers`.
5. Rename of a page referenced only via `[[lowercase name]]` — rewritten; aliased references untouched — pinned by Task 7 `rewritesCaseInsensitiveTargetsButNotAliases` and Task 12 `renameRewritesNativeLinks`.

---

### Task 1: `WikiLinkSyntax` + scanner (wikantik-api)

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/parser/WikiLinkSyntax.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/parser/MarkdownLinkScanner.java:56-95` (split `findLocalLinks`)
- Test: `wikantik-api/src/test/java/com/wikantik/api/parser/WikiLinkSyntaxTest.java` (create), `wikantik-api/src/test/java/com/wikantik/api/parser/MarkdownLinkScannerTest.java` (extend)

**Interfaces:**
- Produces (used by Tasks 3, 5, 7, 8; mirrored in JS by Task 9):
  ```java
  public final class WikiLinkSyntax {
      public static final Pattern TOKEN; // (!?)\[\[([^\[\]\n]+?)]]
      public record WikiLinkRef( int start, int end, boolean embed, String target, String heading,
                                 String alias, int nameFrom, int nameTo ) {
          public boolean isSamePage();     // target.isEmpty()
          public boolean isAttachment();   // target has '/' at index > 0
          public String pageName();        // owner for attachments, else target
          public String fileName();        // part after the first '/', else target
          public String displayText();     // alias ?: (heading only ? H : heading ? "T > H" : T)
          public int[] size();             // alias "300" -> {300,-1}; "300x200" -> {300,200}; else null
      }
      public static Optional< WikiLinkRef > parse( String token );            // offsets relative to token
      public static List< WikiLinkRef > findAll( String markdown );            // outside fenced + inline code
      public static String replaceAll( String markdown, Function< WikiLinkRef, String > fn ); // null = keep
      public static String toPlainText( String markdown );                     // links -> displayText, embeds -> ""
  }
  // MarkdownLinkScanner
  public static Set< String > findMarkdownLinks( String body ); // the OLD findLocalLinks body, unchanged
  public static Set< String > findLocalLinks( String body );    // findMarkdownLinks ∪ wikilink targets
  ```
  `nameFrom`/`nameTo` are offsets (same base as `start`) of the page-name part of the token — the owner for `Owner/file` — so a renamer can splice just the name.

- [ ] **Step 1: Write the failing tests**

```java
package com.wikantik.api.parser;

class WikiLinkSyntaxTest {
    private static WikiLinkSyntax.WikiLinkRef one( final String token ) {
        return WikiLinkSyntax.parse( token ).orElseThrow();
    }

    @Test void plainLinkAliasHeadingAndSamePage() {
        assertEquals( "Page", one( "[[Page]]" ).target() );
        assertEquals( "Page", one( "[[Page]]" ).displayText() );
        final var a = one( "[[Page|The Alias]]" );
        assertEquals( "Page", a.target() );  assertEquals( "The Alias", a.alias() );
        final var h = one( "[[Page#My Heading]]" );
        assertEquals( "My Heading", h.heading() );  assertEquals( "Page > My Heading", h.displayText() );
        final var s = one( "[[#Intro Part]]" );
        assertTrue( s.isSamePage() );  assertEquals( "Intro Part", s.displayText() );
    }

    @Test void tableEscapedPipeSplitsLikeAPlainPipe() {
        final var r = one( "[[Page\\|cell]]" );
        assertEquals( "Page", r.target() );  assertEquals( "cell", r.alias() );
    }

    @Test void embedsAttachmentsAndSizes() {
        final var e = one( "![[Page#H]]" );
        assertTrue( e.embed() );  assertEquals( "Page", e.target() );  assertEquals( "H", e.heading() );
        final var img = one( "![[Owner/pic.png|300x200]]" );
        assertTrue( img.isAttachment() );  assertEquals( "Owner", img.pageName() );
        assertEquals( "pic.png", img.fileName() );  assertArrayEquals( new int[]{ 300, 200 }, img.size() );
        assertArrayEquals( new int[]{ 300, -1 }, one( "![[O/f.png|300]]" ).size() );
        assertNull( one( "[[Page|300 reasons]]" ).size() );
    }

    @Test void leadingWhitespaceEmptyAndBareHashAreNotWikilinks() {           // Review Focus 1
        assertTrue( WikiLinkSyntax.parse( "[[ -f \"$x\" ]]" ).isEmpty() );
        assertTrue( WikiLinkSyntax.parse( "[[ Page]]" ).isEmpty() );
        assertTrue( WikiLinkSyntax.parse( "[[#]]" ).isEmpty() );
        assertTrue( WikiLinkSyntax.parse( "[[a [b] c]]" ).isEmpty() );
    }

    @Test void targetIsTrimmedAtTheEnd() {
        assertEquals( "Page", one( "[[Page  |x]]" ).target() );
        assertEquals( "Page", one( "[[Page ]]" ).target() );
    }

    @Test void findAllSkipsFencedAndInlineCodeAndBackslashEscapes() {
        final String md = "a [[One]] `[[Two]]`\n```\n[[Three]]\n```\n~~~\n![[Four]]\n~~~\n\\[[Five]] ![[Six|x]]";
        assertEquals( List.of( "One", "Six" ),
                WikiLinkSyntax.findAll( md ).stream().map( WikiLinkSyntax.WikiLinkRef::target ).toList() );
    }

    @Test void findAllOffsetsAreAbsoluteAndNameRangeCoversOnlyTheOwner() {
        final String md = "x ![[Owner/f.png|300]]";
        final var r = WikiLinkSyntax.findAll( md ).get( 0 );
        assertEquals( 2, r.start() );  assertEquals( md.length(), r.end() );
        assertEquals( "Owner", md.substring( r.nameFrom(), r.nameTo() ) );
    }

    @Test void replaceAllKeepsTokensTheFunctionDeclines() {
        assertEquals( "A b [[C]]", WikiLinkSyntax.replaceAll( "[[A]] b [[C]]",
                r -> r.target().equals( "A" ) ? "A" : null ) );
    }

    @Test void toPlainTextUsesDisplayTextAndDropsEmbeds() {
        assertEquals( "See Alias and T > H. ", WikiLinkSyntax.toPlainText( "See [[T|Alias]] and [[T#H]]. ![[Other]]" ) );
    }
}
```

Extend `MarkdownLinkScannerTest`:

```java
@Test void findLocalLinksIncludesNativeWikilinkTargetsButNotSamePageOrCode() {
    final Set< String > links = MarkdownLinkScanner.findLocalLinks(
            "[a](Legacy) [[Native|x]] [[Other#H]] ![[Embedded]] ![[Owner/f.png]] [[#Local]] `[[Code]]`" );
    assertEquals( Set.of( "Legacy", "Native", "Other", "Embedded", "Owner/f.png" ), links );
}
@Test void findMarkdownLinksIgnoresNativeWikilinks() {
    assertEquals( Set.of( "Legacy" ), MarkdownLinkScanner.findMarkdownLinks( "[a](Legacy) [[Native]]" ) );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-api -Dtest='WikiLinkSyntaxTest,MarkdownLinkScannerTest'`
Expected: compilation failure (`WikiLinkSyntax` / `findMarkdownLinks` not found).

- [ ] **Step 3: Implement**

`WikiLinkSyntax` core (complete — the JS mirror in Task 9 must follow these exact rules):

```java
public static final Pattern TOKEN = Pattern.compile( "(!?)\\[\\[([^\\[\\]\\n]+?)]]" );
private static final Pattern SIZE = Pattern.compile( "(\\d{1,5})(?:x(\\d{1,5}))?" );

public static Optional< WikiLinkRef > parse( final String token ) {
    final Matcher m = TOKEN.matcher( token == null ? "" : token );
    return m.matches() ? build( m ) : Optional.empty();
}

private static Optional< WikiLinkRef > build( final Matcher m ) {
    final String inner = m.group( 2 );
    if ( inner.isEmpty() || Character.isWhitespace( inner.charAt( 0 ) ) ) {
        return Optional.empty();
    }
    final int pipe = inner.indexOf( '|' );
    final boolean escaped = pipe > 0 && inner.charAt( pipe - 1 ) == '\\';
    final String targetPart = pipe < 0 ? inner : inner.substring( 0, escaped ? pipe - 1 : pipe );
    final String alias = pipe < 0 ? null : blankToNull( inner.substring( pipe + 1 ).trim() );
    final int hash = targetPart.indexOf( '#' );
    final String target = ( hash < 0 ? targetPart : targetPart.substring( 0, hash ) ).stripTrailing();
    final String heading = hash < 0 ? null : blankToNull( targetPart.substring( hash + 1 ).trim() );
    if ( target.isEmpty() && heading == null ) {
        return Optional.empty();
    }
    final int slash = target.indexOf( '/' );
    final int nameFrom = m.start( 2 );
    return Optional.of( new WikiLinkRef( m.start(), m.end(), !m.group( 1 ).isEmpty(), target, heading, alias,
            nameFrom, nameFrom + ( slash > 0 ? slash : target.length() ) ) );
}
```

`findAll`: compute a `boolean[] code` mask, then `while ( m.find() )` skip when `code[ m.start() ]` or the char before `m.start()` is `\` (flexmark treats `\[` as a literal bracket); otherwise `build( m ).ifPresent( out::add )`. The mask is a port of `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/CodeRegions.java` `scan` (fences ```` ``` ````/`~~~` toggled per line, paired backtick runs per line) with one difference: mask **every** fence, including ```` ```math ````. `replaceAll` walks `findAll` results in order, appending untouched text and `fn.apply( ref )` (or the original token when `null`). `toPlainText = replaceAll( md, r -> r.embed() ? "" : r.displayText() )`. `size()` returns `null` unless `alias` fully matches `SIZE`.

`MarkdownLinkScanner`: rename the current `findLocalLinks` body to `public static Set< String > findMarkdownLinks( final String bodyText )` (unchanged), and make `findLocalLinks` return a `LinkedHashSet` of `findMarkdownLinks( body )` plus, for every `WikiLinkSyntax.findAll( body )` ref that is not `isSamePage()`, `ref.target()`; drop blanks. Update the class Javadoc to mention `[[ ]]`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-api -Dtest='WikiLinkSyntaxTest,MarkdownLinkScannerTest'` → PASS. Then `mvn install -pl wikantik-api -DskipTests -q`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/parser/WikiLinkSyntax.java \
        wikantik-api/src/main/java/com/wikantik/api/parser/MarkdownLinkScanner.java \
        wikantik-api/src/test/java/com/wikantik/api/parser/WikiLinkSyntaxTest.java \
        wikantik-api/src/test/java/com/wikantik/api/parser/MarkdownLinkScannerTest.java
git commit -m "feat(wikilinks): WikiLinkSyntax parser and native targets in MarkdownLinkScanner"
```

---

### Task 2: `WikiLinkResolver` (wikantik-main)

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/wikilink/WikiLinkResolver.java`, `wikantik-main/src/main/java/com/wikantik/wikilink/package.html` (one-paragraph description, Apache header like siblings)
- Test: `wikantik-main/src/test/java/com/wikantik/wikilink/WikiLinkResolverTest.java`

**Interfaces:**
- Consumes: `com.wikantik.api.pagegraph.PageTitleLookup` (`entries()` → `TitleEntry( slug, title, phrases )`), `Engine.getFinalPageName(String) throws ProviderException`, `PageGraphSubsystemBridge.fromLegacyEngine( engine ).structuralIndexService()` (may be null), `MarkupParser.cleanLink`.
- Produces (Tasks 3, 4, 6, 7):
  ```java
  public final class WikiLinkResolver {
      @FunctionalInterface public interface ExactLookup { String finalPageName( String name ) throws ProviderException; }
      public record Resolution( String pageName, boolean exists ) {}
      public WikiLinkResolver( ExactLookup exact, Supplier< Optional< PageTitleLookup > > titles );
      public static WikiLinkResolver forEngine( Engine engine );
      public Resolution resolve( String target ); // never throws, never null
  }
  ```

- [ ] **Step 1: Write the failing tests**

```java
class WikiLinkResolverTest {
    private static PageTitleLookup lookup( final PageTitleLookup.TitleEntry... entries ) {
        final PageTitleLookup l = Mockito.mock( PageTitleLookup.class );
        Mockito.when( l.entries() ).thenReturn( List.of( entries ) );
        return l;
    }
    private static PageTitleLookup.TitleEntry entry( final String slug, final String... phrases ) {
        return new PageTitleLookup.TitleEntry( slug, slug, List.of( phrases ) );
    }
    private static WikiLinkResolver resolver( final Set< String > pages, final PageTitleLookup titles ) {
        return new WikiLinkResolver( n -> pages.contains( n ) ? n : null, () -> Optional.ofNullable( titles ) );
    }

    @Test void exactNameWins() {
        assertEquals( new WikiLinkResolver.Resolution( "Alpha", true ),
                resolver( Set.of( "Alpha" ), lookup( entry( "Alpha" ) ) ).resolve( "Alpha" ) );
    }
    @Test void exactLookupKeepsPluralMatching() {   // the ExactLookup is engine.getFinalPageName
        final WikiLinkResolver r = new WikiLinkResolver( n -> n.equals( "Dogs" ) ? "Dog" : null, Optional::empty );
        assertEquals( "Dog", r.resolve( "Dogs" ).pageName() );
    }
    @Test void caseInsensitivePageName() {
        assertEquals( "MachineLearning", resolver( Set.of( "MachineLearning" ),
                lookup( entry( "MachineLearning" ) ) ).resolve( "machinelearning" ).pageName() );
    }
    @Test void titleOrAliasPhrase() {
        final var r = resolver( Set.of( "Kubernetes" ), lookup( entry( "Kubernetes", "K8s Platform" ) ) );
        assertEquals( new WikiLinkResolver.Resolution( "Kubernetes", true ), r.resolve( "k8s  platform" ) );
    }
    @Test void aliasSharedByTwoPagesResolvesToTheLexicographicallyLowest() {   // Review Focus 2
        final var r = resolver( Set.of( "Zeta", "Alpha" ),
                lookup( entry( "Zeta", "Shared Alias" ), entry( "Alpha", "Shared Alias" ) ) );
        assertEquals( "Alpha", r.resolve( "shared alias" ).pageName() );
    }
    @Test void caseCollisionResolvesToTheLexicographicallyLowest() {
        final var r = resolver( Set.of(), lookup( entry( "foo" ), entry( "Foo" ) ) );
        assertEquals( "Foo", r.resolve( "FOO" ).pageName() );
    }
    @Test void indexNotReadyFallsBackToExactThenCleanedName() {
        final var r = resolver( Set.of( "Alpha" ), null );
        assertEquals( new WikiLinkResolver.Resolution( "Foo Bar", false ), r.resolve( "foo bar" ) );
        assertTrue( r.resolve( "Alpha" ).exists() );
    }
    @Test void providerFailureIsLoggedNotThrown() {
        final var r = new WikiLinkResolver( n -> { throw new ProviderException( "boom" ); }, Optional::empty );
        assertFalse( r.resolve( "X" ).exists() );
    }
    @Test void blankTargetIsNotFound() {
        assertFalse( resolver( Set.of(), null ).resolve( "  " ).exists() );
    }
}
```

(`ProviderException` is `com.wikantik.api.exceptions.ProviderException`; adjust the lambda to a block if javac needs it.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-main -Dtest=WikiLinkResolverTest` → compilation failure.

- [ ] **Step 3: Implement**

```java
public Resolution resolve( final String target ) {
    final String t = target == null ? "" : target.strip();
    if ( t.isEmpty() ) {
        return new Resolution( "", false );
    }
    final String exactName = exactName( t );
    if ( exactName != null ) {
        return new Resolution( exactName, true );
    }
    final Optional< Folded > folded = titles.get().map( WikiLinkResolver::fold );
    final String key = key( t );
    final String byName = folded.map( f -> f.byName().get( key ) ).orElse( null );
    if ( byName != null ) {
        return new Resolution( byName, true );
    }
    final String byPhrase = folded.map( f -> f.byPhrase().get( key ) ).orElse( null );
    return byPhrase != null ? new Resolution( byPhrase, true ) : new Resolution( MarkupParser.cleanLink( t ), false );
}
```

- `key( s )` = whitespace runs → one space, `strip()`, `toLowerCase( Locale.ROOT )`.
- `fold( PageTitleLookup )` memoises per lookup **identity** in a `private static final AtomicReference< Folded >` (`record Folded( PageTitleLookup source, Map<String,String> byName, Map<String,String> byPhrase )`); rebuild when `source != lookup`. Build with `map.merge( key, slug, ( a, b ) -> a.compareTo( b ) <= 0 ? a : b )` over every entry's slug (byName) and every phrase (byPhrase) — this is the deterministic tie-break.
- `exactName` wraps `exact.finalPageName( t )` in `try/catch ( ProviderException e ) { LOG.warn( "Wikilink exact lookup failed for '{}': {}", t, e.getMessage() ); return null; }`.
- `forEngine( engine )` = `new WikiLinkResolver( engine::getFinalPageName, () -> titleLookup( engine ) )`; `titleLookup` calls the bridge, returns `Optional.empty()` when the service is null, and catches `RuntimeException` with `LOG.warn( "Title index unavailable for wikilink resolution: {}", e.getMessage() )`.

- [ ] **Step 4: Run** `mvn test -pl wikantik-main -Dtest=WikiLinkResolverTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/wikilink/WikiLinkResolver.java \
        wikantik-main/src/main/java/com/wikantik/wikilink/package.html \
        wikantik-main/src/test/java/com/wikantik/wikilink/WikiLinkResolverTest.java
git commit -m "feat(wikilinks): WikiLinkResolver (exact, case-insensitive, title/alias)"
```

---

### Task 3: Server rendering of `[[ ]]` links + shared parity fixture

**Files:**
- Modify: `pom.xml:340-346` (dependencyManagement: add `flexmark-ext-wikilink` `${flexmark.version}` after `flexmark-ext-gitlab`), `wikantik-main/pom.xml:269-272` (add the dependency after `flexmark-ext-toc`)
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java:55-61,82-84,95-117`
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/MarkdownForWikantikExtension.java:70-83`
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/attributeprovider/WikantikLinkAttributeProvider.java:62-63`
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/renderer/WikantikLinkRenderer.java:47-80`
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/nodes/NativeWikiLinkNode.java`
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiLinkPostProcessor.java`, `.../nativelinks/NativeWikiLinkPostProcessorFactory.java`, `.../nativelinks/package.html`
- Create: `wikantik-frontend/src/utils/__fixtures__/wikilinks.json`
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiLinkRenderingTest.java`, `.../nativelinks/WikiLinkParityTest.java`

**Interfaces:**
- Consumes: `WikiLinkSyntax.parse` (Task 1), `WikiLinkResolver.forEngine(...).resolve(...)` (Task 2), `HeadingSlugs.slug` (`com.wikantik.export`).
- Produces:
  ```java
  // com.wikantik.markdown.nodes
  public class NativeWikiLinkNode extends WikantikLink {
      public enum Kind { PAGE, MISSING, ANCHOR, ATTACHMENT }
      public NativeWikiLinkNode( Link link, Kind kind, String target );
      public Kind kind();  public String target();
  }
  // com.wikantik.markdown.extensions.nativelinks
  public class NativeWikiLinkPostProcessorFactory extends NodePostProcessorFactory {
      public NativeWikiLinkPostProcessorFactory( Context context, boolean isImageInlining, List< Pattern > inlineImagePatterns );
  }
  ```
  Task 5 extends `NativeWikiLinkPostProcessor` with the embed branch; until then a page embed (`![[T]]`) renders exactly like `[[T]]`.

Rendering contract (href/class produced exactly as the existing local-link path does, see `MarkdownRendererTest.testMarkupExtensionSelfViewLink` / `testMarkupExtensionSelfEditLink`):

| Source | Output (TestEngine context path `/test`) |
|---|---|
| `[[T]]`, T exists | `<a href="/test/wiki/T" class="wikipage">T</a>` |
| `[[T#My H|A]]`, T exists | `<a href="/test/wiki/T#my-h" class="wikipage">A</a>` |
| `[[#My H]]` | `<a href="#my-h" class="wikipage">My H</a>` |
| `[[Nope]]` | `<a href="/test/edit/Nope" title="Create &#34;Nope&#34;" class="createpage">Nope</a>` |
| `[[O/f.pdf]]`, attachment exists | `<a href="/test/attach/O/f.pdf" class="attachment">O/f.pdf</a>` |
| `![[O/f.png|300]]`, attachment exists | `<img class="inline" src="/test/attach/O/f.png" alt="f.png" width="300" />` |
| `[[ -f "$x" ]]` | the literal source text |

A missing attachment *link* (`[[O/f.pdf]]` where `O/f.pdf` is not an attachment) renders as the MISSING page link to `O/f.pdf` (what `[x](O/f.pdf)` does today); a missing attachment *embed* (`![[O/f.png]]`, target contains `/`) renders `<span class="wiki-embed-missing">f.png</span>` (spec §4.1) — add a test for it. A target without `/` is an attachment of the current page iff `getAttachmentInfoName( context, target )` finds it; otherwise a page.

- [ ] **Step 1: Write the failing tests**

`NativeWikiLinkRenderingTest` — copy the `TestEngine` properties, `translate(...)` and `newPage(...)` helpers from `MarkdownRendererTest` (lines 51-55, 645-673) and assert:

```java
@Test void existingPageLink() throws Exception {
    newPage( "WlTarget" );
    assertEquals( "<p>See <a href=\"/test/wiki/WlTarget\" class=\"wikipage\">WlTarget</a></p>\n", translate( "See [[WlTarget]]" ) );
}
@Test void aliasAndHeadingUseTheViewSlug() throws Exception {
    newPage( "WlTarget" );
    assertEquals( "<p><a href=\"/test/wiki/WlTarget#my-heading\" class=\"wikipage\">go</a></p>\n",
            translate( "[[WlTarget#My Heading|go]]" ) );
    assertEquals( "<p><a href=\"/test/wiki/WlTarget#my-heading\" class=\"wikipage\">WlTarget &gt; My Heading</a></p>\n",
            translate( "[[WlTarget#My Heading]]" ) );
}
@Test void samePageHeading() throws Exception {
    assertEquals( "<p><a href=\"#intro-part\" class=\"wikipage\">Intro Part</a></p>\n", translate( "[[#Intro Part]]" ) );
}
@Test void missingPageIsACreateLink() throws Exception {
    assertEquals( "<p><a href=\"/test/edit/NoSuchWl\" title=\"Create &#34;NoSuchWl&#34;\" class=\"createpage\">NoSuchWl</a></p>\n",
            translate( "[[NoSuchWl]]" ) );
}
@Test void leadingWhitespaceStaysLiteral() throws Exception {   // Review Focus 1
    final String html = translate( "if [[ -f \"$x\" ]]; then" );
    assertFalse( html.contains( "<a" ), html );
    assertTrue( html.contains( "[[ -f" ), html );
}
@Test void codeIsNeverALink() throws Exception {
    assertFalse( translate( "`[[WlTarget]]`\n\n```\n[[WlTarget]]\n```\n" ).contains( "<a" ) );
}
@Test void tableCellEscapedPipe() throws Exception {
    newPage( "WlTarget" );
    final String html = translate( "| a |\n|---|\n| [[WlTarget\\|cell]] |\n" );
    assertTrue( html.contains( "<a href=\"/test/wiki/WlTarget\" class=\"wikipage\">cell</a>" ), html );
}
@Test void legacyMarkdownLinksAreUnchanged() throws Exception {
    newPage( "WlTarget" );
    assertEquals( "<p><a href=\"/test/wiki/WlTarget\" class=\"wikipage\">x</a></p>\n", translate( "[x](WlTarget)" ) );
}
```

Add attachment cases (`[[WlHost/doc.pdf]]`, `![[WlHost/pic.png|300]]`, `![[pic.png]]` on the current page) by storing attachments the way `MarkdownRendererTest.testAttachmentLink0` does (lines 357-380: `newPage( owner )`, `Wiki.contents().attachment( testEngine, owner, file )`, `getManager( AttachmentManager.class ).storeAttachment( att, testEngine.makeAttachmentFile() )`); assert the `class="attachment"` link and the `<img class="inline" … width="300" />`.

Create `wikantik-frontend/src/utils/__fixtures__/wikilinks.json` (canonical form: `href` = decoded `Page`, `Page#slug` or `#slug`; `class` = `"createpage"` or `""`):

```json
[
  { "name": "plain page", "markdown": "See [[ParityTarget]].\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget", "class": "", "text": "ParityTarget" } ] },
  { "name": "alias", "markdown": "[[ParityTarget|the target]]\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget", "class": "", "text": "the target" } ] },
  { "name": "heading", "markdown": "[[ParityTarget#My Heading]]\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget#my-heading", "class": "", "text": "ParityTarget > My Heading" } ] },
  { "name": "heading with alias", "markdown": "[[ParityTarget#My Heading|see]]\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget#my-heading", "class": "", "text": "see" } ] },
  { "name": "same-page heading", "markdown": "[[#Intro Part]]\n", "pages": [],
    "expected": [ { "href": "#intro-part", "class": "", "text": "Intro Part" } ] },
  { "name": "missing page", "markdown": "[[NoSuchParityPage]]\n", "pages": [],
    "expected": [ { "href": "NoSuchParityPage", "class": "createpage", "text": "NoSuchParityPage" } ] },
  { "name": "table escaped pipe", "markdown": "| a |\n|---|\n| [[ParityTarget\\|cell]] |\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget", "class": "", "text": "cell" } ] },
  { "name": "inline and fenced code", "markdown": "`[[ParityTarget]]`\n\n```\n[[ParityTarget]]\n```\n", "pages": ["ParityTarget"],
    "expected": [] },
  { "name": "leading space is prose", "markdown": "if [[ -f x ]]; then\n", "pages": [], "expected": [] },
  { "name": "trailing space trimmed", "markdown": "[[ParityTarget ]]\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget", "class": "", "text": "ParityTarget" } ] },
  { "name": "two links in one line", "markdown": "[[ParityTarget]] and [[NoSuchParityPage|n]]\n", "pages": ["ParityTarget"],
    "expected": [ { "href": "ParityTarget", "class": "", "text": "ParityTarget" },
                  { "href": "NoSuchParityPage", "class": "createpage", "text": "n" } ] }
]
```

`WikiLinkParityTest` (pattern: `CalloutExtensionTest#matchesTheSharedFrontendFixture`, loads `Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "wikilinks.json" )`): one `TestEngine` (same props as above); for each case save every name in `pages` with `"## My Heading\n\nBody.\n"`, render `markdown` for host page `WikilinkParityHost`, extract anchors with `Pattern.compile( "<a\\s([^>]*)>(.*?)</a>", Pattern.DOTALL )`, canonicalise (`href`: take the part after `/wiki/` or `/edit/` and `URLDecoder.decode(…, UTF_8)`, else the raw value; `class`: `"createpage"` if the class attribute contains it else `""`; `text`: strip tags and unescape `&gt; &lt; &amp; &#34; &quot;`) and `assertEquals( expectedJsonArray, actual, caseName )`.

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl wikantik-main -Dtest='NativeWikiLinkRenderingTest,WikiLinkParityTest'` → the wikilink assertions fail (output is literal `[[…]]`).

- [ ] **Step 3: Implement**

1. Poms: add the managed dependency and the `wikantik-main` dependency (no version in the module pom).
2. `MarkdownDocument`: `private static final Extension WIKILINK_EXT = WikiLinkExtension.create();` added to **both** extension lists; in `structuralOptions()` set `WikiLinkExtension.LINK_FIRST_SYNTAX`, `ALLOW_ANCHORS`, `ALLOW_PIPE_ESCAPE`, `IMAGE_LINKS` to `true`. `MarkdownParser.collectLinks`' `LINK_SCANNER` is untouched.
3. `MarkdownForWikantikExtension.extend( Parser.Builder )`: add `parserBuilder.postProcessorFactory( new NativeWikiLinkPostProcessorFactory( context, isImageInlining, inlineImagePatterns ) );`.
4. Factory: `super( true ); addNodes( WikiLink.class, WikiImage.class );`, `apply( document )` returns `new NativeWikiLinkPostProcessor( context, isImageInlining, inlineImagePatterns )`.
5. Post-processor `process( NodeTracker state, Node node )` (node is a `WikiNode`):
   ```java
   final Optional< WikiLinkRef > ref = WikiLinkSyntax.parse( node.getChars().toString() );
   if ( ref.isEmpty() ) { replace( state, node, new Text( node.getChars() ) ); return; }   // §2 literal rule
   final WikiLinkRef r = ref.get();
   final String attachment = attachmentName( r );            // null unless an existing attachment
   if ( attachment != null ) { replace( state, node, attachmentNode( r, attachment ) ); return; }
   if ( r.embed() && r.isAttachment() ) { replace( state, node, missingAttachmentEmbed( r ) ); return; }
   replace( state, node, pageLink( r ) );                     // Task 5 inserts the embed branch before this line
   ```
   - `attachmentName`: `r.isSamePage()` → null; else `PageSubsystemBridge.fromLegacyEngine( engine ).attachments().getAttachmentInfoName( context, r.target() )` (guard `..` / leading `/` as `LocalLinkNodePostProcessorState:47` does).
   - `pageLink`: build a `Link` (`new Link()`; `setUrl`, `setText( BasedSequence.of( r.displayText() ) )`, `appendChild( new Text( r.displayText() ) )`) and wrap it in `new NativeWikiLinkNode( link, kind, name )`:
     - same page → kind `ANCHOR`, url `"#" + HeadingSlugs.slug( r.heading() )`;
     - else `Resolution res = WikiLinkResolver.forEngine( context.getEngine() ).resolve( r.target() )` (create the resolver once per post-processor instance); exists → `PAGE`, url `context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), res.pageName() ) + ( heading != null ? "#" + HeadingSlugs.slug( heading ) : "" )` (append the fragment **after** `getURL`, never pass `#` into it); missing → `MISSING`, url `context.getURL( PAGE_EDIT, res.pageName() )`, `target = res.pageName()`.
   - `attachmentNode`: image (`new LinkParsingOperations( context ).isImageLink( r.fileName(), isImageInlining, inlineImagePatterns )`) and `r.embed()` → `WikiHtmlInline.of( "<img class=\"inline\" src=\"" + attUrl + "\" alt=\"" + TextUtil.replaceEntities( r.fileName() ) + "\"" + sizeAttrs( r.size() ) + " />" )`; otherwise a `NativeWikiLinkNode` of kind `ATTACHMENT` with url `context.getURL( PAGE_ATTACH, attachment )` and text `r.alias() != null ? r.alias() : r.target()`.
   - `replace`: `node.insertBefore( n ); node.unlink(); state.nodeRemoved( node ); state.nodeAddedWithChildren( n );` (same tracker calls as `WikantikLinkNodePostProcessor.replaceLinkWithWikantikLink`).
6. `WikantikLinkAttributeProvider.setAttributes`: before the `WikantikLink` branch, `if ( node instanceof NativeWikiLinkNode n ) { nativeState( n ).setAttributes( attributes, n ); return; }` where `PAGE`/`ANCHOR` → `new LocalReadLinkAttributeProviderState( wikiContext )`, `MISSING` → `new LocalEditLinkAttributeProviderState( wikiContext, n.target() )`, `ATTACHMENT` → `( a, l ) -> { a.replaceValue( "class", MarkupParser.CLASS_ATTACHMENT ); a.replaceValue( "href", l.getUrl().toString() ); }`.
7. `WikantikLinkRenderer`: flexmark dispatches handlers by **exact** class, so register `NativeWikiLinkNode.class` too — extract the existing `WikantikLink` lambda body into `private static void renderLink( WikantikLink node, NodeRendererContext context, HtmlWriter html )` and register it for both classes.

- [ ] **Step 4: Run** `mvn test -pl wikantik-main -Dtest='NativeWikiLinkRenderingTest,WikiLinkParityTest,MarkdownRendererTest,CalloutExtensionTest,MentionScannerTest'` → PASS (the last three guard regressions from the new extension). Then `mvn pmd:check -Pcomplexity-gate -pl wikantik-main`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml wikantik-main/pom.xml \
  wikantik-main/src/main/java/com/wikantik/parser/markdown/MarkdownDocument.java \
  wikantik-main/src/main/java/com/wikantik/markdown/MarkdownForWikantikExtension.java \
  wikantik-main/src/main/java/com/wikantik/markdown/extensions/wikilinks/attributeprovider/WikantikLinkAttributeProvider.java \
  wikantik-main/src/main/java/com/wikantik/markdown/renderer/WikantikLinkRenderer.java \
  wikantik-main/src/main/java/com/wikantik/markdown/nodes/NativeWikiLinkNode.java \
  wikantik-main/src/main/java/com/wikantik/markdown/extensions/nativelinks/ \
  wikantik-frontend/src/utils/__fixtures__/wikilinks.json \
  wikantik-main/src/test/java/com/wikantik/markdown/extensions/nativelinks/
git commit -m "feat(wikilinks): render native [[ ]] links server-side with a shared parity fixture"
```

---

### Task 4: `WikiEmbedRenderer` + `wikantik.embed.maxChars`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/wikilink/WikiEmbedRenderer.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties:766-768` (append after `wikantik.translatorReader.allowHTML` in the second `# [Rendering & wiki syntax]` section)
- Regenerate: `docs/ConfigurationReference.md` and the `WikantikConfigurationReference` page via `bin/config-reference.sh --write`
- Test: `wikantik-main/src/test/java/com/wikantik/wikilink/WikiEmbedRendererTest.java`

**Interfaces:**
- Consumes: `WikiLinkResolver` (Task 2), `HeadingSlugs.slug/sectionBody`, `FrontmatterParser.parse( text ).body()`, `RenderingManager.getParser( ctx, md ).parse()` + `RenderingManager.getHTML( ctx, WikiDocument )` (the non-caching overload), `Context.clone()/setPage/getVariable/setVariable`, `Context.VAR_VIEWER_SENSITIVE`.
- Produces (Tasks 5, 6):
  ```java
  public final class WikiEmbedRenderer {
      public static final String PROP_MAX_CHARS = "wikantik.embed.maxChars";
      public static final int DEFAULT_MAX_CHARS = 20000;
      public static final int MAX_DEPTH = 3;
      public static final String ATTR_EMBED_STACK = "com.wikantik.wikilink.WikiEmbedRenderer.stack";
      @FunctionalInterface public interface ViewCheck { boolean canView( Context context, Page page ); }
      @FunctionalInterface public interface BodyRenderer { String render( Context context, String markdown ) throws IOException; }
      public record EmbedResult( String title, String href, String bodyHtml,
                                 boolean missing, boolean restricted, boolean truncated ) {}
      public WikiEmbedRenderer( WikiLinkResolver resolver, PageManager pages, ViewCheck viewCheck,
                                BodyRenderer bodyRenderer, int maxChars );
      public static WikiEmbedRenderer forEngine( Engine engine );
      public static BodyRenderer defaultBodyRenderer( Engine engine );
      public EmbedResult render( Context context, String target, String heading ); // never throws
      public String renderBlock( Context context, String target, String heading );  // full <div class="wiki-embed">
  }
  ```

Behaviour of `render` (in this order; every branch calls `context.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE )` first):
1. `res = resolver.resolve( target )`; `page = res.exists() ? pages.getPage( res.pageName() ) : null`. `title` = `res.pageName()` + (heading ? `" › " + heading` : ""); `href` = `context.getURL( PAGE_VIEW, name ) + ( heading ? "#" + HeadingSlugs.slug( heading ) : "" )`.
2. `page == null` → missing: body `<p class="wiki-embed-missing"><a class="createpage" href="{editUrl}">Not created yet</a></p>`.
3. stack = `context.getVariable( ATTR_EMBED_STACK )` or `List.of( context.getPage().getName() )`; if stack contains `page.getName()` or `stack.size() > MAX_DEPTH` → body `<p class="wiki-embed-error">Embed loop stopped</p>`.
4. `!viewCheck.canView( context, page )` → restricted: body `<p class="wiki-embed-restricted">You don't have access to this page.</p>`, `restricted=true` (the block's title is then plain text).
5. text = `FrontmatterParser.parse( pages.getPureText( page ) ).body()`; heading → `HeadingSlugs.sectionBody( text, HeadingSlugs.slug( heading ) )`, empty → missing body `<p class="wiki-embed-missing">Section not found: {H}</p>`.
6. `text.length() > maxChars` → truncate at a block boundary (`lastIndexOf( "\n\n", maxChars )`, else `lastIndexOf( '\n', maxChars )`, else `maxChars`; if the cut lands inside an open ```` ``` ````/`~~~` fence — odd number of fence lines before it — move the cut to that fence's start) and append `<p class="wiki-embed-more"><a href="{href}">Continue reading →</a></p>` after the rendered body; `truncated=true`.
7. inner = `context.clone()`; `inner.setPage( page )`; `inner.setVariable( ATTR_EMBED_STACK, append( stack, page.getName() ) )` (a **new** list — never mutate the parent's); body = `bodyRenderer.render( inner, text )`.
8. Any `IOException | RuntimeException` → `LOG.warn( "Embed of '{}' into '{}' failed: {}", target, context.getPage().getName(), e.getMessage() )`, body `<p class="wiki-embed-error">Embed could not be rendered</p>`.

All page names and headings written into HTML go through `TextUtil.replaceEntities`. `renderBlock` emits:

```html
<div class="wiki-embed" data-embed="T" data-section="H"><div class="wiki-embed-title"><a class="wikipage" href="…">T › H</a></div><div class="wiki-embed-body">…</div></div>
```
(`data-section` omitted without a heading; title plain text when restricted.) `forEngine` wires `WikiLinkResolver.forEngine`, the engine's `PageManager`, `ViewCheck` = `AuthSubsystemBridge.fromLegacyEngine( engine ).authorization().isPermitted( ctx.getWikiSession(), PermissionFactory.getPagePermission( page, "view" ) )` (silent check, as `InsertPage.hasViewPermission`), `defaultBodyRenderer`, and `TextUtil.getIntegerProperty( engine.getWikiProperties(), PROP_MAX_CHARS, DEFAULT_MAX_CHARS )`.

- [ ] **Step 1: Write the failing tests** — `TestEngine.build()`; contexts via `Wiki.context().create( engine, HttpMockFactory.createHttpRequest(), engine.getManager( PageManager.class ).getPage( "EmbHost" ) )`; renderer built with the public constructor so `ViewCheck` and `maxChars` are injectable:

```java
private WikiEmbedRenderer renderer( final WikiEmbedRenderer.ViewCheck view, final int max ) {
    return new WikiEmbedRenderer( WikiLinkResolver.forEngine( engine ), engine.getManager( PageManager.class ), view,
            WikiEmbedRenderer.defaultBodyRenderer( engine ), max );
}
@Test void rendersTheTargetBodyWithoutFrontmatter() throws Exception {
    engine.saveText( "EmbTarget", "---\ntype: article\n---\nHello **embed**.\n" );
    final var r = renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "EmbTarget", null );
    assertTrue( r.bodyHtml().contains( "<strong>embed</strong>" ), r.bodyHtml() );
    assertFalse( r.bodyHtml().contains( "type:" ) );
    assertFalse( r.missing() || r.restricted() || r.truncated() );
}
@Test void sectionEmbedTakesOnlyThatSection() throws Exception {
    engine.saveText( "EmbTarget", "Intro.\n\n## Usage\n\nRun it.\n\n## Other\n\nNo.\n" );
    final var r = renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "EmbTarget", "Usage" );
    assertTrue( r.bodyHtml().contains( "Run it." ) );  assertFalse( r.bodyHtml().contains( "No." ) );
    assertEquals( "EmbTarget › Usage", r.title() );
}
@Test void missingPageAndMissingSection() throws Exception {
    assertTrue( renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "NoSuchEmb", null ).missing() );
    engine.saveText( "EmbTarget", "Body.\n" );
    final var r = renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "EmbTarget", "Nope" );
    assertTrue( r.missing() );  assertTrue( r.bodyHtml().contains( "Section not found: Nope" ) );
}
@Test void deniedViewerGetsNoBody() throws Exception {
    engine.saveText( "EmbSecret", "classified text\n" );
    final var r = renderer( ( c, p ) -> false, 20000 ).render( hostContext(), "EmbSecret", null );
    assertTrue( r.restricted() );  assertFalse( r.bodyHtml().contains( "classified" ) );
    assertFalse( renderer( ( c, p ) -> false, 20000 ).renderBlock( hostContext(), "EmbSecret", null ).contains( "<a class=\"wikipage\"" ) );
}
@Test void everyRenderFlagsTheContextViewerSensitive() throws Exception {
    final Context ctx = hostContext();
    renderer( ( c, p ) -> true, 20000 ).render( ctx, "NoSuchEmb", null );
    assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
}
@Test void truncatesAtABlockBoundaryWithAContinueLink() throws Exception {
    engine.saveText( "EmbLong", "First paragraph.\n\nSecond paragraph that is long.\n" );
    final var r = renderer( ( c, p ) -> true, 25 ).render( hostContext(), "EmbLong", null );
    assertTrue( r.truncated() );
    assertTrue( r.bodyHtml().contains( "First paragraph." ) );  assertFalse( r.bodyHtml().contains( "Second" ) );
    assertTrue( r.bodyHtml().contains( "Continue reading" ) );
}
@Test void bodyRendererFailureIsContained() throws Exception {
    engine.saveText( "EmbTarget", "Body.\n" );
    final var broken = new WikiEmbedRenderer( WikiLinkResolver.forEngine( engine ), engine.getManager( PageManager.class ),
            ( c, p ) -> true, ( c, md ) -> { throw new IOException( "boom" ); }, 20000 );
    assertTrue( broken.render( hostContext(), "EmbTarget", null ).bodyHtml().contains( "wiki-embed-error" ) );
}
@Test void blockMarkupCarriesTheDataAttributes() throws Exception {
    engine.saveText( "EmbTarget", "## Usage\n\nRun.\n" );
    final String html = renderer( ( c, p ) -> true, 20000 ).renderBlock( hostContext(), "EmbTarget", "Usage" );
    assertTrue( html.startsWith( "<div class=\"wiki-embed\" data-embed=\"EmbTarget\" data-section=\"Usage\">" ), html );
}
```

- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest=WikiEmbedRendererTest` → compilation failure.

- [ ] **Step 3: Implement** per the contract above (split into `missing(...)`, `stopped(...)`, `restricted(...)`, `sourceText(...)`, `truncate(...)`, `renderBody(...)` helpers to stay under the PMD gate). Declare the key:

```properties
# Maximum number of characters of Markdown source a `![[Page]]` / `![[Page#Heading]]`
# embed transcludes. Longer sources are cut at the last block boundary before the
# limit and followed by a "Continue reading" link to the embedded page.
# Type: int
wikantik.embed.maxChars=20000
```
Then `bin/config-reference.sh --write`.

- [ ] **Step 4: Run** `mvn test -pl wikantik-main -Dtest=WikiEmbedRendererTest` → PASS; `mvn test -pl wikantik-war,wikantik-extract-cli -am -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest' -Dsurefire.failIfNoSpecifiedTests=false` → PASS (`ConfigSurfaceDriftTest` lives in wikantik-war, `ConfigReferenceRegressionTest` in wikantik-extract-cli).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/wikilink/WikiEmbedRenderer.java \
  wikantik-main/src/main/resources/ini/wikantik.properties docs/ConfigurationReference.md \
  docs/wikantik-pages/WikantikConfigurationReference.md \
  wikantik-main/src/test/java/com/wikantik/wikilink/WikiEmbedRendererTest.java
git commit -m "feat(wikilinks): WikiEmbedRenderer with ACL, loop, section and size limits"
```
(Stage whatever files `bin/config-reference.sh --write` actually changed — check `git status`.)

---

### Task 5: Embeds in the server render + cache safety

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/nodes/WikiEmbedBlock.java` (`extends Block`, holds `String html`)
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiLinkPostProcessor.java` (embed branch)
- Modify: `wikantik-main/src/main/java/com/wikantik/markdown/renderer/WikantikLinkRenderer.java` (handler for `WikiEmbedBlock`)
- Modify: `wikantik-main/src/main/java/com/wikantik/render/DefaultRenderingManager.java:447-451` (R4 guard)
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/markdown/WikantikHtmlSanitizer.java:57` (allow `data-embed`, `data-section` on `div`)
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiEmbedRenderingTest.java`, `wikantik-main/src/test/java/com/wikantik/render/DefaultRenderingManagerCITest.java` (add), `wikantik-main/src/test/java/com/wikantik/parser/markdown/WikantikHtmlSanitizerTest.java` (add)

**Interfaces:**
- Consumes: `WikiEmbedRenderer.forEngine( engine ).renderBlock( context, target, heading )` (Task 4), `NativeWikiLinkPostProcessor` (Task 3).
- Produces: `public class WikiEmbedBlock extends Block { public WikiEmbedBlock( String html ); public String html(); }` rendered raw.

Placement rule (R5): when the `WikiImage`'s parent is a `Paragraph` whose children are all page-embed `WikiImage`s (valid, non-attachment `embed()` refs) plus `SoftLineBreak`/`HardLineBreak`/whitespace-only `Text`, replace the whole paragraph by one `WikiEmbedBlock` per embed (in order). Otherwise a page embed falls through to the Task 3 `pageLink` path. Do the paragraph replacement once (when processing the paragraph's first embed child; later siblings will already be unlinked — check `node.getParent() == null` and return).

- [ ] **Step 1: Write the failing tests**

`NativeWikiEmbedRenderingTest` (TestEngine props as in Task 3; keep a reference to the `Context` you render with):

```java
@Test void standaloneEmbedBecomesABlock() throws Exception {
    engine.saveText( "EmbBody", "Embedded **text**.\n" );
    final String html = translate( "Before.\n\n![[EmbBody]]\n\nAfter.\n" );
    assertTrue( html.contains( "<div class=\"wiki-embed\" data-embed=\"EmbBody\">" ), html );
    assertTrue( html.contains( "<strong>text</strong>" ), html );
    assertFalse( html.contains( "<p><div" ), html );
}
@Test void consecutiveEmbedLinesBecomeConsecutiveBlocks() throws Exception {
    engine.saveText( "EmbA", "aaa\n" );  engine.saveText( "EmbB", "bbb\n" );
    final String html = translate( "![[EmbA]]\n![[EmbB]]\n" );
    assertTrue( html.indexOf( "data-embed=\"EmbA\"" ) < html.indexOf( "data-embed=\"EmbB\"" ), html );
}
@Test void inlineEmbedRendersAsALink() throws Exception {
    engine.saveText( "EmbBody", "x\n" );
    assertTrue( translate( "see ![[EmbBody]] here" ).contains( "class=\"wikipage\"" ) );
}
@Test void selfEmbedStopsTheLoop() throws Exception {                    // Review Focus 3
    engine.saveText( "EmbSelf", "![[EmbSelf]]\n" );
    assertTrue( renderPage( "EmbSelf" ).contains( "Embed loop stopped" ) );
}
@Test void mutualEmbedsStopTheLoop() throws Exception {                  // Review Focus 3
    engine.saveText( "EmbMutA", "A body\n\n![[EmbMutB]]\n" );
    engine.saveText( "EmbMutB", "B body\n\n![[EmbMutA]]\n" );
    final String html = renderPage( "EmbMutA" );
    assertTrue( html.contains( "B body" ) );  assertTrue( html.contains( "Embed loop stopped" ) );
}
@Test void depthIsCappedAtThree() throws Exception {
    for ( int i = 1; i <= 4; i++ ) engine.saveText( "EmbDepth" + i, "level" + i + "\n\n![[EmbDepth" + ( i + 1 ) + "]]\n" );
    engine.saveText( "EmbDepth5", "level5\n" );
    final String html = renderPage( "EmbDepth1" );
    assertTrue( html.contains( "level4" ) );  assertFalse( html.contains( "level5" ) );
    assertTrue( html.contains( "Embed loop stopped" ) );
}
@Test void aRenderWithAnEmbedIsFlaggedViewerSensitive() throws Exception {  // Review Focus 4
    engine.saveText( "EmbBody", "x\n" );
    final Context ctx = hostContext();
    render( ctx, "![[EmbBody]]\n" );
    assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
}
@Test void aRenderWithOnlyLinksIsNotViewerSensitive() throws Exception {
    final Context ctx = hostContext();
    render( ctx, "[[EmbBody]]\n" );
    assertNull( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
}
```
(`renderPage( name )` = render the saved page's text with a context for that page, via `new MarkdownParser( ctx, reader ).parse()` + `new MarkdownRenderer( ctx, doc ).getString()` exactly as `MarkdownRendererTest.translate`.)

`DefaultRenderingManagerCITest` (next to `getRenderedDocumentDoesNotCacheAViewerSensitiveRender`, ~line 200):

```java
@Test
void textToHTMLDoesNotCacheAViewerSensitiveRender() {                       // Review Focus 4
    when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );
    when( cachingManager.get( eq( CachingManager.CACHE_HTML ), anyString(), any() ) ).thenReturn( null );
    when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) ).thenReturn( "false" );
    final Context ctx = viewContext( "EmbedderPage", 1 );
    when( ctx.getVariable( com.wikantik.api.core.Context.VAR_VIEWER_SENSITIVE ) ).thenReturn( Boolean.TRUE );
    mgr.textToHTML( ctx, "**hi**" );
    verify( cachingManager, never() ).put( eq( CachingManager.CACHE_HTML ), anyString(), any() );
}
```

`WikantikHtmlSanitizerTest`: `sanitize( "<div class=\"wiki-embed\" data-embed=\"A B\" data-section=\"H\">x</div>" )` keeps both data attributes.

- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest='NativeWikiEmbedRenderingTest,DefaultRenderingManagerCITest,WikantikHtmlSanitizerTest'` → failures (embeds render as links; cache put happens; data attrs stripped).

- [ ] **Step 3: Implement**
  - Post-processor: before `pageLink`, `if ( r.embed() && embedOnlyParagraph( node ) ) { replaceParagraphWithEmbeds( state, (Paragraph) node.getParent() ); return; }`. Each block's HTML = `embeds.renderBlock( context, r.target().isEmpty() ? context.getPage().getName() : r.target(), r.heading() )` (`embeds = WikiEmbedRenderer.forEngine( context.getEngine() )`, created once per post-processor).
  - `WikantikLinkRenderer`: `set.add( new NodeRenderingHandler<>( WikiEmbedBlock.class, ( node, ctx, html ) -> { html.line(); html.raw( node.html() ); html.line(); } ) );`.
  - `DefaultRenderingManager` line 448: `if( useHtmlCache( context ) && !isViewerSensitive( context ) ) {`.
  - Sanitizer: `.allowAttributes( "data-embed", "data-section" ).onElements( "div" )`.

- [ ] **Step 4: Run** the Step 2 command plus `-Dtest=…,InsertPageTest,MarkdownRendererTest,NativeWikiLinkRenderingTest` → PASS; `mvn pmd:check -Pcomplexity-gate -pl wikantik-main`.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/markdown/nodes/WikiEmbedBlock.java \
  wikantik-main/src/main/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiLinkPostProcessor.java \
  wikantik-main/src/main/java/com/wikantik/markdown/renderer/WikantikLinkRenderer.java \
  wikantik-main/src/main/java/com/wikantik/render/DefaultRenderingManager.java \
  wikantik-main/src/main/java/com/wikantik/parser/markdown/WikantikHtmlSanitizer.java \
  wikantik-main/src/test/java/com/wikantik/markdown/extensions/nativelinks/NativeWikiEmbedRenderingTest.java \
  wikantik-main/src/test/java/com/wikantik/render/DefaultRenderingManagerCITest.java \
  wikantik-main/src/test/java/com/wikantik/parser/markdown/WikantikHtmlSanitizerTest.java
git commit -m "feat(wikilinks): transclude ![[Page]] embeds; never HTML-cache viewer-sensitive renders"
```

---

### Task 6: REST — embed endpoint and `resolve=true`

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java:168-172` (dispatch), add `handleEmbed` next to `handlePreview` (~802)
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java:78-80,153-158,248-257` (`resolve` param)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java` (after the preview block ~969-1030), `wikantik-rest/src/test/java/com/wikantik/rest/PageListResourceTest.java`

**Interfaces:**
- Consumes: `WikiEmbedRenderer.forEngine(…).render(…)` (Task 4), `WikiLinkResolver` (Task 2).
- Produces (Tasks 9-10):
  - `GET /api/pages/{name}/embed[?section=H]` → `200 {"html": string, "missing": bool, "restricted": bool, "truncated": bool}`, header `Cache-Control: private, no-cache`; `403` only when the caller lacks `view` on the resolved name; never `404` for a missing page (`missing:true`).
  - `GET /api/pages?names=a,b&resolve=true` → existing payload **plus** `"resolved": { "<requested name verbatim>": "<PageName>" | null }`; `null` when unresolved or when the caller cannot view the resolved page.

- [ ] **Step 1: Write the failing tests**

`PageResourceTest`:

```java
@Test void embedReturnsRenderedBodyAndFlags() throws Exception {
    engine.saveText( "RestEmbedPage", "---\ntitle: X\n---\nHello **there**.\n\n## Usage\n\nRun it.\n" );
    final JsonObject all = gson.fromJson( doGet( "RestEmbedPage/embed" ), JsonObject.class );
    assertTrue( all.get( "html" ).getAsString().contains( "<strong>there</strong>" ) );
    assertFalse( all.get( "missing" ).getAsBoolean() );
    assertFalse( all.get( "restricted" ).getAsBoolean() );
    assertFalse( all.get( "truncated" ).getAsBoolean() );
    final JsonObject sec = gson.fromJson( doGetWithParams( "RestEmbedPage/embed", Map.of( "section", "Usage" ) ), JsonObject.class );
    assertTrue( sec.get( "html" ).getAsString().contains( "Run it." ) );
    assertFalse( sec.get( "html" ).getAsString().contains( "there" ) );
}
@Test void embedOfAMissingPageIsMissingNot404() throws Exception {
    final JsonObject obj = gson.fromJson( doGet( "RestNoSuchEmbed/embed" ), JsonObject.class );
    assertTrue( obj.get( "missing" ).getAsBoolean() );
}
@Test void embedWithoutViewPermissionIs403() throws Exception {
    engine.saveText( "RestEmbedSecret", "Secret." );
    final PageResource spy = Mockito.spy( servlet );
    // checkPagePermission is the enforcing path; stub it the way the preview ACL test stubs hasPagePermission
    Mockito.doAnswer( inv -> { ( (HttpServletResponse) inv.getArgument( 1 ) ).setStatus( 403 ); return false; } )
           .when( spy ).checkPagePermission( Mockito.any(), Mockito.any(), Mockito.eq( "RestEmbedSecret" ), Mockito.eq( "view" ) );
    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    spy.doGet( createRequest( "RestEmbedSecret/embed" ), response );
    Mockito.verify( response ).setStatus( 403 );
}
@Test void embedIsNeverServedFromTheHttpCache() throws Exception {
    engine.saveText( "RestEmbedPage", "Body." );
    final HttpServletResponse response = HttpMockFactory.createHttpResponse();
    Mockito.doReturn( new PrintWriter( new StringWriter() ) ).when( response ).getWriter();
    servlet.doGet( createRequest( "RestEmbedPage/embed" ), response );
    Mockito.verify( response ).setHeader( "Cache-Control", "private, no-cache" );
}
```
(Check whether `checkPagePermission` is `protected` and spy-able; if it is `final`/private use the existing `previewOfAMissingPageAndOfAnUnviewablePageAreIndistinguishable` approach of stubbing `hasPagePermission` and make `handleEmbed` deny through a helper that calls it — keep the 403 status either way.)

`PageListResourceTest` (reuse `indexAwareServlet()` at ~350 for the alias case):

```java
@Test void resolveTrueMapsEachRequestedNameToItsPageOrNull() throws Exception {
    engine.saveText( "RestResolveTarget", "---\naliases: [zebra notes]\n---\nBody." );
    engine.saveText( "RestResolveSecret", "[{ALLOW view Admin}]\nSecret." );
    try {
        final String json = doGetParams( indexAwareServlet(), Map.of(
                "names", "RestResolveTarget,restresolvetarget,zebra notes,RestNoSuch,RestResolveSecret", "resolve", "true" ) );
        final JsonObject resolved = gson.fromJson( json, JsonObject.class ).getAsJsonObject( "resolved" );
        assertEquals( "RestResolveTarget", resolved.get( "RestResolveTarget" ).getAsString() );
        assertEquals( "RestResolveTarget", resolved.get( "restresolvetarget" ).getAsString() );
        assertEquals( "RestResolveTarget", resolved.get( "zebra notes" ).getAsString() );
        assertTrue( resolved.get( "RestNoSuch" ).isJsonNull() );
        assertTrue( resolved.get( "RestResolveSecret" ).isJsonNull(), "anonymous caller must not learn a restricted page" );
    } finally {
        engine.deleteQuietly( "RestResolveTarget", "RestResolveSecret" );
    }
}
@Test void withoutResolveThereIsNoResolvedMap() throws Exception {
    assertFalse( gson.fromJson( doGetParams( servlet, Map.of( "names", "Anything" ) ), JsonObject.class ).has( "resolved" ) );
}
```
(Use the existing `doGetParams` helper's signature; check it with `grep -an "doGetParams" PageListResourceTest.java`. Gson must serialise null map values — build the map as a `JsonObject` with `add( name, JsonNull.INSTANCE )` or send via a Gson with `serializeNulls()`; mirror how `sendJson` is implemented in `RestServletBase`.)

- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -am -Dtest='PageResourceTest,PageListResourceTest' -Dsurefire.failIfNoSpecifiedTests=false` → new tests fail.

- [ ] **Step 3: Implement**
  - `doGet`: after the `/preview` branch, `if ( pathParam.endsWith( "/embed" ) ) { handleEmbed( request, response, pathParam.substring( 0, pathParam.length() - "/embed".length() ) ); return; }`.
  - `handleEmbed`: `resolved = WikiLinkResolver.forEngine( getEngine() ).resolve( name )`; `if ( !checkPagePermission( request, response, resolved.pageName(), "view" ) ) return;` build a `Context` with `Wiki.context().create( getEngine(), request, page )` where `page` is the existing page or `Wiki.contents().page( getEngine(), resolved.pageName() )`; `r = WikiEmbedRenderer.forEngine( getEngine() ).render( ctx, name, section )`; respond with the four fields and the `Cache-Control` header (set before `sendJson`, as `handlePreview` does).
  - `PageListResource`: read `resolve`; when `"true".equals( resolveParam ) && namesParam != null`, build `new WikiLinkResolver( getEngine()::getFinalPageName, this::titleLookup )` (the existing private `titleLookup()` at line 248 — that is what makes `indexAwareServlet()` alias resolution work), resolve every `wantedNames` entry, filter the resolved names through `filterViewable( request, … )`, and put the ordered map under `"resolved"`.

- [ ] **Step 4: Run** the Step 2 command → PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java \
  wikantik-rest/src/main/java/com/wikantik/rest/PageListResource.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageResourceTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/PageListResourceTest.java
git commit -m "feat(wikilinks): GET /api/pages/{name}/embed and names= resolve=true"
```

---

### Task 7: Reference graph + rename

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/pagegraph/references/DefaultReferenceManager.java:542-562`
- Modify: `wikantik-main/src/main/java/com/wikantik/content/DefaultPageRenamer.java:188,254-275`
- Test: `wikantik-main/src/test/java/com/wikantik/pagegraph/references/DefaultReferenceManagerCITest.java` (near 560-590), `wikantik-main/src/test/java/com/wikantik/content/PageRenamerTest.java`, create `wikantik-main/src/test/java/com/wikantik/content/DefaultPageRenamerWikiLinkTest.java`

**Interfaces:**
- Consumes: `WikiLinkSyntax.findAll/replaceAll`, `MarkdownLinkScanner.findMarkdownLinks` (Task 1), `WikiLinkResolver` (Task 2).
- Produces:
  ```java
  // DefaultReferenceManager (package-private test seam)
  void setWikiLinkResolver( WikiLinkResolver resolver );
  // DefaultPageRenamer (package-private, pure)
  static String rewriteWikiLinks( String text, String from, String to );
  ```

`scanWikiLinks`: `links.addAll( MarkdownLinkScanner.findMarkdownLinks( body ) )`; then for each `WikiLinkSyntax.findAll( body )` ref that is not same-page: attachment → `resolve( ref.pageName() ).pageName() + "/" + ref.fileName()`; page (incl. embeds) → `resolve( ref.target() ).pageName()`. Resolver: lazily `WikiLinkResolver.forEngine( engine )` unless the seam set one. Frontmatter `related` handling unchanged.

`rewriteWikiLinks( text, from, to )` = `WikiLinkSyntax.replaceAll( text, ref -> renames( ref, from ) ? splice( text, ref, to ) : null )`, where `splice` returns `text.substring( ref.start(), ref.nameFrom() ) + to + text.substring( ref.nameTo(), ref.end() )` (keeps `!`, `#Heading`, `|alias`, `\|`, `|300`, `/file`), and `renames( ref, from )` is true for non-same-page refs whose `pageName()` `equals` / `equalsIgnoreCase( from )`, or whose `MarkupParser.cleanLink( name )` or `MarkupParser.wikifyLink( name )` equals `from`, or (R8) whose whitespace-folded lower-case form equals that of `TextUtil.beautifyString( from )`. Call it in `renameReferrers` right after `replaceReferrerString` (line 188): `newText = rewriteWikiLinks( newText, fromPage.getName(), toPage.getName() );`.

- [ ] **Step 1: Write the failing tests**

`DefaultReferenceManagerCITest`:

```java
@Test void scanWikiLinksResolvesNativeTargets() {
    mgr.setWikiLinkResolver( new WikiLinkResolver( n -> n.equals( "FooBar" ) ? "FooBar" : null, () -> Optional.of( titles( "FooBar", "Foo Bar Notes" ) ) ) );
    final Collection< String > links = mgr.scanWikiLinks( mockPage( "P" ),
            "[[foo bar notes]] [[FooBar#H|x]] ![[FooBar]] ![[FooBar/pic.png]] [[#Local]] `[[Code]]` [[new page]]" );
    assertEquals( List.of( "FooBar", "FooBar/pic.png", "New Page" ), List.copyOf( links ) );
}
```
(`titles(slug, phrase)` = a Mockito `PageTitleLookup` whose `entries()` returns one `TitleEntry`; `links` is a `LinkedHashSet`, so duplicates collapse in first-seen order.)

`DefaultPageRenamerWikiLinkTest` (pure, no engine):

```java
@Test void rewritesEveryNativeFormPreservingTheRest() {
    final String in = "[[OldPage]] [[OldPage#H|a]] [[OldPage\\|t]] ![[OldPage]] ![[OldPage#H]] ![[OldPage/f.png|300]] [[Other]]";
    assertEquals( "[[NewPage]] [[NewPage#H|a]] [[NewPage\\|t]] ![[NewPage]] ![[NewPage#H]] ![[NewPage/f.png|300]] [[Other]]",
            DefaultPageRenamer.rewriteWikiLinks( in, "OldPage", "NewPage" ) );
}
@Test void rewritesCaseInsensitiveTargetsButNotAliases() {               // Review Focus 5
    assertEquals( "[[NewPage]] [[NewPage|x]] [[Legacy Alias]]",
            DefaultPageRenamer.rewriteWikiLinks( "[[oldpage]] [[old page|x]] [[Legacy Alias]]", "OldPage", "NewPage" ) );
}
@Test void leavesCodeFencesAndInlineCodeAlone() {
    final String in = "`[[OldPage]]`\n```\n[[OldPage]]\n```\n";
    assertEquals( in, DefaultPageRenamer.rewriteWikiLinks( in, "OldPage", "NewPage" ) );
}
```

`PageRenamerTest` (end-to-end, mirrors `testReferrerChangeAnchor` at line 188):

```java
@Test
public void testReferrerChangeNativeWikilinks() throws Exception {
    m_engine.saveText( "TestPage", "foofoo" );
    m_engine.saveText( "TestPage2", "See [[TestPage#heading1|here]] and ![[TestPage]]." );
    final Page p = m_engine.getManager( PageManager.class ).getPage( "TestPage" );
    m_engine.getManager( PageRenamer.class ).renamePage( Wiki.context().create( m_engine, p ), "TestPage", "FooTest", true );
    assertEquals( "See [[FooTest#heading1|here]] and ![[FooTest]].",
            m_engine.getManager( PageManager.class ).getPureText( "TestPage2", WikiProvider.LATEST_VERSION ).trim() );
}
```

- [ ] **Step 2: Run** `mvn test -pl wikantik-main -Dtest='DefaultReferenceManagerCITest,DefaultPageRenamerWikiLinkTest,PageRenamerTest'` → failures.

- [ ] **Step 3: Implement** as specified above.

- [ ] **Step 4: Run** the Step 2 command plus `ReferenceManagerTest` (grep for its exact name under `pagegraph/references`) → PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/pagegraph/references/DefaultReferenceManager.java \
  wikantik-main/src/main/java/com/wikantik/content/DefaultPageRenamer.java \
  wikantik-main/src/test/java/com/wikantik/pagegraph/references/DefaultReferenceManagerCITest.java \
  wikantik-main/src/test/java/com/wikantik/content/DefaultPageRenamerWikiLinkTest.java \
  wikantik-main/src/test/java/com/wikantik/content/PageRenamerTest.java
git commit -m "feat(wikilinks): native links in the reference graph and page rename"
```

---

### Task 8: Other link consumers

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/mentions/MentionMasking.java:24-45`, `wikantik-main/src/main/java/com/wikantik/mentions/MentionScanner.java:81-95`
- Modify: `wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java:52`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/NodeTextAssembler.java:60`
- Modify: `wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java:332-333`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java:217-222` (+ new static method)
- Modify: `wikantik-main/src/main/java/com/wikantik/export/ObsidianPageConverter.java:77-82` (+ `collectWikiLinkEdits`)
- Test: `MentionScannerTest`, `PageExcerptsTest`, `NodeTextAssemblerTest`, `WikiToMarkdownConverterTest` (line 89-90), `WikiPageFormatFilterTest`, `ObsidianPageConverterTest` (all existing; paths in the File Structure notes below)

**Interfaces:**
- Consumes: `WikiLinkSyntax.TOKEN/findAll/replaceAll/toPlainText` (Task 1), `WikiLinkResolver` (Task 2), `HeadingSlugs.slug`, `ObsidianLinkRenderer.linkTarget( page, state )` (package-private, same package), `ExportLinkContext.attachmentTarget( page, file )`.
- Produces:
  ```java
  // WikiPageFormatFilter
  static String rewriteWikiLinks( String body, String baseUrl, String currentPage,
                                  UnaryOperator< String > resolvePage, Predicate< String > isCurrentPageAttachment );
  ```

Changes, each with its failing test first:

1. **Mentions.** `MentionMasking.mask` also blanks every `WikiLinkSyntax.TOKEN` match. `MentionScanner.scan`: `INELIGIBLE` gains `WikiLink.class, WikiImage.class`; after building `linked`, add the lower-cased slug of every `TitleEntry` that has a phrase (whitespace-folded, case-insensitive) equal to a native page target in the text. Test (`MentionScannerTest`): text `"Read [[k8s platform]] about Kubernetes."` with entry `Kubernetes` (phrases `Kubernetes`, `K8s Platform`) → no mention of `Kubernetes`; and `"[[Kubernetes]]"` alone yields no mention inside the brackets.
2. **Excerpts.** `PageExcerpts.excerpt`: apply `WikiLinkSyntax.toPlainText` to the masked body before parsing. Test: `excerpt( "See [[Target|the target]], [[T#H]] ![[Embedded]] done.", 280 )` → `"See the target, T > H done."`.
3. **stripMarkdown.** `NodeTextAssembler.stripMarkdown`: `s = WikiLinkSyntax.toPlainText( s );` immediately after the `CODE_FENCE` line (60). Test: `stripMarkdown( "a [[T|Alias]] b ![[X]] c [[T#H]]" )` → `"a Alias b c T > H"`.
4. **Legacy converter.** Restore escaped brackets as `\[` (line 333: `result.replace( ESCAPED_BRACKET, "\\[" )`). Update `WikiToMarkdownConverterTest:90` to expect `"\\[Literal]"`, and add `assertFalse( convert( "[[[[x]" ).contains( "[[" ) )`.
5. **`?format=md`.** In `writeMarkdown` (line 221) call `rewriteWikiLinks( body, baseUrl, page.getName(), resolver, attachmentPredicate )` **before** `rewriteInternalLinks`, where `resolver = t -> WikiLinkResolver.forEngine( engine ).resolve( t ).pageName()` and the predicate checks `attachments().getAttachmentInfo( page.getName() + "/" + f ) != null` (wrap provider exceptions: `LOG.warn` + `false`). Output rules (base = baseUrl without trailing `/`; names percent-encoded with `URLEncoder.encode( n, UTF_8 ).replace( "+", "%20" )`, attachment paths per segment):
   - `[[T|A]]` → `[A](base/wiki/T)`; `[[T]]` → `[T](base/wiki/T)`; `[[T#H]]` → `[T > H](base/wiki/T#h-slug)`; `[[#H]]` → `[H](base/wiki/Current#h-slug)`;
   - page embed `![[T#H]]` → `[Embedded: T > H](base/wiki/T#h-slug)` (not expanded);
   - attachment link `[[O/f.pdf]]` → `[O/f.pdf](base/attach/O/f.pdf)`; attachment embed `![[O/f.png|300]]` or current-page `![[f.png]]` → `![](base/attach/O/f.png)`.
   Test (`WikiPageFormatFilterTest`, pure static call): input `"[[Target|A]] [[Target#My H]] [[#Intro]] ![[Target]] ![[Owner/p.png|300]] ![[local.png]] `[[Code]]`"` with resolver `String::toString`, predicate `"local.png"::equals`, current page `Cur`, base `https://w.example/` → `"[A](https://w.example/wiki/Target) [Target > My H](https://w.example/wiki/Target#my-h) [Intro](https://w.example/wiki/Cur#intro) [Embedded: Target](https://w.example/wiki/Target) ![](https://w.example/attach/Owner/p.png) ![](https://w.example/attach/Cur/local.png) `[[Code]]`"`; and `rewriteInternalLinks` applied afterwards leaves it unchanged.
6. **Obsidian export.** `ObsidianPageConverter.convert`: after `collectLinkEdits`, `collectWikiLinkEdits( body, edits, state )` — for each `WikiLinkSyntax.findAll( body )` ref not overlapping an existing edit: page ref → splice `ObsidianLinkRenderer.linkTarget( ref.target(), state )` over `[nameFrom, nameTo)`; attachment ref (`O/f`, or a no-slash target for which `state.ctx.attachmentTarget( state.currentPage, target )` is present) → `state.attachments.add( new AttachmentRef( owner, file ) )` and replace the whole target span (`nameFrom` to the end of the target) with the vault attachment target; same-page refs untouched; `!` and `|alias` kept. Test (`ObsidianPageConverterTest`, reuse its `ExportLinkContext` stub): a page whose body is `"[[Hub Page|h]] ![[Hub Page#Usage]] ![[Hub Page/pic.png|300]]"` with vault basename `Hub Page~2` and attachment target `pic.png` → `"[[Hub Page~2|h]] ![[Hub Page~2#Usage]] ![[pic.png|300]]"` and the attachment is listed in `ConvertedPage.attachments()`.

Test file paths: `wikantik-main/src/test/java/com/wikantik/mentions/MentionScannerTest.java`, `…/preview/PageExcerptsTest.java`, `…/knowledge/NodeTextAssemblerTest.java`, `…/content/WikiToMarkdownConverterTest.java`, `…/export/ObsidianPageConverterTest.java`, `wikantik-rest/src/test/java/com/wikantik/rest/WikiPageFormatFilterTest.java`.

- [ ] **Step 1: Write the six failing tests above.**
- [ ] **Step 2: Run** `mvn test -pl wikantik-rest -am -Dtest='MentionScannerTest,PageExcerptsTest,NodeTextAssemblerTest,WikiToMarkdownConverterTest,ObsidianPageConverterTest,WikiPageFormatFilterTest' -Dsurefire.failIfNoSpecifiedTests=false` → the new assertions fail.
- [ ] **Step 3: Implement** the six changes.
- [ ] **Step 4: Run** the Step 2 command → PASS; `mvn pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest`.
- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/mentions/MentionMasking.java \
  wikantik-main/src/main/java/com/wikantik/mentions/MentionScanner.java \
  wikantik-main/src/main/java/com/wikantik/preview/PageExcerpts.java \
  wikantik-main/src/main/java/com/wikantik/knowledge/NodeTextAssembler.java \
  wikantik-main/src/main/java/com/wikantik/content/WikiToMarkdownConverter.java \
  wikantik-main/src/main/java/com/wikantik/export/ObsidianPageConverter.java \
  wikantik-rest/src/main/java/com/wikantik/rest/WikiPageFormatFilter.java \
  wikantik-main/src/test/java/com/wikantik/mentions/MentionScannerTest.java \
  wikantik-main/src/test/java/com/wikantik/preview/PageExcerptsTest.java \
  wikantik-main/src/test/java/com/wikantik/knowledge/NodeTextAssemblerTest.java \
  wikantik-main/src/test/java/com/wikantik/content/WikiToMarkdownConverterTest.java \
  wikantik-main/src/test/java/com/wikantik/export/ObsidianPageConverterTest.java \
  wikantik-rest/src/test/java/com/wikantik/rest/WikiPageFormatFilterTest.java
git commit -m "feat(wikilinks): mentions, excerpts, ?format=md, Obsidian export and converter understand [[ ]]"
```

---

### Task 9: Frontend — syntax, `remarkWikiLinks`, resolution hook, parity

**Files:**
- Create: `wikantik-frontend/src/utils/wikiLinkSyntax.js`, `wikantik-frontend/src/utils/wikiLinkSyntax.test.js`
- Create: `wikantik-frontend/src/utils/remarkWikiLinks.js`, `wikantik-frontend/src/utils/remarkWikiLinks.test.jsx`
- Create: `wikantik-frontend/src/hooks/useWikiLinkResolution.js`, `wikantik-frontend/src/hooks/useWikiLinkResolution.test.js`
- Modify: `wikantik-frontend/src/api/client.js:82-86,121-127` (`getPageEmbed`, `listPages` `resolve`)

**Interfaces:**
- Consumes: `slugify` (`utils/headings.js`), fixture `utils/__fixtures__/wikilinks.json` (Task 3), `GET /api/pages?names=&resolve=true` and `/api/pages/{name}/embed` (Task 6).
- Produces (Tasks 10-11):
  ```js
  // utils/wikiLinkSyntax.js — same rules as WikiLinkSyntax.java (Task 1)
  export const WIKILINK_TOKEN;                    // /(!?)\[\[([^[\]\n]+?)\]\]/g
  export function parseWikiLink(token);          // {embed,target,heading,alias,size,isSamePage,isAttachment,pageName,fileName} | null
  export function findWikiLinks(text);           // [{from,to,raw,...parsed}] — no code masking (callers pass prose)
  export function wikiLinkDisplayText(ref);
  export function wikiLinkHref(ref, resolvedName); // '#slug' | encodeURIComponent(name) + ('#'+slug)?
  export function collectNativeWikiLinkTargets(md); // distinct raw page targets outside code (remark-parsed), sorted
  export const isImageFileName;                  // /\.(png|jpe?g|gif|svg|webp|bmp|avif)$/i
  // utils/remarkWikiLinks.js
  export function remarkWikiLinks({ resolved = new Map(), attachments = [], pageName = '' } = {});
  // hooks/useWikiLinkResolution.js
  export function useWikiLinkResolution(markdown); // Map<lowercased raw target, string|null>
  // api/client.js
  api.getPageEmbed(name, { section, signal } = {});   // GET /api/pages/{name}/embed
  api.listPages({ prefix, q, names, resolve, limit, offset }); // resolve → &resolve=true
  ```

`remarkWikiLinks` behaviour (mdast; visit `text` nodes not under a `link`/`linkReference`):
- split each text node around `findWikiLinks(node.value)` matches; skip a match when the plugin's source (`String(file)`) is available and the node's source slice, with `\|` normalised to `|`, does not contain the raw token verbatim (the token came from backslash escapes);
- page/same-page link → `{ type: 'link', url: wikiLinkHref(ref, resolvedName), children: [{ type: 'text', value: displayText }] }`; `resolvedName = resolved.get(target.toLowerCase())`: string → use it; `null` → keep raw target and set `data.hProperties = { className: ['createpage'], 'data-missing-page': target, title: \`${target} does not exist yet\` }`; undefined → raw target;
- attachment: owner/file from `O/f`, or current page when the target has no `/` and `attachments.some(a => a.fileName === target)`; embed of an image file → `{ type: 'image', url: \`/attach/${owner}/${file}\`, alt: file, data: { hProperties: { width, height } } }` (from `size`); otherwise a link to `/attach/${owner}/${file}`;
- page embed: if the paragraph holds only page embeds + whitespace/`break` nodes, replace the paragraph with one `{ type: 'wikiEmbed', data: { hName: 'wiki-embed', hProperties: { 'data-page': name, 'data-section': heading || '' } }, position: paragraph.position }` per embed (R5); otherwise render it as a page link.

- [ ] **Step 1: Write the failing tests**

`wikiLinkSyntax.test.js` — port every Task 1 `WikiLinkSyntaxTest` case to JS (same inputs, same expected values), plus:

```js
it('builds view hrefs', () => {
  expect(wikiLinkHref(parseWikiLink('[[My Page#Set Up]]'))).toBe('My%20Page#set-up');
  expect(wikiLinkHref(parseWikiLink('[[#Intro Part]]'))).toBe('#intro-part');
  expect(wikiLinkHref(parseWikiLink('[[foo]]'), 'Foo')).toBe('Foo');
});
it('collects page targets outside code only', () => {
  expect(collectNativeWikiLinkTargets('[[B]] `[[C]]` [[A|x]] [[#H]] ![[O/f.png]]\n\n```\n[[D]]\n```')).toEqual(['A', 'B']);
});
```

`remarkWikiLinks.test.jsx` — the shared parity fixture (Review Focus 1 is in it) plus embeds/attachments:

```jsx
import { render } from '@testing-library/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { remarkWikiLinks } from './remarkWikiLinks';
import { collectNativeWikiLinkTargets } from './wikiLinkSyntax';
import cases from './__fixtures__/wikilinks.json';

const resolvedFor = (c) => new Map(collectNativeWikiLinkTargets(c.markdown).map((t) => [
  t.toLowerCase(), c.pages.find((p) => p.toLowerCase() === t.toLowerCase()) ?? null]));
const describeLinks = (el) => [...el.querySelectorAll('a')].map((a) => ({
  href: decodeURIComponent(a.getAttribute('href')),
  class: a.classList.contains('createpage') ? 'createpage' : '',
  text: a.textContent,
}));

it.each(cases.map((c) => [c.name, c]))('matches the shared server fixture: %s', (_n, c) => {
  const { container } = render(
    <ReactMarkdown remarkPlugins={[remarkGfm, [remarkWikiLinks, { resolved: resolvedFor(c), pageName: 'WikilinkParityHost' }]]}>
      {c.markdown}
    </ReactMarkdown>);
  expect(describeLinks(container)).toEqual(c.expected);
});

it('turns an embed-only paragraph into wiki-embed elements and an inline embed into a link', () => {
  const { container } = render(
    <ReactMarkdown remarkPlugins={[[remarkWikiLinks, {}]]}
      components={{ 'wiki-embed': (p) => <section data-testid="embed">{p['data-page']}|{p['data-section']}</section> }}>
      {'![[A]]\n![[B#Usage]]\n\nsee ![[C]] here'}
    </ReactMarkdown>);
  expect([...container.querySelectorAll('[data-testid="embed"]')].map((n) => n.textContent)).toEqual(['A|', 'B|Usage']);
  expect(container.querySelector('a').getAttribute('href')).toBe('C');
});

it('renders attachment embeds as sized images', () => {
  const { container } = render(
    <ReactMarkdown remarkPlugins={[[remarkWikiLinks, { pageName: 'Host', attachments: [{ fileName: 'pic.png' }] }]]}>
      {'![[pic.png|300]] and ![[Owner/doc.pdf]]'}
    </ReactMarkdown>);
  const img = container.querySelector('img');
  expect(img.getAttribute('src')).toBe('/attach/Host/pic.png');
  expect(img.getAttribute('width')).toBe('300');
  expect(container.querySelector('a').getAttribute('href')).toBe('/attach/Owner/doc.pdf');
});

it('leaves backslash-escaped brackets alone', () => {
  const { container } = render(<ReactMarkdown remarkPlugins={[[remarkWikiLinks, {}]]}>{'\\[[NotALink]]'}</ReactMarkdown>);
  expect(container.querySelector('a')).toBeNull();
});
```

`useWikiLinkResolution.test.js` (pattern: `hooks/useMissingPages.test.js` — fake timers + mocked `api`):

```js
it('asks the server to resolve native targets and maps them case-insensitively', async () => {
  api.listPages.mockResolvedValue({ pages: [], resolved: { 'foo bar': 'FooBar', Nope: null } });
  const { result } = renderHook(() => useWikiLinkResolution('[[foo bar]] and [[Nope]] and `[[Code]]`'));
  await act(async () => { vi.advanceTimersByTime(600); });
  expect(api.listPages).toHaveBeenCalledWith({ names: ['Nope', 'foo bar'], resolve: true, limit: 50 });
  expect(result.current.get('foo bar')).toBe('FooBar');
  expect(result.current.get('nope')).toBeNull();
});
```

- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/utils/wikiLinkSyntax.test.js src/utils/remarkWikiLinks.test.jsx src/hooks/useWikiLinkResolution.test.js` → fail (modules missing).

- [ ] **Step 3: Implement.** `parseWikiLink` mirrors the Java `build` line for line (leading-whitespace rejection, first `|` with optional preceding `\`, first `#`, `trimEnd` target, `trim` heading/alias, blank → null, `size` from `/^(\d{1,5})(?:x(\d{1,5}))?$/`). `findWikiLinks` skips a match preceded by `\`. `useWikiLinkResolution` copies `useMissingPages`' shape (500 ms debounce, `known` ref cache keyed by lower-cased target, batches of 50, `cancelled` flag, `console.warn('[wikilink-resolve] …')` on failure) and returns a `Map`. Add to `client.js`:

```js
getPageEmbed: (name, { section, signal } = {}) =>
  request(`/api/pages/${encodeURIComponent(name)}/embed${section ? `?section=${encodeURIComponent(section)}` : ''}`, { signal }),
```
and in `listPages` destructure `resolve` and `if (resolve) params.set('resolve', 'true');`.

- [ ] **Step 4: Run** the Step 2 command → PASS; `npx vitest run` and `npm run lint` → clean.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/wikiLinkSyntax.js wikantik-frontend/src/utils/wikiLinkSyntax.test.js \
  wikantik-frontend/src/utils/remarkWikiLinks.js wikantik-frontend/src/utils/remarkWikiLinks.test.jsx \
  wikantik-frontend/src/hooks/useWikiLinkResolution.js wikantik-frontend/src/hooks/useWikiLinkResolution.test.js \
  wikantik-frontend/src/api/client.js
git commit -m "feat(wikilinks): remarkWikiLinks with server parity fixture and target resolution"
```

---

### Task 10: Frontend — `WikiEmbed` + editor preview wiring + styles

**Files:**
- Create: `wikantik-frontend/src/components/WikiEmbed.jsx`, `wikantik-frontend/src/components/WikiEmbed.test.jsx`
- Modify: `wikantik-frontend/src/components/PageEditor.jsx:21-24` (imports), `:338-339` (hook), `:501` area (cache clear on preview open), `:976-985` (plugins + components)
- Modify: `wikantik-frontend/src/styles/article.css` (append `.article-prose .wiki-embed*` rules next to the callout block at ~503)

**Interfaces:**
- Consumes: `api.getPageEmbed` (Task 9), `remarkWikiLinks`, `useWikiLinkResolution` (Task 9), `slugify`.
- Produces:
  ```js
  export default function WikiEmbed({ page, section });   // section: string | null
  export function clearWikiEmbedCache();
  export function WikiEmbedElement(props);               // adapter: reads props['data-page'], props['data-section']
  ```

`WikiEmbed`: module-level `Map` cache of promises keyed `${page}#${section || ''}` (dedupes concurrent requests, survives re-renders, cleared by `clearWikiEmbedCache`). State machine: `loading` → `ok` (`{html, missing, truncated}`) | `restricted` (`err.status === 403`) | `error` (anything else; `console.warn('[wiki-embed] …', err?.message)`). Markup:

```jsx
<div className="wiki-embed" data-embed={page} data-section={section || undefined}>
  <div className="wiki-embed-title">
    {state === 'restricted' ? label : <a className="wikipage" href={href}>{label}</a>}
  </div>
  <div className="wiki-embed-body">{body}</div>
</div>
```
`label = section ? \`${page} › ${section}\` : page`; `href = \`${BASE}/wiki/${encodeURIComponent(page)}${section ? '#' + slugify(section) : ''}\`` (`BASE` as in `linkInteraction.js:5`). The `ok` body is a **memoized** host — `const host = useMemo(() => <div dangerouslySetInnerHTML={{ __html: html }} />, [html]);` (React 19 re-applies `dangerouslySetInnerHTML` on every re-render otherwise — see `PageView`'s memoized article). Restricted → `<p className="wiki-embed-restricted">You don't have access to this page.</p>`; error → `<p className="wiki-embed-error">Embed could not be loaded</p>`; loading → `<p className="wiki-embed-loading">Loading…</p>`.

- [ ] **Step 1: Write the failing tests** (`vi.mock('../api/client')`, `beforeEach(clearWikiEmbedCache)`):

```jsx
it('renders the server html under a title link', async () => {
  api.getPageEmbed.mockResolvedValue({ html: '<p>Body <strong>x</strong></p>', missing: false, restricted: false, truncated: false });
  const { container, findByText } = render(<WikiEmbed page="Target" section="Usage" />);
  await findByText('x');
  expect(container.querySelector('.wiki-embed-title a').getAttribute('href')).toMatch(/\/wiki\/Target#usage$/);
  expect(container.querySelector('.wiki-embed-title').textContent).toBe('Target › Usage');
});
it('shows the restricted state on 403 with a plain-text title', async () => {
  api.getPageEmbed.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
  const { findByText, container } = render(<WikiEmbed page="Secret" />);
  await findByText("You don't have access to this page.");
  expect(container.querySelector('.wiki-embed-title a')).toBeNull();
});
it('shows the server-provided missing body', async () => {
  api.getPageEmbed.mockResolvedValue({ html: '<p class="wiki-embed-missing">Not created yet</p>', missing: true, restricted: false, truncated: false });
  expect(await render(<WikiEmbed page="Nope" />).findByText('Not created yet')).toBeTruthy();
});
it('shows an error state on other failures', async () => {
  api.getPageEmbed.mockRejectedValue(Object.assign(new Error('boom'), { status: 500 }));
  expect(await render(<WikiEmbed page="X" />).findByText('Embed could not be loaded')).toBeTruthy();
});
it('dedupes identical embeds into one request', async () => {
  api.getPageEmbed.mockResolvedValue({ html: '<p>b</p>', missing: false, restricted: false, truncated: false });
  render(<><WikiEmbed page="T" /><WikiEmbed page="T" /></>);
  await waitFor(() => expect(api.getPageEmbed).toHaveBeenCalledTimes(1));
});
it('keeps the injected html across an unrelated re-render', async () => {
  api.getPageEmbed.mockResolvedValue({ html: '<p id="kept">b</p>', missing: false, restricted: false, truncated: false });
  const { rerender, findByText, container } = render(<WikiEmbed page="T" />);
  await findByText('b');
  container.querySelector('#kept').setAttribute('data-touched', '1');
  rerender(<WikiEmbed page="T" />);
  expect(container.querySelector('#kept').getAttribute('data-touched')).toBe('1');
});
```

- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/components/WikiEmbed.test.jsx` → fail.

- [ ] **Step 3: Implement** `WikiEmbed.jsx`, then wire `PageEditor.jsx`:

```jsx
import { remarkWikiLinks } from '../utils/remarkWikiLinks';
import { useWikiLinkResolution } from '../hooks/useWikiLinkResolution';
import { WikiEmbedElement, clearWikiEmbedCache } from './WikiEmbed';
// next to missingPages (line 339)
const wikiLinkResolution = useWikiLinkResolution(previewContent);
const previewComponents = useMemo(() => ({ 'wiki-embed': WikiEmbedElement }), []);
// after the previewOpen state (line 501): re-fetch embeds whenever the preview is (re)opened
useEffect(() => { if (previewOpen) clearWikiEmbedCache(); }, [previewOpen]);
// ReactMarkdown (976-985): insert before remarkMissingLinks, add components
[remarkWikiLinks, { resolved: wikiLinkResolution, attachments: attachments.list, pageName: name }],
// ... <ReactMarkdown components={previewComponents} remarkPlugins={[…]} rehypePlugins={[…]}>
```
Place the hook calls where the existing hooks are declared (hooks must run unconditionally, before any early return). CSS (both themes use existing tokens; check `styles/globals.css` for the border/surface variables the callouts use and reuse them — do not invent new `var(--…)` names):

```css
.article-prose .wiki-embed { border-left: 3px solid var(--color-border); padding: var(--space-sm) var(--space-md); margin: var(--space-md) 0; }
.article-prose .wiki-embed-title { font-size: 0.85em; opacity: 0.8; margin-bottom: var(--space-xs); }
.article-prose .wiki-embed-restricted, .article-prose .wiki-embed-missing,
.article-prose .wiki-embed-error, .article-prose .wiki-embed-loading { font-style: italic; opacity: 0.75; }
```

- [ ] **Step 4: Run** `npx vitest run` (whole suite — PageEditor tests must still pass) and `npm run lint` → clean.

- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/components/WikiEmbed.jsx wikantik-frontend/src/components/WikiEmbed.test.jsx \
  wikantik-frontend/src/components/PageEditor.jsx wikantik-frontend/src/styles/article.css
git commit -m "feat(wikilinks): live embeds and native links in the editor preview"
```

---

### Task 11: Frontend — `[[` completion inserts native syntax; Ctrl-hover/click on `[[ ]]`

**Files:**
- Modify: `wikantik-frontend/src/utils/wikiLinkComplete.js:1-7` (header comment), `:86-97` (`completeWiki`)
- Modify: `wikantik-frontend/src/utils/linkInteraction.js:8-17` (`linkAt`), `:35-47` (`linkRanges`)
- Test: `wikantik-frontend/src/utils/wikiLinkComplete.test.js` (lines 38-44, 72, 85, 154 change), `wikantik-frontend/src/utils/linkInteraction.test.js`

**Interfaces:**
- Consumes: `findWikiLinks`, `wikiLinkHref` (Task 9).
- Produces: `linkAt(state, pos)` keeps its `{ url, from, to } | null` contract; for a wikilink `url` is `wikiLinkHref(ref)` (relative, e.g. `Index%20Funds%20Hub`, `Page#set-up`, `#setup`, `Owner/f.pdf`).

Completion rules: page → `[[Name]]`; new page → `[[${text}]]` (the typed text, trimmed — the new-page option keeps its `Link to new page: ${slug}` label); heading on another page → `[[${page}#${h.text}]]`; heading on the current page (`[[#frag`) → `[[#${h.text}]]`. `escapeLinkText` is no longer applied on the `[[` path (brackets cannot occur in a heading id path; strip `[`, `]` and `|` from heading text instead). `](` target completion, Mod-K and paste/drop are unchanged. A preceding `!` is outside `match.from`, so `![[` stays an embed.

`linkAt`: before the Lezer walk, scan `state.doc.lineAt(pos)` with `findWikiLinks(line.text)`; if a match covers `pos` and `syntaxTree(state).resolveInner(pos, 1)` has no `InlineCode`/`FencedCode`/`CodeBlock`/`CodeText` ancestor, return `{ url: wikiLinkHref(ref), from: line.from + m.from, to: line.from + m.to }`. This must run first: Lezer parses `[Page]` inside `[[Page]]` as a URL-less `Link`, which would otherwise return `null`. `linkRanges.build` also marks every wikilink in the visible lines (same code-ancestor check) with `linkMark`; sort ranges by `from` before `Decoration.set(ranges, true)`.

- [ ] **Step 1: Write the failing tests**

`wikiLinkComplete.test.js` — change the existing expectations and add the embed/same-page cases:

```js
expect(res.options[0]).toMatchObject({ label: 'MachineLearning', apply: '[[MachineLearning]]' });   // line 44
expect(last.apply).toBe('[[retirement planning]]');                                                // line 72
expect(res.options[0]).toMatchObject({ label: 'Install Steps', apply: '[[MachineLearning#Install Steps]]' }); // line 85
expect(res.options[0].apply).toBe('[[Page#Arrays and ab]]');                                       // line 154 case: strip [ ] | \
it('[[# completes headings of the page being edited as [[#Heading]]', async () => {
  const d = deps({ getHeadings: vi.fn(async () => [{ text: 'Setup', id: 'setup' }]) });
  const res = await createWikiLinkSource(d)(ctx('[[#se'));
  expect(res.options[0].apply).toBe('[[#Setup]]');
});
it('keeps the ! of an embed outside the replaced range', async () => {
  const res = await createWikiLinkSource(deps())(ctx('![[Mach'));
  expect(res.from).toBe(1);
  expect(res.options[0].apply).toBe('[[MachineLearning]]');
});
```
(Read the existing `ctx`/`deps` helpers at the top of the test file and the exact line-154 input before editing; adjust the expected heading text to what stripping `[`, `]`, `|`, `\` from that input produces.)

`linkInteraction.test.js`:

```js
describe('linkAt on native wikilinks', () => {
  it('finds [[ ]] and ![[ ]] with the same relative url scheme', () => {
    expect(at('see [[Index Funds Hub|hub]] now', 8)).toEqual({ url: 'Index%20Funds%20Hub', from: 4, to: 27 });
    expect(at('x ![[Page#Set Up]] y', 6).url).toBe('Page#set-up');
    expect(at('go [[#Setup]]', 6).url).toBe('#setup');
  });
  it('ignores wikilinks inside inline code and prose with a leading space', () => {
    expect(at('`[[Page]]` x', 4)).toBeNull();
    expect(at('if [[ -f x ]]; then', 6)).toBeNull();
  });
  it('maps wikilink urls into the app', () => {
    expect(hrefFor('Index%20Funds%20Hub')).toBe('/wiki/Index%20Funds%20Hub');
  });
});
```

- [ ] **Step 2: Run** `cd wikantik-frontend && npx vitest run src/utils/wikiLinkComplete.test.js src/utils/linkInteraction.test.js` → fail.
- [ ] **Step 3: Implement** as described (update the header comment of `wikiLinkComplete.js` to say `[[` inserts native `[[Name]]`).
- [ ] **Step 4: Run** `npx vitest run` and `npm run lint` → clean.
- [ ] **Step 5: Commit**

```bash
git add wikantik-frontend/src/utils/wikiLinkComplete.js wikantik-frontend/src/utils/wikiLinkComplete.test.js \
  wikantik-frontend/src/utils/linkInteraction.js wikantik-frontend/src/utils/linkInteraction.test.js
git commit -m "feat(wikilinks): [[ completion inserts native syntax; Ctrl-hover/click on [[ ]]"
```

---

### Task 12: Integration test, docs, CHANGELOG, final gate

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WikiLinksIT.java`
- Modify: `CHANGELOG.md:7` (`## [Unreleased]`), `CLAUDE.md:405` (`/api/*` row text only — no count change)

**Interfaces:**
- Consumes: everything above over HTTP: `PUT /api/pages/{name}` (`{"content", "metadata"}`), `GET /api/pages?names=&resolve=true`, `GET /api/backlinks/{name}`, `GET /api/pages/{name}?render=true` (`contentHtml`), `GET /wiki/{name}?format=md`, `GET /api/pages/{name}/embed`, `POST /api/pages/{name}/rename` (`{"newName"}`), `DELETE /api/pages/{name}`, `POST /api/auth/login|logout`.

`WikiLinksIT` — copy `ExportIT`'s class scaffolding verbatim (lines 60-260: `baseUrl` system property, `HttpClient` with `secureCookieOverHttp()`, `getString/put/post/delete`, `loginAsAdmin()` = janne / `myP@5sw0rd`, `logoutAdmin()`), `@TestMethodOrder( OrderAnnotation )`, a random 6-char suffix on every page name, and `@AfterAll` best-effort cleanup that logs failures to `System.err`. Add a `pollUntil( String what, int seconds, Supplier<HttpResponse<String>> call, Predicate<HttpResponse<String>> ok )` helper (copy the shape of `RestApiIT.pollUntilReady`, line ~341). Pages (suffix `S`): `WlTarget{S}` = frontmatter `aliases: [wlit alias {S}]` + `"## Section A\n\nalpha text\n"`; `WlSecret{S}` = `"[{ALLOW view Admin}]\n\nclassified text\n"`; `WlLinker{S}` = `"See [[wltarget{S}]] and [[wlit alias {S}]].\n\n![[WlTarget{S}#Section A]]\n"`; `WlEmbedsSecret{S}` = `"![[WlSecret{S}]]\n"`.

Tests (in order):
1. `seedPages` — as admin: save `WlTarget{S}` and `WlSecret{S}`; `pollUntil` `/api/pages?names=wltarget{S}&resolve=true` reports `resolved["wltarget{S}"] == "WlTarget{S}"` (the structural index must know the page before the linker's references are computed); then save `WlLinker{S}` and `WlEmbedsSecret{S}`.
2. `backlinksIncludeNativeLinks` — `pollUntil` `/api/backlinks/WlTarget{S}` contains `WlLinker{S}`.
3. `renderedPageHasLinksAndEmbed` — `contentHtml` of `WlLinker{S}` contains `href="` + `/wiki/WlTarget{S}"`, `class="wiki-embed"` and `alpha text`.
4. `formatMdEmitsStandardLinks` — `/wiki/WlLinker{S}?format=md` contains `/wiki/WlTarget{S}` and `[Embedded: WlTarget{S} > Section A](` and no `[[`.
5. `embedEndpointHonoursAcl` — as admin `/api/pages/WlSecret{S}/embed` → 200 and `html` contains `classified text`; after logout → 403.
6. `restrictedEmbedIsNotSharedBetweenViewers` (Review Focus 4) — anonymous `GET /api/pages/WlEmbedsSecret{S}?render=true` → `contentHtml` contains `wiki-embed-restricted` and not `classified`; login as admin → contains `classified text`; logout → anonymous again → not `classified`. (If anonymous lacks view on ordinary pages in the IT seed, use `loginAsNonAdmin()` — Alice — as the unprivileged viewer.)
7. `renameRewritesNativeLinks` (Review Focus 5) — as admin `POST /api/pages/WlTarget{S}/rename` `{"newName":"WlRenamed{S}"}` → 200; `GET /api/pages/WlLinker{S}` `content` contains `[[WlRenamed{S}]]` and `![[WlRenamed{S}#Section A]]` and still contains `[[wlit alias {S}]]`.

- [ ] **Step 1: Write `WikiLinksIT`** (it fails until the WAR contains Tasks 1-11 — run it once against a stale build only if you want to see it red; otherwise go straight to Step 2).
- [ ] **Step 2: Run the REST IT module**

```bash
bin/agent-build.sh start unit -- mvn clean install -DskipITs -T 1C
bin/agent-build.sh wait unit 540    # repeat until SUCCESS/FAILED; never end the turn while RUNNING
bin/agent-build.sh start itrest -- bin/run-tests.sh --module rest
bin/agent-build.sh wait itrest 540  # repeat until SUCCESS/FAILED
```
Expected: `WikiLinksIT` 7/7 pass; no other REST IT regresses.

- [ ] **Step 3: Docs**
  - `CLAUDE.md:405`: in the `/api/*` row add `` `GET /api/pages/{name}/embed` = rendered `![[ ]]` embed body (sub-path of `PageResource`, view-gated) `` next to the `/preview` mention, and `` `names=` accepts `resolve=true` `` — counts stay **35 distinct servlets (37 url-patterns)**.
  - `CHANGELOG.md` under `## [Unreleased]`:
    ```markdown
    ### Added
    - Native Obsidian-style wikilinks and embeds: `[[Page]]`, `[[Page|Alias]]`, `[[Page#Heading]]`, `[[#Heading]]`,
      `![[Page]]`, `![[Page#Heading]]`, `![[Owner/file.png|300]]` render in pages and the editor preview and are
      understood by the Page Graph, backlinks, rename, unlinked mentions, `?format=md`, excerpts and Obsidian export.
      `[[` targets resolve case-insensitively and by title/alias. The `[[` completion now inserts native syntax.
    - `GET /api/pages/{name}/embed` (rendered embed body, view-gated) and `GET /api/pages?names=…&resolve=true`.
    - Config `wikantik.embed.maxChars` (20000).

    ### Fixed
    - `RenderingManager.textToHTML` no longer writes viewer-sensitive renders (ACL-aware plugins, embeds) to the
      shared HTML cache.
    - The legacy-syntax converter emits `\[` for JSPWiki `[[` escapes so its output can never form a wikilink.
    ```
- [ ] **Step 4: Final gate**

```bash
bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4
bin/agent-build.sh wait gate 540     # repeat until SUCCESS/FAILED
mvn pmd:check -Pcomplexity-gate
cd wikantik-frontend && npx vitest run && npm run lint
git diff --stat build-support/pmd-complexity-baseline.properties   # must be empty
```
Expected: all green. A red gate is fixed in this task, never deferred as pre-existing.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/WikiLinksIT.java CHANGELOG.md CLAUDE.md
git commit -m "test(wikilinks): REST IT for native links, embeds, ACL and rename; changelog"
```
