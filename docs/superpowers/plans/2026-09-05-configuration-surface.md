# Configuration Surface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ini/wikantik.properties` (and `wikantik-mcp.properties`) a complete, machine-verified, explicitly-defaulted reference for every configuration key the code reads, and generate the admin documentation from it.

**Architecture:** A small parser in `wikantik-util` reads the structured comment convention. A source-scanning drift test in `wikantik-war` (next to `TestSchemaSingleSourceTest`) compares code literals against the file and burns down a baseline TSV, PMD-ratchet style. A CLI in `wikantik-extract-cli` (same shape as `GenerateMainPageCli`) renders `docs/ConfigurationReference.md` and a wiki page from the file, guarded by a regression test. Six content tasks then promote, add, or delete keys subsystem by subsystem until the baseline is empty.

**Tech Stack:** Java 25, JUnit 5, Maven, `java.util.Properties`, Mustache (already used by `GenerateMainPageCli`), bash.

**Spec:** `docs/superpowers/specs/2026-09-05-configuration-surface-design.md`

## Global Constraints

- TDD: a failing test first for every code change (CLAUDE.md).
- Never leave a red gate. The drift test lands green with a baseline; each content task removes baseline lines *before* editing the file so the red/green cycle is real.
- Never `git add -A`. Stage files by name. Commit messages 1-3 lines, ending with the attribution trailer from the session reminder.
- No empty catch blocks; log `LOG.warn()` with context.
- `Type: secret` entries must be blank in the defaults file.
- Blank value ⇒ `Blank means:` line, and every reader of that key must treat `""` as unset (`isBlank()`), with a unit test.
- Repo-style Java: spaces inside parentheses (`foo( bar )`), `final` on locals and params, 4-space indent.
- Long Maven runs go through `bin/agent-build.sh` (CLAUDE.md). Single-module runs (`mvn -pl wikantik-war test -Dtest=…`) are fine in the foreground.
- Do not change `PropertyReader` or `TextUtil` precedence semantics. Document them as they are.
- Do not touch `wikantik-*/src/test/resources/**/wikantik.properties` fixtures.
- **Wiring facts to rely on:** `wikantik-war` test scope already depends on `wikantik-extract-cli`, `wikantik-main`, and ArchUnit. `wikantik-extract-cli` depends on `wikantik-main` (hence `wikantik-util`). `wikantik-war` tests locate the repo root by walking up from `user.dir` until `bin/db/migrations` exists (copy `TestSchemaSingleSourceTest.repoRoot()`).

---

## File Structure

| Path | Responsibility |
|---|---|
| `wikantik-util/src/main/java/com/wikantik/util/config/ConfigReference.java` | Parse a properties file into structured entries (key, value, description, type, blank-means, source, section, line). Report commented-out keys and duplicates. Pure function of the lines. |
| `wikantik-util/src/test/java/com/wikantik/util/config/ConfigReferenceTest.java` | Parser tests. |
| `wikantik-war/src/test/java/com/wikantik/architecture/ConfigSurfaceDriftTest.java` | Scans production source for key literals, parses both properties files, computes violations, compares against the baseline. |
| `build-support/config-surface-baseline.tsv` | Burn-down baseline: `key<TAB>violation`. Only ever shrinks. Deleted in Task 12. |
| `bin/config-inventory.sh` | Developer aid: lists every key literal with status and, where literal, the code default. |
| `wikantik-main/src/main/resources/ini/wikantik.properties` | The reference. Gains `# [Section]` markers, `Type:` lines, all missing keys. Loses commented-out and dead keys. |
| `wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties` | Same treatment for `mcp.*`. |
| `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/configref/GenerateConfigReferenceCli.java` | `--write` / `--check` renderer for the two markdown outputs. |
| `wikantik-extract-cli/src/main/resources/ConfigurationReference.md.mustache` | Template for `docs/ConfigurationReference.md`. |
| `wikantik-extract-cli/src/main/resources/WikantikConfigurationReference.md.mustache` | Template for the wiki page (frontmatter + same body). |
| `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/configref/GenerateConfigReferenceCliTest.java` | Renderer unit tests against an in-memory file. |
| `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/configref/ConfigReferenceRegressionTest.java` | Committed markdown matches generated output (mirrors `MainPageRegressionTest`). |
| `bin/config-reference.sh` | Wrapper over the CLI jar, same shape as `bin/kg-policy.sh`. |
| `docs/ConfigurationReference.md`, `docs/wikantik-pages/WikantikConfigurationReference.md` | Generated. Never hand-edited. |
| `README.md`, `CLAUDE.md`, `docs/ProjectReference.md`, `docs/DockerDeployment.md` | Pointers, the "adding a key" rule, and a reconciled env-var table. |

---

## Reference data (measured 2026-09-05, reproduce with `bin/config-inventory.sh` after Task 2)

**Comment convention every entry must follow (from the spec):**

```
#
#  Description line(s). What it does, when to change it, units.
#  Type: boolean
#  Blank means: <only when the value is blank>
#  Source: system-property   <only when not read from this file>
wikantik.some.key = value
```

Section marker: a line `# [Section Name]`. Everything after it until the next marker belongs to that section.

**Literals that are NOT config keys** (seed for `NOT_CONFIG`; the executor of Task 3 verifies each with the grep shown in the task):

| Literal | Reason |
|---|---|
| `wikantik.page.views`, `wikantik.page.edits`, `wikantik.page.deletes`, `wikantik.auth.logins`, `wikantik.kg_judge.timeouts`, `wikantik.kg_judge.short_circuit_total`, `wikantik.kg_judge.timeout_multiplier_applied`, `wikantik.insights.ingest.rows`, `wikantik.insights.ingest.sites`, `wikantik.insights.ingest.engines`, `wikantik.insights.ingest.last_success_timestamp` | Micrometer metric names |
| `wikantik.apikey.record`, `wikantik.context` | request/servlet attribute names |
| `wikantik.runFilters` | `Context` variable name |
| `wikantik.policy` | policy **file** name (`DEFAULT_POLICY`) |
| `wikantik.custom.config` | servlet init-param name, documented in the precedence section |
| `wikantik.ingest.truncated` | response-body marker in `/api/ingest` |

**Dynamic prefixes** (seed for `DYNAMIC_PREFIXES`; file keys under these are accepted as examples, and must still carry a description):
`wikantik.interWikiRef.`, `wikantik.specialPage.`, `wikantik.loginModule.options.`, `wikantik.sso.claimMapping.`, `wikantik.translatorReader.inlinePattern.`, `wikantik.custom.cascade.`, `wikantik.connectors.` (per-connector `<name>.` settings), `wikantik.knowledge.extractor.`, `wikantik.bundle.reranker.`, `wikantik.bundle.decomposition.`, `wikantik.briefing.`, `wikantik.search.hybrid.embedder.`, `wikantik.tools.`.

**Known deliberate divergence:** `wikantik.search.dense.backend` = `lucene-hnsw` in `SearchWiringHelper.resolveDenseBackend` (production path) and `inmemory` in `SearchSubsystemFactory.resolveFallbackDenseBackend` (no-DataSource fallback), pinned by `DenseBackendResolutionTest`. The file declares `lucene-hnsw`.

**Reads with no default at all (blank-means audit candidates, Tasks 6–10):**
`McpToolRegistry.java:89` (`wikantik.indexnow.apiKey`), `KgJudgeConfig.java:59-63` (`wikantik.knowledge.extractor.ollama.base_url|endpoint|model`), `BundleServiceWiring.java:238` (`wikantik.bundle.sections_per_page`), `BundleServiceWiring.java:281` (`wikantik.bundle.rerank.chain`), `OntologyWiringHelper.java:160` (`wikantik.ontology.tdb2.dir`, already `isBlank`-safe), `DefaultUserManager.java:291` (`wikantik.admin.notification.email`), `ScimAccessFilter.java:46` (`wikantik.scim.token`, system property only).

---

### Task 1: `ConfigReference` parser (wikantik-util)

