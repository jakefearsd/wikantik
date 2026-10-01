# Obsidian Vault Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upload a zipped Obsidian vault, review a dry-run plan, and import it as wiki pages, hubs and attachments in a background job that reports a result for every page.

**Architecture:** A new pure package `com.wikantik.importer` (wikantik-main) holds the zip reader (`VaultArchiveReader`), the planner (`VaultImportPlanner` plus small collaborators for names, frontmatter, clusters and body rewriting), and the job runtime (`VaultImportJob`, `ImportJobRegistry`). Engine access sits behind three narrow ports (`WikiSnapshot`, `ImportPageSink`, `AttachmentGate`). `ObsidianImportResource` (wikantik-rest) is a thin multipart/JSON shell that owns the in-memory job registry. The React `ImportDialog` drives plan → apply → poll → result.

**Tech Stack:** Java 25, `java.util.zip` (no new dependency), SnakeYAML via `FrontmatterParser`, Gson records, JUnit 5 + Mockito + `TestEngine`, React 19 + vitest + happy-dom, Cargo IT (wikantik-it-test-rest).

**Spec:** `docs/superpowers/specs/2026-10-01-obsidian-vault-import-design.md` (binding). Dependency: `docs/superpowers/specs/2026-10-01-native-wikilinks-design.md` (already on main: `[[ ]]` is stored natively and resolved by `WikiLinkResolver`; an attachment target is written `Owner/file`).

## Global Constraints

- TDD: every task writes a failing test first, runs it red, then implements.
- Never swallow an exception: every `catch` logs at least `LOG.warn( "<context>: {}", e.getMessage(), e )` or rethrows.
- Stage files by name (`git add <paths>`). Never `git add -A`. Work on `main`.
- Every commit message ends with exactly these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg
  ```
- New config keys are declared in `wikantik-main/src/main/resources/ini/wikantik.properties` under a `# [Import]` section with description, `Type:` and explicit default. The reader uses `PROP_*`/`DEFAULT_*` constants with the same default (the `ExportService` pattern). Then run `bin/config-reference.sh --write`. `ConfigSurfaceDriftTest` and `ConfigReferenceRegressionTest` must pass.
- `mvn pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest` passes with **no additions** to `build-support/pmd-complexity-baseline.properties`. Limits: method cyclomatic 15, cognitive 20, NPath 200, method NCSS 60, ≤ 7 parameters, no GodClass, class cyclomatic 80. Keep methods at about 25 lines or fewer. The planner and rewriter are already split into small classes below; keep them that way.
- No `WikiEngine#getManager` in production code (DecompositionArchTest R-2/R-4). Use `WikiSubsystems` (`getSubsystems()` in REST).
- **No DB migration.** All import state is in memory (`ImportJobRegistry`). No `bin/db/migrations/` file is added.
- Frontend: `npx vitest run <file>` green and `npm run lint` clean (0 errors, 0 warnings) in `wikantik-frontend`.
- CLAUDE.md `/api/*` row: servlet and url-pattern counts re-derived from web.xml (Task 12). CHANGELOG `[Unreleased]` entry in Task 12.
- Use `grep -a` (the shell `grep` is ugrep and silently skips some files).
- Limits (spec §3): `wikantik.import.maxUploadBytes`=104857600, `wikantik.import.maxUncompressedBytes`=524288000, `wikantik.import.maxEntries`=20000, `wikantik.import.maxPages`=2000, `wikantik.import.maxConcurrent`=1. Exceeding any limit → HTTP 413 with a message naming the limit key.
- Status codes (spec §7): 400 malformed zip / unsafe entry (message names the entry), 413 limit, 415 not multipart, 409 hash mismatch or the user already has a running job, 429 global cap. All sent with `RestServletBase.sendError`.

## Rulings (closest faithful reading where code and spec diverge)

