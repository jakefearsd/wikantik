/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.extractcli.configref;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import com.wikantik.util.config.ConfigReference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates {@code docs/ConfigurationReference.md} and the wiki page
 * {@code docs/wikantik-pages/WikantikConfigurationReference.md} from the
 * three defaults files parsed by {@link ConfigReference}: {@code
 * ini/wikantik.properties} ({@code wikantik.} prefix), {@code
 * wikantik-mcp.properties} ({@code mcp.} and {@code wikantik.mcp.} prefixes,
 * merged), and {@code wikantik-tools.properties} ({@code tools.} prefix).
 *
 * <p>Mirrors {@code GenerateMainPageCli}: two modes, {@code --write}
 * (overwrite the committed docs) and {@code --check} (fail if they have
 * drifted). Rendering uses jmustache the same way {@code MainPageRenderer}
 * does — {@code standardsMode(false).escapeHTML(false)} on a template
 * compiled from a classpath resource.</p>
 */
public final class GenerateConfigReferenceCli {

    public enum Mode { WRITE, CHECK }

    public record Result( int exitCode, String summary ) {}

    /**
     * One defaults file feeding the generated docs, with the prose used to introduce it.
     *
     * @param rawLines  the file's raw lines (same charset {@link ConfigReference} parses with),
     *                  used only to recover paragraph grouping inside a comment block that
     *                  {@link ConfigReference.Entry#description()} itself does not expose — see
     *                  {@link #scanHoists}. Tests that don't exercise that feature may pass
     *                  {@code List.of()}.
     * @param fixedOverrideText when non-null, rendered verbatim in every row's Override column
     *                          instead of the entry's env-var name — for sources such as
     *                          {@code wikantik-mcp.properties}/{@code wikantik-tools.properties}
     *                          whose loader (McpConfig/ToolsConfig) reads only the jar-bundled
     *                          file overlaid by a same-named file in {@code tomcat/lib/}: no
     *                          environment variable, {@code -D} flag, or
     *                          {@code wikantik-custom.properties} entry ever reaches it.
     */
    public record SourceFile( String label, String pathLabel, String overrideNote, ConfigReference.Parsed parsed,
                              List<String> rawLines, String fixedOverrideText ) {
        public SourceFile( final String label, final String pathLabel, final String overrideNote,
                           final ConfigReference.Parsed parsed ) {
            this( label, pathLabel, overrideNote, parsed, List.of(), null );
        }
        public SourceFile( final String label, final String pathLabel, final String overrideNote,
                           final ConfigReference.Parsed parsed, final String fixedOverrideText ) {
            this( label, pathLabel, overrideNote, parsed, List.of(), fixedOverrideText );
        }
        public SourceFile( final String label, final String pathLabel, final String overrideNote,
                           final ConfigReference.Parsed parsed, final List<String> rawLines ) {
            this( label, pathLabel, overrideNote, parsed, rawLines, null );
        }
    }

    private static final String MAIN_TEMPLATE = "ConfigurationReference.md.mustache";
    private static final String WIKI_TEMPLATE = "WikantikConfigurationReference.md.mustache";

