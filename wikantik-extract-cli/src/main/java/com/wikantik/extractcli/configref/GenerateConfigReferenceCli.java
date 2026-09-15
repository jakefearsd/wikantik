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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>This class is the thin orchestrator: it locates the source files, drives the mustache
 * render, and does the write-vs-check comparison. The actual per-section table-building lives in
 * {@link SectionBuilder} (which in turn uses {@link HoistScanner}, {@link DescriptionRenderer},
 * {@link AnchorSlugger} and {@link RowCellRenderer}) — split out in 2026-09 as a complexity
 * burn-down; behaviour is unchanged.</p>
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
     *                  {@link HoistScanner#scanHoists}. Tests that don't exercise that feature may
     *                  pass {@code List.of()}.
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
     * <p>Two cross-file, whole-render concerns live here rather than in {@link SectionBuilder}:
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
            final List<Map<String, Object>> sections = SectionBuilder.buildSections( sf, usedSectionNames, secretRows );
            f.put( "sections", sections );
            f.put( "toc", SectionBuilder.tocFor( sections ) );
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
    // Small local helpers — everything with real branching moved to SectionBuilder/HoistScanner/
    // DescriptionRenderer/AnchorSlugger/RowCellRenderer.
    // ------------------------------------------------------------------

    private static String introText( final SourceFile sf ) {
        return "The following settings come from `" + sf.pathLabel() + "` (" + sf.overrideNote() + ").";
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