- **Ruling: existing-page lookup is a snapshot, not a `WikiLinkResolver` call.** The native-wikilinks spec does not pin a step-limited resolver API, and a full resolve would also apply plural and alias steps that §5.1 excludes. `EngineWikiSnapshot` takes `PageManager.getAllPages()` once per plan and applies the resolver's step 1 (exact) and step 2 (case-insensitive, lexicographically lowest wins). This is the same semantics and is deterministic for the plan hash.
- **Ruling (controller, supersedes the planner's central-directory parser):** the zip is read with `ZipInputStream(…, UTF_8)` only; symlink entries are ordinary entries (nothing is written to disk); the 100:1 ratio uses a byte-counting stream, never header sizes; non-UTF-8 names are rejected. See Task 2.
- **Ruling: non-UTF-8 (e.g. CP437) entry names are rejected with a 400, not decoded.** The message tells the user to re-zip with a UTF-8-aware tool. Pure-ASCII CP437 names are valid UTF-8 and pass. Backslash names are rejected (spec §4).
- **Ruling: the job registry lives in `ObsidianImportResource`** (one servlet instance per webapp, like `ExportResource`'s semaphore). It is closed in `Servlet.destroy()`, which runs at webapp/engine shutdown and deletes temp zips. This avoids new engine-manager wiring.
- **Ruling: a vault note's own `type: hub` is downgraded to `article` (with a warning)** unless the planner chose that note as its cluster's hub. Otherwise the import could create a duplicate cluster declaration or a headless hub.
- **Ruling: generated hub names also avoid existing wiki page names** (`Projects Hub` exists → `Projects Hub 2`). A generated page has no prior identity to "skip".
- **Ruling: an empty legal name becomes `Untitled`** (then normal collision rules). Attachment file names have `# | [ ] ^ \ /` and control characters replaced by `-` so `![[Owner/file]]` parses. Two different files assigned to one owner with the same name get ` 2`, ` 3` before the extension.
- **Ruling: an over-size note (> `wikantik.api.maxPageBytes`) is not buffered.** It is planned as `WILL_FAIL` with the limit named. The whole archive is not rejected.
- **Ruling: attachment upload policy is applied with no admin exemption.** The plan must be identical for every user who uploads the same vault, and a pure planner cannot see the session.
- **Ruling: `/api/auth/user` gains `canCreatePages`** (silent `isPermitted` check, so it never writes access.denied audit rows). The SPA has no other createPages signal, and spec §2 requires showing the menu item only to users with createPages.
- **Ruling: the IT builds its own compact vault in code.** The IT module's test resources are redirected to wikantik-selenide-tests, so it cannot see wikantik-main's fixture. The shared fixture under `wikantik-main/src/test/resources` is reused by the planner, service and REST tests (REST tests reach it through the wikantik-main test-jar).
- **Ruling: a plan-time `WILL_FAIL` page is not attempted.** The job reports it as `FAILED` with the plan reason. `SKIPPED_RESERVED` appears in job results as its own status.

## Review Focus

1. **Windows-zipped vault** (backslash or CP437 entry names): rejected with a 400 naming the entry. Nothing is read outside the vault model. Pinned in Task 2 (`backslashNameRejected`, `cp437NameRejected`).
2. **Notes named `Main`, `LeftMenu` or other system pages**: `SKIPPED_RESERVED` and reported. Links to them are left verbatim. Pinned in Task 7 (`systemPageNotesAreSkippedReserved`).
3. **`Ideas.md` and `ideas.md` in the same folder**: both import (`Ideas`, `ideas (Projects)`), and `[[ideas]]` resolves deterministically. Pinned in Task 3 (`caseVariantsInSameFolder`) and Task 3 (`ambiguousBasenameResolvesDeterministically`).
4. **Another user creates a planned page before apply**: 409 at apply (hash changed). If the page appears mid-job, it is skipped and never overwritten. Pinned in Task 7 (`hashChangesWhenAPlannedNameStartsExisting`), Task 8 (`pageCreatedDuringJobIsSkippedNotOverwritten`) and Task 9 (`applyWithStaleHashIs409`).
5. **100 MB vault where 95 MB is unreferenced images**: the plan buffers only notes. Unreferenced files are `SKIPPED_UNREFERENCED` and never read by the job. Pinned in Task 2 (`nonNoteEntriesAreNotBuffered`) and Task 8 (`unreferencedFilesAreNeverRead`).

---

## File Structure

New production files (package `com.wikantik.importer`, dir `wikantik-main/src/main/java/com/wikantik/importer/`):

| File | Responsibility |
|---|---|
| `ImportLimits.java` | The six limit values + `fromProperties` |
| `ImportLimitException.java`, `VaultArchiveException.java` | 413 / 400 failures |
| `SpooledUpload.java` | Upload → temp file with cap + sha256 |
| `CountingInputStream.java` | byte counter under the ZipInputStream for the ratio guard (or commons-io's if already a dependency) |
| `VaultArchiveReader.java`, `VaultArchive.java`, `VaultNote.java`, `VaultFile.java`, `VaultPaths.java` | Safe streaming read into the vault model |
| `VaultNames.java`, `VaultNoteNamer.java`, `WikiSnapshot.java`, `VaultLinkIndex.java` | Names, collisions, Obsidian link resolution |
| `CodeSegments.java`, `InlineTags.java`, `VaultFrontmatterMapper.java`, `NoteContext.java`, `MappedNote.java` | Frontmatter rules |
| `LinkTarget.java`, `VaultTargets.java`, `VaultBodyRewriter.java`, `RewriteResult.java` | Body rewriting |
| `ImportOptions.java`, `ClusterMode.java`, `VaultClusterPlanner.java`, `PlannedCluster.java`, `ClusterAction.java` | Cluster planning |
| `PageStatus.java`, `PlannedPage.java`, `AttachmentStatus.java`, `PlannedAttachment.java`, `PlanTotals.java`, `ImportPlan.java`, `PageDraft.java`, `PlanResult.java`, `AttachmentGate.java`, `PlanTargets.java`, `PlanRun.java`, `VaultImportPlanner.java`, `PlanHasher.java` | Plan orchestration |
| `ImportPageSink.java`, `ImportSaveException.java`, `EngineImportPageSink.java`, `EngineWikiSnapshot.java`, `WikiSnapshotSource.java`, `JobState.java`, `ItemStatus.java`, `ItemResult.java`, `JobView.java`, `VaultImportJob.java`, `ImportJobRegistry.java`, `ImportJobConflictException.java`, `VaultImportService.java` | Apply + jobs |

REST: `wikantik-rest/src/main/java/com/wikantik/rest/ObsidianImportResource.java`. Frontend: `wikantik-frontend/src/components/ImportDialog.jsx`. IT: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ObsidianImportIT.java`.

---

### Task 1: Limits, failures, upload spooling, config keys

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/importer/{ImportLimits,ImportLimitException,VaultArchiveException,SpooledUpload}.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` (append a `# [Import]` section after the `# [Export]` block, before `### End of configuration file.` at ~line 2611)
- Regenerate: `docs/ConfigurationReference.md` and the wiki page via `bin/config-reference.sh --write`
- Test: `wikantik-main/src/test/java/com/wikantik/importer/ImportLimitsTest.java`, `SpooledUploadTest.java`

**Interfaces (produces):**
```java
public record ImportLimits( long maxUploadBytes, long maxUncompressedBytes, int maxEntries,
                            int maxPages, int maxConcurrent, int maxPageBytes ) {
    public static final String PROP_MAX_UPLOAD_BYTES = "wikantik.import.maxUploadBytes";      public static final int DEFAULT_MAX_UPLOAD_BYTES = 104857600;
    public static final String PROP_MAX_UNCOMPRESSED_BYTES = "wikantik.import.maxUncompressedBytes"; public static final int DEFAULT_MAX_UNCOMPRESSED_BYTES = 524288000;
    public static final String PROP_MAX_ENTRIES = "wikantik.import.maxEntries";               public static final int DEFAULT_MAX_ENTRIES = 20000;
    public static final String PROP_MAX_PAGES = "wikantik.import.maxPages";                   public static final int DEFAULT_MAX_PAGES = 2000;
    public static final String PROP_MAX_CONCURRENT = "wikantik.import.maxConcurrent";         public static final int DEFAULT_MAX_CONCURRENT = 1;
    public static final String PROP_MAX_PAGE_BYTES = "wikantik.api.maxPageBytes";             public static final int DEFAULT_MAX_PAGE_BYTES = 262144;
    public static ImportLimits fromProperties( Properties props );
    public static ImportLimits defaults();
}
public class ImportLimitException extends Exception { public ImportLimitException( String limitKey, long limit, String what ); public String limitKey(); }
public class VaultArchiveException extends Exception { public VaultArchiveException( String message ); public VaultArchiveException( String message, Throwable cause ); }
public record SpooledUpload( Path file, String sha256, long size, String originalName ) {
    public static SpooledUpload spool( InputStream in, String originalName, long maxBytes ) throws IOException, ImportLimitException;
    public void delete(); // idempotent; LOG.warn on IOException
}
```
`ImportLimitException` message format: `"<what> exceeds " + limitKey + " (" + limit + ")"`, e.g. `"upload exceeds wikantik.import.maxUploadBytes (104857600)"`.

- [ ] **Step 1: Write failing tests**

```java
class ImportLimitsTest {
    @Test void defaultsMatchSpec() {
        final ImportLimits l = ImportLimits.fromProperties( new Properties() );
        assertEquals( 104857600L, l.maxUploadBytes() );
        assertEquals( 524288000L, l.maxUncompressedBytes() );
        assertEquals( 20000, l.maxEntries() );
        assertEquals( 2000, l.maxPages() );
        assertEquals( 1, l.maxConcurrent() );
        assertEquals( 262144, l.maxPageBytes() );
    }
    @Test void propertiesOverride() {
        final Properties p = new Properties();
        p.setProperty( ImportLimits.PROP_MAX_PAGES, "5" );
        p.setProperty( ImportLimits.PROP_MAX_CONCURRENT, "3" );
        assertEquals( 5, ImportLimits.fromProperties( p ).maxPages() );
        assertEquals( 3, ImportLimits.fromProperties( p ).maxConcurrent() );
    }
}
class SpooledUploadTest {
    @Test void spoolsAndHashes() throws Exception {
        final SpooledUpload u = SpooledUpload.spool( new ByteArrayInputStream( "abc".getBytes( UTF_8 ) ), "v.zip", 10 );
        try {
            assertEquals( 3, u.size() );
            assertEquals( "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", u.sha256() );
            assertArrayEquals( "abc".getBytes( UTF_8 ), Files.readAllBytes( u.file() ) );
        } finally { u.delete(); }
        assertFalse( Files.exists( u.file() ) );
    }
    @Test void overCapThrowsAndLeavesNoTempFile() throws Exception {
        final ImportLimitException e = assertThrows( ImportLimitException.class,
            () -> SpooledUpload.spool( new ByteArrayInputStream( new byte[ 11 ] ), "v.zip", 10 ) );
        assertEquals( ImportLimits.PROP_MAX_UPLOAD_BYTES, e.limitKey() );
        assertTrue( e.getMessage().contains( "wikantik.import.maxUploadBytes" ) );
    }
    @Test void deleteIsIdempotent() throws Exception {
        final SpooledUpload u = SpooledUpload.spool( new ByteArrayInputStream( new byte[ 1 ] ), "v.zip", 10 );
        u.delete(); u.delete();
    }
}
```
For `overCapThrowsAndLeavesNoTempFile`, also assert that no `wikantik-import-*` file was left behind. Count matching files in `java.io.tmpdir` before and after.

- [ ] **Step 2: Run red.** `mvn test -pl wikantik-main -Dtest='ImportLimitsTest,SpooledUploadTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement.**
  - `ImportLimits.fromProperties`: read each key with `TextUtil.getIntegerProperty( props, PROP_X, DEFAULT_X )` (all defaults fit in `int`).
  - `SpooledUpload.spool`: create `Files.createTempFile( "wikantik-import-", ".zip" )`. Copy through `DigestInputStream( in, MessageDigest.getInstance( "SHA-256" ) )` with an 8 KiB buffer and a running count. When the count exceeds `maxBytes`, delete the temp file and throw `new ImportLimitException( ImportLimits.PROP_MAX_UPLOAD_BYTES, maxBytes, "upload" )`. On any `IOException`, delete and rethrow. Encode the hash as lowercase hex with `HexFormat.of().formatHex(...)`. Wrap `NoSuchAlgorithmException` in `IllegalStateException`.
  - Properties block (append verbatim, keep the blank lines):
```properties
# [Import]
#
#  Maximum size, in bytes, of an uploaded Obsidian vault zip (POST /api/import/obsidian/plan
#  and /apply). Larger uploads are refused with HTTP 413 naming this key.
#
#  Type: int
wikantik.import.maxUploadBytes = 104857600

#  Maximum total uncompressed size, in bytes, of all entries in an imported vault zip,
#  counted while streaming (header sizes are never trusted). Exceeding it is HTTP 413.
#
#  Type: int
wikantik.import.maxUncompressedBytes = 524288000

#  Maximum number of entries (files and folders) in an imported vault zip. HTTP 413 above it.
#
#  Type: int
wikantik.import.maxEntries = 20000

#  Maximum number of Markdown notes a single vault import may contain. HTTP 413 above it.
#
#  Type: int
wikantik.import.maxPages = 2000

#  Maximum number of vault import jobs that may run at the same time across the wiki.
#  An apply arriving while this many jobs are running is refused with HTTP 429.
#
#  Type: int
wikantik.import.maxConcurrent = 1
```
  `wikantik.api.maxPageBytes` is already declared (~line 1159). Do not redeclare it.

- [ ] **Step 4: Run green.** Run the same command, then `bin/config-reference.sh --write`, then `mvn test -pl wikantik-war -Dtest='ConfigSurfaceDriftTest,ConfigReferenceRegressionTest' -Dsurefire.failIfNoSpecifiedTests=false`. If wikantik-war resolves a stale wikantik-main jar, run `mvn install -DskipTests -pl wikantik-main -q` first. Expected: PASS.

- [ ] **Step 5: Commit.**
```bash
git add wikantik-main/src/main/java/com/wikantik/importer/ImportLimits.java wikantik-main/src/main/java/com/wikantik/importer/ImportLimitException.java wikantik-main/src/main/java/com/wikantik/importer/VaultArchiveException.java wikantik-main/src/main/java/com/wikantik/importer/SpooledUpload.java wikantik-main/src/test/java/com/wikantik/importer/ImportLimitsTest.java wikantik-main/src/test/java/com/wikantik/importer/SpooledUploadTest.java wikantik-main/src/main/resources/ini/wikantik.properties docs/ConfigurationReference.md
git status --short   # also stage the regenerated WikantikConfigurationReference page under docs/wikantik-pages/ by name
git commit -m "feat(import): vault import limits, upload spooling and config keys" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```

---

### Task 2: Safe zip reader (`VaultArchiveReader`)

**Controller ruling (overrides the planner's `ZipCentralDirectory` design):** do NOT hand-write a zip
central-directory parser. Read with `java.util.zip.ZipInputStream(…, UTF_8)` only, so local headers are
the single source of truth (no CD/local-header confusion). Symlink detection is unnecessary: nothing is
ever written to disk, so a "symlink" entry is just a small file whose bytes are its target text — it is
treated as an ordinary entry. Compression ratio is measured from a byte-counting stream under the
`ZipInputStream`, never from header sizes. A malformed (non-UTF-8, e.g. CP437) entry name makes
`ZipInputStream.getNextEntry()` throw `IllegalArgumentException` — map it to the UTF-8 rejection message.

**Files:**
- Create: `VaultArchiveReader.java`, `VaultArchive.java`, `VaultNote.java`, `VaultFile.java`, `VaultPaths.java`, `CountingInputStream.java` (package-private, or reuse commons-io `CountingInputStream` if commons-io is already a compile dependency of wikantik-main — check the pom)
- Create test helper: `wikantik-main/src/test/java/com/wikantik/importer/TestVaults.java` (public, shipped in the test-jar for wikantik-rest)
- Test: `wikantik-main/src/test/java/com/wikantik/importer/VaultArchiveReaderTest.java`

**Interfaces:**
- Consumes: `ImportLimits`, `ImportLimitException`, `VaultArchiveException` (Task 1).
- Produces:
```java
public record VaultNote( String path, String text, long size ) { public boolean oversized() { return text == null; } }   // path like "Projects/Alpha.md"
public record VaultFile( String path, String entryName, long size ) {}   // entryName = raw zip entry name (incl. any stripped wrapper)
public record VaultArchive( List< VaultNote > notes, List< VaultFile > files, int ignoredEntries ) {}
public final class VaultArchiveReader {
    public VaultArchiveReader( ImportLimits limits );
    public VaultArchive read( Path zip ) throws IOException, VaultArchiveException, ImportLimitException;
}
public final class VaultPaths {
    public static final Comparator< String > ORDER;            // String.CASE_INSENSITIVE_ORDER.thenComparing( Comparator.naturalOrder() )
    public static String basename( String path );              // "a/b/C.md" -> "C.md"
    public static String parentFolder( String path );          // "a/b/C.md" -> "a/b"; root -> ""
    public static String withoutMd( String path );             // strips a trailing ".md" (case-insensitive)
    public static boolean isNote( String path );               // endsWith ".md", case-insensitive
}
// test helper
public final class TestVaults {
    public static byte[] zip( Map< String, byte[] > entries );                 // insertion order, DEFLATED
    public static byte[] zipText( Map< String, String > entries );             // UTF-8 convenience
    public static byte[] zipWithRawName( byte[] nameBytes, byte[] data );      // single STORED entry with arbitrary name bytes (no UTF-8 flag)
    public static Path write( byte[] zip ) throws IOException;                 // temp file, caller deletes
    public static SpooledUpload upload( byte[] zip, String name ) throws Exception;
    // fixture() is NOT added here — Task 7 adds `public static Map< String, byte[] > fixture() throws IOException`
}
```

`VaultArchiveReader.read` algorithm (two passes over the file, split into small methods):
1. **Pass 1 (names only):** open `new ZipInputStream( new BufferedInputStream( Files.newInputStream( zip ) ), UTF_8 )`, iterate `getNextEntry()` collecting names (do not read data — `getNextEntry` skips it). Count entries; above `limits.maxEntries()` → `ImportLimitException( ImportLimits.PROP_MAX_ENTRIES, … )`. `IllegalArgumentException` from `getNextEntry` → `VaultArchiveException("zip entry name is not valid UTF-8 (CP437 or another legacy encoding?) — re-zip the vault with a UTF-8 aware tool", e)`. `ZipException` → `LOG.warn` + `VaultArchiveException("malformed zip: " + msg, e)`. Zero entries and the file does not start with `PK` → `VaultArchiveException("not a zip archive")`.
2. Validate every name with `requireSafeName(name)`. Throw `VaultArchiveException("unsafe zip entry '" + name + "': <why>")` when the name is empty, starts with `/`, matches `^[A-Za-z]:`, contains `\` or `\0`, or has any `/`-separated segment equal to `.`, `..` or empty (a trailing `/` on a directory is allowed).
3. Compute `prefix = wrapperPrefix( names )`. Consider only file names (not ending `/`) that do not start with `__MACOSX/` and whose first segment does not start with `.`. If every such name contains `/` and they all share the same first segment `X`, return `X + "/"`, else `""`.
4. Map each raw name to a vault path: `rel = name.startsWith( prefix ) ? name.substring( prefix.length() ) : name`. The entry is **ignored** (counted in `ignoredEntries`) when it is a directory, the raw name starts with `__MACOSX/`, any segment of `rel` starts with `.`, or `rel.equals( "Wikantik Export.md" )`.
5. **Pass 2 (data):** `CountingInputStream raw = new CountingInputStream( Files.newInputStream( zip ) )`; `ZipInputStream zin = new ZipInputStream( raw, UTF_8 )` (no BufferedInputStream between them, so the count tracks consumption closely). For each entry record `rawBefore = raw.count()`, drain through **one** reusable 8 KiB buffer counting `entryBytes`:
   - Add to the cumulative total. Above `maxUncompressedBytes` → `ImportLimitException( PROP_MAX_UNCOMPRESSED_BYTES, ..., "uncompressed vault" )`.
   - When `entryBytes > 1_048_576 && entryBytes > 100L * Math.max( 1, raw.count() - rawBefore )` → `VaultArchiveException("zip entry '" + name + "' expands more than 100:1 (zip bomb?)")`. (Check inside the drain loop so a bomb is stopped early.)
   - Buffer to a `ByteArrayOutputStream` **only** for non-ignored notes, and only while `entryBytes <= maxPageBytes`. Once over, drop the buffer and keep draining to count. Ignored and non-note entries are drained without buffering.
6. Notes become `VaultNote( rel, text-or-null, size )` with `text = new String( bytes, UTF_8 )` (a leading BOM `\uFEFF` is stripped). Non-notes become `VaultFile( rel, rawName, size )`. Sort both by `VaultPaths.ORDER` on path.
7. A `java.util.zip.ZipException` in pass 2 → `LOG.warn` + `VaultArchiveException("malformed zip: " + msg, e)`.

- [ ] **Step 1: Write failing tests** (`VaultArchiveReaderTest`, using `TestVaults` and `@TempDir`). Write each as a real test. Key ones:
```java
private VaultArchive read( final byte[] zip ) throws Exception {
    final Path p = TestVaults.write( zip );
    try { return new VaultArchiveReader( ImportLimits.defaults() ).read( p ); } finally { Files.deleteIfExists( p ); }
}
@Test void readsNotesAndFilesInPathOrder() throws Exception {
    final VaultArchive a = read( TestVaults.zipText( Map.of( "b/Note.md", "# B", "A.md", "# A", "b/img.png", "x" ) ) );
    assertEquals( List.of( "A.md", "b/Note.md" ), a.notes().stream().map( VaultNote::path ).toList() );
    assertEquals( "# A", a.notes().get( 0 ).text() );
    assertEquals( List.of( "b/img.png" ), a.files().stream().map( VaultFile::path ).toList() );
}
@Test void zipSlipRejected() {  // parameterise over "../evil.md", "a/../../evil.md", "/abs.md", "C:/x.md", "a/./b.md", "a//b.md"
    final VaultArchiveException e = assertThrows( VaultArchiveException.class, () -> read( TestVaults.zipText( Map.of( "../evil.md", "x" ) ) ) );
    assertTrue( e.getMessage().contains( "../evil.md" ) );
}
@Test void backslashNameRejected() {   // Review Focus 1
    assertThrows( VaultArchiveException.class, () -> read( TestVaults.zipWithRawName( "Notes\\A.md".getBytes( US_ASCII ), "x".getBytes() ) ) );
}
@Test void cp437NameRejected() {       // Review Focus 1: 0x81 = 'ü' in CP437, invalid as a UTF-8 lead byte
    final VaultArchiveException e = assertThrows( VaultArchiveException.class,
        () -> read( TestVaults.zipWithRawName( new byte[]{ 'M', (byte) 0x81, '.', 'm', 'd' }, "x".getBytes() ) ) );
    assertTrue( e.getMessage().contains( "UTF-8" ) );
}
@Test void nulInNameRejected() { /* raw name bytes "a\0.md" */ }
@Test void bombRatioRejected() {       // 2 MiB of zeros deflates ~1000:1
    final byte[] zip = TestVaults.zip( Map.of( "big.png", new byte[ 2 * 1024 * 1024 ] ) );
    assertTrue( assertThrows( VaultArchiveException.class, () -> read( zip ) ).getMessage().contains( "100:1" ) );
}
@Test void incompressibleLargeFileIsNotABomb() throws Exception {
    final byte[] img = new byte[ 3 * 1024 * 1024 ]; new Random( 1 ).nextBytes( img );
    assertEquals( 1, read( TestVaults.zip( Map.of( "big.png", img ) ) ).files().size() );
}
@Test void uncompressedCapIs413Limit() throws Exception {
    final ImportLimits tiny = new ImportLimits( 1_000_000, 10, 100, 100, 1, 262144 );
    final Path p = TestVaults.write( TestVaults.zipText( Map.of( "A.md", "0123456789ABC" ) ) );
    final ImportLimitException e = assertThrows( ImportLimitException.class, () -> new VaultArchiveReader( tiny ).read( p ) );
    assertEquals( ImportLimits.PROP_MAX_UNCOMPRESSED_BYTES, e.limitKey() );
}
@Test void entryCapIs413Limit() { /* limits maxEntries=2, zip of 3 entries -> PROP_MAX_ENTRIES */ }
@Test void wrapperFolderStripped() throws Exception {
    final VaultArchive a = read( TestVaults.zipText( Map.of( "MyVault/A.md", "a", "MyVault/.obsidian/app.json", "{}", "__MACOSX/MyVault/._A.md", "x" ) ) );
    assertEquals( "A.md", a.notes().get( 0 ).path() );
    assertEquals( 2, a.ignoredEntries() );
}
@Test void ignoredPaths() { /* .obsidian/x, .trash/y.md, .git/HEAD, .DS_Store, sub/.hidden.md, .wikantik/manifest.json,
                               "Wikantik Export.md" all ignored; "sub/Wikantik Export.md" is NOT ignored */ }
@Test void oversizedNoteIsNotBuffered() throws Exception {
    final ImportLimits l = new ImportLimits( 1_000_000, 1_000_000, 100, 100, 1, 4 );
    final Path p = TestVaults.write( TestVaults.zipText( Map.of( "A.md", "123456" ) ) );
    final VaultNote n = new VaultArchiveReader( l ).read( p ).notes().get( 0 );
    assertTrue( n.oversized() ); assertEquals( 6, n.size() );
}
@Test void nonNoteEntriesAreNotBuffered() throws Exception {   // Review Focus 5
    final byte[] img = new byte[ 3 * 1024 * 1024 ]; new Random( 1 ).nextBytes( img );
    final VaultArchive a = read( TestVaults.zip( Map.of( "big.png", img, "A.md", "a".getBytes( UTF_8 ) ) ) );
    assertEquals( img.length, a.files().get( 0 ).size() );   // VaultFile has no byte[] component by construction
}
@Test void notAZipRejected() { /* write "hello" bytes -> VaultArchiveException "not a zip archive" */ }
```
  `TestVaults.zipWithRawName`: write the bytes by hand — one local file header (STORED, CRC via `java.util.zip.CRC32`), the data, one central directory header, then the EOCD; flag bit 11 clear. About 30 lines with a little-endian `ByteBuffer`.

- [ ] **Step 2: Run red.** `mvn test -pl wikantik-main -Dtest=VaultArchiveReaderTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation failure.
- [ ] **Step 3: Implement** as specified above.
- [ ] **Step 4: Run green** (same command) and `mvn pmd:check -Pcomplexity-gate -pl wikantik-main`.
- [ ] **Step 5: Commit** the production files + `TestVaults.java` + `VaultArchiveReaderTest.java` by name. Message: `feat(import): safe streaming vault zip reader` + trailer.

---

### Task 3: Names, collisions and Obsidian link resolution

**Files:**
- Create: `importer/VaultNames.java`, `VaultNoteNamer.java`, `WikiSnapshot.java`, `VaultLinkIndex.java`
- Test: `VaultNamesTest.java`, `VaultNoteNamerTest.java`, `VaultLinkIndexTest.java`

**Interfaces (produces):**
```java
public interface WikiSnapshot {
    Optional< String > existingPage( String name );   // exact, else case-insensitive (lexicographically lowest)
    boolean isSystemPage( String name );
    Optional< String > hubPage( String cluster );     // hub page declaring cluster, if any
    default boolean isClusterDeclared( String cluster ) { return hubPage( cluster ).isPresent(); }
}
public final class VaultNames {
    public static String legalPageName( String raw );     // spec §5.1; "Untitled" if empty
    public static String slug( String folderName );       // "" if nothing survives
    public static String attachmentName( String fileName );
}
public final class VaultNoteNamer {
    public VaultNoteNamer( WikiSnapshot snapshot );
    public Map< String, String > assign( List< String > notePaths );   // vault path -> page name (LinkedHashMap, ORDER)
    public String allocateGenerated( String desired );                 // avoids taken + existing wiki names
}
public final class VaultLinkIndex {
    public VaultLinkIndex( Collection< String > notePaths, Collection< String > filePaths );
    public Optional< String > resolveNote( String target );                          // target without fragment; ".md" optional
    public Optional< String > resolveFile( String target );                          // full file name incl. extension
    public Optional< String > resolveNoteRelative( String target, String fromPath ); // markdown links
    public Optional< String > resolveFileRelative( String target, String fromPath );
}
```

Implementation notes:
```java
public static String legalPageName( final String raw ) {
    final StringBuilder sb = new StringBuilder( raw.length() );
    for ( int i = 0; i < raw.length(); i++ ) {
        final char c = raw.charAt( i );
        sb.append( Character.isLetterOrDigit( c ) || TextUtil.PUNCTUATION_CHARS_ALLOWED.indexOf( c ) >= 0 ? c : ' ' );
    }
    String s = sb.toString().replaceAll( "\\.{2,}", " " ).replaceAll( "\\s+", " " ).trim();
    if ( s.length() > WikiPageNameValidator.MAX_LENGTH ) { s = s.substring( 0, WikiPageNameValidator.MAX_LENGTH ).trim(); }
    return s.isEmpty() ? "Untitled" : s;
}
public static String slug( final String folder ) {
    return folder.toLowerCase( Locale.ROOT ).replaceAll( "[^a-z0-9]+", "-" ).replaceAll( "^-+|-+$", "" );
}
public static String attachmentName( final String f ) {
    return f.replaceAll( "[#|\\[\\]^\\\\/\\p{Cntrl}]", "-" ).trim();
}
```
`VaultNoteNamer.assign`:
- Sort the paths by `VaultPaths.ORDER`.
- For each path: `base = legalPageName( withoutMd( basename ) )`.
- If `base` is taken (case-insensitive) and the note has a parent folder, try `fit( base, " (" + legalPageName( lastFolderSegment ) + ")" )`.
- If that is still taken, append ` 2`, ` 3`, … to the current candidate, using `fit` each time.
- Add the result (lowercased) to `taken`.

`fit( base, suffix )` truncates `base` so that `base + suffix` is at most 128 characters. Wiki-existing names do not participate in `assign`; they produce `SKIPPED_EXISTS` later. `allocateGenerated(desired)` runs `legalPageName` and then the numbering loop. It treats as taken both `taken` and `snapshot.existingPage( candidate ).isPresent()`.

`VaultLinkIndex` keys are paths lowercased with `.md` stripped (for notes). Resolution of `target` (strip a leading `/`; for notes strip a trailing `.md`; lowercase) goes in this order:
1. Exact path match.
2. If the target contains `/`: candidates whose key ends with `"/" + target`.
3. Otherwise: candidates whose basename key equals the target.

Ties at steps 2–3 go to the shortest path, then `VaultPaths.ORDER`. If several originals share one lowercased exact key (`Ideas.md` and `ideas.md`), the first in `ORDER` wins. Relative variants first normalise `parentFolder( fromPath ) + "/" + target`, resolving `.` and `..`. If that escapes the root, skip it. Otherwise try the exact lookup and then fall back to the vault-wide method.

- [ ] **Step 1: Write failing tests.** Key cases:
```java
@Test void legalNames() {
    assertEquals( "Beta (v2) 1", VaultNames.legalPageName( "Beta (v2) #1" ) );
    assertEquals( "What is this", VaultNames.legalPageName( "What is this?" ) );
    assertEquals( "a b", VaultNames.legalPageName( "a..b" ) );
    assertEquals( "Untitled", VaultNames.legalPageName( "🎉" ) );
    assertEquals( 128, VaultNames.legalPageName( "x".repeat( 200 ) ).length() );
    assertTrue( WikiPageNameValidator.isValid( VaultNames.legalPageName( "a/b\\c\u0001" ) ) );
}
@Test void slugs() {
    assertEquals( "my-projects", VaultNames.slug( "My Projects!" ) );
    assertEquals( "", VaultNames.slug( "日本" ) );
    assertTrue( "a-b".matches( FrontmatterSchema.CLUSTER_SLUG_PATTERN ) );
}
@Test void caseVariantsInSameFolder() {   // Review Focus 3
    final Map< String, String > n = new VaultNoteNamer( EMPTY ).assign( List.of( "Projects/ideas.md", "Projects/Ideas.md" ) );
    assertEquals( "Ideas", n.get( "Projects/Ideas.md" ) );        // ORDER: case-insensitive tie -> natural order, "I" < "i"
    assertEquals( "ideas (Projects)", n.get( "Projects/ideas.md" ) );
}
@Test void collisionAcrossFoldersThenNumbers() {
    final Map< String, String > n = new VaultNoteNamer( EMPTY ).assign( List.of( "A/Note.md", "B/Note.md", "B/x/Note.md", "Note.md" ) );
    assertEquals( "Note", n.get( "A/Note.md" ) );
    assertEquals( "Note (B)", n.get( "B/Note.md" ) );
    assertEquals( "Note (x)", n.get( "B/x/Note.md" ) );
    assertEquals( "Note 2", n.get( "Note.md" ) );                 // root note: no parent suffix, straight to numbering
}
@Test void generatedNamesAvoidExistingWikiPages() {
    final VaultNoteNamer namer = new VaultNoteNamer( snapshotWithPages( "Projects Hub" ) );
    assertEquals( "Projects Hub 2", namer.allocateGenerated( "Projects Hub" ) );
}
@Test void resolveExactPathThenBasenameThenShortest() {
    final VaultLinkIndex ix = new VaultLinkIndex( List.of( "Projects/Alpha.md", "Archive/Old/Alpha.md", "Beta.md" ), List.of( "Projects/assets/d.png" ) );
    assertEquals( Optional.of( "Archive/Old/Alpha.md" ), ix.resolveNote( "Archive/Old/Alpha" ) );
    assertEquals( Optional.of( "Projects/Alpha.md" ), ix.resolveNote( "alpha" ) );     // shortest path
    assertEquals( Optional.of( "Archive/Old/Alpha.md" ), ix.resolveNote( "Old/Alpha.md" ) );
    assertEquals( Optional.of( "Projects/assets/d.png" ), ix.resolveFile( "D.PNG" ) );
    assertEquals( Optional.empty(), ix.resolveNote( "Gamma" ) );
}
@Test void ambiguousBasenameResolvesDeterministically() {   // Review Focus 3
    final VaultLinkIndex ix = new VaultLinkIndex( List.of( "Projects/ideas.md", "Projects/Ideas.md" ), List.of() );
    assertEquals( Optional.of( "Projects/Ideas.md" ), ix.resolveNote( "ideas" ) );
}
@Test void relativeMarkdownLinks() {
    final VaultLinkIndex ix = new VaultLinkIndex( List.of( "Projects/Alpha.md", "Notes/Code.md" ), List.of() );
    assertEquals( Optional.of( "Projects/Alpha.md" ), ix.resolveNoteRelative( "../Projects/Alpha.md", "Notes/Code.md" ) );
    assertEquals( Optional.empty(), ix.resolveNoteRelative( "../../x.md", "Notes/Code.md" ) );
}
```
  `EMPTY` and `snapshotWithPages(...)` are small in-test lambdas or records implementing `WikiSnapshot`. Put a reusable `public final class FakeWikiSnapshot implements WikiSnapshot` in the test sources (constructor `( Set<String> pages, Set<String> systemPages, Map<String,String> hubsByCluster )`). Tasks 6–9 reuse it.

- [ ] **Step 2: Run red.** `mvn test -pl wikantik-main -Dtest='VaultNamesTest,VaultNoteNamerTest,VaultLinkIndexTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.** **Step 4: Run green.**
- [ ] **Step 5: Commit** (4 production files, 3 tests, `FakeWikiSnapshot.java`). Message: `feat(import): vault page naming, collisions and Obsidian link resolution` + trailer.

---

### Task 4: Code segments, inline tags, frontmatter mapping

**Files:**
- Create: `importer/CodeSegments.java`, `InlineTags.java`, `VaultFrontmatterMapper.java`, `NoteContext.java`, `MappedNote.java`
- Test: `CodeSegmentsTest.java`, `InlineTagsTest.java`, `VaultFrontmatterMapperTest.java`

**Interfaces (produces):**
```java
public final class CodeSegments {
    public static String mapOutsideFences( String md, UnaryOperator< String > f );   // fenced blocks untouched
    public static String mapProse( String md, UnaryOperator< String > f );           // fences AND inline code spans untouched
    public static void forEachProse( String md, Consumer< String > c );
}
public final class InlineTags { public static Set< String > scan( String body ); }  // normalised, insertion order
public record NoteContext( String pageName, String basename, String cluster, boolean hub ) {}   // basename without ".md"; cluster nullable
public record MappedNote( Map< String, Object > metadata, String body, List< String > warnings ) {}
public final class VaultFrontmatterMapper {
    public VaultFrontmatterMapper( FrontmatterSchema schema );
    public MappedNote map( String noteText, NoteContext ctx );
    public static String normaliseTag( String raw );   // strip leading '#', '/'->'-', lowercase
}
```

`CodeSegments` fence rule: a line matching `^ {0,3}(`{3,}|~{3,})` opens a fence. It closes at a later line that has the same character repeated at least as many times and nothing else but whitespace. An unclosed fence runs to the end of the text. Inline code within prose: a run of n backticks opens and the next run of exactly n backticks closes; an unmatched run is literal. Keep line terminators exactly as they were (the round trip must be identity for `f = identity`).

`InlineTags.scan`, applied to each prose segment:
1. Blank out wikilinks `\[\[[^\]\n]*\]\]`, markdown link destinations `\]\([^)\n]*\)` and URLs `\S+://\S+` by replacing them with a space.
2. Skip lines matching `^\s{0,3}#{1,6}\s`.
3. Match `(?<![^\s])#([\p{L}\p{N}_/-]+)` and keep a match only if it contains `[^\p{N}]`.
4. Normalise each tag with `normaliseTag`.

`VaultFrontmatterMapper.map` steps (one private method each):
1. **parse**: `FrontmatterParser.parseStrict( text )`. On `FrontmatterParseException`, use `RawFrontmatter` (a private static helper). If the text starts with `---\n` or `---\r\n`, the YAML runs to the next line equal to `---` (trimmed). Then metadata = `new LinkedHashMap<>()` and body = ```` "```yaml\n" + yaml + "\n```\n\n" + rest ````, plus warning `"frontmatter: malformed YAML kept as a code block (" + e.getMessage() + ")"`. Copy parsed metadata into a mutable `LinkedHashMap`.
2. **dropKeys**: remove `canonical_id`, `wikantik_url`, `wikantik_version`, `verified_at` and `verified_by`, plus every schema field whose `widget() == Widget.READONLY` (`confidence`, `agent_hints`). Do not warn.
3. **tags**: merge the frontmatter `tags` (a string, which may be comma- or space-separated, or a list) through `normaliseTag` with `InlineTags.scan( body )`. Keep the union in order and de-duplicated. Remove the key when the union is empty.
4. **aliases**: merge `aliases` and `alias` (string or list) into one ordered de-duplicated list. When `!ctx.pageName().equals( ctx.basename() )`, add `ctx.basename()`. Remove `alias`. Remove `aliases` when the list is empty.
5. **title**: when the name changed and `title` is absent, set `title = ctx.basename()`.
6. **type**:
   - If `ctx.hub()`, set `type = "hub"`.
   - Otherwise, if the value lowercased is `hub`, set `type = "article"` and warn `"type: hub downgraded to article (not this folder's hub)"`.
   - Otherwise, if the value lowercased is one of the schema `type` field's `canonicalValues()`, keep it lowercased.
   - Otherwise, drop it and warn `"type: '" + v + "' is not a wiki page type; dropped"`.
7. **cluster**: remove any vault `cluster`. If `ctx.cluster() != null`, put it.

Warning strings always start with a category followed by `:` (`frontmatter:`, `type:`, `link:`, `blockref:`, `comment:`, `attachment:`). The plan groups warnings by that prefix (Task 7).

- [ ] **Step 1: Write failing tests.** Key cases:
```java
@Test void fencesAndInlineCodeUntouched() {
    final String md = "a [[X]]\n```\n[[Y]]\n```\nb `[[Z]]` c";
    assertEquals( "a Q\n```\n[[Y]]\n```\nb `[[Z]]` c", CodeSegments.mapProse( md, s -> s.replace( "[[X]]", "Q" ) ) );
    assertEquals( md, CodeSegments.mapProse( md, UnaryOperator.identity() ) );
}
@Test void inlineTagRules() {
    assertEquals( Set.of( "work", "team-core" ), InlineTags.scan( "Hi #Work and #team/core\n# Heading #nope\n`#code` [[#anchor]] http://x/#frag #123" ) );
}
@Test void frontmatterRules() {
    final String note = "---\ncanonical_id: abc\nconfidence: stale\ntags: [Work, \"#team/core\"]\nalias: Old\ntype: Report\ncluster: elsewhere\nstatus: draft\n---\nBody #extra\n";
    final MappedNote m = new VaultFrontmatterMapper( FrontmatterSchema.defaultSchema() )
        .map( note, new NoteContext( "Beta (v2) 1", "Beta (v2) #1", "projects", false ) );
    assertFalse( m.metadata().containsKey( "canonical_id" ) );
    assertFalse( m.metadata().containsKey( "confidence" ) );
    assertEquals( List.of( "work", "team-core", "extra" ), m.metadata().get( "tags" ) );
    assertEquals( List.of( "Old", "Beta (v2) #1" ), m.metadata().get( "aliases" ) );
    assertEquals( "Beta (v2) #1", m.metadata().get( "title" ) );
    assertFalse( m.metadata().containsKey( "type" ) );                  // "report" is not a schema type
    assertEquals( "projects", m.metadata().get( "cluster" ) );
    assertEquals( "draft", m.metadata().get( "status" ) );              // everything else preserved
    assertTrue( m.warnings().stream().anyMatch( w -> w.startsWith( "type:" ) ) );
}
@Test void malformedYamlBecomesCodeBlock() {
    final MappedNote m = mapper.map( "---\ntitle: a: b: [\n---\nBody\n", new NoteContext( "Daily", "Daily", null, false ) );
    assertTrue( m.body().startsWith( "```yaml\ntitle: a: b: [\n```\n\nBody" ) );
    assertTrue( m.warnings().get( 0 ).startsWith( "frontmatter:" ) );
}
@Test void hubFlagSetsTypeAndOtherHubsDowngrade() { /* ctx.hub()=true -> "hub"; type: hub with hub=false -> "article" + warning */ }
@Test void noFrontmatterYieldsEmptyMetadataForUnchangedName() { /* "Body" -> metadata {} , body "Body" */ }
```
- [ ] **Step 2: Run red.** **Step 3: Implement.** **Step 4: Run green.** Use `-Dtest='CodeSegmentsTest,InlineTagsTest,VaultFrontmatterMapperTest'`.
- [ ] **Step 5: Commit.** Message: `feat(import): frontmatter mapping and inline tag extraction` + trailer.

---

### Task 5: Body rewriter

**Files:**
- Create: `importer/LinkTarget.java`, `VaultTargets.java`, `VaultBodyRewriter.java`, `RewriteResult.java`
- Test: `VaultBodyRewriterTest.java`

**Interfaces (produces):**
```java
public record LinkTarget( Kind kind, String value ) {
    public enum Kind { RENAME, KEEP, UNRESOLVED }
    public static LinkTarget rename( String v ); public static LinkTarget keep(); public static LinkTarget unresolved();
}
public interface VaultTargets {
    /** Page target T (no fragment). RENAME value = wiki page name to write (written only if != T). */
    LinkTarget page( String target, String fromPath, boolean relative );
    /** File target incl. extension. RENAME value = "Owner/file". KEEP = blocked (leave as written). */
    LinkTarget attachment( String target, String fromPath, boolean relative );
}
public record RewriteResult( String body, List< String > warnings ) {}
public final class VaultBodyRewriter {
    public RewriteResult rewrite( String body, String fromPath, VaultTargets targets );
}
```

Algorithm:
1. `body = CodeSegments.mapOutsideFences( body, this::removeComments )`. The comment regex is `%%[\s\S]*?%%`. Count the matches. If the count is above 0, warn `"comment: N Obsidian comment(s) removed"`.
2. `body = CodeSegments.mapProse( body, s -> stripBlockMarkers( rewriteMarkdownLinks( rewriteWikiLinks( s ) ) ) )`.

Wikilinks use regex `(!?)\[\[([^\[\]\n]+?)\]\]`, handled in `rewriteWikiLinks( String, Ctx )`:
- Split the inner text at the first `|` into `target` and `alias`. If the character before `|` is `\`, the pipe was escaped: drop the `\` from `target` and re-emit `\|`.
- If the target is empty or starts with whitespace, leave the link unchanged.
- Split `target` at the first `#` into `t` and `frag`. If `frag` starts with `^`, or contains `#^`, cut the block part: `frag = null` or the part before `#^`. Count it toward the block-ref count. If `t` is empty (`[[#H]]` or `[[#^x]]`), leave the link unchanged.
- **Attachment first** when `t` has an extension (last `.` after the last `/`) other than `md`:
  - `targets.attachment( t, fromPath, false )` returns RENAME → emit `bang + "[[" + value + (alias != null ? sep + alias : "") + "]]"`, where `sep` is `|` or `\|`. The alias text (for example `300`) is kept verbatim.
  - KEEP → leave unchanged.
  - UNRESOLVED → fall through to the page step.
- **Page**: `targets.page( t, fromPath, false )`.
  - RENAME with `value.equals( t )` → re-emit with only the block ref stripped.
  - RENAME with a different value → `[[value(#frag)|alias']]`, where `alias' = alias` if one was given. For a non-embed with no alias, `alias' = frag == null ? t : t + " > " + frag`. Embeds (`!`) never gain an alias.
  - KEEP → re-emit with only the block ref stripped.
  - UNRESOLVED → leave as written (minus the block ref) and warn `"link: unresolved [[" + t + "]]"` (once per distinct `t`).

Markdown links use regex `(!?)\[([^\]\n]*)\]\((?:<([^>\n]+)>|([^)\s]+))(?:\s+"[^"\n]*")?\)`, handled in `rewriteMarkdownLinks`:
- Skip the link when the destination matches `^[A-Za-z][A-Za-z0-9+.-]*:` (a URL scheme) or starts with `#`.
- Percent-decode the destination with `URLDecoder.decode( d.replace( "+", "%2B" ), UTF_8 )`. On `IllegalArgumentException`, `LOG.warn` the bad link and leave it unchanged.
- Split off the `#frag` as above.
- If the path ends with `.md` (case-insensitive): call `targets.page( withoutMd( path ), fromPath, true )`.
  - RENAME → `[[value(#frag)|text]]`.
  - KEEP → `[[` + `withoutMd( basename( path ) )` + `(#frag)|text]]`.
  - UNRESOLVED → leave unchanged + `link:` warning.
  - Omit `|text` when the text is empty.
- Otherwise call `targets.attachment( path, fromPath, true )`.
  - RENAME → for an image (`!`) emit `![[value]]` (alt dropped, per the spec). For a link emit `[[value|text]]`.
  - KEEP → unchanged.
  - UNRESOLVED → unchanged, with no warning (it may be a site-relative path).

Trailing block markers: `(?m)[ \t]+\^[A-Za-z0-9-]+[ \t]*$` → `""` and `(?m)^\^[A-Za-z0-9-]+[ \t]*$` → `""`. These are counted together with the fragment block refs. If the total is above 0, warn `"blockref: N block reference(s) stripped"`.

Keep each `rewriteX` in its own method, using a `Matcher.appendReplacement` loop with `Matcher.quoteReplacement`. Hold per-call counters in a tiny private `Ctx` class (`blockRefs`, `unresolved` LinkedHashSet, `warnings`) so that methods take at most three parameters.

- [ ] **Step 1: Write failing tests.** Use a table-driven fake `VaultTargets`: pages `Old name → New name`, `Same → Same`, `Existing → KEEP`; attachments `f.png → Owner/f.png`, `doc.pdf → Owner/doc.pdf`, `evil.svg → KEEP`. Assert exact output for each row:

| input | output |
|---|---|
| `[[Old name]]` | `[[New name\|Old name]]` |
| `[[Old name\|Shown]]` | `[[New name\|Shown]]` |
| `[[Old name#Intro]]` | `[[New name#Intro\|Old name > Intro]]` |
| `![[Old name]]` | `![[New name]]` |
| `[[Same]]`, `[[Existing]]` | unchanged |
| `[[Same#^abc]]` | `[[Same]]` + `blockref:` warning |
| `[[Ghost]]` | unchanged + `link: unresolved [[Ghost]]` |
| `![[f.png\|300]]` | `![[Owner/f.png\|300]]` |
| `[[doc.pdf]]` / `[[doc.pdf\|Doc]]` | `[[Owner/doc.pdf]]` / `[[Owner/doc.pdf\|Doc]]` |
| `![[evil.svg]]` | unchanged |
| `\| [[Old name\|x]] \|` inside a table row written `[[Old name\\|x]]` | `[[New name\\|x]]` |
| `![alt](assets/f.png)` | `![[Owner/f.png]]` |
| `[read](doc.pdf)` | `[[Owner/doc.pdf\|read]]` |
| `[x](Old%20name.md)` | `[[New name\|x]]` |
| `[x](<Old name.md#Intro>)` | `[[New name#Intro\|x]]` |
| `[x](Existing.md)` | `[[Existing\|x]]` |
| `[x](https://e.com/a.md)` | unchanged |
| `para ^block-1` (line end) | `para` + `blockref:` warning |
| `a %% hidden\nmore %% b` | `a  b` + `comment: 1 …` |
| ```` ```\n[[Old name]] %% c %%\n``` ```` | unchanged |
| `` `[[Old name]]` `` | unchanged |
| `[[ -f "$x" ]]` | unchanged |

```java
@Test void table() {
    for ( final String[] row : ROWS ) {
        assertEquals( row[ 1 ], rewriter.rewrite( row[ 0 ], "Notes/Here.md", FAKE ).body(), row[ 0 ] );
    }
}
```
- [ ] **Step 2: Run red.** **Step 3: Implement.** **Step 4: Run green** + `mvn pmd:check -Pcomplexity-gate -pl wikantik-main`.
- [ ] **Step 5: Commit.** Message: `feat(import): code-fence aware vault body rewriter` + trailer.

---

### Task 6: Import options and cluster planning

**Files:**
- Create: `importer/ImportOptions.java`, `ClusterMode.java`, `ClusterAction.java`, `PlannedCluster.java`, `VaultClusterPlanner.java`
- Test: `ImportOptionsTest.java`, `VaultClusterPlannerTest.java`

**Interfaces (produces):**
```java
public enum ClusterMode { FOLDERS, FIXED, NONE }
public record ImportOptions( ClusterMode mode, String cluster ) {
    /** null/blank mode -> FOLDERS; "folders|fixed|none" case-insensitive; FIXED needs cluster matching
     *  FrontmatterSchema.CLUSTER_SLUG_PATTERN; anything else -> IllegalArgumentException (400). cluster is null unless FIXED. */
    public static ImportOptions parse( String mode, String cluster );
}
public enum ClusterAction { JOIN, CREATE }
public record PlannedCluster( String cluster, ClusterAction action, String hubPage, String folder ) {}
public final class VaultClusterPlanner {
    public record NoteRef( String path, String name, boolean importable ) {}
    public record GeneratedHub( String name, String cluster, String folderName ) {}
    public record Result( Map< String, String > clusterByPath, Set< String > hubNotePaths,
                          List< GeneratedHub > generated, List< PlannedCluster > clusters ) {}
    public Result plan( List< NoteRef > notes, ImportOptions options, WikiSnapshot snapshot, VaultNoteNamer namer );
}
```

**FOLDERS** mode. Process the importable notes in `ORDER`:
- `segs` = folder segments of the path, each paired with `VaultNames.slug( seg )`. Drop pairs whose slug is empty.
- If there are no segments → no cluster.
- Otherwise `cluster = s0` when there is one segment, else `s0 + "/" + s1` (depth > 2 folds into the second segment).
- Record `display[cluster]` and `folderPath[cluster]` (the original folder path of the pairs used) the first time each cluster is seen. Do the same for the parent `s0` of a sub-cluster.

Then for each needed cluster in natural order (parents sort before `parent/child`):
- If `snapshot.hubPage( c )` is present → `JOIN` with that hub.
- Otherwise look for the **folder note**: an importable note whose path equals `folderPath[c] + "/" + lastSegment( folderPath[c] ) + ".md"`, compared case-insensitively.
  - If found → add it to `hubNotePaths`, set its cluster to `c`, and use its page name as the hub.
  - If not found → `namer.allocateGenerated( display[c] + " Hub" )` and record a `GeneratedHub`.
  - The action is `CREATE`.

Notes that are not importable never get a cluster and are never chosen as hubs. **FIXED**: every importable note gets `options.cluster()`, and `clusters = [ JOIN with snapshot.hubPage(...) ]`. If that hub is absent, throw `IllegalArgumentException("cluster '<c>' is not declared by any hub")`. **NONE**: empty result.

Split the work into `assignClusters`, `neededClusters`, `planCluster` and `findFolderNote`, each ≤ ~25 lines.

- [ ] **Step 1: Write failing tests.**
```java
@Test void foldersCreateHubsFromFolderNotesOrGenerated() {
    final List< NoteRef > notes = List.of(
        new NoteRef( "Projects/Projects.md", "Projects", true ),
        new NoteRef( "Projects/Alpha.md", "Alpha", true ),
        new NoteRef( "Projects/Deep/Deeper/Gamma.md", "Gamma", true ),
        new NoteRef( "Notes/Daily.md", "Daily", true ),
        new NoteRef( "Root.md", "Root", true ) );
    final FakeWikiSnapshot snap = new FakeWikiSnapshot( Set.of(), Set.of(), Map.of() );
    final Result r = new VaultClusterPlanner().plan( notes, ImportOptions.parse( "folders", null ), snap, new VaultNoteNamer( snap ) );
    assertEquals( "projects", r.clusterByPath().get( "Projects/Alpha.md" ) );
    assertEquals( "projects/deep", r.clusterByPath().get( "Projects/Deep/Deeper/Gamma.md" ) );   // depth folded
    assertNull( r.clusterByPath().get( "Root.md" ) );
    assertEquals( Set.of( "Projects/Projects.md" ), r.hubNotePaths() );
    assertEquals( List.of( "Deep Hub", "Notes Hub" ), r.generated().stream().map( GeneratedHub::name ).sorted().toList() );
    assertEquals( List.of( "notes", "projects", "projects/deep" ), r.clusters().stream().map( PlannedCluster::cluster ).toList() );
    assertTrue( r.clusters().stream().allMatch( c -> c.action() == ClusterAction.CREATE ) );
}
@Test void declaredClusterIsJoined() { /* snapshot hubs {"projects": "ProjectsHub"} -> JOIN, hub "ProjectsHub", Projects/Projects.md NOT a hub */ }
@Test void subClusterNeedsParentDeclared() { /* only "A/B/x.md" -> clusters a (CREATE, "A Hub") and a/b (CREATE, "B Hub") */ }
@Test void emptySlugSegmentSkipped() { /* "日本/Note.md" -> no cluster; "日本/Work/N.md" -> "work" */ }
@Test void sameSlugFromTwoFoldersIsOneCluster() { /* "My Notes/a.md", "my-notes/b.md" -> one cluster "my-notes", display "My Notes" */ }
@Test void fixedModeRequiresDeclaredCluster() { /* undeclared -> IllegalArgumentException; declared -> all notes get it, one JOIN */ }
@Test void noneModeHasNoClusters() {}
@Test void nonImportableFolderNoteIsNotHub() { /* folder note importable=false -> generated hub instead */ }
// ImportOptionsTest: parse(null,null)=FOLDERS; parse("NONE",x).cluster()==null; parse("fixed","Bad Slug") and parse("bogus",null) -> IAE
```
- [ ] **Step 2: Run red.** **Step 3: Implement.** **Step 4: Run green.**
- [ ] **Step 5: Commit.** Message: `feat(import): folder-to-cluster planning with hub creation` + trailer.

---

### Task 7: Plan orchestration, plan hash, attachment gate, fixture vault

**Files:**
- Create: `importer/PageStatus.java`, `PlannedPage.java`, `AttachmentStatus.java`, `PlannedAttachment.java`, `PlanTotals.java`, `ImportPlan.java`, `PageDraft.java`, `PlanResult.java`, `AttachmentGate.java`, `PlanTargets.java` (package-private), `PlanRun.java` (package-private), `VaultImportPlanner.java`, `PlanHasher.java`
- Create fixture: `wikantik-main/src/test/resources/com/wikantik/importer/fixture-vault/` (files below)
- Modify: `TestVaults.java` — implement `fixture()`
- Test: `VaultImportPlannerTest.java`, `AttachmentGateTest.java`, `PlanHasherTest.java`

**Interfaces (produces):**
```java
public enum PageStatus { NEW, SKIPPED_EXISTS, SKIPPED_RESERVED, WILL_FAIL }
public record PlannedPage( String vaultPath, String name, PageStatus status, String reason, boolean hub,
                           String cluster, List< String > warnings, int validationWarnings ) {}   // vaultPath null for generated hubs
public enum AttachmentStatus { IMPORT, BLOCKED, SKIPPED_UNREFERENCED }
public record PlannedAttachment( String vaultPath, String entryName, String owner, String fileName, long size,
                                 AttachmentStatus status, String reason ) {}
public record PlanTotals( int pagesNew, int pagesSkippedExisting, int pagesSkippedReserved, int pagesFailing,
                          int attachments, int attachmentsBlocked, int attachmentsSkipped,
                          int clustersCreate, int clustersJoin, int hubsCreate ) {}
public record ImportPlan( String planHash, String vaultName, ClusterMode clusterMode, PlanTotals totals,
                          List< PlannedPage > pages, List< PlannedAttachment > attachments,
                          List< PlannedCluster > clusters, Map< String, Integer > warningGroups ) {}
public record PageDraft( String name, String vaultPath, Map< String, Object > metadata, String body, boolean hub ) {}
public record PlanResult( ImportPlan plan, List< PageDraft > drafts, List< PlannedAttachment > attachmentsToImport ) {}
public final class AttachmentGate {
    public AttachmentGate( AttachmentUploadPolicy policy );
    public static AttachmentGate fromProperties( Properties props );
    public Optional< String > rejection( String fileName, long size );
}
public final class PlanHasher { public static String hash( String zipSha256, ImportOptions options, Collection< String > collidedNames ); }
public final class VaultImportPlanner {
    public VaultImportPlanner( FrontmatterSchema schema, AttachmentGate gate, int maxPages );
    public PlanResult plan( VaultArchive archive, ImportOptions options, WikiSnapshot snapshot,
                            String zipSha256, String vaultName ) throws ImportLimitException;
}
```

`AttachmentGate.rejection`:
- `AttachmentNameValidator.getExtension( name )` is in `AttachmentManager.BLOCKED_UPLOAD_EXTENSIONS` → `"blocked file type ." + ext`.
- `!policy.isSizeAllowed( size )` → `"exceeds wikantik.attachment.maxsize (" + policy.maxSize() + " bytes)"`.
- `!policy.isTypeAllowed( name )` → `"file type not allowed by the attachment upload policy"`.

`PlanHasher.hash` = sha256 hex of `zipSha256 + "\n" + mode + "\n" + nullToEmpty( cluster ) + "\n" + String.join( "\n", new TreeSet<>( collided ) )`.

`VaultImportPlanner.plan` throws `ImportLimitException( PROP_MAX_PAGES, maxPages, notes + " notes" )` when `archive.notes().size() > maxPages`. Otherwise it creates `new PlanRun( this-config, archive, options, snapshot ).execute( zipSha256, vaultName )`. `PlanRun` holds the per-plan state. Its steps (one method each):
1. `classify()`:
   - `names = new VaultNoteNamer( snapshot ).assign( notePaths )`.
   - For each note:
     - `oversized()` → WILL_FAIL `"note exceeds wikantik.api.maxPageBytes"`.
     - `snapshot.isSystemPage( name )` → SKIPPED_RESERVED `"system page name"`.
     - `snapshot.existingPage( name )` present → SKIPPED_EXISTS (reason `"page exists"`); add the existing name to `collided`.
     - Otherwise NEW.
2. `clusters()`: call `VaultClusterPlanner` with `NoteRef( path, name, status == NEW )` (the namer is shared, so generated hub names never collide with notes).
3. `mapAndValidate()`: for each NEW note, `VaultFrontmatterMapper.map( text, new NoteContext( name, withoutMd( basename ), clusterByPath.get( path ), hubNotePaths.contains( path ) ) )`. Validate the metadata with `new SchemaDrivenFrontmatterValidator( schema ).validate( metadata, ctx )`, where `ctx = new ValidationCtx( p -> true, a -> true, Severity.WARNING, c -> snapshot.isClusterDeclared( c ) || createdClusters.contains( c ) )`. If any violation has `Severity.ERROR`, the note is WILL_FAIL with reason `"frontmatter: " + messages joined "; "`. Otherwise keep the WARNING count.
4. `rewrite()`: build `VaultLinkIndex( all note paths, all file paths )` and `PlanTargets`. For each NEW note in `ORDER`, set `targets.currentPage( name )` and run `VaultBodyRewriter.rewrite`. Add the warnings.
5. `generatedHubs()`: each `GeneratedHub` becomes a NEW `PlannedPage( null, name, NEW, null, true, cluster, [], 0 )` with draft metadata `{ type: hub, cluster: c, title: folderName, summary: "Notes imported from the Obsidian folder " + folderName + "." }` and body `"# " + folderName + "\n"`.
6. `attachments()`: for each `VaultFile`:
   - owner assigned → IMPORT (fileName from `PlanTargets`).
   - in `targets.blocked()` → BLOCKED with the gate reason.
   - otherwise → SKIPPED_UNREFERENCED.
7. `assemble()`:
   - **Drafts:** hubs (generated + folder-note hubs) sorted by cluster (parents first), then the other NEW notes in `ORDER`.
   - **Pages view:** all notes plus generated hubs.
   - `warningGroups`: count of every warning by its prefix before the first `:`.
   - Then the totals and `planHash = PlanHasher.hash( zipSha256, options, collided )`.

`PlanTargets implements VaultTargets` (package-private) is built with `names`, `statuses`, `snapshot`, `index`, `gate`, `filesByPath`.

`page( t, from, relative )`:
- Resolve the vault path with `index.resolveNote` (or `resolveNoteRelative` when `relative`).
- If present, by its status:
  - NEW or WILL_FAIL → `rename( names.get( path ) )`.
  - SKIPPED_EXISTS → let `existing = snapshot.existingPage( name ).get()`; if `existing.equalsIgnoreCase( t )` → `keep()`, else `rename( existing )`.
  - SKIPPED_RESERVED → `keep()`.
- If absent: `snapshot.existingPage( t )` present → `keep()`, else `unresolved()`.

`attachment( t, from, relative )`:
- Resolve with `index.resolveFile` (or `resolveFileRelative`). Absent → `unresolved()`.
- `gate.rejection( basename, size )` present → add to `blocked`, return `keep()`.
- Otherwise use `owners.computeIfAbsent( path, p -> currentPage )` and a file name allocated per owner: `VaultNames.attachmentName( basename )`, with a ` 2`/` 3` suffix before the extension when that owner already uses the name case-insensitively.
- Return `rename( owner + "/" + fileName )`.

Because notes are rewritten in `ORDER` and only NEW notes are rewritten, the owner is always the first NEW page that references the file.

**Fixture vault.** Create these text files under `src/test/resources/com/wikantik/importer/fixture-vault/`. `TestVaults.fixture()` walks this directory into `Map<path, bytes>` and **adds** `Projects/assets/diagram.png` (8-byte PNG signature `89 50 4E 47 0D 0A 1A 0A`) and `Projects/assets/unused.png` (same bytes) programmatically, so no binary is committed.
```
.obsidian/app.json            {"legacyEditor":false}
Wikantik Export.md            # ignored readme
Welcome.md                    ---\ntags: Start\n---\n# Welcome\nSee [[Projects/Alpha]], [[Beta v2!|the beta]] and [[Missing Note]].\n![[diagram.png|300]] #root-tag %% private %%\n
Main.md                       # reserved system page name
Projects/Projects.md          ---\ntags: [Work, "#team/core"]\n---\n# Projects\n
Projects/Alpha.md             ---\ncanonical_id: old-id\naliases: A1\ntype: report\n---\n# Alpha\nBack to [[Welcome#Welcome]]. Deep: [[Gamma#^b1]]. ![[evil.svg]]\nA line ^a1\n
Projects/Beta v2!.md          # Beta\n[code](../Notes/Code.md)\n
Projects/Deep/Deeper/Gamma.md ---\ntype: hub\n---\n# Gamma\n
Projects/assets/evil.svg      <svg/>
Notes/Daily.md                ---\ntitle: a: b: [\n---\nDaily body\n
Notes/Code.md                 # Code\n```\n[[NotALink]]\n```\nInline `[[AlsoNot]]`. [alpha](../Projects/Alpha.md) [beta](../Projects/Beta%20v2!.md) [beta2](<../Projects/Beta v2!.md>)\n
```
(`\n` above means a real newline in the file. `Main.md` relies on `FakeWikiSnapshot` declaring `Main` a system page in the planner tests and on the real registry in Task 8.)

- [ ] **Step 1: Write failing tests** (`VaultImportPlannerTest`, all against the fixture with `FakeWikiSnapshot( pages = {"Existing Page"}, systemPages = {"Main","LeftMenu"}, hubs = {} )`):
```java
private PlanResult plan( final Map< String, byte[] > vault, final WikiSnapshot snap, final ImportOptions o ) throws Exception {
    final Path p = TestVaults.write( TestVaults.zip( vault ) );
    try {
        final VaultArchive a = new VaultArchiveReader( ImportLimits.defaults() ).read( p );
        return new VaultImportPlanner( FrontmatterSchema.defaultSchema(), new AttachmentGate( new AttachmentUploadPolicy( new String[0], new String[0], Long.MAX_VALUE ) ), 2000 )
            .plan( a, o, snap, "sha", "fixture.zip" );
    } finally { Files.deleteIfExists( p ); }
}
@Test void fixtureEndToEnd() throws Exception {
    final PlanResult r = plan( TestVaults.fixture(), SNAP, ImportOptions.parse( "folders", null ) );
    final Map< String, PlannedPage > byPath = r.plan().pages().stream().filter( p -> p.vaultPath() != null )
        .collect( toMap( PlannedPage::vaultPath, p -> p ) );
    assertEquals( PageStatus.SKIPPED_RESERVED, byPath.get( "Main.md" ).status() );
    assertEquals( "Beta v2", byPath.get( "Projects/Beta v2!.md" ).name() );
    assertTrue( byPath.get( "Projects/Projects.md" ).hub() );
    assertEquals( "projects/deep", byPath.get( "Projects/Deep/Deeper/Gamma.md" ).cluster() );
    final PageDraft welcome = draft( r, "Welcome" );
    assertTrue( welcome.body().contains( "[[Alpha|Projects/Alpha]]" ) );
    assertTrue( welcome.body().contains( "[[Beta v2|the beta]]" ) );
    assertTrue( welcome.body().contains( "[[Missing Note]]" ) );
    assertTrue( welcome.body().contains( "![[Welcome/diagram.png|300]]" ) );   // first referencing page owns it
    assertFalse( welcome.body().contains( "private" ) );
    assertEquals( List.of( "start", "root-tag" ), welcome.metadata().get( "tags" ) );
    final PageDraft gamma = draft( r, "Gamma" );
    assertEquals( "article", gamma.metadata().get( "type" ) );                 // non-folder-note hub downgraded
    final PageDraft alpha = draft( r, "Alpha" );
    assertTrue( alpha.body().contains( "[[Gamma]]" ) && !alpha.body().contains( "^b1" ) && !alpha.body().contains( "^a1" ) );
    assertTrue( alpha.body().contains( "![[evil.svg]]" ) );                     // blocked: left as written
    final PageDraft code = draft( r, "Code" );
    assertTrue( code.body().contains( "```\n[[NotALink]]\n```" ) && code.body().contains( "`[[AlsoNot]]`" ) );
    assertTrue( code.body().contains( "[[Alpha|alpha]]" ) && code.body().contains( "[[Beta v2|beta]]" ) && code.body().contains( "[[Beta v2|beta2]]" ) );
    assertTrue( draft( r, "Daily" ).body().startsWith( "```yaml" ) );
    assertEquals( List.of( "Notes Hub", "Projects", "Deep Hub" ), r.drafts().stream().limit( 3 ).map( PageDraft::name ).toList() ); // hubs first, by cluster: notes, projects, projects/deep
    assertStatus( r, "Projects/assets/evil.svg", AttachmentStatus.BLOCKED );
    assertStatus( r, "Projects/assets/unused.png", AttachmentStatus.SKIPPED_UNREFERENCED );
    assertStatus( r, "Projects/assets/diagram.png", AttachmentStatus.IMPORT );
    assertTrue( r.plan().warningGroups().containsKey( "link" ) );
}
@Test void systemPageNotesAreSkippedReserved() throws Exception {   // Review Focus 2
    final PlanResult r = plan( Map.of( "Main.md", b( "x" ), "LeftMenu.md", b( "y" ), "Other.md", b( "[[Main]]" ) ), SNAP, FOLDERS );
    assertEquals( 2, r.plan().totals().pagesSkippedReserved() );
    assertEquals( List.of( "Other" ), r.drafts().stream().map( PageDraft::name ).toList() );
    assertEquals( "[[Main]]", r.drafts().get( 0 ).body() );
}
@Test void existingPageSkippedAndLinksPointToIt() { /* "existing page.md" + "B.md" linking [[existing page]]: status SKIPPED_EXISTS,
     B body "[[Existing Page|existing page]]"? -> NO: equalsIgnoreCase -> KEEP -> "[[existing page]]" unchanged */ }
@Test void frontmatterErrorIsWillFail() { /* note with "audience: robots" -> WILL_FAIL, reason starts "frontmatter:", not in drafts */ }
@Test void maxPagesIs413() { /* planner maxPages=1, two notes -> ImportLimitException PROP_MAX_PAGES */ }
@Test void hashChangesWhenAPlannedNameStartsExisting() throws Exception {   // Review Focus 4
    final String h1 = plan( TestVaults.fixture(), SNAP, FOLDERS ).plan().planHash();
    final String h2 = plan( TestVaults.fixture(), SNAP, FOLDERS ).plan().planHash();
    final FakeWikiSnapshot later = new FakeWikiSnapshot( Set.of( "Existing Page", "Alpha" ), Set.of( "Main", "LeftMenu" ), Map.of() );
    assertEquals( h1, h2 );
    assertNotEquals( h1, plan( TestVaults.fixture(), later, FOLDERS ).plan().planHash() );
    assertNotEquals( h1, plan( TestVaults.fixture(), SNAP, ImportOptions.parse( "none", null ) ).plan().planHash() );
}
@Test void sameAttachmentNameFromTwoFoldersGetsSuffix() { /* "N.md" -> ![[a/x.png]] ![[b/x.png]] -> "N/x.png" and "N/x 2.png" */ }
// AttachmentGateTest: svg/html blocked; size over policy; forbidden extension via wikantik.attachment.forbidden; png ok
// PlanHasherTest: order-insensitive over collided names; differs by mode and cluster
```
  `draft( r, name )` finds a draft by name. `assertStatus` finds a `PlannedAttachment` by `vaultPath`. `b( String )` is an in-test helper returning UTF-8 bytes. `SNAP = new FakeWikiSnapshot( Set.of( "Existing Page" ), Set.of( "Main", "LeftMenu" ), Map.of() )` and `FOLDERS = ImportOptions.parse( "folders", null )`.

- [ ] **Step 2: Run red.** `mvn test -pl wikantik-main -Dtest='VaultImportPlannerTest,AttachmentGateTest,PlanHasherTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.** If an assertion in `fixtureEndToEnd` disagrees with a rule in Tasks 3–6, the earlier task's rule wins. Fix the assertion only after re-reading spec §5 and noting why in the commit body.
- [ ] **Step 4: Run green** plus all `com.wikantik.importer.*Test`, and `mvn pmd:check -Pcomplexity-gate -pl wikantik-main`.
- [ ] **Step 5: Commit** (all files above, the fixture directory files by name). Message: `feat(import): vault import planner with plan hash and fixture vault` + trailer.

---

### Task 8: Apply — engine ports, job, registry, service

**Files:**
- Create: `importer/ImportPageSink.java`, `ImportSaveException.java`, `EngineImportPageSink.java`, `WikiSnapshotSource.java`, `EngineWikiSnapshot.java`, `JobState.java`, `ItemStatus.java`, `ItemResult.java`, `JobView.java`, `VaultImportJob.java`, `ImportJobRegistry.java`, `ImportJobConflictException.java`, `VaultImportService.java`
- Create test helper: `wikantik-main/src/test/java/com/wikantik/importer/ImportTestJobs.java` (public, test-jar)
- Test: `VaultImportServiceTest.java` (TestEngine), `ImportJobRegistryTest.java`, `EngineWikiSnapshotTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces:
```java
public interface ImportPageSink {
    boolean pageExists( String name );
    List< String > savePage( String name, String body, Map< String, Object > metadata, String author, String changeNote ) throws ImportSaveException;
    void storeAttachment( String page, String fileName, InputStream in, String author ) throws Exception;
}
public class ImportSaveException extends Exception { public ImportSaveException( String reason, Throwable cause ); }
@FunctionalInterface public interface WikiSnapshotSource { WikiSnapshot capture() throws ProviderException; }
public final class EngineWikiSnapshot implements WikiSnapshot {
    public static EngineWikiSnapshot capture( PageManager pages, SystemPageRegistry registry, StructuralIndexService index ) throws ProviderException; // registry/index may be null
}
public final class EngineImportPageSink implements ImportPageSink {
    public EngineImportPageSink( Engine engine, PageManager pages, AttachmentManager attachments, PageSaveHelper saveHelper );
}
public enum JobState { RUNNING, DONE, FAILED }
public enum ItemStatus { CREATED, SKIPPED_EXISTS, SKIPPED_RESERVED, FAILED }
public record ItemResult( String kind, String name, String vaultPath, ItemStatus status, String reason, List< String > warnings ) {} // kind "page"|"attachment"
public record JobView( String jobId, JobState state, int done, int total, String current,
                       List< ItemResult > results, Map< String, Object > summary, String message ) {}
public final class VaultImportJob implements Runnable {
    public VaultImportJob( String id, String owner, String author, SpooledUpload upload, PlanResult plan,
                           ImportPageSink sink, BooleanSupplier permitted );   // 7 params max
    public String id(); public String owner(); public Instant createdAt(); public Instant finishedAt(); // finishedAt null while running
    public boolean isRunning(); public JobView view(); public void run(); public void discardUpload();
}
public final class ImportJobConflictException extends Exception {
    public enum Reason { USER_RUNNING, CAPACITY }
    public ImportJobConflictException( Reason reason, String message ); public Reason reason();
}
public final class ImportJobRegistry implements AutoCloseable {
    public static final Duration RETENTION = Duration.ofHours( 1 );
    public ImportJobRegistry( int maxConcurrent, Clock clock, Executor executor );
    public static ImportJobRegistry create( int maxConcurrent );   // cached pool of daemon threads "obsidian-import-N"
    public synchronized void ensureCanStart( String owner ) throws ImportJobConflictException;
    public synchronized VaultImportJob start( String owner, Function< String, VaultImportJob > factory ) throws ImportJobConflictException;
    public synchronized Optional< VaultImportJob > find( String jobId );
    public synchronized Optional< VaultImportJob > current( String owner );
    @Override public void close();
}
public final class VaultImportService {
    public VaultImportService( ImportLimits limits, VaultImportPlanner planner, WikiSnapshotSource snapshots, ImportPageSink sink );
    public static VaultImportService fromSubsystems( Engine engine, WikiSubsystems subs );
    public ImportLimits limits();
    public PlanResult plan( SpooledUpload upload, ImportOptions options )
            throws IOException, VaultArchiveException, ImportLimitException, ProviderException;
    public VaultImportJob newJob( String jobId, String owner, String author, SpooledUpload upload, PlanResult plan, BooleanSupplier permitted );
}
```

Implementation notes:
- `EngineWikiSnapshot.capture`: collect `pages.getAllPages()` names into a `Set` (exact) and a `TreeMap<String lowercase, String name>`, keeping the lexicographically lowest name per key. `existingPage( n )` returns `n` if it is in the exact set, else the lowercase lookup. `hubPage( c )` returns `index == null ? empty : index.getCluster( c ).map( ClusterDetails::hubPage ).map( PageDescriptor::slug )`.
- `EngineImportPageSink.savePage` (the `WritePagesTool` model):
```java
FrontmatterWarningSink.clear(); ContentWarningSink.clear();
try {
    saveHelper.saveText( name, body, SaveOptions.builder().author( author ).changeNote( changeNote ).markupSyntax( "markdown" )
        .metadata( metadata.isEmpty() ? null : metadata ).replaceMetadata( true ).build() );
    final List< String > w = new ArrayList<>();
    FrontmatterWarningSink.drain( name ).forEach( v -> w.add( "frontmatter: " + v.message() ) );
    ContentWarningSink.drain().forEach( v -> w.add( "content: " + v.message() ) );
    return w;
} catch ( final FrontmatterValidationException e ) {
    throw new ImportSaveException( "frontmatter validation failed: " + messages( e.violations() ), e );
} catch ( final ContentValidationException e ) {
    throw new ImportSaveException( "math validation failed: " + e.violations().stream().map( ContentViolation::message ).toList(), e );
} catch ( final WikiException | RuntimeException e ) {
    throw new ImportSaveException( String.valueOf( e.getMessage() ), e );
} finally { FrontmatterWarningSink.clear(); ContentWarningSink.clear(); }
```
  `pageExists` returns `pages.wikiPageExists( name )`. `storeAttachment`: `Attachment att = Wiki.contents().attachment( engine, page, fileName ); att.setAuthor( author ); attachments.storeAttachment( att, in );`.
- `VaultImportJob` constructor sets `state=RUNNING`, `total = plan.drafts().size() + plan.attachmentsToImport().size()`, `createdAt = Instant.now()`. Guard every mutable field with `synchronized` accessors (`view()` returns copies). `run()`:
```java
try {
    if ( !permitted.getAsBoolean() ) { finish( JobState.FAILED, "createPages permission is no longer granted" ); return; }
    recordPlanOutcomes();                  // SKIPPED_EXISTS / SKIPPED_RESERVED / WILL_FAIL(->FAILED with plan reason) pages from plan().pages()
    final Set< String > created = new HashSet<>();
    for ( final PageDraft d : plan.drafts() ) { createPage( d, created ); }
    importAttachments( created );          // try-with-resources new ZipFile( upload.file().toFile(), UTF_8 ); skips when list empty
    finish( JobState.DONE, null );
} catch ( final IOException | RuntimeException e ) {
    LOG.warn( "Obsidian import job {} failed: {}", id, e.getMessage(), e );
    finish( JobState.FAILED, e.getMessage() );
} finally { discardUpload(); }
```
  - `createPage`: `setCurrent( d.name() )`. If `sink.pageExists( d.name() )`, record SKIPPED_EXISTS with reason `"created by someone else after planning"`. Otherwise `savePage( …, author, "Imported from Obsidian vault " + upload.originalName() )` → CREATED with warnings, and add the name to `created`. On `ImportSaveException`, `LOG.warn( "Obsidian import {}: page {} failed: {}", id, d.name(), e.getMessage(), e )` → FAILED. Then `done++`.
  - Attachment: if the owner is not in `created` → FAILED `"owner page <x> was not created"`. If `zip.getEntry( entryName ) == null` → FAILED. Otherwise call `storeAttachment` inside try-with-resources on the entry stream; on `Exception`, LOG.warn + FAILED. The result `name` is `owner + "/" + fileName`.
  - Summary map: `pagesCreated`, `pagesSkipped`, `pagesFailed`, `attachmentsCreated`, `attachmentsFailed`, `unreferencedFiles` (= plan totals `attachmentsSkipped`), `hubs` (list of CREATED drafts with `hub()`).
  - `discardUpload()` = `upload.delete()`, idempotent.
- `ImportJobRegistry`:
  - `start` does: `evictExpired()`, `ensureCanStart( owner )`, `id = UUID.randomUUID().toString()`, `job = factory.apply( id )`, `jobs.put( id, job )`, then `executor.execute( job )`. On `RejectedExecutionException`: remove the job, call `job.discardUpload()`, rethrow as `IllegalStateException`.
  - `ensureCanStart`: if any running job has this owner → `USER_RUNNING` ("an import is already running for you"). If the running count is at least `maxConcurrent` → `CAPACITY` ("too many imports in progress; try again shortly").
  - `evictExpired`: remove jobs that are not running and whose `finishedAt` is before `clock.instant().minus( RETENTION )`.
  - `current( owner )`: the latest `createdAt` among that owner's jobs that are running or finished within `RETENTION`.
  - `close()`: `if ( executor instanceof ExecutorService es ) es.shutdownNow();` then `discardUpload()` on every job.
- `VaultImportService.fromSubsystems`:
  - `limits = ImportLimits.fromProperties( engine.getWikiProperties() )`.
  - planner: `new VaultImportPlanner( FrontmatterSchema.defaultSchema(), AttachmentGate.fromProperties( props ), limits.maxPages() )`.
  - snapshots: `() -> EngineWikiSnapshot.capture( subs.page().pages(), subs.core().systemPageRegistry(), subs.pageGraph() == null ? null : subs.pageGraph().structuralIndexService() )`.
  - sink: `new EngineImportPageSink( engine, subs.page().pages(), subs.page().attachments(), subs.page().pageSaveHelper() )`.
  - `plan()` = `new VaultArchiveReader( limits ).read( upload.file() )` then `planner.plan( archive, options, snapshots.capture(), upload.sha256(), upload.originalName() )`.

- [ ] **Step 1: Write failing tests.**
  `VaultImportServiceTest`:
  - `@BeforeEach engine = TestEngine.build()`, `@AfterEach engine.stop()`.
  - The service is built from `engine.getManager( PageManager.class )`, `AttachmentManager.class` and `new PageSaveHelper( engine, pm )`. The snapshot is `EngineWikiSnapshot.capture( pm, CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry(), null )`.
  - Jobs run synchronously via `job.run()`.
```java
@Test void applyCreatesPagesHubsAndAttachments() throws Exception {
    final SpooledUpload up = TestVaults.upload( TestVaults.zip( TestVaults.fixture() ), "fixture.zip" );
    final PlanResult plan = service.plan( up, ImportOptions.parse( "folders", null ) );
    final VaultImportJob job = service.newJob( "j1", "admin", "admin", up, plan, () -> true );
    job.run();
    final JobView v = job.view();
    assertEquals( JobState.DONE, v.state() );
    assertEquals( v.total(), v.done() );
    assertTrue( pm.wikiPageExists( "Projects" ) && pm.wikiPageExists( "Notes Hub" ) && pm.wikiPageExists( "Beta v2" ) );
    final String welcome = pm.getPureText( "Welcome", WikiProvider.LATEST_VERSION );
    assertTrue( welcome.contains( "[[Alpha|Projects/Alpha]]" ) );   // root note: no cluster key
    assertFalse( welcome.contains( "cluster:" ) );
    assertNotNull( am.getAttachmentInfo( "Welcome/diagram.png" ) );
    assertEquals( "Imported from Obsidian vault fixture.zip", pm.getPage( "Alpha" ).getAttribute( Page.CHANGENOTE ) );
    assertEquals( List.of( "Notes Hub", "Projects", "Deep Hub" ), v.summary().get( "hubs" ) );
    assertEquals( ItemStatus.SKIPPED_RESERVED, result( v, "Main" ).status() );
    assertFalse( Files.exists( up.file() ) );                          // temp zip removed
}
@Test void pageCreatedDuringJobIsSkippedNotOverwritten() throws Exception {   // Review Focus 4
    final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "vault A", "B.md", "vault B" ) ), "v.zip" );
    final PlanResult plan = service.plan( up, ImportOptions.parse( "none", null ) );
    engine.saveText( "A", "someone else's A" );
    final VaultImportJob job = service.newJob( "j2", "admin", "admin", up, plan, () -> true );
    job.run();
    assertEquals( ItemStatus.SKIPPED_EXISTS, result( job.view(), "A" ).status() );
    assertEquals( "someone else's A", pm.getPureText( "A", WikiProvider.LATEST_VERSION ).trim() );
    assertEquals( ItemStatus.CREATED, result( job.view(), "B" ).status() );
}
@Test void blockedAttachmentReportedAndReferenceKept() { /* fixture: evil.svg is BLOCKED in plan, absent from attachmentsToImport, Alpha body keeps ![[evil.svg]] */ }
@Test void frontmatterErrorPageFailsAlone() throws Exception {
    // a decorating sink rejects page "B" exactly as the schema filter would; A and C still created
    final ImportPageSink failing = new ImportPageSink() { /* delegate to real sink; savePage("B",…) throws new ImportSaveException("frontmatter validation failed: audience", null) */ };
    /* plan A.md, B.md, C.md; newJob with `failing` via new VaultImportService(limits, planner, snaps, failing); run; assert A,C CREATED, B FAILED with reason, state DONE */
}
@Test void unreferencedFilesAreNeverRead() throws Exception {   // Review Focus 5
    // zip: N.md (no refs) + big.png 2 MiB random; sink.storeAttachment never called; plan totals attachmentsSkipped=1; job done==total==1
}
@Test void revokedPermissionFailsJobBeforeAnyWrite() { /* permitted=()->false -> FAILED, no page created, temp file deleted */ }
```
  `ImportJobRegistryTest` (no engine). Use `List<Runnable> queued = new ArrayList<>(); new ImportJobRegistry( 1, clock, queued::add )`, so started jobs stay RUNNING until you run them. `ImportTestJobs.job( id, owner )` builds a `VaultImportJob` over an empty `PlanResult` and a no-op sink.
```java
@Test void secondJobForSameUserIs409Reason() { /* start("alice") ok; start("alice") -> USER_RUNNING */ }
@Test void capacityIs429Reason() { /* maxConcurrent 1: start("alice"); start("bob") -> CAPACITY */ }
@Test void finishedJobFreesSlotAndIsCurrentForAnHour() { /* run queued job; start("bob") ok; current("alice") present; advance clock 61 min -> current empty, find empty */ }
@Test void closeDiscardsTempFiles() { /* job with real SpooledUpload; close(); file gone */ }
```
  `EngineWikiSnapshotTest` (TestEngine): `existingPage( "testpage" )` returns `TestPage` after saving `TestPage`. `isSystemPage( "LeftMenu" )` is true. `hubPage` is empty when the index is null.

- [ ] **Step 2: Run red.** `mvn test -pl wikantik-main -Dtest='VaultImportServiceTest,ImportJobRegistryTest,EngineWikiSnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement.** First add a precondition to `applyCreatesPagesHubsAndAttachments`: `assertFalse( pm.wikiPageExists( n ) )` for every fixture page name except `Main`. A fresh `TestEngine.build()` should have none of them; if one exists, rename that fixture note rather than weakening the assertions. If `LeftMenu` (or `Main`) is not a system page in the TestEngine registry, use whichever shipped page `SystemPageRegistryTest` asserts as system (check `wikantik-main/src/test/java/com/wikantik/content/SystemPageRegistryTest.java`) in both `EngineWikiSnapshotTest` and the fixture expectations.
- [ ] **Step 4: Run green** + all `com.wikantik.importer.*Test` + `mvn pmd:check -Pcomplexity-gate -pl wikantik-main`.
- [ ] **Step 5: Commit.** Message: `feat(import): background import job, job registry and engine adapters` + trailer.