    public Result run( final Path repoRoot, final Mode mode ) throws IOException {
        final Path iniPath = repoRoot.resolve( "wikantik-main/src/main/resources/ini/wikantik.properties" );
        final Path mcpPath = repoRoot.resolve( "wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties" );
        final Path toolsPath = repoRoot.resolve( "wikantik-tools/src/main/resources/wikantik-tools.properties" );

        final ConfigReference.Parsed iniParsed = ConfigReference.parse( iniPath, "wikantik." );
        final ConfigReference.Parsed mcpParsed = mergeParsed(
                ConfigReference.parse( mcpPath, "mcp." ),
                ConfigReference.parse( mcpPath, "wikantik.mcp." ) );
        final ConfigReference.Parsed toolsParsed = ConfigReference.parse( toolsPath, "tools." );

        final List<String> iniLines = Files.readAllLines( iniPath, StandardCharsets.ISO_8859_1 );
        final List<String> mcpLines = Files.readAllLines( mcpPath, StandardCharsets.ISO_8859_1 );
        final List<String> toolsLines = Files.readAllLines( toolsPath, StandardCharsets.ISO_8859_1 );

        final List<SourceFile> sources = List.of(
                new SourceFile( "Wikantik core settings",
                        "wikantik-main/src/main/resources/ini/wikantik.properties",
                        "the primary settings surface: bundled in the wikantik-main jar, "
                        + "overridden via the precedence chain described above", iniParsed, iniLines ),
                new SourceFile( "MCP admin server",
                        "wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties",
                        "bundled in the wikantik-admin-mcp jar, overlaid by a same-named file in `tomcat/lib/`", mcpParsed,
                        mcpLines, "`tomcat/lib/wikantik-mcp.properties`" ),
                new SourceFile( "OpenAPI tools server",
                        "wikantik-tools/src/main/resources/wikantik-tools.properties",
                        "bundled in the wikantik-tools jar, overlaid by a same-named file in `tomcat/lib/`", toolsParsed,
                        toolsLines, "`tomcat/lib/wikantik-tools.properties`" )
        );

        final String mainMd = render( sources, MAIN_TEMPLATE );
        final String wikiMd = render( sources, WIKI_TEMPLATE );

        final Path mainFile = repoRoot.resolve( "docs/ConfigurationReference.md" );
        final Path wikiFile = repoRoot.resolve( "docs/wikantik-pages/WikantikConfigurationReference.md" );

        if ( mode == Mode.WRITE ) {
            Files.writeString( mainFile, mainMd, StandardCharsets.UTF_8 );
            Files.writeString( wikiFile, wikiMd, StandardCharsets.UTF_8 );
            return new Result( 0, "wrote " + mainFile + " and " + wikiFile );
        }

        final List<String> stale = new ArrayList<>();
        if ( !matches( mainFile, mainMd, false ) ) {
            stale.add( mainFile.toString() );
        }
        if ( !matches( wikiFile, wikiMd, true ) ) {
            stale.add( wikiFile.toString() );
        }
        if ( stale.isEmpty() ) {
            return new Result( 0, "in sync" );
        }
        return new Result( 1, "stale: " + String.join( ", ", stale )
                + " (run bin/config-reference.sh --write to regenerate)" );
    }

    /**
     * Renders {@code sources} (in the order given — ini, then MCP, then tools) through the
     * named classpath template. Sections are grouped by name in order of first appearance
     * <em>within each source file</em>, so a section marker repeated at several points in one
     * defaults file still renders as a single heading with every entry under it.
     *
     * <p>Two cross-file, whole-render concerns live here rather than in {@link #buildSections}:
     * a heading whose text was already used by an earlier source file gets the current file's
     * label appended so the two stay separately linkable (see {@code REST API, MCP & agent
     * surfaces}, declared verbatim in both {@code ini/wikantik.properties} and {@code
     * wikantik-mcp.properties}), and every {@code secret}-typed entry, across all files, is
     * collected into one top-of-document index.</p>
     */
    public String render( final List<SourceFile> sources, final String templateResource ) {
        final Template template = loadTemplate( templateResource );
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put( "date", LocalDate.now().toString() );

        final Set<String> usedSectionNames = new LinkedHashSet<>();
        final List<Map<String, Object>> secretRows = new ArrayList<>();

        final List<Map<String, Object>> files = new ArrayList<>( sources.size() );
        for ( final SourceFile sf : sources ) {
            final Map<String, Object> f = new LinkedHashMap<>();
            f.put( "introText", introText( sf ) );
            final List<Map<String, Object>> sections = buildSections( sf, usedSectionNames, secretRows );
            f.put( "sections", sections );
            f.put( "toc", tocFor( sections ) );
            files.add( f );
        }
        root.put( "files", files );
        root.put( "secrets", secretRows );
        root.put( "hasSecrets", !secretRows.isEmpty() );

        return normalize( template.execute( root ) );
    }

    public static void main( final String[] args ) throws Exception {
        if ( args.length < 1 ) {
            System.err.println( "Usage: generate-config-reference <repoRoot> [--write|--check]" );
            System.exit( 64 );
        }
        final Path repoRoot = Path.of( args[ 0 ] );
        Mode mode = Mode.WRITE;
        for ( int i = 1; i < args.length; i++ ) {
            switch ( args[ i ] ) {
                case "--write" -> mode = Mode.WRITE;
                case "--check" -> mode = Mode.CHECK;
                default -> {
                    System.err.println( "unknown flag: " + args[ i ] );
                    System.exit( 64 );
                }
            }
        }
        final Result result = new GenerateConfigReferenceCli().run( repoRoot, mode );
        System.out.println( result.summary() );
        System.exit( result.exitCode() );
    }

    // ------------------------------------------------------------------
    // Rendering helpers
    // ------------------------------------------------------------------

