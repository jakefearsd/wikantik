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
    private static final Pattern COMMENTED_KEY = Pattern.compile( "^[#!]\\s*([A-Za-z0-9_.\\-]+)\\s*[=:].*$" );
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