---

### Task 9: REST resource `ObsidianImportResource` + `canCreatePages`

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/ObsidianImportResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`:
  - Add a `<servlet>` block after `DerivedIngestResource` (~line 610–617) with its own `<multipart-config>` (`<max-file-size>209715200</max-file-size><max-request-size>209715200</max-request-size>`).
  - Add a `<servlet-mapping>` with `<url-pattern>/api/import/obsidian/*</url-pattern>` after the `/api/ingest` mapping (~line 950–952).
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` `handleGetUser` (~line 243–275): after `roles`, add `result.put( "canCreatePages", session.isAuthenticated() && AuthSubsystemBridge.fromLegacyEngine( engine ).authorization().isPermitted( session, WikiPermission.CREATE_PAGES ) );`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/ObsidianImportResourceTest.java`, add one case to `AuthResourceTest.java`

**Interfaces:**
- Consumes: `VaultImportService`, `ImportJobRegistry`, `ImportOptions`, `SpooledUpload`, `PlanResult`, `JobView`, the exceptions (Task 8).
- Produces (HTTP contract the frontend and IT rely on):
  - `POST /api/import/obsidian/plan` (multipart `file`, `clusterMode`, `cluster`) → 200 `ImportPlan` JSON.
  - `POST /api/import/obsidian/apply` (+ `planHash`) → 202 `{"jobId": "…"}`.
  - `GET /api/import/obsidian/jobs/{id}` and `GET /api/import/obsidian/jobs/current` → 200 `JobView` JSON or 404.
  - `GET /api/auth/user` gains boolean `canCreatePages`.

Resource skeleton (keep each method small):
```java
public class ObsidianImportResource extends RestServletBase {
    private static final Logger LOG = LogManager.getLogger( ObsidianImportResource.class );
    private static final int SC_TOO_MANY_REQUESTS = 429;
    private transient ImportJobRegistry registry;
    private transient ImportLimits limits = ImportLimits.defaults();

    @Override public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        if ( getEngine() != null ) { limits = ImportLimits.fromProperties( getEngine().getWikiProperties() ); }
        registry = newRegistry( limits );
    }
    @Override public void destroy() { if ( registry != null ) { registry.close(); } super.destroy(); }

    protected ImportJobRegistry newRegistry( final ImportLimits l ) { return ImportJobRegistry.create( l.maxConcurrent() ); }   // test seam
    protected VaultImportService importService() { return VaultImportService.fromSubsystems( getEngine(), getSubsystems() ); } // test seam
    protected boolean canCreatePages( final Session s ) { return authz().checkPermission( s, WikiPermission.CREATE_PAGES ); }
    protected boolean isAdmin( final Session s ) { return authz().isPermitted( s, new AllPermission( getEngine().getApplicationName() ) ); }
    ImportJobRegistry registry() { return registry; }   // package-private for tests

    @Override protected void doPost( req, resp ) {
        final Session s = gate( req, resp ); if ( s == null ) return;
        switch ( path( req ) ) { case "/plan" -> plan( req, resp ); case "/apply" -> apply( req, resp, s ); default -> sendNotFound( resp, "Unknown import path" ); }
    }
    @Override protected void doGet( req, resp ) {
        final Session s = gate( req, resp ); if ( s == null ) return;
        final String p = path( req );
        if ( "/jobs/current".equals( p ) ) currentJob( resp, s );
        else if ( p.startsWith( "/jobs/" ) ) job( resp, s, p.substring( 6 ) );
        else sendNotFound( resp, "Unknown import path" );
    }
}
```
- `gate`: not authenticated → 401 `"Login required to import"`. `!canCreatePages` → 403 `"Forbidden: createPages permission required"`. Returns the session or `null`.
- `readUpload( req, resp )`:
  - The content type must start with `multipart/` (case-insensitive), else 415 `"Vault import requires a multipart/form-data request"`.
  - `getPart( "file" )` null → 400.
  - `part.getSize() > limits.maxUploadBytes()` → 413 naming `wikantik.import.maxUploadBytes`.
  - Spool with `SpooledUpload.spool( part.getInputStream(), DerivedIngestResource.sanitizeFilename( part.getSubmittedFileName() ), limits.maxUploadBytes() )` (same package; `sanitizeFilename` is package-private static).
  - `ImportOptions.parse( req.getParameter( "clusterMode" ), req.getParameter( "cluster" ) )` (IAE → 400).
- `plan`: `try { sendJson( resp, service.plan( up, opts ).plan() ); } catch … finally { up.delete(); }`.
- `apply`:
  1. `registry.ensureCanStart( owner )` **before** spooling (cheap 409/429).
  2. Spool and plan.
  3. If `!plan.plan().planHash().equals( req.getParameter( "planHash" ) )`, delete the upload and return 409 `"The vault or the wiki changed since the plan was made; review the new plan"`.
  4. `registry.start( owner, id -> service.newJob( id, owner, author, up, plan, () -> canCreatePages( s ) ) )`.
  5. `resp.setStatus( HttpServletResponse.SC_ACCEPTED )`, then `sendJson( resp, Map.of( "jobId", job.id() ) )`. `RestJson.sendJson` only sets the content type and writes, so the 202 survives.
  6. If anything fails before `start` returns, delete the upload.
  - `owner = session.getLoginPrincipal().getName()`; `author = session.getUserPrincipal().getName()`.
- `job`: `registry.find( id )`. Absent → 404. Not the owner and `!isAdmin` → 403 `"Forbidden: not your import job"`. Otherwise `sendJson( resp, job.view() )`.
- `currentJob`: `registry.current( owner )` → view or 404 `"No recent import job"`.
- **One failure mapper** `fail( resp, Exception e )`:
  - `VaultArchiveException` / `IllegalArgumentException` → 400.
  - `ImportLimitException` → 413.
  - `ImportJobConflictException` → 409 for `USER_RUNNING`, 429 for `CAPACITY`.
  - `ProviderException` / `IOException` / `ServletException` → `LOG.warn( "Obsidian import failed: {}", e.getMessage(), e )` + 500 `"Import failed: " + e.getMessage()`.
  - Every send goes through `sendError`.

- [ ] **Step 1: Write failing tests.** Model the test on `ExportResourceTest`: `TestEngine`, `HttpMockFactory`, a `loginAdmin()`-style helper parameterised by user, and a servlet subclass overriding `newRegistry` (queued executor, as in Task 8) and optionally `canCreatePages`/`isAdmin`. The multipart request is a `HttpMockFactory.createHttpRequest( "/api/import/obsidian/plan" )` with `doReturn( "/plan" ).when( req ).getPathInfo()`, `doReturn( "multipart/form-data; boundary=x" ).when( req ).getContentType()`, and a Mockito `Part` returning `new ByteArrayInputStream( zip )`, `zip.length` and `"v.zip"`; parameters via `doReturn`.
```java
@Test void anonymousIs401() { /* POST /plan without login -> 401 */ }
@Test void noCreatePagesIs403() { /* override canCreatePages=false -> 403 on POST /plan and GET /jobs/current */ }
@Test void notMultipartIs415() {}
@Test void planReturnsPlanJson() { /* admin, zip {A.md,"# A"} -> 200, body has "planHash" and "pagesNew":1 */ }
@Test void unsafeEntryIs400NamingEntry() { /* zip {"../x.md"} -> 400, message contains "../x.md" */ }
@Test void overUploadLimitIs413() { /* Part.getSize() = 104857601 -> 413 contains "wikantik.import.maxUploadBytes" */ }
@Test void tooManyNotesIs413() { /* a SEPARATE engine TestEngine.build( Map.entry( "wikantik.import.maxPages", "1" ) ) (stopped in finally),
                                    servlet init'ed against it, zip with 2 notes -> 413 containing "wikantik.import.maxPages" */ }