    private static String introText( final SourceFile sf ) {
        return "The following settings come from `" + sf.pathLabel() + "` (" + sf.overrideNote() + ").";
    }

    /**
     * Builds this file's section models, in file order. Each section carries: its (possibly
     * disambiguated) display name and anchor slug, any hoisted section-preamble paragraphs (see
     * {@link #scanHoists}), its table rows, and a definition list of full descriptions for the
     * rows whose Description cell got truncated (see {@link #renderDescription}). As a side
     * effect, every {@code secret}-typed entry is appended to {@code secretsOut}.
     */
    private static List<Map<String, Object>> buildSections( final SourceFile sf, final Set<String> usedSectionNames,
                                                              final List<Map<String, Object>> secretsOut ) {
        final Map<Integer, Hoist> hoists = scanHoists( sf.rawLines() );

        final List<ConfigReference.Entry> sorted = new ArrayList<>( sf.parsed().entries() );
        sorted.sort( Comparator.comparingInt( ConfigReference.Entry::line ) );

        final LinkedHashMap<String, List<ConfigReference.Entry>> grouped = new LinkedHashMap<>();
        for ( final ConfigReference.Entry e : sorted ) {
            final String name = ( e.section() == null || e.section().isBlank() ) ? "Uncategorized" : e.section();
            grouped.computeIfAbsent( name, k -> new ArrayList<>() ).add( e );
        }

        final List<Map<String, Object>> sections = new ArrayList<>( grouped.size() );
        for ( final Map.Entry<String, List<ConfigReference.Entry>> g : grouped.entrySet() ) {
            final String originalName = g.getKey();
            final String displayName = usedSectionNames.contains( originalName )
                    ? originalName + " (" + sf.label() + ")"
                    : originalName;
            usedSectionNames.add( originalName );
            final String anchor = slug( displayName );

            final List<Map<String, Object>> preamble = new ArrayList<>();
            final List<Map<String, Object>> entries = new ArrayList<>( g.getValue().size() );
            final List<Map<String, Object>> fullDescriptions = new ArrayList<>();

            for ( final ConfigReference.Entry e : g.getValue() ) {
                final Hoist hoist = hoists.get( e.line() );
                final List<String> ownDescription = hoist != null ? hoist.ownDescription() : e.description();
                if ( hoist != null ) {
                    for ( final String paragraph : hoist.preambleParagraphs() ) {
                        final Map<String, Object> p = new LinkedHashMap<>();
                        p.put( "text", paragraph );
                        preamble.add( p );
                    }
                }

                final DescriptionRender dr = renderDescription( ownDescription );

                final Map<String, Object> row = new LinkedHashMap<>();
                row.put( "key", e.key() );
                row.put( "typeCell", typeCell( e ) );
                row.put( "defaultCell", defaultCell( e ) );
                row.put( "overrideCell", overrideCell( e, sf.fixedOverrideText() ) );
                row.put( "descriptionCell", dr.summary().replace( "|", "\\|" ) );
                entries.add( row );

                if ( dr.truncated() ) {
                    final Map<String, Object> fd = new LinkedHashMap<>();
                    fd.put( "key", e.key() );
                    fd.put( "full", dr.full() );
                    fullDescriptions.add( fd );
                }

                if ( "secret".equals( e.type() ) ) {
                    final Map<String, Object> sr = new LinkedHashMap<>();
                    sr.put( "key", e.key() );
                    sr.put( "fileLabel", sf.label() );
                    sr.put( "sectionName", displayName );
                    sr.put( "sectionAnchor", anchor );
                    secretsOut.add( sr );
                }
            }

            final Map<String, Object> sect = new LinkedHashMap<>();
            sect.put( "name", displayName );
            sect.put( "anchor", anchor );
            sect.put( "preamble", preamble );
            sect.put( "entries", entries );
            sect.put( "fullDescriptions", fullDescriptions );
            sect.put( "hasFullDescriptions", !fullDescriptions.isEmpty() );
            sections.add( sect );
        }
        return sections;
    }

    private static List<Map<String, Object>> tocFor( final List<Map<String, Object>> sections ) {
        final List<Map<String, Object>> toc = new ArrayList<>( sections.size() );
        for ( final Map<String, Object> s : sections ) {
            final Map<String, Object> t = new LinkedHashMap<>();
            t.put( "name", s.get( "name" ) );
            t.put( "anchor", s.get( "anchor" ) );
            toc.add( t );
        }
        return toc;
    }

