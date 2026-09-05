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