@Test void applyWithStaleHashIs409() { /* Review Focus 4: planHash "nope" -> 409; no job registered */ }
@Test void applyStartsJobAnd202() { /* plan, then apply with returned hash -> 202, body {"jobId":…}; registry().find(id) present */ }
@Test void secondApplyWhileRunningIs409() { /* queued executor keeps job RUNNING; second apply -> 409 */ }
@Test void otherUsersApplyAtCapacityIs429() { /* registry().start("Bob", ImportTestJobs::job…) then admin apply -> 429 */ }
@Test void jobReadableOnlyByOwnerOrAdmin() {
    /* registry().start("Bob", id -> ImportTestJobs.job(id,"Bob")); login Alice, isAdmin=false -> GET /jobs/{id} 403;
       isAdmin=true -> 200; unknown id -> 404 */ }
@Test void currentJobIs404WhenNone() {}
// AuthResourceTest: admin GET user -> "canCreatePages": true; anonymous -> false
```
- [ ] **Step 2: Run red.** `mvn install -DskipTests -pl wikantik-main -q && mvn test -pl wikantik-rest -Dtest='ObsidianImportResourceTest,AuthResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** the resource, the web.xml blocks and the AuthResource line.
- [ ] **Step 4: Run green** (same command) + `mvn pmd:check -Pcomplexity-gate -pl wikantik-rest`. A web.xml smoke check: `grep -a -n "ObsidianImportResource" wikantik-war/src/main/webapp/WEB-INF/web.xml` shows both the servlet and its mapping.
- [ ] **Step 5: Commit.** Message: `feat(import): REST endpoints for Obsidian vault plan, apply and job status` + trailer.