    /**
     * The Type cell, e.g. {@code enum(a|b|c)}, must not corrupt the Markdown table: the {@code |}
     * separators inside an enum value are indistinguishable from real cell delimiters to a GFM
     * parser. Backticks alone are not sufficient — not every renderer honours "no special meaning
     * inside code spans" for table-cell splitting — so the pipe is escaped as well.
     */
    static String typeCell( final ConfigReference.Entry e ) {
        final String type = e.type() == null ? "" : e.type();
        return "`" + type.replace( "|", "\\|" ) + "`";
    }

    static String defaultCell( final ConfigReference.Entry e ) {
        if ( !e.isBlank() ) {
            return "`" + e.value() + "`";
        }
        if ( "secret".equals( e.type() ) ) {
            return "*(blank)*";
        }
        if ( e.blankMeans() != null && !e.blankMeans().isBlank() ) {
            return "*(blank: " + e.blankMeans() + ")*";
        }
        return "*(blank)*";
    }

    /**
     * @param fixedOverrideText when non-null (see {@link SourceFile#fixedOverrideText()}),
     *                          rendered verbatim instead of the entry's env-var name
     */
    static String overrideCell( final ConfigReference.Entry e, final String fixedOverrideText ) {
        if ( fixedOverrideText != null ) {
            return fixedOverrideText;
        }
        if ( "system-property".equals( e.source() ) ) {
            return "`-D" + e.key() + "` only";
        }
        return "`" + e.envOverrideName() + "`";
    }

    // ------------------------------------------------------------------
    // Description rendering: summarise for the table, keep the full text (with `Example:` lines
    // rendered as code rather than run-on prose) for a per-section definition list.
    // ------------------------------------------------------------------

    /** A key's Description cell is a summary; anything cut from it is shown in full below the
     *  table, so this only needs to keep GFM table rows scannable, not to fit everything. */
    private static final int SUMMARY_MAX_CHARS = 155;

    private static final Pattern EXAMPLE_LINE = Pattern.compile( "^Example:.*$" );
    private static final Pattern SENTENCE_END = Pattern.compile( "[.!?](?=\\s|$)" );

    private record Segment( boolean example, String text ) {}

    record DescriptionRender( String summary, boolean truncated, String full ) {}

    /**
     * Splits {@code lines} into prose runs and standalone {@code Example:} lines, preserving
     * order — a raw comment block interleaves worked examples with prose (see
     * {@code wikantik.pageNameComparator.class}), and joining everything with a bare space (the
     * old behaviour) reads as run-on prose that swallows the example text's own meaning.
     */
    private static List<Segment> toSegments( final List<String> lines ) {
        final List<Segment> segments = new ArrayList<>();
        final List<String> prose = new ArrayList<>();
        for ( final String line : lines ) {
            if ( EXAMPLE_LINE.matcher( line ).matches() ) {
                if ( !prose.isEmpty() ) {
                    segments.add( new Segment( false, String.join( " ", prose ) ) );
                    prose.clear();
                }
                segments.add( new Segment( true, line ) );
            } else {
                prose.add( line );
            }
        }
        if ( !prose.isEmpty() ) {
            segments.add( new Segment( false, String.join( " ", prose ) ) );
        }
        return segments;
    }

    /** Joins {@code lines} into the entry's full text: prose flows normally, each
     *  {@code Example:} line becomes its own inline code span. */
    private static String renderFull( final List<String> lines ) {
        return toSegments( lines ).stream()
                .map( s -> s.example() ? "`" + s.text() + "`" : s.text() )
                .collect( Collectors.joining( " " ) );
    }

    /**
     * Produces the table-cell summary and, when that summary drops content, the full text to
     * list below the table. A description that already fits (no {@code Example:} lines, full
     * text at or under {@link #SUMMARY_MAX_CHARS}) is returned unchanged in both fields and
     * {@code truncated} is false — the common case for the ~80% of keys with a one-sentence
     * description, which render exactly as before.
     */
    static DescriptionRender renderDescription( final List<String> ownDescriptionLines ) {
        final List<Segment> segments = toSegments( ownDescriptionLines );
        final boolean hasExamples = segments.stream().anyMatch( Segment::example );
        final String full = segments.stream()
                .map( s -> s.example() ? "`" + s.text() + "`" : s.text() )
                .collect( Collectors.joining( " " ) );

        if ( !hasExamples && full.length() <= SUMMARY_MAX_CHARS ) {
            return new DescriptionRender( full, false, full );
        }

        final String proseOnly = segments.stream()
                .filter( s -> !s.example() )
                .map( Segment::text )
                .collect( Collectors.joining( " " ) );

        final String base;
        if ( proseOnly.isBlank() ) {
            base = "(see full description below)";
        } else {
            final Matcher m = SENTENCE_END.matcher( proseOnly );
            base = ( m.find() && m.end() <= SUMMARY_MAX_CHARS ) ? proseOnly.substring( 0, m.end() )
                                                                 : truncateAtWordBoundary( proseOnly, SUMMARY_MAX_CHARS );
        }
        return new DescriptionRender( base + " …", true, full );
    }