**Files:**
- Create: `wikantik-util/src/main/java/com/wikantik/util/config/ConfigReference.java`
- Test: `wikantik-util/src/test/java/com/wikantik/util/config/ConfigReferenceTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class ConfigReference {
      public record Entry( String key, String value, List<String> description, String type,
                           String blankMeans, String source, String section, int line ) {
          public boolean isBlank();            // value.isBlank()
          public boolean hasType();            // type != null && !type.isBlank()
          public boolean hasDescription();     // !description.isEmpty()
          public String envOverrideName();     // key.replace( '.', '_' )  (exact case, per TextUtil)
      }
      public record Parsed( List<Entry> entries, List<String> commentedOutKeys, List<String> duplicateKeys ) {
          public Optional<Entry> entry( String key );
          public Set<String> keys();
      }
      public static Parsed parse( List<String> lines, String keyPrefix );   // keyPrefix e.g. "wikantik." or "mcp."
      public static Parsed parse( Path file, String keyPrefix ) throws IOException;
  }
  ```

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.util.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigReferenceTest {

    private static final List<String> SAMPLE = List.of(
        "# [Ontology]",
        "#",
        "#  Master switch for the RDF/OWL ontology layer.",
        "#  Off = no TDB2 store is opened.",
        "#  Type: boolean",
        "wikantik.ontology.enabled = true",
        "",
        "#  Directory for the TDB2 store.",
        "#  Type: path",
        "#  Blank means: ${wikantik.workDir}/ontology-tdb2",
        "wikantik.ontology.tdb2.dir =",
        "",
        "#  Bearer token for SCIM.",
        "#  Type: secret",
        "#  Source: system-property",
        "wikantik.scim.token =",
        "",
        "#wikantik.old.commented = 3",
        "# wikantik.other.commented=x",
        "wikantik.no.description = 7",
        "wikantik.ontology.enabled = false"
    );

    @Test
    void parses_description_type_and_section() {
        final ConfigReference.Parsed p = ConfigReference.parse( SAMPLE, "wikantik." );
        final ConfigReference.Entry e = p.entry( "wikantik.ontology.enabled" ).orElseThrow();
        assertEquals( "true", e.value() );
        assertEquals( List.of( "Master switch for the RDF/OWL ontology layer.", "Off = no TDB2 store is opened." ), e.description() );
        assertEquals( "boolean", e.type() );
        assertEquals( "Ontology", e.section() );
        assertEquals( 6, e.line() );
        assertNull( e.blankMeans() );
        assertEquals( "properties", e.source() );
    }

    @Test
    void blank_value_and_directives() {
        final ConfigReference.Parsed p = ConfigReference.parse( SAMPLE, "wikantik." );
        final ConfigReference.Entry dir = p.entry( "wikantik.ontology.tdb2.dir" ).orElseThrow();
        assertTrue( dir.isBlank() );
        assertEquals( "${wikantik.workDir}/ontology-tdb2", dir.blankMeans() );
        final ConfigReference.Entry tok = p.entry( "wikantik.scim.token" ).orElseThrow();
        assertEquals( "system-property", tok.source() );
        assertEquals( "secret", tok.type() );
    }

    @Test
    void reports_commented_out_and_duplicate_keys() {
        final ConfigReference.Parsed p = ConfigReference.parse( SAMPLE, "wikantik." );
        assertEquals( List.of( "wikantik.old.commented", "wikantik.other.commented" ), p.commentedOutKeys() );
        assertEquals( List.of( "wikantik.ontology.enabled" ), p.duplicateKeys() );
    }

    @Test
    void entry_without_comment_block_has_no_description_or_type() {
        final ConfigReference.Entry e = ConfigReference.parse( SAMPLE, "wikantik." ).entry( "wikantik.no.description" ).orElseThrow();
        assertFalse( e.hasDescription() );
        assertFalse( e.hasType() );
    }

    @Test
    void comment_block_must_be_adjacent_to_the_key() {
        final List<String> lines = List.of( "#  Orphan comment.", "#  Type: int", "", "wikantik.x = 1" );
        final ConfigReference.Entry e = ConfigReference.parse( lines, "wikantik." ).entry( "wikantik.x" ).orElseThrow();
        assertFalse( e.hasDescription(), "a blank line breaks the block" );
    }

    @Test
    void env_override_name_keeps_case() {
        final ConfigReference.Entry e = ConfigReference.parse( List.of( "wikantik.baseURL = x" ), "wikantik." ).entry( "wikantik.baseURL" ).orElseThrow();
        assertEquals( "wikantik_baseURL", e.envOverrideName() );
    }

    @Test
    void ignores_keys_outside_prefix() {
        final ConfigReference.Parsed p = ConfigReference.parse( List.of( "log4j.rootLogger = INFO", "wikantik.a = 1" ), "wikantik." );
        assertEquals( 1, p.entries().size() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl wikantik-util test -Dtest=ConfigReferenceTest`
Expected: compilation failure, `ConfigReference` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.wikantik.util.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Wikantik defaults file ({@code ini/wikantik.properties},
 * {@code wikantik-mcp.properties}) into structured entries. The comment block
 * immediately above a key (no blank line in between) is that key's
 * documentation: free description lines plus the directives {@code Type:},
 * {@code Blank means:} and {@code Source:}. A {@code # [Section]} line opens a
 * section. See docs/superpowers/specs/2026-09-05-configuration-surface-design.md.
 */
public final class ConfigReference {

    private static final Pattern SECTION = Pattern.compile( "^#\\s*\\[(.+?)\\]\\s*$" );
    private static final Pattern KEY_VALUE = Pattern.compile( "^([A-Za-z0-9_.\\-]+)\\s*[=:]\\s*(.*)$" );
    private static final Pattern COMMENTED_KEY = Pattern.compile( "^#\\s*([A-Za-z0-9_.\\-]+)\\s*=.*$" );
    private static final Pattern DIRECTIVE = Pattern.compile( "^(Type|Blank means|Source):\\s*(.*)$" );

    private ConfigReference() {}

    public record Entry( String key, String value, List<String> description, String type,
                         String blankMeans, String source, String section, int line ) {
        public boolean isBlank() { return value.isBlank(); }
        public boolean hasType() { return type != null && !type.isBlank(); }
        public boolean hasDescription() { return !description.isEmpty(); }
        public String envOverrideName() { return key.replace( '.', '_' ); }
    }

    public record Parsed( List<Entry> entries, List<String> commentedOutKeys, List<String> duplicateKeys ) {
        public Optional<Entry> entry( final String key ) {
            return entries.stream().filter( e -> e.key().equals( key ) ).findFirst();
        }
        public Set<String> keys() {
            final Set<String> keys = new LinkedHashSet<>();
            entries.forEach( e -> keys.add( e.key() ) );
            return keys;
        }
    }

    public static Parsed parse( final Path file, final String keyPrefix ) throws IOException {
        return parse( Files.readAllLines( file, StandardCharsets.ISO_8859_1 ), keyPrefix );
    }

    public static Parsed parse( final List<String> lines, final String keyPrefix ) {
        final List<Entry> entries = new ArrayList<>();
        final List<String> commented = new ArrayList<>();
        final List<String> duplicates = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        final List<String> block = new ArrayList<>();   // comment lines since the last blank/non-comment line
        String section = null;

        for( int i = 0; i < lines.size(); i++ ) {
            final String raw = lines.get( i );
            final String line = raw.strip();
            if( line.isEmpty() ) {
                block.clear();
                continue;
            }
            final Matcher sec = SECTION.matcher( line );
            if( sec.matches() ) {
                section = sec.group( 1 ).strip();
                block.clear();
                continue;
            }
            if( line.startsWith( "#" ) || line.startsWith( "!" ) ) {
                final Matcher ck = COMMENTED_KEY.matcher( line );
                if( ck.matches() && ck.group( 1 ).startsWith( keyPrefix ) ) {
                    commented.add( ck.group( 1 ) );
                    block.clear();
                } else {
                    block.add( line.substring( 1 ).strip() );
                }
                continue;
            }
            final Matcher kv = KEY_VALUE.matcher( line );
            if( !kv.matches() ) {
                block.clear();
                continue;
            }
            final String key = kv.group( 1 );
            final String value = kv.group( 2 ).strip();
            if( key.startsWith( keyPrefix ) ) {
                if( !seen.add( key ) && !duplicates.contains( key ) ) {
                    duplicates.add( key );
                }
                entries.add( toEntry( key, value, block, section, i + 1 ) );
            }
            block.clear();
        }
        return new Parsed( List.copyOf( entries ), List.copyOf( commented ), List.copyOf( duplicates ) );
    }

    private static Entry toEntry( final String key, final String value, final List<String> block,
                                  final String section, final int line ) {
        final List<String> description = new ArrayList<>();
        String type = null;
        String blankMeans = null;
        String source = "properties";
        for( final String c : block ) {
            if( c.isEmpty() ) {
                continue;
            }
            final Matcher d = DIRECTIVE.matcher( c );
            if( d.matches() ) {
                final String v = d.group( 2 ).strip();
                switch( d.group( 1 ) ) {
                    case "Type" -> type = v;
                    case "Blank means" -> blankMeans = v;
                    default -> source = v;
                }
            } else {
                description.add( c );
            }
        }
        return new Entry( key, value, List.copyOf( description ), type, blankMeans, source, section, line );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl wikantik-util test -Dtest=ConfigReferenceTest`
Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-util/src/main/java/com/wikantik/util/config/ConfigReference.java wikantik-util/src/test/java/com/wikantik/util/config/ConfigReferenceTest.java docs/superpowers/specs/2026-09-05-configuration-surface-design.md docs/superpowers/plans/2026-09-05-configuration-surface.md
git commit -m "feat(config): ConfigReference parser for the structured defaults-file convention"
```

---

### Task 2: `bin/config-inventory.sh` developer aid

**Files:**
- Create: `bin/config-inventory.sh`

**Interfaces:**
- Produces a TSV on stdout: `key<TAB>status<TAB>refs<TAB>literal-defaults` where status ∈ `EXPLICIT|COMMENTED|MISSING` and `literal-defaults` is a `|`-joined list of literal default arguments found at `get*Property(…, "key", <literal>)` / `getProperty("key", <literal>)` call sites (empty when the default is a constant or absent).

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# config-inventory.sh — list every "wikantik.*" property literal in production
# Java, its status in ini/wikantik.properties, reference count, and any literal
# default(s) used at read sites.
#
# Usage: bin/config-inventory.sh [--missing|--commented|--explicit] [REPO_ROOT]
#   Output TSV: key  status  refs  literal-defaults
#
# This is a developer aid for the config-surface burn-down; the authoritative
# gate is ConfigSurfaceDriftTest (wikantik-war).
set -euo pipefail
filter=""
case "${1:-}" in
  -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
  --missing) filter=MISSING; shift ;;
  --commented) filter=COMMENTED; shift ;;
  --explicit) filter=EXPLICIT; shift ;;
esac
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
INI="$ROOT/wikantik-main/src/main/resources/ini/wikantik.properties"
# Strip // and /* */ comments so javadoc mentions do not count as reads.
strip() { sed -E 's#//.*$##' "$1" | perl -0777 -pe 's{/\*.*?\*/}{}gs'; }
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
find "$ROOT"/wikantik-*/src/main/java -name '*.java' -print0 | while IFS= read -r -d '' f; do strip "$f"; done > "$tmp"
grep -oE '"wikantik\.[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)*"' "$tmp" | tr -d '"' | sort | uniq -c | awk '{print $2"\t"$1}' |
while IFS=$'\t' read -r key refs; do
  if grep -qE "^${key//./\\.}\s*=" "$INI"; then st=EXPLICIT
  elif grep -qE "^#\s*${key//./\\.}\s*=" "$INI"; then st=COMMENTED
  else st=MISSING; fi
  defaults=$(grep -oE "Property\(\s*[^,()]*,?\s*\"${key//./\\.}\"\s*,\s*(\"[^\"]*\"|-?[0-9.]+[LlDdFf]?|true|false)\s*\)" "$tmp" \
             | sed -E 's/.*",\s*//; s/\s*\)$//' | sort -u | paste -sd'|' -)
  [[ -n "$filter" && "$st" != "$filter" ]] && continue
  printf '%s\t%s\t%s\t%s\n' "$key" "$st" "$refs" "$defaults"
done
```

- [ ] **Step 2: Run it and check the totals match the reference data**

Run: `chmod +x bin/config-inventory.sh && bin/config-inventory.sh | cut -f2 | sort | uniq -c`
Expected: roughly `EXPLICIT 97`, `COMMENTED 48`, `MISSING ~110-122` (comment stripping removes `wikantik.properties` and possibly a few more javadoc-only mentions; any drop below 100 means the strip broke and must be investigated).

- [ ] **Step 3: Commit**

```bash
git add bin/config-inventory.sh
git commit -m "build: config-inventory.sh lists property literals vs ini/wikantik.properties"
```

---

### Task 3: `ConfigSurfaceDriftTest` with a burn-down baseline

**Files:**
- Create: `wikantik-war/src/test/java/com/wikantik/architecture/ConfigSurfaceDriftTest.java`
- Create: `build-support/config-surface-baseline.tsv` (generated by the test in write mode)

**Interfaces:**
- Consumes: `ConfigReference.parse(Path, String)` from Task 1.
- Produces: violation vocabulary used by every later task, one per line of the baseline, `key<TAB>KIND`:
  `MISSING` (code reads it, file lacks it), `UNREFERENCED` (file has it, no code reads it, not under a dynamic prefix), `COMMENTED_OUT`, `DUPLICATE`, `NO_SECTION`, `NO_DESCRIPTION`, `NO_TYPE`, `BAD_TYPE` (unknown type word), `BAD_VALUE` (does not parse for its type), `BLANK_NO_MEANING`, `SECRET_HAS_VALUE`. Task 11 adds `DEFAULT_MISMATCH`.
- System property `wikantik.configSurface.writeBaseline=true` rewrites the baseline from the current violations instead of asserting.

- [ ] **Step 1: Write the test**

```java
package com.wikantik.architecture;

import com.wikantik.util.config.ConfigReference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration-surface drift gate. Every {@code wikantik.*} literal read by
 * production code must be declared, explicitly defaulted and described in
 * {@code ini/wikantik.properties} ({@code mcp.*} in {@code wikantik-mcp.properties}).
 * Violations are burned down through {@code build-support/config-surface-baseline.tsv}:
 * a violation not in the baseline fails, and a baseline line whose violation is
 * gone also fails (the file only shrinks). Regenerate the baseline with
 * {@code -Dwikantik.configSurface.writeBaseline=true} ONLY when landing the gate.
 * Spec: docs/superpowers/specs/2026-09-05-configuration-surface-design.md
 */
class ConfigSurfaceDriftTest {

    static final Path INI = Path.of( "wikantik-main/src/main/resources/ini/wikantik.properties" );
    static final Path MCP_INI = Path.of( "wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties" );
    static final Path BASELINE = Path.of( "build-support/config-surface-baseline.tsv" );
    static final List<String> MCP_MODULES = List.of( "wikantik-mcp-core", "wikantik-admin-mcp", "wikantik-knowledge" );

    /** Literals that look like keys but are not configuration. Each must still occur in source (self-pruning). */
    static final Map<String, String> NOT_CONFIG = Map.ofEntries(
        Map.entry( "wikantik.page.views", "metric" ),
        Map.entry( "wikantik.page.edits", "metric" ),
        Map.entry( "wikantik.page.deletes", "metric" ),
        Map.entry( "wikantik.auth.logins", "metric" ),
        Map.entry( "wikantik.kg_judge.timeouts", "metric" ),
        Map.entry( "wikantik.kg_judge.short_circuit_total", "metric" ),
        Map.entry( "wikantik.kg_judge.timeout_multiplier_applied", "metric" ),
        Map.entry( "wikantik.insights.ingest.rows", "metric" ),
        Map.entry( "wikantik.insights.ingest.sites", "metric" ),
        Map.entry( "wikantik.insights.ingest.engines", "metric" ),
        Map.entry( "wikantik.insights.ingest.last_success_timestamp", "metric" ),
        Map.entry( "wikantik.apikey.record", "request attribute" ),
        Map.entry( "wikantik.context", "request attribute" ),
        Map.entry( "wikantik.runFilters", "Context variable" ),
        Map.entry( "wikantik.policy", "policy file name" ),
        Map.entry( "wikantik.custom.config", "servlet init-param, documented in precedence section" ),
        Map.entry( "wikantik.ingest.truncated", "response marker" )
    );

    /** Key families read by prefix; file entries under them are examples and are never UNREFERENCED. */
    static final List<String> DYNAMIC_PREFIXES = List.of(
        "wikantik.interWikiRef.", "wikantik.specialPage.", "wikantik.loginModule.options.",
        "wikantik.sso.claimMapping.", "wikantik.translatorReader.inlinePattern.", "wikantik.custom.cascade.",
        "wikantik.connectors.", "wikantik.knowledge.extractor.", "wikantik.bundle.reranker.",
        "wikantik.bundle.decomposition.", "wikantik.briefing.", "wikantik.search.hybrid.embedder.", "wikantik.tools."
    );

    static final Set<String> TYPES = Set.of( "boolean", "int", "long", "double", "string", "path", "url", "class", "list", "secret" );
    static final Pattern ENUM_TYPE = Pattern.compile( "^enum\\(([^)]+)\\)$" );
    static final Pattern KEY_LITERAL = Pattern.compile( "\"((?:wikantik|mcp)\\.[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*)\"" );
    static final Pattern BLOCK_COMMENT = Pattern.compile( "/\\*.*?\\*/", Pattern.DOTALL );
    static final Pattern LINE_COMMENT = Pattern.compile( "//[^\\n]*" );

    @Test
    void configuration_surface_matches_baseline() throws IOException {
        final Path root = repoRoot();
        final Set<String> violations = computeViolations( root );

        if( Boolean.getBoolean( "wikantik.configSurface.writeBaseline" ) ) {
            Files.write( root.resolve( BASELINE ), violations, StandardCharsets.UTF_8 );
            return;
        }
        final Set<String> baseline = Files.exists( root.resolve( BASELINE ) )
            ? Files.readAllLines( root.resolve( BASELINE ) ).stream().map( String::strip ).filter( s -> !s.isEmpty() && !s.startsWith( "#" ) ).collect( Collectors.toCollection( TreeSet::new ) )
            : new TreeSet<>();

        final Set<String> newViolations = new TreeSet<>( violations );
        newViolations.removeAll( baseline );
        final Set<String> staleBaseline = new TreeSet<>( baseline );
        staleBaseline.removeAll( violations );

        assertTrue( newViolations.isEmpty(), () -> "New configuration-surface violations (declare the key in ini/wikantik.properties "
            + "with a description and Type:, see the spec):\n  " + String.join( "\n  ", newViolations ) );
        assertTrue( staleBaseline.isEmpty(), () -> "Baseline lines no longer violated — delete them from " + BASELINE + ":\n  "
            + String.join( "\n  ", staleBaseline ) );
    }

    // ---------------------------------------------------------------- scanning

    static Set<String> computeViolations( final Path root ) throws IOException {
        final Set<String> out = new TreeSet<>();
        final String allSource = readStrippedSource( root, null );
        final String mcpSource = readStrippedSource( root, MCP_MODULES );

        final Set<String> wikantikLiterals = literals( allSource, "wikantik." );
        final Set<String> mcpLiterals = literals( mcpSource, "mcp." );

        for( final Map.Entry<String, String> nc : NOT_CONFIG.entrySet() ) {
            if( !wikantikLiterals.contains( nc.getKey() ) ) {
                out.add( nc.getKey() + "\tSTALE_NOT_CONFIG" );   // prune the allow-list
            }
        }
        wikantikLiterals.removeAll( NOT_CONFIG.keySet() );

        checkFile( ConfigReference.parse( root.resolve( INI ), "wikantik." ), wikantikLiterals, out );
        checkFile( ConfigReference.parse( root.resolve( MCP_INI ), "mcp." ), mcpLiterals, out );
        return out;
    }

    static void checkFile( final ConfigReference.Parsed parsed, final Set<String> codeKeys, final Set<String> out ) {
        final Set<String> fileKeys = parsed.keys();
        for( final String k : codeKeys ) {
            if( !fileKeys.contains( k ) ) {
                out.add( k + "\tMISSING" );
            }
        }
        parsed.commentedOutKeys().forEach( k -> out.add( k + "\tCOMMENTED_OUT" ) );
        parsed.duplicateKeys().forEach( k -> out.add( k + "\tDUPLICATE" ) );
        for( final ConfigReference.Entry e : parsed.entries() ) {
            if( !codeKeys.contains( e.key() ) && DYNAMIC_PREFIXES.stream().noneMatch( e.key()::startsWith ) ) {
                out.add( e.key() + "\tUNREFERENCED" );
            }
            if( e.section() == null ) {
                out.add( e.key() + "\tNO_SECTION" );
            }
            if( !e.hasDescription() ) {
                out.add( e.key() + "\tNO_DESCRIPTION" );
            }
            if( !e.hasType() ) {
                out.add( e.key() + "\tNO_TYPE" );
            } else if( !TYPES.contains( e.type() ) && !ENUM_TYPE.matcher( e.type() ).matches() ) {
                out.add( e.key() + "\tBAD_TYPE" );
            } else if( !valueParses( e ) ) {
                out.add( e.key() + "\tBAD_VALUE" );
            }
            if( e.isBlank() && e.blankMeans() == null ) {
                out.add( e.key() + "\tBLANK_NO_MEANING" );
            }
            if( "secret".equals( e.type() ) && !e.isBlank() ) {
                out.add( e.key() + "\tSECRET_HAS_VALUE" );
            }
        }
    }

    static boolean valueParses( final ConfigReference.Entry e ) {
        final String v = e.value();
        if( v.isBlank() ) {
            return true;
        }
        try {
            switch( e.type() ) {
                case "boolean" -> { return Set.of( "true", "false", "yes", "no", "on", "off" ).contains( v.toLowerCase( Locale.ROOT ) ); }
                case "int" -> Integer.parseInt( v );
                case "long" -> Long.parseLong( v );
                case "double" -> Double.parseDouble( v );
                default -> {
                    final Matcher m = ENUM_TYPE.matcher( e.type() );
                    if( m.matches() ) {
                        return Set.of( m.group( 1 ).split( "\\|" ) ).contains( v );
                    }
                }
            }
            return true;
        } catch( final NumberFormatException nfe ) {
            return false;
        }
    }

    static Set<String> literals( final String source, final String prefix ) {
        final Set<String> keys = new LinkedHashSet<>();
        final Matcher m = KEY_LITERAL.matcher( source );
        while( m.find() ) {
            if( m.group( 1 ).startsWith( prefix ) ) {
                keys.add( m.group( 1 ) );
            }
        }
        return keys;
    }

    static String readStrippedSource( final Path root, final List<String> onlyModules ) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try( Stream<Path> modules = Files.list( root ) ) {
            final List<Path> srcRoots = modules
                .filter( p -> p.getFileName().toString().startsWith( "wikantik-" ) )
                .filter( p -> onlyModules == null || onlyModules.contains( p.getFileName().toString() ) )
                .map( p -> p.resolve( "src/main/java" ) )
                .filter( Files::isDirectory )
                .toList();
            for( final Path src : srcRoots ) {
                try( Stream<Path> files = Files.walk( src ) ) {
                    for( final Path f : files.filter( p -> p.toString().endsWith( ".java" ) ).toList() ) {
                        final String text = Files.readString( f, StandardCharsets.ISO_8859_1 );
                        sb.append( LINE_COMMENT.matcher( BLOCK_COMMENT.matcher( text ).replaceAll( "" ) ).replaceAll( "" ) ).append( '\n' );
                    }
                }
            }
        }
        return sb.toString();
    }

    static Path repoRoot() {
        Path dir = Paths.get( System.getProperty( "user.dir" ) ).toAbsolutePath();
        while( dir != null && !Files.isDirectory( dir.resolve( "bin/db/migrations" ) ) ) {
            dir = dir.getParent();
        }
        if( dir == null ) {
            throw new IllegalStateException( "Could not locate repo root (bin/db/migrations)" );
        }
        return dir;
    }
}
```

Note on `readStrippedSource`: ISO-8859-1 is used deliberately because two tracked source files are not UTF-8 (see memory `reference_iso8859_files_evade_grep`); every byte decodes, and ASCII key literals are unaffected.

- [ ] **Step 2: Run it once without a baseline to see it fail with the real violation list**

Run: `mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest`
Expected: FAIL. The message lists ~120 `MISSING`, 48 `COMMENTED_OUT`, ~60 `UNREFERENCED` minus dynamic-prefix examples, and `NO_SECTION`/`NO_TYPE` for every existing entry. Sanity-check three things in the output before continuing: `wikantik.ontology.enabled MISSING` is present, `wikantik.properties` is **absent** (comment stripping worked), and no `STALE_NOT_CONFIG` line appears (every allow-list literal really exists). If a `STALE_NOT_CONFIG` appears, grep for it (`grep -rn '"<literal>"' wikantik-*/src/main`) and remove it from `NOT_CONFIG`.

- [ ] **Step 3: Generate the baseline and verify the gate is green**

Run:
```bash
mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest -Dwikantik.configSurface.writeBaseline=true
wc -l build-support/config-surface-baseline.tsv
mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest
```
Expected: the second run passes. Then prepend a header to the baseline (lines starting with `#` are ignored by the reader):

```
# Config-surface burn-down baseline for ConfigSurfaceDriftTest (wikantik-war).
# key<TAB>violation. Entries only ever come OUT. Spec:
# docs/superpowers/specs/2026-09-05-configuration-surface-design.md
```

- [ ] **Step 4: Prove the ratchet bites both ways**

Delete the line `wikantik.ontology.enabled\tMISSING` from the baseline, run the test, expect FAIL naming that key as a *new* violation. Restore the line. Append a fake line `wikantik.nope\tMISSING`, run, expect FAIL naming it as *stale*. Remove it. Run once more, expect PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-war/src/test/java/com/wikantik/architecture/ConfigSurfaceDriftTest.java build-support/config-surface-baseline.tsv
git commit -m "test(config): ConfigSurfaceDriftTest ratchets the property surface against ini/wikantik.properties"
```

---

### Task 4: Section markers and `Type:` lines for the 97 existing explicit entries

This task touches only entries already declared. It burns down every `NO_SECTION` and `NO_TYPE` line for keys whose baseline status is *not* `MISSING`/`COMMENTED_OUT`.

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Modify: `wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties`
- Modify: `build-support/config-surface-baseline.tsv`

- [ ] **Step 1: Remove the target lines from the baseline (red first)**

```bash
grep -vE $'\t(NO_SECTION|NO_TYPE|NO_DESCRIPTION|BAD_TYPE|BAD_VALUE|BLANK_NO_MEANING|SECRET_HAS_VALUE)$' build-support/config-surface-baseline.tsv > /tmp/b && mv /tmp/b build-support/config-surface-baseline.tsv
mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest
```
Expected: FAIL listing every existing entry lacking a section/type.

- [ ] **Step 2: Add section markers to the existing file**

Insert `# [Section]` lines at the existing headings so that every present entry falls under one. Use these names, in file order (they become the headings of the generated doc): `General`, `Page storage`, `Attachments`, `Page references`, `Page filters`, `URL construction`, `Sitemap & SEO`, `Rendering & wiki syntax`, `Encoding`, `Authentication`, `Cookies & sessions`, `SSO`, `Authorization`, `User database`, `Access control lists`, `InterWiki links`, `User preferences`, `Mail`, `Logging`, `Spam filtering`. Later tasks add: `Caches`, `Search & hybrid retrieval`, `Knowledge Graph & extraction`, `Context bundle & briefing`, `Ontology`, `Frontmatter & structural enforcement`, `Connectors`, `Content Intelligence`, `REST API, MCP & agent surfaces`, `Security & passwords`, `Observability & profiling`, `Recent articles`, `Page ownership`.

- [ ] **Step 3: Give every existing entry a `Type:` line and, where the comment block is separated from the key by a blank line, close the gap**

Worked example of the transformation:

Before:
```
#
#  Determines which page provider class you will be using.
#

wikantik.pageProvider = VersioningFileProvider
```
After:
```
#
#  Determines which page provider class you will be using. FileSystemProvider
#  stores pages without history; VersioningFileProvider keeps every version.
#  Type: enum(FileSystemProvider|VersioningFileProvider)
wikantik.pageProvider = VersioningFileProvider
```

Rules: keep the existing prose, tighten it if it is stale, no blank line between the block and the key. Example lines inside a comment that start with a key (`#  wikantik.fileSystemProvider.pageDir = /p/web/...`) are parsed as `COMMENTED_OUT`; reword them as prose (`e.g. /p/web/www-data/wikantik/`). Pick the type from how the reader parses it (`TextUtil.getBooleanProperty` ⇒ `boolean`, `getIntegerProperty` ⇒ `int`, a class name ⇒ `class`, a directory ⇒ `path`, `wikantik.baseURL` ⇒ `url`, space- or comma-separated ⇒ `list` and say which separator in the prose).

- [ ] **Step 4: Run the gate until green**

Run: `mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest`
Expected: PASS. Also run `mvn -q -pl wikantik-util test -Dtest=PropertyReaderTest` to prove the file still loads.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties build-support/config-surface-baseline.tsv
git commit -m "docs(config): section markers and Type: lines for every existing property entry"
```

---

### Task 5: MCP properties file (`mcp.*`) — complete it

**Files:**
- Modify: `wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties`
- Modify: `wikantik-mcp-core/src/main/java/com/wikantik/mcp/McpConfig.java` only if a reader needs `isBlank()` handling
- Modify: `build-support/config-surface-baseline.tsv`

The four undeclared keys are `mcp.access`, `mcp.access.allowedCidrs`, `mcp.access.allowUnrestricted`, `mcp.instructions.file`. Read `McpAccessFilter.java` and `McpConfig.java` for each one's reader and code default.

- [ ] **Step 1: Red** — delete every `mcp.*` line from the baseline; run the gate; expect FAIL listing the four `MISSING`.
- [ ] **Step 2: Declare them**, e.g.

```
# [MCP access]
#
#  Allow MCP requests without a bearer token or API key. The integration
#  tests set this to true; production must leave it false.
#  Type: boolean
mcp.access.allowUnrestricted = false
```

If `mcp.access` turns out to be a prefix used for concatenation, add `"mcp.access."` handling by declaring it `NOT_CONFIG` in the test with reason `prefix constant` instead of inventing an entry.

- [ ] **Step 3: Green** — run the gate; PASS. Run `mvn -q -pl wikantik-mcp-core,wikantik-admin-mcp test -Dtest='McpConfig*Test,McpAccessFilter*Test'` to prove nothing reads differently.
- [ ] **Step 4: Commit** — `git add` the two files and the baseline; message `docs(config): declare every mcp.* key in wikantik-mcp.properties`.

---

### Tasks 6–10: Burn-down by subsystem group

Each task has the same shape. The **procedure** is written out once here and referenced by the group tasks; each group task lists its exact keys and any known reader fixes.

**Procedure (repeat per task):**

1. **Red.** Remove every baseline line for the task's keys (all violation kinds), run `mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest`, confirm it fails naming exactly those keys.
2. **Find the default.** `bin/config-inventory.sh | grep -P '^<key>\t'` gives the literal default when the read site uses one. Otherwise open the read site (`grep -rn '"<key>"' wikantik-*/src/main/java`) and take the constant's value. For a key with no default in code the value is blank and a `Blank means:` line is mandatory.
3. **Promote or add the entry** under the right `# [Section]`, following the convention. A `COMMENTED_OUT` line is either promoted (uncommented, real default filled in) or deleted if it is a stale example of a key that no longer exists.
4. **Blank-means audit.** For every entry left blank, read the reader. If it tests `== null` only, change it to `== null || isBlank()` **test-first**: write a unit test in the reader's module that passes a `Properties` with `key=""` and asserts the derived fallback is used; run it (fails); fix; run (passes).
5. **Reconcile `UNREFERENCED` lines** in scope: if the key is read via a prefix, add the prefix to `DYNAMIC_PREFIXES` (with the grep evidence in a comment); if it is dead, delete the entry from the file; if it is read by a `bin/` script or the frontend rather than Java, keep it and add it to `NOT_CONFIG`-style allow-list `EXTERNALLY_READ` (create it as a `Map<String,String>` beside `NOT_CONFIG`, keys exempt from `UNREFERENCED`).
6. **Green.** Gate passes. Run `mvn -q -pl wikantik-util test -Dtest=PropertyReaderTest`.
7. **Commit** the file, the baseline, any reader fix and its test. Message `docs(config): declare <group> properties with explicit defaults`.

**Worked example (belongs to Task 9, shown once):**

`wikantik.ontology.tdb2.dir` — read at `OntologyWiringHelper.java:160` with no default; the code derives `${wikantik.workDir}/ontology-tdb2`, then `java.io.tmpdir/wikantik-ontology-tdb2`. The reader already uses `isBlank()`, so no code change. Entry:

```
#
#  Directory for the Jena TDB2 store behind /sparql, /id/* and /export/*.
#  The store is a rebuildable cache: deleting it and restarting triggers a
#  full re-projection from PostgreSQL.
#  Type: path
#  Blank means: <wikantik.workDir>/ontology-tdb2 (or java.io.tmpdir/wikantik-ontology-tdb2 when workDir is unset)
wikantik.ontology.tdb2.dir =
```

---

### Task 6: Group A — core, storage, caches, rendering

**Keys (MISSING):** `wikantik.baseURL`, `wikantik.public.baseURL`, `wikantik.feed.baseURL`, `wikantik.templateDir`, `wikantik.datasource`, `wikantik.policy.file`, `wikantik.storeIPAddress`, `wikantik.storeUserName`, `wikantik.attachmentCollectionsCache`, `wikantik.attachmentsCache`, `wikantik.dynamicAttachmentCache`, `wikantik.forAgentCache`, `wikantik.htmlCache`, `wikantik.pageCache`, `wikantik.pageHistoryCache`, `wikantik.pageTextCache`, `wikantik.renderingCache`, `wikantik.cache.allPagesTTL`, `wikantik.cache.memcached.servers`, `wikantik.cache.memcached.ttl`, `wikantik.cache.watcherEnabled`, `wikantik.cache.watcherInterval`, `wikantik.versioningFileProvider.cacheSize`, `wikantik.watcher.internalSaveGuardMillis`, `wikantik.provider.impl.acls`, `wikantik.provider.impl.contents`, `wikantik.provider.impl.context`, `wikantik.provider.impl.engine`, `wikantik.provider.impl.session`, `wikantik.contextualDiffProvider.unchangedContextLimit`, `wikantik.renderingManager.renderer.wysiwyg`, `wikantik.translatorReader.inlinePattern`, `wikantik.translatorReader.runPlugins`, `wikantik.translatorReader.useAttachmentImage`, `wikantik.translatorReader.useRelNofollow`, `wikantik.systemPages.extraPatterns`, `wikantik.systemPages.mcpEditable`, `wikantik.recentArticles.cacheTTL`, `wikantik.recentArticles.defaultCount`, `wikantik.recentArticles.defaultExcerptLength`, `wikantik.recentArticles.excludePatterns`, `wikantik.filters.spamfilter.allowedgroups`, `wikantik.profiling.dir`, `wikantik.profiling.dir.max_bytes`.

**Keys (COMMENTED_OUT → promote):** `wikantik.allowCreationOfEmptyPages`, `wikantik.attachment.allowed`, `wikantik.attachment.forbidden`, `wikantik.attachment.maxsize`, `wikantik.attachmentProvider`, `wikantik.basicAttachmentProvider.disableCache`, `wikantik.basicAttachmentProvider.storageDir`, `wikantik.fileSystemProvider.pageDir`, `wikantik.filterConfig`, `wikantik.frontPage`, `wikantik.nofilterencoding`, `wikantik.pageNameComparator.class`, `wikantik.workDir`, `wikantik.mail.jndiname`.

**UNREFERENCED to reconcile:** `wikantik.usePageCache` (deprecated, delete), `wikantik.userdatabase.isSharedWithContainer` (grep; delete if dead), `wikantik.interWikiRef.*` and `wikantik.specialPage.*` and `wikantik.translatorReader.inlinePattern.N` (dynamic prefixes, keep as examples, give each block a description and `Type:`).

**Known hazards:**
- `wikantik.workDir` blank: `PropertyReader.setWorkDir` and `WikiEngine.getWorkDir` derive a temp dir. Confirm both treat `""` as unset (test-first if not).
- `wikantik.baseURL` blank: `WikiEngine.getBaseURL()` returns the context path when unset (memory `reference_indexnow_and_baseurl_wiring`). Verify blank behaves like absent.
- `wikantik.cache.memcached.servers` is read by the opt-in `wikantik-cache-memcached` module; declare it blank with `Blank means: memcached adapter disabled (EhCache is the default)`.

- [ ] Steps 1–7 of the procedure. Commit message: `docs(config): declare core, storage, cache and rendering properties with explicit defaults`.

---

### Task 7: Group B — security, authentication, passwords, SSO, API access

**Keys (MISSING):** `wikantik.admin.bootstrap`, `wikantik.admin.bootstrap.maxAgeSeconds`, `wikantik.auth.masterpassword`, `wikantik.auth.password.verifyCache.ttlSeconds`, `wikantik.password.blocklist.enabled`, `wikantik.password.maxLength`, `wikantik.password.minLength`, `wikantik.passwordMustChange`, `wikantik.plugin.jdbc.enabled`, `wikantik.sso.identityClaim`, `wikantik.sso.saml.authnRequestBindingType`, `wikantik.sso.saml.serviceProviderMetadataPath`, `wikantik.scim.token`, `wikantik.cors.allowedOrigins`, `wikantik.api.maxPageBytes`, `wikantik.api.write.requireExpectedVersion`, `wikantik.audit.readClusters`, `wikantik.mcp.kg_curation.bulk_limit`, `wikantik.mcp.rate_limit.max_clients`, `wikantik.indexnow.apiKey`, `wikantik.page_ownership.default_owner`, `wikantik.page_ownership.enforcement.enabled`, `wikantik.admin.notification.email`.

**Keys (COMMENTED_OUT → promote):** `wikantik.cookieAssertions`, `wikantik.cookieAuthentication`, `wikantik.cookieAuthentication.expiry`, `wikantik.sso.autoProvision`, `wikantik.sso.enabled`, `wikantik.sso.type`, `wikantik.sso.oidc.clientId`, `wikantik.sso.oidc.clientSecret`, `wikantik.sso.oidc.discoveryUri`, `wikantik.sso.oidc.scope`, `wikantik.sso.saml.identityProviderMetadataPath`, `wikantik.sso.saml.keystorePassword`, `wikantik.sso.saml.keystorePath`, `wikantik.sso.saml.privateKeyPassword`, `wikantik.sso.saml.serviceProviderEntityId`.

**UNREFERENCED to reconcile:** `wikantik.sso.claimMapping.*` (dynamic prefix, keep as examples), `wikantik.loginModule.options.param1/param2` (dynamic prefix examples).

**Known hazards and required reader tests:**
- `Type: secret`, blank: `wikantik.auth.masterpassword`, `wikantik.sso.oidc.clientSecret`, `wikantik.sso.saml.keystorePassword`, `wikantik.sso.saml.privateKeyPassword`, `wikantik.scim.token`, `wikantik.indexnow.apiKey`.
- `wikantik.scim.token`: `Source: system-property`. Description must say the value is read from `-Dwikantik.scim.token` or the filter init-param and that a value in this file is ignored.
- `wikantik.indexnow.apiKey` (`McpToolRegistry.java:89`, null-checked): write `McpToolRegistryTest.blank_indexnow_key_disables_indexnow()` first, then make the reader `isBlank`-safe.
- `wikantik.admin.notification.email` (`DefaultUserManager.java:291`): same pattern, test in `DefaultUserManagerTest` (or a new `DefaultUserManagerNotificationTest`).
- `wikantik.sso.*` props are bridged into JAAS options (memory `feedback_sso_claim_props_need_jaas_bridge`); declaring them blank must not turn SSO on. Run `mvn -q -pl wikantik-main test -Dtest='SSOConfig*Test,SSOLoginModule*Test'`.
- `wikantik.admin.bootstrap` blank must mean "no bootstrap override". Check `DefaultAuthorizationManager` / the bootstrap reader for `isBlank`.

- [ ] Steps 1–7 of the procedure. Commit message: `docs(config): declare security, auth, SSO and API-access properties with explicit defaults`.

---

### Task 8: Group C — search and retrieval

**Keys (MISSING):** `wikantik.lucene.indexdelay`, `wikantik.lucene.initialdelay`, `wikantik.lucene.missingPageCheckInterval`, `wikantik.search.highlighter.enabled`, `wikantik.search.hybrid`, `wikantik.search.hybrid.rrf.k`, `wikantik.search.hybrid.rrf.truncate`, `wikantik.search.hybrid.vector_index.size`, `wikantik.search.ontologyExpansion.enabled`, `wikantik.retrieval.cron.enabled`, `wikantik.retrieval.cron.hour_utc`, `wikantik.bundle.coverage.partial_similarity`, `wikantik.bundle.coverage.strong_similarity`, `wikantik.briefing.log.enabled`, `wikantik.verification.stale_days`, `wikantik.hub.reviewPercentile`.

**Keys (COMMENTED_OUT → promote):** `wikantik.lucene.analyzer`, `wikantik.search.dense.lucene.ef_construction`, `wikantik.search.dense.lucene.ef_search`, `wikantik.search.dense.lucene.m`, `wikantik.bundle.eval.corpus`, `wikantik.bundle.eval.precision_k`, `wikantik.bundle.knee.enabled`, `wikantik.bundle.knee.retain_ratio`, `wikantik.bundle.rerank.chain`, `wikantik.bundle.rerank.metadata_boost.positions`, `wikantik.bundle.rerank.metadata_boost.window`, `wikantik.bundle.rerank.mmr.lambda`.

**UNREFERENCED to reconcile:** `wikantik.bundle.reranker.*`, `wikantik.bundle.decomposition.*` (dynamic prefixes — verify with `grep -rn '"wikantik.bundle.decomposition."' wikantik-main/src/main/java`; keep as examples with descriptions).

**Known hazards:**
- `wikantik.search.dense.backend` is already explicit; rewrite its description to name the fallback default (`inmemory` when no DataSource is wired) and reference `DenseBackendResolutionTest`. Do **not** change either code default.
- `wikantik.bundle.sections_per_page` (`BundleServiceWiring.java:238`, no default, parsed as int): test-first `BundleServiceWiringTest.blank_sections_per_page_uses_default()`, then `isBlank`-guard.
- `wikantik.bundle.rerank.chain` (`BundleServiceWiring.java:281`): same. Blank means "default chain".
- The frontmatter memo in HybridRetrieval.md says `lucene-hnsw` is the docker1 default; the file must agree.

- [ ] Steps 1–7 of the procedure. Commit message: `docs(config): declare search, dense retrieval and bundle properties with explicit defaults`.

---

### Task 9: Group D — Knowledge Graph, extraction, ontology, frontmatter enforcement

**Keys (MISSING):** `wikantik.kg.extractor.allow_claude`, `wikantik.kg.judge.allow_claude`, `wikantik.kg.judge.keep_alive`, `wikantik.knowledge.extractor.ollama.endpoint`, `wikantik.ontology.compaction.interval.hours`, `wikantik.ontology.enabled`, `wikantik.ontology.incremental.coalesce.ms`, `wikantik.ontology.incremental.enabled`, `wikantik.ontology.rebuild.interval.hours`, `wikantik.ontology.tdb2.dir`, `wikantik.frontmatter.autoDefaults`, `wikantik.frontmatter.defaultTags`, `wikantik.frontmatter.enforcement.enabled`, `wikantik.frontmatter.enum.nonCanonical.severity`, `wikantik.frontmatter.trustedAuthors`, `wikantik.cluster_declaration.enforcement.enabled`, `wikantik.structural_spine.enforcement.enabled`, `wikantik.runbook.enforcement.enabled`, `wikantik.math.enforcement.enabled`.

**Keys (COMMENTED_OUT → promote):** `wikantik.genai.mode`, `wikantik.kg.judge.endpoint`, `wikantik.kg.judge.model`, `wikantik.knowledge.enabled`.

**UNREFERENCED to reconcile:** `wikantik.knowledge.extractor.*` (dynamic prefix — `KgJudgeConfig`/`EntityExtractorConfig` read `"wikantik.knowledge.extractor." + …`; keep as examples), `wikantik.kg_policy.bootstrap.exclude`, `wikantik.kg_policy.bootstrap.include`, `wikantik.kg_policy.reconciliation.eager`, `wikantik.kg_policy.review.page_count_change_pct`, `wikantik.kg_policy.review.staleness_days` (only `wikantik.kg_policy.enabled` is read from Java; check `bin/kg-policy.sh` and `KgPolicyCli` — if read there, mark `EXTERNALLY_READ`, else delete).

**Known hazards:**
- `KgJudgeConfig.java:59-63` reads `wikantik.knowledge.extractor.ollama.base_url|endpoint|model` with no default. Test-first `KgJudgeConfigTest.blank_ollama_settings_fall_back_to_extractor_defaults()`, then `isBlank`-guard.
- `wikantik.cluster_declaration.enforcement.enabled = false` ships dark deliberately (CLAUDE.md). The description must say why and what flipping it does.
- The ontology group's descriptions come from CLAUDE.md's ontology paragraph (defaults 24 h, 500 ms, true).
- `wikantik.frontmatter.defaultTags` may be legitimately empty as an override; `Blank means: no default tags`.

- [ ] Steps 1–7 of the procedure. Commit message: `docs(config): declare Knowledge Graph, ontology and frontmatter-enforcement properties with explicit defaults`.

---

### Task 10: Group E — connectors, Content Intelligence, leftovers, baseline empty

**Keys (MISSING):** `wikantik.connectors.egress.allowPrivate`, `wikantik.insights.ingest.max_bytes` (verify it is config, not a metric; if a metric, move it to `NOT_CONFIG`), plus anything still in the baseline after Tasks 6–9.

**Keys (COMMENTED_OUT → promote):** `wikantik.connectors.enabled`, `wikantik.connectors.sync.interval.hours`.

**UNREFERENCED to reconcile:** `wikantik.connectors.crypto.key`, `wikantik.connectors.filesystem.docs.root` (both under the `wikantik.connectors.` dynamic prefix; `crypto.key` is `Type: secret`, blank, `Blank means: credentials store disabled`; confirm the reader).

- [ ] Steps 1–7 of the procedure, then:
- [ ] **Assert the baseline is empty**: `grep -vc '^#' build-support/config-surface-baseline.tsv` prints `0`.
- [ ] Commit message: `docs(config): declare connector and Content Intelligence properties; config-surface baseline is empty`.

---

### Task 11: `DEFAULT_MISMATCH` — the file value must equal the literal code default

**Files:**
- Modify: `wikantik-war/src/test/java/com/wikantik/architecture/ConfigSurfaceDriftTest.java`
- Possibly modify: read sites whose literal default disagrees with the file (fix the *file* unless the code default is the bug; either way, one commit per decision)

**Interfaces:**
- Produces: violation kind `DEFAULT_MISMATCH`; `KNOWN_DIVERGENT` map `key → reason`.

- [ ] **Step 1: Write the failing unit test for the extractor**

Add to `ConfigSurfaceDriftTest`:

```java
    static final Pattern LITERAL_DEFAULT = Pattern.compile(
        "Property\\(\\s*[^,()]*,?\\s*\"((?:wikantik|mcp)\\.[a-zA-Z0-9_.]+)\"\\s*,\\s*(\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?)[LlDdFf]?|(true|false))\\s*\\)" );

    /** Deliberate code/file divergences, each with the reason an executor needs. */
    static final Map<String, String> KNOWN_DIVERGENT = Map.of(
        "wikantik.search.dense.backend", "fallback path defaults to inmemory (no DataSource); wiring path and file say lucene-hnsw — DenseBackendResolutionTest pins both"
    );

    /** key -> set of distinct literal defaults seen at read sites. */
    static Map<String, Set<String>> literalDefaults( final String source ) {
        final Map<String, Set<String>> out = new java.util.TreeMap<>();
        final Matcher m = LITERAL_DEFAULT.matcher( source );
        while( m.find() ) {
            final String value = m.group( 3 ) != null ? m.group( 3 ) : m.group( 4 ) != null ? m.group( 4 ) : m.group( 5 );
            out.computeIfAbsent( m.group( 1 ), k -> new TreeSet<>() ).add( value );
        }
        return out;
    }

    static boolean sameValue( final String fileValue, final String codeValue ) {
        try {
            return Double.parseDouble( fileValue ) == Double.parseDouble( codeValue );
        } catch( final NumberFormatException nfe ) {
            return fileValue.strip().equalsIgnoreCase( codeValue.strip() );
        }
    }

    @Test
    void literal_default_extractor_reads_strings_numbers_and_booleans() {
        final String src = "x = TextUtil.getBooleanProperty( props, \"wikantik.a\", true );\n"
            + "y = props.getProperty( \"wikantik.b\", \"lucene-hnsw\" );\n"
            + "z = TextUtil.getIntegerProperty( props, \"wikantik.c\", 300 );\n"
            + "w = props.getProperty( \"wikantik.d\", SOME_CONSTANT );\n";
        final Map<String, Set<String>> d = literalDefaults( src );
        assertEquals( Set.of( "true" ), d.get( "wikantik.a" ) );
        assertEquals( Set.of( "lucene-hnsw" ), d.get( "wikantik.b" ) );
        assertEquals( Set.of( "300" ), d.get( "wikantik.c" ) );
        assertFalse( d.containsKey( "wikantik.d" ), "constant defaults are not checkable here" );
    }
```

(Add `import static org.junit.jupiter.api.Assertions.*;` in place of the single `assertTrue` import.)

Run: `mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest#literal_default_extractor_reads_strings_numbers_and_booleans` — expect PASS for the helper (it is pure). The *gate* test is what turns red in Step 2.

- [ ] **Step 2: Wire the check into `checkFile`**

Change `checkFile`'s signature to `checkFile( parsed, codeKeys, defaults, out )` where `defaults` is `literalDefaults( source )` for that file's source, and add inside the entry loop:

```java
            final Set<String> code = defaults.getOrDefault( e.key(), Set.of() );
            if( !code.isEmpty() && !KNOWN_DIVERGENT.containsKey( e.key() )
                && code.stream().noneMatch( c -> sameValue( e.value(), c ) ) ) {
                out.add( e.key() + "\tDEFAULT_MISMATCH(file=" + e.value() + ", code=" + String.join( "|", code ) + ")" );
            }
```

Update `computeViolations` to pass `literalDefaults( allSource )` and `literalDefaults( mcpSource )`. Run the gate. Expected: FAIL listing every key whose file value disagrees with a literal code default. Record the list in the commit message body.

- [ ] **Step 3: Resolve each mismatch**

For each: open the read site. If the file is wrong, fix the file. If two read sites disagree with each other (as `wikantik.workDir` may), decide which is production behaviour, fix the other site test-first, or add the key to `KNOWN_DIVERGENT` with a one-sentence reason. Re-run until green.

- [ ] **Step 4: Commit**

```bash
git add wikantik-war/src/test/java/com/wikantik/architecture/ConfigSurfaceDriftTest.java wikantik-main/src/main/resources/ini/wikantik.properties <any fixed readers and tests>
git commit -m "test(config): file defaults must equal literal code defaults (DEFAULT_MISMATCH)"
```

---

### Task 12: Generated reference docs — CLI, templates, regression test, wrapper

**Files:**
- Create: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/configref/GenerateConfigReferenceCli.java`
- Create: `wikantik-extract-cli/src/main/resources/ConfigurationReference.md.mustache`
- Create: `wikantik-extract-cli/src/main/resources/WikantikConfigurationReference.md.mustache`
- Create: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/configref/GenerateConfigReferenceCliTest.java`
- Create: `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/configref/ConfigReferenceRegressionTest.java`
- Create: `bin/config-reference.sh`
- Create (generated): `docs/ConfigurationReference.md`, `docs/wikantik-pages/WikantikConfigurationReference.md`
- Modify: `build-support/config-surface-baseline.tsv` → **delete** (Task 10 left it empty; the test tolerates absence)

**Interfaces:**
- Consumes: `ConfigReference.parse(Path, String)`.
- Produces:
  ```java
  public final class GenerateConfigReferenceCli {
      public enum Mode { WRITE, CHECK }
      public record Result( int exitCode, String summary ) {}
      public Result run( Path repoRoot, Mode mode ) throws IOException;   // renders both outputs
      String render( ConfigReference.Parsed wikantik, ConfigReference.Parsed mcp, String templateResource );
      public static void main( String[] args );  // generate-config-reference <repoRoot> [--write|--check]
  }
  ```
  Look at `GenerateMainPageCli` first and copy its Mustache loading, `--write/--check` handling, and exit codes (64 usage, 0 in sync, 1 stale).

- [ ] **Step 1: Write the failing renderer test**

```java
package com.wikantik.extractcli.configref;

import com.wikantik.util.config.ConfigReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenerateConfigReferenceCliTest {

    private static final ConfigReference.Parsed WIKANTIK = ConfigReference.parse( List.of(
        "# [Ontology]",
        "#  Master switch for the ontology layer.",
        "#  Type: boolean",
        "wikantik.ontology.enabled = true",
        "#  Directory for the TDB2 store.",
        "#  Type: path",
        "#  Blank means: <workDir>/ontology-tdb2",
        "wikantik.ontology.tdb2.dir =",
        "# [Security & passwords]",
        "#  SCIM bearer token.",
        "#  Type: secret",
        "#  Source: system-property",
        "wikantik.scim.token ="
    ), "wikantik." );

    private static final ConfigReference.Parsed MCP = ConfigReference.parse( List.of(
        "# [MCP access]",
        "#  Allow unauthenticated MCP calls.",
        "#  Type: boolean",
        "mcp.access.allowUnrestricted = false"
    ), "mcp." );

    @Test
    void renders_one_table_per_section_with_key_type_default_env_and_description() {
        final String md = new GenerateConfigReferenceCli().render( WIKANTIK, MCP, "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "## Ontology" ) );
        assertTrue( md.contains( "| `wikantik.ontology.enabled` | boolean | `true` | `wikantik_ontology_enabled` | Master switch for the ontology layer. |" ), md );
        assertTrue( md.contains( "| `wikantik.ontology.tdb2.dir` | path | *(blank: <workDir>/ontology-tdb2)* |" ), md );
        assertTrue( md.contains( "## MCP access" ) );
        assertTrue( md.contains( "`mcp.access.allowUnrestricted`" ) );
    }

    @Test
    void system_property_sourced_keys_are_flagged() {
        final String md = new GenerateConfigReferenceCli().render( WIKANTIK, MCP, "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "`wikantik.scim.token` | secret | *(blank)* | `-Dwikantik.scim.token` only |" ), md );
    }

    @Test
    void precedence_section_is_present_and_accurate() {
        final String md = new GenerateConfigReferenceCli().render( WIKANTIK, MCP, "ConfigurationReference.md.mustache" );
        assertTrue( md.contains( "## How a value is resolved" ) );
        assertTrue( md.contains( "wikantik-custom.properties" ) );
        assertTrue( md.contains( "wikantik.custom.cascade." ) );
        assertTrue( md.contains( "exact case" ), "env override names are case-sensitive — the doc must say so" );
    }

    @Test
    void wiki_variant_has_frontmatter_for_the_development_cluster() {
        final String md = new GenerateConfigReferenceCli().render( WIKANTIK, MCP, "WikantikConfigurationReference.md.mustache" );
        assertTrue( md.startsWith( "---\n" ) );
        assertTrue( md.contains( "cluster: wikantik-development" ) );
        assertTrue( md.contains( "canonical_id: " ) );
        assertTrue( md.contains( "type: article" ) );
    }

    @Test
    void output_is_deterministic() {
        final GenerateConfigReferenceCli cli = new GenerateConfigReferenceCli();
        assertEquals( cli.render( WIKANTIK, MCP, "ConfigurationReference.md.mustache" ), cli.render( WIKANTIK, MCP, "ConfigurationReference.md.mustache" ) );
    }
}
```

Run: `mvn -q -pl wikantik-extract-cli test -Dtest=GenerateConfigReferenceCliTest` — expect compile failure.

- [ ] **Step 2: Write the templates**

`ConfigurationReference.md.mustache` (the wiki variant is identical after a frontmatter block; keep the body in one partial if the Mustache library in use supports partials, otherwise duplicate the body and let `output_is_deterministic` plus the regression test keep them honest):

```
<!-- GENERATED by bin/config-reference.sh from ini/wikantik.properties and wikantik-mcp.properties. Do not edit. -->
# Wikantik Configuration Reference

Every setting Wikantik reads, with its shipped default. The defaults file
`wikantik-main/src/main/resources/ini/wikantik.properties` is the source of
this page and is complete by construction (`ConfigSurfaceDriftTest`).

## How a value is resolved

1. **Shipped defaults** — `ini/wikantik.properties` inside the wikantik-main jar. Every key below is declared there.
2. **Environment variables** whose name starts with `wikantik`, with `_` read as `.` — `PropertyReader.loadWebAppProps`. The name must match the key in **exact case** (`wikantik_baseURL`, not `WIKANTIK_BASE_URL`); the Docker image's `WIKANTIK_*` variables work because `docker/entrypoint.sh` rewrites them into the custom properties file.
3. **`wikantik-custom.properties`** on the container classpath (`tomcat/lib/`), or the file named by the `wikantik.custom.config` servlet init-param.
4. **Cascade files** `wikantik.custom.cascade.1`, `.2`, … named in the custom file.
5. **`${…}` expansion** against system properties and environment.
6. **JVM system properties** starting with `wikantik` (`-Dwikantik.foo=bar`) override everything above.

Readers that go through `TextUtil.get*Property` additionally consult the system property and the exact-case environment variable **at read time**, before the merged set, so `-D` and `wikantik_foo` win for those keys even when set after startup. Keys marked *`-D` only* are never read from the properties files at all.

`mcp.*` keys live in a separate file: `wikantik-mcp.properties` in the wikantik-admin-mcp jar, overlaid by a copy in `tomcat/lib/`.

Blank values mean "unset"; the **Default** column says what the code derives in that case. `secret` keys never ship a value.

{{#sections}}
## {{name}}

| Key | Type | Default | Override | Description |
|---|---|---|---|---|
{{#entries}}
| `{{key}}` | {{type}} | {{{defaultCell}}} | {{{overrideCell}}} | {{{descriptionCell}}} |
{{/entries}}

{{/sections}}
```

Rendering rules for the cells (implement in the CLI, unit-tested above): `defaultCell` = `` `value` `` when non-blank; `*(blank: <Blank means text>)*` when blank with a meaning; `*(blank)*` for secrets. `overrideCell` = `` `-Dkey` only `` when `Source: system-property`, else `` `envOverrideName()` ``. `descriptionCell` = description lines joined with a space, `|` escaped as `\|`. Sections appear in file order; the MCP file's sections follow the main file's.

`WikantikConfigurationReference.md.mustache` prepends:

```
---
title: Wikantik Configuration Reference
cluster: wikantik-development
canonical_id: 01M1S6EHZHT62VAB8JK3ZWM5BX
type: article
status: active
date: '{{date}}'
summary: Every configuration key Wikantik reads, with its shipped default, type, override name and description. Generated from ini/wikantik.properties.
tags:
- configuration
- administration
- wikantik
auto-generated: true
---
```

The `canonical_id` above was minted once with `UlidCreator.getUlid()` (the same generator `StructuralSpinePageFilter` uses) and is fixed for the life of the page; never regenerate it. `{{date}}` is the generation date; the regression test must therefore compare **ignoring the `date:` line** (see Step 4).

- [ ] **Step 3: Implement the CLI** (mirror `GenerateMainPageCli` + `mainpage/MainPageRenderer` — jmustache, `com.samskivert.mustache.Mustache.compiler().compile(...)` on a classpath resource; load template from classpath, build the view model as `Map<String,Object>` with `sections → List<Map>` and `entries → List<Map>`; `run()` writes/compares `docs/ConfigurationReference.md` and `docs/wikantik-pages/WikantikConfigurationReference.md` under `repoRoot`; `--check` returns `Result(1, "stale: <paths>")` when either differs, `Result(0, "in sync")` otherwise; `main` exits with the code). Run the renderer test until green.

- [ ] **Step 4: Regression test**

```java
package com.wikantik.extractcli.configref;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Committed docs/ConfigurationReference.md and the wiki page must match the generator. Regenerate with bin/config-reference.sh --write. */
@DisabledIfEnvironmentVariable( named = "WIKANTIK_SKIP_MAIN_REGRESSION", matches = "1" )
class ConfigReferenceRegressionTest {

    @Test
    void committed_reference_docs_match_generated_output() throws Exception {
        final Path root = locateRepoRoot();
        if( root == null ) {
            return;   // IDE run without the project root on the cwd chain; CI always has it
        }
        final GenerateConfigReferenceCli.Result r = new GenerateConfigReferenceCli().run( root, GenerateConfigReferenceCli.Mode.CHECK );
        assertEquals( 0, r.exitCode(), "Configuration reference is stale: " + r.summary() + " (run bin/config-reference.sh --write)" );
    }

    private static Path locateRepoRoot() {
        Path cursor = Path.of( "" ).toAbsolutePath();
        while( cursor != null ) {
            if( Files.isDirectory( cursor.resolve( "bin/db/migrations" ) ) ) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
```

`Mode.CHECK` inside `run()` must compare the wiki page with its `date:` line removed from both sides, so a regenerate on a later day is not "stale" by itself.

- [ ] **Step 5: Wrapper script** `bin/config-reference.sh` — copy `bin/kg-policy.sh`'s header, `--help`, jar-staleness rebuild and `java -cp` invocation; main class `com.wikantik.extractcli.configref.GenerateConfigReferenceCli`; pass the repo root and `--write` (default) or `--check`.

- [ ] **Step 6: Generate, verify, remove the empty baseline**

```bash
bin/config-reference.sh --write
mvn -q -pl wikantik-extract-cli test -Dtest='GenerateConfigReferenceCliTest,ConfigReferenceRegressionTest'
git rm build-support/config-surface-baseline.tsv
mvn -q -pl wikantik-war test -Dtest=ConfigSurfaceDriftTest
```
Expected: all pass; the drift test treats a missing baseline as empty. Open `docs/ConfigurationReference.md` and read three sections end to end for admin-legibility; fix prose in the **properties file**, regenerate, never in the markdown.

- [ ] **Step 7: Commit**

```bash
git add wikantik-extract-cli/src/main/java/com/wikantik/extractcli/configref wikantik-extract-cli/src/main/resources/ConfigurationReference.md.mustache wikantik-extract-cli/src/main/resources/WikantikConfigurationReference.md.mustache wikantik-extract-cli/src/test/java/com/wikantik/extractcli/configref bin/config-reference.sh docs/ConfigurationReference.md docs/wikantik-pages/WikantikConfigurationReference.md
git commit -m "feat(config): generate docs/ConfigurationReference.md and the wiki page from the defaults file"
```

---

### Task 13: Documentation pointers and reconciliation

**Files:**
- Modify: `CLAUDE.md` § "Important Configuration" (line ~453)
- Modify: `docs/ProjectReference.md` — new `## Configuration` section before `## bin/ script conventions`
- Modify: `README.md` — one bullet in the module/docs list pointing at `docs/ConfigurationReference.md`
- Modify: `docs/DockerDeployment.md` — env-var table `Default` column reconciled with the file
- Modify: `docs/wikantik-pages/WikantikDevelopment.md` — add `WikantikConfigurationReference` to `related:`

- [ ] **Step 1: CLAUDE.md** — replace the four bullets under "Important Configuration" with:

```
### Important Configuration

- **`ini/wikantik.properties` is the complete reference.** Every `wikantik.*` key production code reads is declared there, uncommented, with its explicit default, a description and a `Type:` line (`mcp.*` keys: `wikantik-mcp.properties`). `ConfigSurfaceDriftTest` (wikantik-war) fails the build on a key read but not declared, declared but not read, commented out, undescribed, untyped, blank without `Blank means:`, a secret with a value, or a file default that differs from the literal code default. Spec: `docs/superpowers/specs/2026-09-05-configuration-surface-design.md`.
- **Adding a key:** declare it in the file first (section, description, `Type:`, explicit default), then read it in code with the same default. If the default is derived, leave the value blank, add `Blank means:`, and make the reader `isBlank()`-safe.
- **Docs are generated:** `bin/config-reference.sh --write` regenerates `docs/ConfigurationReference.md` and the wiki page `WikantikConfigurationReference`; `ConfigReferenceRegressionTest` fails when they are stale. Never hand-edit them.
- Overrides: `wikantik-custom.properties` (container lib or WEB-INF), cascade files, `-D` system properties. Precedence is documented in the generated reference.
- Security policy: `policy_grants` table (database-backed) or `WEB-INF/wikantik.policy` (file-based fallback). Schema: `bin/db/migrations/V*.sql` only.
```

- [ ] **Step 2: docs/ProjectReference.md** — add a `## Configuration` section: where the file lives, the comment convention with one example entry, the violation vocabulary of the drift test in a table, how to regenerate docs, and how to publish the wiki page (`bin/remote.sh pages-push docs/wikantik-pages` or the `write_pages` MCP tool when the tunnel is up).

- [ ] **Step 3: docs/DockerDeployment.md** — for each row of the `WIKANTIK_*` table, set the `Default` column to the value now in `ini/wikantik.properties` for the key in `Maps to`. Add one sentence above the table: "Defaults below are copied from `docs/ConfigurationReference.md`; that page is authoritative."

- [ ] **Step 4: README.md and the hub page** — add the pointer bullet and the `related:` entry.

- [ ] **Step 5: Verify and commit**

```bash
mvn -q -pl wikantik-extract-cli test -Dtest='MainPageRegressionTest,ConfigReferenceRegressionTest'
git add CLAUDE.md docs/ProjectReference.md README.md docs/DockerDeployment.md docs/wikantik-pages/WikantikDevelopment.md
git commit -m "docs: configuration reference pointers; DockerDeployment defaults reconciled with ini/wikantik.properties"
```

---

### Task 14: Full gate and publish

- [ ] **Step 1: Canonical gate**

```bash
bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4
bin/agent-build.sh wait gate 540   # poll until SUCCESS or FAILED; never end the turn waiting
```
Expected: SUCCESS. If any test that boots an engine changed behaviour because a formerly-null property is now `""`, that is a blank-means miss: fix the reader test-first (procedure step 4), not the file.

- [ ] **Step 2: Publish the wiki page** when the MCP tunnel is reachable: `write_pages` with the content of `docs/wikantik-pages/WikantikConfigurationReference.md`; otherwise `bin/remote.sh pages-push docs/wikantik-pages` after confirming the `--dry-run` only adds/updates that one file. Verify with `assemble_bundle` or a browser that the page renders under the Wikantik Development hub.

- [ ] **Step 3: Record completion** — one line in the spec's Status: `shipped <date>, <commit>`; commit as `docs: configuration surface shipped`.

---

## Self-Review

**Spec coverage:** Decision 1 (complete, explicit, uncommented) → Tasks 3, 6–10. Decision 2 (structured description, sections, Type) → Tasks 1, 4. Decision 3 (secrets blank) → `SECRET_HAS_VALUE`, Task 7. Decision 4 (blank means unset + reader tests) → procedure step 4, hazards in Tasks 6–9. Decision 5 (drift test) → Tasks 3, 11. Decision 6 (ratchet) → Tasks 3, 10, 12 step 6. Decision 7 (generated docs + regression test + wrapper + wiki page) → Tasks 12, 13, 14. Decision 8 (dense backend divergence) → Task 8, `KNOWN_DIVERGENT` in Task 11. Out-of-scope items are not touched by any task.

**Placeholder scan:** Group tasks list exact keys and reuse one written-out procedure with a fully worked entry. Descriptions of 170 entries are the work itself and are constrained by the convention and the parser tests, not left as "TBD".

**Type consistency:** `ConfigReference.parse(List<String>, String)` / `parse(Path, String)`, `Parsed.entries()/commentedOutKeys()/duplicateKeys()/entry()/keys()`, `Entry.isBlank()/hasType()/hasDescription()/envOverrideName()/blankMeans()/source()/section()` are used identically in Tasks 1, 3, 11, 12. `GenerateConfigReferenceCli.run(Path, Mode)` returning `Result(exitCode, summary)` matches Tasks 12 step 3 and step 4. Violation kinds in Task 3 match the baseline grep in Task 4 and the new kind in Task 11.