---

### Task 10: Frontend — `ImportDialog`, client, entry points

**Files:**
- Create: `wikantik-frontend/src/components/ImportDialog.jsx`, `ImportDialog.test.jsx`
- Modify: `wikantik-frontend/src/api/client.js` — add `importVault` next to `exportVault` (~line 131)
- Modify: `wikantik-frontend/src/components/PersonalZone.jsx` — new prop `onImport`; button `data-testid="personal-import"` "Import Obsidian vault…" after the export button (~line 65–71), shown only when `user.canCreatePages`
- Modify: `wikantik-frontend/src/components/Sidebar.jsx` — `importOpen` state; `onImport={() => setImportOpen(true)}`; render `<ImportDialog isOpen={importOpen} onClose={() => setImportOpen(false)} />` beside `ExportDialog` (~line 287); register the palette command with `useRegisterCommands` when `user?.canCreatePages`
- Modify: `wikantik-frontend/src/styles/globals.css` — append `.import-dialog-*` rules after the `.export-dialog-*` block (~line 2084–2115)
- Test: `ImportDialog.test.jsx`; extend `PersonalZone.test.jsx`; add a `client.test.js` case

**Interfaces:**
- Consumes: the HTTP contract from Task 9 (`ImportPlan`: `planHash`, `totals.{pagesNew,pagesSkippedExisting,pagesSkippedReserved,pagesFailing,attachments,attachmentsBlocked,attachmentsSkipped,clustersCreate,clustersJoin,hubsCreate}`, `pages[].{vaultPath,name,status,reason,warnings}`, `clusters[].{cluster,action,hubPage}`, `warningGroups`; `JobView`: `jobId,state,done,total,current,results[].{kind,name,status,reason},summary.{hubs}`, `message`).
- Produces: `api.importVault.{plan, apply, job, current}`.