    static String truncateAtWordBoundary( final String text, final int max ) {
        if ( text.length() <= max ) {
            return text;
        }
        final int cut = text.lastIndexOf( ' ', max );
        return ( cut > 0 ? text.substring( 0, cut ) : text.substring( 0, max ) ).stripTrailing();
    }

    // ------------------------------------------------------------------
    // Section-preamble recovery.
    //
    // ConfigReference.Entry#description() flattens every paragraph in a key's comment block into
    // one list — it has no notion of "this paragraph is generic section prose, that one is the
    // key's own description". Immediately after a `# [Section]` marker, that flattening lets the
    // section's own preamble bleed into the first key that follows it (separated only by a
    // "#"-only comment line, not a truly blank one) — see wikantik.applicationName and
    // wikantik.loginModule.class. This re-scans the raw file once per source to recover the
    // paragraph grouping ConfigReference already discards, scoped deliberately to just the first
    // key after each section marker: multi-paragraph comment blocks occur elsewhere too (a key
    // musing across a few remarks about itself, e.g. wikantik.cache.enable), and outside that
    // "right after the heading" position there's no section-level home to hoist them to.
    // ------------------------------------------------------------------

    private static final Pattern HOIST_SECTION = Pattern.compile( "^#\\s*\\[(.+?)\\]\\s*$" );
    private static final Pattern HOIST_KEY_VALUE = Pattern.compile( "^([A-Za-z0-9_.\\-]+)\\s*[=:]\\s*(.*)$" );
    private static final Pattern HOIST_DIRECTIVE = Pattern.compile( "^(Type|Blank means|Source):\\s*(.*)$" );

    private record Hoist( List<String> preambleParagraphs, List<String> ownDescription ) {}

    /**
     * @return key line number (1-based, matches {@link ConfigReference.Entry#line()}) → the
     *         section preamble to hoist and that key's own trimmed description. Absent for every
     *         key whose comment block didn't need splitting — which is most of them.
     */
    private static Map<Integer, Hoist> scanHoists( final List<String> lines ) {
        final Map<Integer, Hoist> result = new LinkedHashMap<>();
        final List<String> block = new ArrayList<>();
        boolean justEnteredSection = false;

        for ( int i = 0; i < lines.size(); i++ ) {
            final String line = lines.get( i ).strip();
            if ( line.isEmpty() ) {
                block.clear();
                continue;
            }
            if ( HOIST_SECTION.matcher( line ).matches() ) {
                block.clear();
                justEnteredSection = true;
                continue;
            }
            if ( line.startsWith( "#" ) || line.startsWith( "!" ) ) {
                block.add( line.substring( 1 ).strip() );
                continue;
            }
            if ( !HOIST_KEY_VALUE.matcher( line ).matches() ) {
                block.clear();
                continue;
            }

            if ( justEnteredSection ) {
                final List<List<String>> groups = splitIntoGroups( block );
                if ( groups.size() > 1 ) {
                    final List<String> lastGroup = groups.get( groups.size() - 1 );
                    final int ownStart = firstDirectiveIndex( lastGroup );
                    // Only hoist when the key's own paragraph still has prose of its own once its
                    // directives are stripped — otherwise (e.g. wikantik.pageProvider, whose last
                    // group is bare "Type: class") hoisting would leave the row with nothing.
                    if ( ownStart > 0 ) {
                        final List<String> preamble = new ArrayList<>();
                        for ( int g = 0; g < groups.size() - 1; g++ ) {
                            preamble.add( renderFull( groups.get( g ) ) );
                        }
                        result.put( i + 1, new Hoist( List.copyOf( preamble ),
                                List.copyOf( lastGroup.subList( 0, ownStart ) ) ) );
                    }
                }
            }
            justEnteredSection = false;
            block.clear();
        }
        return result;
    }

