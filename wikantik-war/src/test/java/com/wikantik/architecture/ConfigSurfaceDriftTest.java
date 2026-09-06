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

import static org.junit.jupiter.api.Assertions.*;

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
    static final Path TOOLS_INI = Path.of( "wikantik-tools/src/main/resources/wikantik-tools.properties" );
    static final Path BASELINE = Path.of( "build-support/config-surface-baseline.tsv" );
    static final List<String> MCP_MODULES = List.of( "wikantik-mcp-core", "wikantik-admin-mcp", "wikantik-knowledge" );
    static final List<String> TOOLS_MODULES = List.of( "wikantik-tools" );

    /** Literals that look like keys but are not configuration. Each must still occur in source (self-pruning). */
    static final Map<String, String> NOT_CONFIG = Map.ofEntries(
        Map.entry( "wikantik.page.views", "metric" ),
        Map.entry( "wikantik.page.edits", "metric" ),
        Map.entry( "wikantik.page.deletes", "metric" ),
        Map.entry( "wikantik.auth.logins", "metric" ),
        Map.entry( "wikantik.kg_judge.timeouts", "metric" ),
        Map.entry( "wikantik.kg_judge.short_circuit_total", "metric" ),
        Map.entry( "wikantik.kg_judge.timeout_multiplier_applied", "metric" ),
        // Fix round 1 (Task 10 pre-review): .sites/.engines were mislabelled here — they are
        // real config (InsightsIngestResource.PROP_SITES/PROP_ENGINES), now declared in
        // ini/wikantik.properties. .rows/.last_success_timestamp really are Micrometer metric
        // names (InsightsMetrics.java:41-42, ROWS_METRIC/LAST_SUCCESS_METRIC).
        Map.entry( "wikantik.insights.ingest.rows", "metric (InsightsMetrics.ROWS_METRIC)" ),
        Map.entry( "wikantik.insights.ingest.last_success_timestamp", "metric (InsightsMetrics.LAST_SUCCESS_METRIC)" ),
        Map.entry( "wikantik.apikey.record", "request attribute" ),
        Map.entry( "wikantik.context", "request attribute" ),
        Map.entry( "wikantik.runFilters", "Context variable" ),
        Map.entry( "wikantik.policy", "policy file name" ),
        Map.entry( "wikantik.custom.config", "servlet init-param, documented in precedence section" ),
        Map.entry( "wikantik.ingest.truncated", "response marker" ),
        Map.entry( "wikantik.attachmentsCache", "cache name" ),
        Map.entry( "wikantik.attachmentCollectionsCache", "cache name" ),
        Map.entry( "wikantik.dynamicAttachmentCache", "cache name" ),
        Map.entry( "wikantik.pageCache", "cache name" ),
        Map.entry( "wikantik.pageTextCache", "cache name" ),
        Map.entry( "wikantik.pageHistoryCache", "cache name" ),
        Map.entry( "wikantik.renderingCache", "cache name" ),
        Map.entry( "wikantik.htmlCache", "cache name" ),
        Map.entry( "wikantik.forAgentCache", "cache name" ),
        Map.entry( "wikantik.translatorReader.runPlugins", "dead constant, never consulted" ),
        Map.entry( "wikantik.translatorReader.useAttachmentImage", "dead constant, never consulted" ),
        Map.entry( "wikantik.nofilterencoding", "dead constant, never consulted" ),
        Map.entry( "mcp.access", "access-surface label" ),
        // Task 7 (Group B): both are literal "wikantik.*" strings caught by KEY_LITERAL
        // that are not, in fact, config read from this file.
        Map.entry( "wikantik.tools", "prefix constant" ),                    // ToolsMetricsBridge.PFX; real meter names are PFX + ".x" concatenations, invisible to KEY_LITERAL
        Map.entry( "wikantik.passwordMustChange", "session attribute" ),     // PasswordChangeGate.SESSION_ATTRIBUTE, not a config property
        // Task 8 (Group C): HybridMetricsBridge.PFX ("wikantik.search.hybrid") is a
        // Micrometer meter-name prefix, not a config key - real config under the
        // same string ("wikantik.search.hybrid.enabled", ".rrf.k", ...) is declared
        // separately and unaffected. ".vector_index.size" is one of the concatenated
        // gauge names (PFX + ".vector_index.size"), read back by AdminOverviewResource
        // via MetricReads.gauge(), same pattern as the other "metric" entries above.
        Map.entry( "wikantik.search.hybrid", "prefix constant" ),            // HybridMetricsBridge.PFX
        Map.entry( "wikantik.search.hybrid.vector_index.size", "metric" ),   // Micrometer gauge name
        // Task 10: ToolsAccessFilter.SURFACE label (same pattern as "mcp.access" above) - not a
        // config key, the real tools.access.* keys are declared separately.
        Map.entry( "tools.access", "access-surface label" )
    );

    /** Config keys physically declared in {@link #MCP_INI} even though the code reads them with a {@code wikantik.} prefix. */
    static final String MCP_FILE_WIKANTIK_PREFIX = "wikantik.mcp.";

    /** Key families read by prefix; file entries under them are examples and are never UNREFERENCED. */
    static final List<String> DYNAMIC_PREFIXES = List.of(
        "wikantik.interWikiRef.", "wikantik.specialPage.", "wikantik.loginModule.options.",
        "wikantik.sso.claimMapping.", "wikantik.translatorReader.inlinePattern.", "wikantik.custom.cascade.",
        "wikantik.connectors.", "wikantik.knowledge.extractor.", "wikantik.bundle.reranker.",
        "wikantik.bundle.decomposition.", "wikantik.briefing.", "wikantik.search.hybrid.embedder.", "wikantik.tools."
    );

    static final Set<String> TYPES = Set.of( "boolean", "int", "long", "double", "string", "path", "url", "class", "list", "secret" );
    static final Pattern ENUM_TYPE = Pattern.compile( "^enum\\(([^)]+)\\)$" );
    // Both segments include '-' so hyphenated keys (wikantik.preferences.default-locale,
    // wikantik.search.hybrid.embedder.cache.ttl-seconds, wikantik.search.embedding.base-url, ...)
    // are detected as codeKeys instead of being invisible to the scanner. "tools" is the third
    // properties namespace (wikantik-tools.properties), scanned only within TOOLS_MODULES.
    static final Pattern KEY_LITERAL = Pattern.compile( "\"((?:wikantik|mcp|tools)\\.[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*)\"" );

    /**
     * Matches a {@code *Property(..., "key", <literal default>)} read site and captures the
     * key plus the literal default (string, numeric, or boolean). Widened per the Task 11
     * addendum to include {@code tools.*} keys and hyphenated key segments; a non-literal
     * default (a constant name such as {@code SOME_CONSTANT}) simply fails to match group 2
     * and the site is invisible here, which is deliberate — it is not checkable this way.
     */
    static final Pattern LITERAL_DEFAULT = Pattern.compile(
        "Property\\(\\s*[^,()]*,?\\s*\"((?:wikantik|mcp|tools)\\.[a-zA-Z0-9_.-]+)\"\\s*,\\s*(\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?)[LlDdFf]?|(true|false))\\s*\\)" );

    /** Deliberate code/file divergences, each with the reason an executor needs. */
    static final Map<String, String> KNOWN_DIVERGENT = Map.ofEntries(
        Map.entry( "wikantik.search.dense.backend", "fallback path defaults to inmemory (no DataSource); wiring path and file say lucene-hnsw — DenseBackendResolutionTest pins both" ),
        // ToolsConfig/McpConfig's own literal (0 = unlimited) is a code-level "properties object
        // has no key at all" fallback, pinned by rateLimitDefaults()/testRateLimitDefaults() for an
        // empty Properties. Production always loads the bundled wikantik-tools.properties /
        // wikantik-mcp.properties from the classpath, so the shipped 100/10 rate limits are what
        // actually governs — the 0 fallback is defense against a missing/corrupt bundled file, not
        // the intended production value.
        Map.entry( "tools.ratelimit.global", "code default 0 (unlimited) is the no-properties-object fallback, pinned by ToolsConfigTest#rateLimitDefaults; the bundled wikantik-tools.properties always ships 100 and is what production runs with" ),
        Map.entry( "tools.ratelimit.perClient", "code default 0 (unlimited) is the no-properties-object fallback, pinned by ToolsConfigTest#rateLimitDefaults; the bundled wikantik-tools.properties always ships 10 and is what production runs with" ),
        Map.entry( "mcp.ratelimit.global", "code default 0 (unlimited) is the no-properties-object fallback, pinned by McpConfigTest#testRateLimitDefaults; the bundled wikantik-mcp.properties always ships 100 and is what production runs with" ),
        Map.entry( "mcp.ratelimit.perClient", "code default 0 (unlimited) is the no-properties-object fallback, pinned by McpConfigTest#testRateLimitDefaults; the bundled wikantik-mcp.properties always ships 10 and is what production runs with" ),
        // wireHybridRetrieval's own getProperty(...,"false") fires only when the key is absent
        // from the Properties object passed in. Production's ini/wikantik.properties has shipped
        // "true" since f979f0d698 (2026-06-18 recall sweep: strict improvement over dense-only,
        // eval/bm25-chunk-spike/findings.md). The per-module test-resource ini/wikantik.properties
        // fixtures deliberately do NOT declare this key, so ~4000 wikantik-main unit tests that
        // boot a TestEngine keep building dense-only bundle sources instead of a real Lucene BM25
        // index against the test Postgres DataSource on every startup — flipping the code literal
        // to match the shipped file would change that behaviour suite-wide, not just fix a stale
        // constant. BundleSourcesWithoutEmbedderTest opts a single test in explicitly.
        Map.entry( "wikantik.bundle.bm25.enabled", "code default false is the property-absent fallback the test-resource ini fixtures rely on to skip a per-test Lucene BM25 build; production's shipped ini has been true since f979f0d698 (2026-06-18 recall sweep)" )
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

    @Test
    void defaults_files_are_pure_ascii() throws IOException {
        final Path root = repoRoot();
        for( final Path f : List.of( root.resolve( INI ), root.resolve( MCP_INI ), root.resolve( TOOLS_INI ) ) ) {
            final List<String> lines = Files.readAllLines( f, StandardCharsets.ISO_8859_1 );
            for( int i = 0; i < lines.size(); i++ ) {
                final String l = lines.get( i );
                final int line = i + 1;
                assertTrue( l.chars().allMatch( ch -> ch < 128 ), () -> f.getFileName() + ":" + line + " contains a non-ASCII character: " + l );
            }
        }
    }

    /**
     * Loads each shipped defaults file with {@link java.util.Properties#load(java.io.InputStream)}
     * (the SOURCE path, not the classpath — test-resource copies of the same filename shadow the
     * production file on every module's test classpath, so nothing else proves the shipped file
     * itself is even well-formed) and asserts its key set matches {@link ConfigReference}'s parse
     * of the same file. Any disagreement means a malformed line (bad continuation, stray colon,
     * unescaped character) that {@code ConfigReference}'s line-oriented parser tolerates but the
     * JDK's stricter parser does not, or vice versa.
     */
    @Test
    void production_defaults_load_as_java_properties() throws IOException {
        final Path root = repoRoot();

        // ini/wikantik.properties also carries a trailing log4j2 config block (appender.*,
        // logger.wikantik.*, mail.*, ...) in the same file — real, unrelated keys that must
        // stay out of the comparison, hence the prefix filter on both sides.
        assertPropertiesKeysMatch( root.resolve( INI ), "wikantik." );
        assertPropertiesKeysMatch( root.resolve( TOOLS_INI ), "tools." );

        // MCP_INI declares both "mcp.*" and "wikantik.mcp.*" keys in the one file — union both
        // ConfigReference prefixes and filter the Properties side to either prefix.
        final java.util.Properties mcpProps = loadProperties( root.resolve( MCP_INI ) );
        final Set<String> mcpExpected = new TreeSet<>( ConfigReference.parse( root.resolve( MCP_INI ), "mcp." ).keys() );
        mcpExpected.addAll( ConfigReference.parse( root.resolve( MCP_INI ), MCP_FILE_WIKANTIK_PREFIX ).keys() );
        final Set<String> mcpActual = new TreeSet<>();
        for( final String k : mcpProps.stringPropertyNames() ) {
            if( k.startsWith( "mcp." ) || k.startsWith( MCP_FILE_WIKANTIK_PREFIX ) ) {
                mcpActual.add( k );
            }
        }
        assertEquals( mcpExpected, mcpActual,
            () -> MCP_INI + " does not parse identically under java.util.Properties and ConfigReference" );
    }

    private static void assertPropertiesKeysMatch( final Path file, final String prefix ) throws IOException {
        final java.util.Properties props = loadProperties( file );
        final Set<String> expected = ConfigReference.parse( file, prefix ).keys();
        final Set<String> actual = new TreeSet<>();
        for( final String k : props.stringPropertyNames() ) {
            if( k.startsWith( prefix ) ) {
                actual.add( k );
            }
        }
        assertEquals( new TreeSet<>( expected ), actual,
            () -> file + " does not parse identically under java.util.Properties and ConfigReference" );
    }

    private static java.util.Properties loadProperties( final Path file ) throws IOException {
        final java.util.Properties props = new java.util.Properties();
        try( java.io.InputStream is = Files.newInputStream( file ) ) {
            props.load( is );
        }
        return props;
    }

    @Test
    void comment_stripper_ignores_comment_markers_inside_string_literals() {
        final String src = "a = props.getProperty( \"http://x/\", \"wikantik.after.url\" ); // trailing /* not a block\n"
            + "b = \"wikantik.in.string\"; /* real block \"wikantik.in.block\" */ c = \"wikantik.after.block\";\n"
            + "// line comment with \"wikantik.in.line.comment\"\n"
            + "d = '\"'; e = \"wikantik.after.char\";\n"
            + "/** javadoc\n * \"wikantik.in.javadoc\"\n */ f = \"wikantik.after.javadoc\";\n";
        final Set<String> keys = literals( stripComments( src ), "wikantik." );
        assertEquals( Set.of( "wikantik.after.url", "wikantik.in.string", "wikantik.after.block", "wikantik.after.char", "wikantik.after.javadoc" ), keys );
    }

    @Test
    void line_comment_containing_block_opener_does_not_swallow_following_lines() {
        final String src = "// D27: match /api/structure/*.\nx = \"wikantik.survives\";\n/** doc */\n";
        assertEquals( Set.of( "wikantik.survives" ), literals( stripComments( src ), "wikantik." ) );
    }

    @Test
    void wikantik_mcp_prefixed_literals_route_to_the_mcp_set_not_the_wikantik_set() {
        final String src = "a = props.getProperty( \"wikantik.mcp.rate_limit.max_clients\" );\n"
            + "b = \"wikantik.other.key\";\n";
        final Set<String> wikantik = new TreeSet<>( literals( stripComments( src ), "wikantik." ) );
        final Set<String> mcp = new TreeSet<>();

        routeMcpFileKeys( wikantik, mcp );

        assertEquals( Set.of( "wikantik.other.key" ), wikantik );
        assertEquals( Set.of( "wikantik.mcp.rate_limit.max_clients" ), mcp );
    }

    @Test
    void tools_prefixed_literal_is_collected_by_literals_after_key_literal_widening() {
        // Third properties namespace (wikantik-tools.properties): KEY_LITERAL must recognise
        // "tools.*" string literals the same way it already recognises "wikantik.*"/"mcp.*".
        final String src = "a = props.getProperty( \"tools.x\" );\n";
        assertEquals( Set.of( "tools.x" ), literals( stripComments( src ), "tools." ) );
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

    @Test
    void literal_default_extractor_captures_hyphenated_and_tools_prefixed_keys() {
        final String src = "x = props.getProperty( \"wikantik.preferences.default-locale\", \"en\" );\n"
            + "y = ToolsConfig.intProperty( \"tools.ratelimit.global\", 0 );\n";
        final Map<String, Set<String>> d = literalDefaults( src );
        assertEquals( Set.of( "en" ), d.get( "wikantik.preferences.default-locale" ) );
        assertEquals( Set.of( "0" ), d.get( "tools.ratelimit.global" ) );
    }

    @Test
    void value_parses_accepts_a_class_value_that_resolves_via_forname() {
        final ConfigReference.Entry e = new ConfigReference.Entry( "wikantik.k", "java.lang.String",
            List.of(), "class", null, null, "section", 1 );
        assertTrue( valueParses( e ) );
    }

    @Test
    void value_parses_rejects_a_class_value_that_does_not_resolve() {
        final ConfigReference.Entry e = new ConfigReference.Entry( "wikantik.k",
            "org.apache.lucene.analysis.standard.ClassicAnalyzer", List.of(), "class", null, null, "section", 1 );
        assertFalse( valueParses( e ) );
    }

    @Test
    void value_parses_skips_a_short_class_name_without_a_dot() {
        // Resolved by ClassUtil package search at runtime (e.g. "BasicAttachmentProvider"), not Class.forName.
        final ConfigReference.Entry e = new ConfigReference.Entry( "wikantik.k", "BasicAttachmentProvider",
            List.of(), "class", null, null, "section", 1 );
        assertTrue( valueParses( e ) );
    }

    // ---------------------------------------------------------------- scanning

    static Set<String> computeViolations( final Path root ) throws IOException {
        final Set<String> out = new TreeSet<>();
        final String allSource = readStrippedSource( root, null );
        final String mcpSource = readStrippedSource( root, MCP_MODULES );
        final String toolsSource = readStrippedSource( root, TOOLS_MODULES );

        final Set<String> wikantikLiterals = new TreeSet<>( literals( allSource, "wikantik." ) );
        final Set<String> mcpLiterals = new TreeSet<>( literals( mcpSource, "mcp." ) );
        final Set<String> toolsLiterals = new TreeSet<>( literals( toolsSource, "tools." ) );
        routeMcpFileKeys( wikantikLiterals, mcpLiterals );

        for( final Map.Entry<String, String> nc : NOT_CONFIG.entrySet() ) {
            if( !wikantikLiterals.contains( nc.getKey() ) && !mcpLiterals.contains( nc.getKey() ) && !toolsLiterals.contains( nc.getKey() ) ) {
                out.add( nc.getKey() + "\tSTALE_NOT_CONFIG" );   // prune the allow-list
            }
        }
        wikantikLiterals.removeAll( NOT_CONFIG.keySet() );
        mcpLiterals.removeAll( NOT_CONFIG.keySet() );
        toolsLiterals.removeAll( NOT_CONFIG.keySet() );

        final Map<String, Set<String>> wikantikDefaults = new java.util.TreeMap<>( literalDefaults( allSource ) );
        final Map<String, Set<String>> mcpDefaults = new java.util.TreeMap<>( literalDefaults( mcpSource ) );
        final Map<String, Set<String>> toolsDefaults = literalDefaults( toolsSource );
        routeMcpFileKeyDefaults( wikantikDefaults, mcpDefaults );

        checkFile( ConfigReference.parse( root.resolve( INI ), "wikantik." ), wikantikLiterals, wikantikDefaults, out );
        checkFile( mergeParsed(
            ConfigReference.parse( root.resolve( MCP_INI ), "mcp." ),
            ConfigReference.parse( root.resolve( MCP_INI ), MCP_FILE_WIKANTIK_PREFIX ) ), mcpLiterals, mcpDefaults, out );
        checkFile( ConfigReference.parse( root.resolve( TOOLS_INI ), "tools." ), toolsLiterals, toolsDefaults, out );
        return out;
    }

    /** Moves {@code wikantik.mcp.*} literals (declared in {@link #MCP_INI}, not {@link #INI}) from the {@code wikantik.*} set to the {@code mcp.*} set. */
    static void routeMcpFileKeys( final Set<String> wikantik, final Set<String> mcp ) {
        final Set<String> toMove = new TreeSet<>();
        for( final String k : wikantik ) {
            if( k.startsWith( MCP_FILE_WIKANTIK_PREFIX ) ) {
                toMove.add( k );
            }
        }
        wikantik.removeAll( toMove );
        mcp.addAll( toMove );
    }

    /**
     * Moves {@code wikantik.mcp.*} literal-default entries from the {@code wikantik.*} defaults map to
     * the {@code mcp.*} one (mirrors {@link #routeMcpFileKeys}), unioning with anything already found
     * there — {@code allSource} and {@code mcpSource} scan the same MCP-module files, so the same read
     * sites are normally found in both; the union is defensive, not load-bearing.
     */
    static void routeMcpFileKeyDefaults( final Map<String, Set<String>> wikantik, final Map<String, Set<String>> mcp ) {
        final Set<String> toMove = new TreeSet<>();
        for( final String k : wikantik.keySet() ) {
            if( k.startsWith( MCP_FILE_WIKANTIK_PREFIX ) ) {
                toMove.add( k );
            }
        }
        for( final String k : toMove ) {
            final Set<String> moved = wikantik.remove( k );
            mcp.merge( k, moved, ( a, b ) -> { final Set<String> u = new TreeSet<>( a ); u.addAll( b ); return u; } );
        }
    }

    /** Combines two {@link ConfigReference.Parsed} results (same file, different key-prefix filters) into one. */
    static ConfigReference.Parsed mergeParsed( final ConfigReference.Parsed a, final ConfigReference.Parsed b ) {
        final List<ConfigReference.Entry> entries = new ArrayList<>( a.entries() );
        entries.addAll( b.entries() );
        final List<String> commented = new ArrayList<>( a.commentedOutKeys() );
        commented.addAll( b.commentedOutKeys() );
        final List<String> duplicates = new ArrayList<>( a.duplicateKeys() );
        duplicates.addAll( b.duplicateKeys() );
        return new ConfigReference.Parsed( List.copyOf( entries ), List.copyOf( commented ), List.copyOf( duplicates ) );
    }

    static void checkFile( final ConfigReference.Parsed parsed, final Set<String> codeKeys,
                           final Map<String, Set<String>> defaults, final Set<String> out ) {
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
            final Set<String> code = defaults.getOrDefault( e.key(), Set.of() );
            if( !code.isEmpty() && !KNOWN_DIVERGENT.containsKey( e.key() )
                && code.stream().noneMatch( c -> sameValue( e.value(), c ) ) ) {
                out.add( e.key() + "\tDEFAULT_MISMATCH(file=" + e.value() + ", code=" + String.join( "|", code ) + ")" );
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
                case "class" -> {
                    // Short names without a dot are resolved by ClassUtil package search at
                    // runtime (e.g. "BasicAttachmentProvider"), not Class.forName — skip those.
                    if( v.contains( "." ) ) {
                        try {
                            Class.forName( v, false, ConfigSurfaceDriftTest.class.getClassLoader() );
                        } catch( final ClassNotFoundException cnfe ) {
                            return false;
                        }
                    }
                }
                default -> {
                    final Matcher m = ENUM_TYPE.matcher( e.type() );
                    if( m.matches() ) {
                        return java.util.Arrays.asList( m.group( 1 ).split( "\\|" ) ).contains( v );
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
                        sb.append( stripComments( text ) ).append( '\n' );
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Removes line and block comments while leaving string and char literals intact (a comment marker inside a literal is content). */
    static String stripComments( final String text ) {
        final StringBuilder out = new StringBuilder( text.length() );
        final int n = text.length();
        int i = 0;
        while( i < n ) {
            final char c = text.charAt( i );
            final char next = i + 1 < n ? text.charAt( i + 1 ) : '\0';
            if( c == '"' || c == '\'' ) {                    // string or char literal: copy verbatim, honouring escapes
                final char quote = c;
                out.append( c );
                i++;
                while( i < n ) {
                    final char d = text.charAt( i );
                    out.append( d );
                    i++;
                    if( d == '\\' && i < n ) {
                        out.append( text.charAt( i ) );
                        i++;
                    } else if( d == quote || d == '\n' ) {
                        break;
                    }
                }
            } else if( c == '/' && next == '/' ) {          // line comment: drop to end of line, keep the newline
                while( i < n && text.charAt( i ) != '\n' ) {
                    i++;
                }
            } else if( c == '/' && next == '*' ) {          // block comment: drop through the closing */
                final int end = text.indexOf( "*/", i + 2 );
                i = end < 0 ? n : end + 2;
            } else {
                out.append( c );
                i++;
            }
        }
        return out.toString();
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