Client (multipart must keep `status`/`body` on errors like `request()` does):
```js
async function postMultipart(path, form) {
  const resp = await fetch(`${BASE}${path}`, { method: 'POST', body: form, credentials: 'same-origin', headers: { Accept: 'application/json' } });
  const body = await resp.json().catch(() => ({ message: resp.statusText }));
  if (!resp.ok) throw Object.assign(new Error(body.message || resp.statusText), { status: resp.status, body });
  return body;
}
function vaultForm(file, { clusterMode = 'folders', cluster = '' } = {}, planHash) {
  const form = new FormData();
  form.append('file', file);
  form.append('clusterMode', clusterMode);
  if (clusterMode === 'fixed' && cluster) form.append('cluster', cluster);
  if (planHash) form.append('planHash', planHash);
  return form;
}
// inside api:
importVault: {
  plan: (file, opts) => postMultipart('/api/import/obsidian/plan', vaultForm(file, opts)),
  apply: (file, opts, planHash) => postMultipart('/api/import/obsidian/apply', vaultForm(file, opts, planHash)),
  job: (id) => request(`/api/import/obsidian/jobs/${encodeURIComponent(id)}`),
  current: () => request('/api/import/obsidian/jobs/current'),
},
```

`ImportDialog` behaviour (reuse `Modal`, `Select`, `Spinner`, `EmptyState` from `components/ui/`):
- `phase`: `'choose' | 'planning' | 'plan' | 'running' | 'result'`.
- **On open:** call `api.importVault.current()`. On success → `setJob(data)` and phase `running` (if `state === 'RUNNING'`) or `result`. On a 404 → `choose`. Other errors → error banner and `choose`.
- **File input** `data-testid="import-file"` (`accept=".zip,application/zip"`). On change → `runPlan(file, options)`.
- **Option** `data-testid="import-cluster-mode"`: a radio group with `folders` (default, "Folders become clusters"), `fixed` ("Put everything in cluster…") and `none` ("No clusters").
  - When `fixed` is chosen, load `api.listClusters()` once (`res.clusters.map(c => c.name)`) into a `Select` (`data-testid="import-cluster"`).
  - Changing the mode re-plans immediately, except `fixed` waits until a cluster is chosen.