    /** Splits a raw comment block into paragraph groups, separated by "#"-only lines (which
     *  {@code block} carries as empty strings — see the caller). A leading separator with no
     *  content before it produces no empty leading group. */
    private static List<List<String>> splitIntoGroups( final List<String> block ) {
        final List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for ( final String c : block ) {
            if ( c.isEmpty() ) {
                if ( !current.isEmpty() ) {
                    groups.add( current );
                    current = new ArrayList<>();
                }
            } else {
                current.add( c );
            }
        }
        if ( !current.isEmpty() ) {
            groups.add( current );
        }
        return groups;
    }

    /** Index of the first directive-matching line in {@code group}, or {@code group.size()} if
     *  it has none (a pure-prose paragraph with no {@code Type:}/{@code Blank means:}/{@code
     *  Source:} of its own). */
    private static int firstDirectiveIndex( final List<String> group ) {
        for ( int i = 0; i < group.size(); i++ ) {
            if ( HOIST_DIRECTIVE.matcher( group.get( i ) ).matches() ) {
                return i;
            }
        }
        return group.size();
    }

    // ------------------------------------------------------------------
    // Heading anchors.
    // ------------------------------------------------------------------

    private static final Pattern SLUG_STRIP = Pattern.compile( "[^a-z0-9 _-]" );

    /** Replicates GitHub's Markdown heading-anchor slug closely enough for the table of
     *  contents links generated alongside these headings to resolve: lowercase, drop anything
     *  that isn't a letter/digit/space/hyphen/underscore, then turn spaces into hyphens (without
     *  collapsing runs — {@code "A & B"} deliberately slugs to {@code "a--b"}). */
    static String slug( final String heading ) {
        final String lower = heading.toLowerCase( Locale.ROOT );
        return SLUG_STRIP.matcher( lower ).replaceAll( "" ).replace( ' ', '-' );
    }

    private static boolean matches( final Path file, final String generated, final boolean ignoreDateLine ) throws IOException {
        if ( !Files.exists( file ) ) {
            return false;
        }
        String existing = Files.readString( file, StandardCharsets.UTF_8 ).replace( "\r\n", "\n" );
        String expected = generated;
        if ( ignoreDateLine ) {
            existing = stripDateLine( existing );
            expected = stripDateLine( expected );
        }
        return existing.equals( expected );
    }

    /** Drops any line of the form {@code date: '...'} so a regenerate on a later day isn't "stale" by itself. */
    static String stripDateLine( final String content ) {
        return Arrays.stream( content.split( "\n", -1 ) )
                .filter( line -> !line.startsWith( "date: '" ) )
                .collect( Collectors.joining( "\n" ) );
    }

    private static ConfigReference.Parsed mergeParsed( final ConfigReference.Parsed a, final ConfigReference.Parsed b ) {
        final List<ConfigReference.Entry> entries = new ArrayList<>( a.entries() );
        entries.addAll( b.entries() );
        final List<String> commented = new ArrayList<>( a.commentedOutKeys() );
        commented.addAll( b.commentedOutKeys() );
        final List<String> duplicates = new ArrayList<>( a.duplicateKeys() );
        duplicates.addAll( b.duplicateKeys() );
        return new ConfigReference.Parsed( List.copyOf( entries ), List.copyOf( commented ), List.copyOf( duplicates ) );
    }

    private static Template loadTemplate( final String templateResource ) {
        final String path = templateResource.startsWith( "/" ) ? templateResource : "/" + templateResource;
        try ( InputStream in = GenerateConfigReferenceCli.class.getResourceAsStream( path ) ) {
            if ( in == null ) {
                throw new IllegalStateException( "Mustache template " + path + " not found on the classpath" );
            }
            try ( BufferedReader reader = new BufferedReader( new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
                return Mustache.compiler()
                        .standardsMode( false )
                        .escapeHTML( false )
                        .compile( reader );
            }
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Could not load template " + path, e );
        }
    }

    /**
     * Force a single trailing newline and LF line endings so the regression test can
     * byte-compare against the on-disk file regardless of the platform that wrote it.
     */
    static String normalize( final String body ) {
        String s = body == null ? "" : body.replace( "\r\n", "\n" );
        if ( s.startsWith( "\n" ) ) {
            s = s.substring( 1 );
        }
        while ( s.contains( "\n\n\n" ) ) {
            s = s.replace( "\n\n\n", "\n\n" );
        }
        if ( !s.endsWith( "\n" ) ) {
            s = s + "\n";
        }
        return s;
    }
}