- **Plan view:**
  - `data-testid="import-totals"` shows the lines "N new · N already exist · N reserved · N will fail", "N attachments (N blocked, N unreferenced)" and "Clusters: N new hubs, N joined".
  - Filter input `data-testid="import-filter"` filters `pages` by vault path or name (case-insensitive).
  - Table `data-testid="import-pages"` with columns Vault path / Page / Status / Notes (`reason` or `warnings.join('; ')`). Show the first 200 rows plus "… N more".
  - Grouped warnings list from `warningGroups` (`data-testid="import-warnings"`).
- **Import button** `data-testid="import-apply"` "Import N pages":
  - Disabled when `totals.pagesNew === 0` or while planning.
  - Calls `api.importVault.apply(file, options, plan.planHash)` → `{jobId}` → phase `running`.
  - A 409 whose message contains "changed since" → set the error and re-run the plan automatically. A 409 "already running" → fetch `current()` and show that job. 413/429/400 → error banner.
- **Polling** while `running`: `setInterval(() => api.importVault.job(id)…, 1000)`, cleared on unmount, on close and when the state is not RUNNING. Use a `useEffect` keyed on `[phase, jobId]` with cleanup, and promise-chain style (memory: async/await inside effects trips set-state-in-effect).
  - Progress `data-testid="import-progress"`: `<progress value={done} max={total}>` + "done / total — current".
- **Result** `data-testid="import-result"`:
  - The created pages (`results.filter(r => r.kind==='page' && r.status==='CREATED')`) as `<Link to={`/wiki/${encodeURIComponent(name)}`}>`.
  - Skipped pages (`SKIPPED_*`) with their reason, and failed items with their reason.
  - If `summary.hubs?.length`, an "Open imported hub" link (`data-testid="import-open-hub"`) to the first hub.
  - A FAILED state shows `job.message`.
- **Close** never cancels the job. Reopening shows it again via `current()`.

Palette command in `Sidebar.jsx`:
```js
const importCommands = useMemo(() => (user?.canCreatePages
  ? [{ id: 'import-vault', title: 'Import Obsidian vault…', section: 'Page', run: () => setImportOpen(true) }]
  : []), [user?.canCreatePages]);
useRegisterCommands(importCommands, [importCommands]);
```

- [ ] **Step 1: Write failing tests** (`ImportDialog.test.jsx`, `vi.mock('../api/client', …)` as `ExportDialog.test.jsx` does, wrapped in `MemoryRouter`):
```js
const PLAN = { planHash: 'h1', totals: { pagesNew: 2, pagesSkippedExisting: 1, pagesSkippedReserved: 0, pagesFailing: 0,
  attachments: 1, attachmentsBlocked: 0, attachmentsSkipped: 3, clustersCreate: 1, clustersJoin: 0, hubsCreate: 1 },
  pages: [{ vaultPath: 'Projects/Alpha.md', name: 'Alpha', status: 'NEW', warnings: [] },
          { vaultPath: 'Main.md', name: 'Main', status: 'SKIPPED_EXISTS', reason: 'page exists', warnings: [] }],
  clusters: [{ cluster: 'projects', action: 'CREATE', hubPage: 'Projects' }], warningGroups: { link: 2 } };
it('plans, applies, polls and shows the result', async () => {
  api.importVault.current.mockRejectedValue(Object.assign(new Error('none'), { status: 404 }));
  api.importVault.plan.mockResolvedValue(PLAN);
  api.importVault.apply.mockResolvedValue({ jobId: 'j1' });
  api.importVault.job.mockResolvedValueOnce({ jobId: 'j1', state: 'RUNNING', done: 1, total: 3, current: 'Alpha', results: [], summary: {} })
    .mockResolvedValue({ jobId: 'j1', state: 'DONE', done: 3, total: 3, results: [{ kind: 'page', name: 'Alpha', status: 'CREATED' }], summary: { hubs: ['Projects'] } });
  vi.useFakeTimers({ shouldAdvanceTime: true });
  render(<MemoryRouter><ImportDialog isOpen onClose={() => {}} /></MemoryRouter>);
  const file = new File(['zip'], 'v.zip', { type: 'application/zip' });
  fireEvent.change(await screen.findByTestId('import-file'), { target: { files: [file] } });
  expect(await screen.findByTestId('import-totals')).toHaveTextContent('2 new');
  fireEvent.click(screen.getByTestId('import-apply'));
  await waitFor(() => expect(api.importVault.apply).toHaveBeenCalledWith(file, { clusterMode: 'folders', cluster: '' }, 'h1'));
  await act(async () => { vi.advanceTimersByTime(2100); });
  expect(await screen.findByTestId('import-result')).toHaveTextContent('Alpha');
  expect(screen.getByTestId('import-open-hub')).toHaveAttribute('href', '/wiki/Projects');
  vi.useRealTimers();
});
it('re-plans when the cluster option changes', async () => { /* click 'none' radio -> plan called again with {clusterMode:'none'} */ });
it('reopening shows the running job instead of the picker', async () => { /* current() resolves RUNNING job -> import-progress visible, no import-file */ });
it('a stale-hash 409 re-plans and shows the message', async () => { /* apply rejects {status:409,message:'The vault or the wiki changed since…'} -> plan called twice, error banner text */ });
it('filters the page table', async () => { /* type 'alpha' in import-filter -> only Alpha row */ });
```
  `PersonalZone.test.jsx`: the import button is rendered only when `canCreatePages` is true. `client.test.js`: `importVault.plan` posts FormData with `clusterMode`, and a non-OK response rejects with `status`.
- [ ] **Step 2: Run red.** `cd wikantik-frontend && npx vitest run src/components/ImportDialog.test.jsx src/components/PersonalZone.test.jsx src/api/client.test.js`
- [ ] **Step 3: Implement.** **Step 4: Run green** + `npm run lint` (0 problems) + `npx vitest run` (full suite; per memory, re-run a lone flaky file before suspecting a regression).
- [ ] **Step 5: Commit** (the files above by name). Message: `feat(frontend): Import Obsidian vault dialog with plan review and job progress` + trailer.

---

### Task 11: End-to-end IT (`ObsidianImportIT`)

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ObsidianImportIT.java`

**Interfaces:** Consumes the HTTP contract of Task 9 and the native-wikilinks backlink behaviour (`/api/backlinks/{page}` lists `[[ ]]` referrers).

Model on `DerivedIngestIT`: the `secureCookieOverHttp()` cookie handler, `it-wikantik.base.url`, admin login `janne`/`myP@5sw0rd`, a hand-built multipart body (add text parts for `clusterMode` and `planHash` with `Content-Disposition: form-data; name="clusterMode"`), and best-effort `@AfterAll` DELETE of every created page.

Vault built in code with `java.util.zip.ZipOutputStream`. `U` = 8 hex chars of a random UUID. The page names are unique per run.
```
Imp<U>/Imp<U>.md          "# Folder hub <U>\n"                         -> hub of cluster "imp<u>"
Imp<U>/Target <U>.md      "# Target\n"
Imp<U>/Source <U>.md      "# Source\nSee [[Target <U>]].\n![[pic<U>.png]]\n"
Imp<U>/pic<U>.png         8-byte PNG signature
```
- [ ] **Step 1: Write the test** (ordered):
  1. `POST /api/import/obsidian/plan` (clusterMode=folders) → 200. Assert `totals.pagesNew == 3` and `totals.attachments == 1`, and keep `planHash`.
  2. `POST /api/import/obsidian/apply` with the hash → 202 `jobId`.
  3. Poll `GET /api/import/obsidian/jobs/{jobId}` every 500 ms for up to 60 s until `state != RUNNING`. Assert `DONE` and `summary.pagesCreated == 3`.
  4. `GET /api/pages/Source%20<U>` → 200, and the content contains `[[Target <U>]]`.
  5. Poll `GET /api/backlinks/Target%20<U>` for up to 10 s until `backlinks` contains `"Source <U>"`.
  6. `GET /api/attachments/Source%20<U>` → `attachments[].name` (or `fileName`) includes `pic<U>.png`.
  7. `GET /api/pages/Imp<U>` frontmatter has `type: hub` and `cluster: imp<u>`. Read the page JSON's metadata the same way `ExportIT` asserts frontmatter. Check the field name with `grep -a -n "metadata\|frontmatter" wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/its/rest/ExportIT.java`.
  8. Apply again with the **old** hash → 409, because the planned names now exist and the plan hash changed.
- [ ] **Step 2: Build and run.** The WAR must contain Tasks 1–10, so first `bin/agent-build.sh start war -- mvn clean install -DskipTests -T 1C`, polling `bin/agent-build.sh status war` until SUCCESS. Then `bin/agent-build.sh start itrest -- bin/run-tests.sh --module rest`, polling `status itrest` / `wait itrest 540` until it terminates. Expected: `ObsidianImportIT` passes. On failure, `bin/agent-build.sh tail itrest 80`, then follow superpowers:systematic-debugging.
- [ ] **Step 3: Commit.** Message: `test(import): end-to-end Obsidian vault import IT` + trailer.

---

### Task 12: Docs, counts, CHANGELOG, final gates

**Files:**
- Modify: `CLAUDE.md` `/api/*` row (~line 405) — servlet and url-pattern counts plus a mention of `POST /api/import/obsidian/{plan,apply}` + `GET …/jobs/*` (`createPages`-gated)
- Modify: `CHANGELOG.md` under `## [Unreleased]` (line 7)

- [ ] **Step 1: Re-derive the counts** from web.xml. Do not hand-add +1: the native-wikilinks work may already have changed them.
```bash
python3 - <<'EOF'
import xml.etree.ElementTree as ET
ns={'j':'https://jakarta.ee/xml/ns/jakartaee'}
r=ET.parse('wikantik-war/src/main/webapp/WEB-INF/web.xml').getroot()
tag=lambda e,t: e.findall('j:'+t,ns) or e.findall(t)
names=set(); pats=0
for m in tag(r,'servlet-mapping'):
    ps=[p.text for p in tag(m,'url-pattern') if p.text.startswith('/api/')]
    if ps: names.add(tag(m,'servlet-name')[0].text); pats+=len(ps)
print(len(names),'servlets',pats,'url-patterns')
EOF
```
  Update the row text ("N distinct servlets (M url-patterns — …)"). Keep the existing parenthetical about which servlets carry two patterns accurate. Add: `` `POST /api/import/obsidian/{plan,apply}` + `GET /api/import/obsidian/jobs/{id|current}` = Obsidian vault import (plan-hash-confirmed, async in-memory job), `createPages`-gated ``.
- [ ] **Step 2: CHANGELOG** under `## [Unreleased]`:
```markdown
### Added
- Obsidian vault import: upload a zipped vault, review a dry-run plan (new / existing / reserved / failing pages,
  attachments, clusters and hubs to create or join), then import it as a background job with per-page results.
  Folders become clusters (or one chosen cluster, or none); links are kept as native `[[ ]]` and re-targeted only
  where a page name had to change; referenced attachments are imported, unreferenced and blocked ones reported.
  `POST /api/import/obsidian/{plan,apply}`, `GET /api/import/obsidian/jobs/{id|current}` (createPages), config
  `wikantik.import.{maxUploadBytes,maxUncompressedBytes,maxEntries,maxPages,maxConcurrent}`. No schema change.
```
  If an `### Added` heading already exists under `[Unreleased]`, append the bullet to it instead.
- [ ] **Step 3: Gates** (each must end in success; poll the long ones with `bin/agent-build.sh status`/`wait`):
```bash
bin/config-reference.sh --write && git status --short   # expect no diff (already regenerated in Task 1)
mvn pmd:check -Pcomplexity-gate -pl wikantik-main,wikantik-rest
(cd wikantik-frontend && npm run lint && npx vitest run)
bin/agent-build.sh start unit -- mvn clean install -DskipITs -Pcoverage   # coverage floors (wikantik-main, wikantik-rest)
bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4            # canonical pre-commit gate
```
  Any red gate is fixed in this session (never deferred as "pre-existing"). If a coverage floor fails, add tests for the uncovered importer branches. Never lower a floor.
- [ ] **Step 4: Commit.**
```bash
git add CLAUDE.md CHANGELOG.md
git commit -m "docs(import): CLAUDE.md API counts and CHANGELOG for Obsidian vault import" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0116wAS9b7XPfXDLMyHmLmAg"
```
- [ ] **Step 5:** Run superpowers:requesting-code-review over the branch range for Tasks 1–12. Give the reviewer the spec, this plan's Rulings, and the five Review Focus items.

---

## Spec coverage map (self-review)

| Spec | Task |
|---|---|
| §2 sidebar item, palette `import-vault`, createPages-gated; dialog plan/options/re-plan/apply/progress/result/reopen | 9 (`canCreatePages`), 10 |
| §3 endpoints, 401/403, planHash, 202/409/429, job ownership, current ≤ 1 h, multipart-config 209715200, CLAUDE.md count | 9, 8 (registry), 12 |
| §3 limits + 413 | 1, 2, 7, 9 |
| §4 zip safety, ignored paths, wrapper strip, md-only buffering | 2 |
| §5.1 names, collisions, existing, reserved | 3, 7 |
| §5.2 frontmatter rules, malformed YAML, validator → WILL_FAIL | 4, 7 |
| §5.3 clusters, folder notes, generated hubs, depth folding, fixed/none | 6, 7 |
| §5.4 body rewriting incl. md links, block refs, comments, code fences | 5, 7 |
| §5.5 attachments: referenced only, owner, blocked/policy | 7, 8 |
| §6 apply order, changeNote, never aborts, skip-not-overwrite, createPages once, LOG.warn | 8 |
| §7 error codes | 9 |
| §8 tests incl. fixture vault and IT | 2–11 |
| §9 review focus | Review Focus section → Tasks 2, 3, 7, 8, 9 |
