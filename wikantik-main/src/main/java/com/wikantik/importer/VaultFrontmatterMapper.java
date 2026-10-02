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
package com.wikantik.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.frontmatter.schema.FieldSpec;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.Widget;

/** Maps an Obsidian note's frontmatter and inline tags onto wiki frontmatter (spec 5.2). */
public final class VaultFrontmatterMapper {

    private static final Logger LOG = LogManager.getLogger( VaultFrontmatterMapper.class );
    private static final Set< String > ALWAYS_DROPPED =
        Set.of( "canonical_id", "wikantik_url", "wikantik_version", "verified_at", "verified_by" );
    private static final Pattern COMMA_SPLIT = Pattern.compile( "," );
    private static final Pattern LIST_SPLIT = Pattern.compile( "[,\\s]+" );

    private final FrontmatterSchema schema;

    public VaultFrontmatterMapper( final FrontmatterSchema schema ) {
        this.schema = schema;
    }

    /** Strip a leading {@code #}, turn {@code /} into {@code -}, lowercase. */
    public static String normaliseTag( final String raw ) {
        String t = raw.strip();
        while ( t.startsWith( "#" ) ) {
            t = t.substring( 1 );
        }
        return t.replace( '/', '-' ).toLowerCase( Locale.ROOT );
    }

    public MappedNote map( final String noteText, final NoteContext ctx ) {
        final List< String > warnings = new ArrayList<>();
        final Map< String, Object > meta = new LinkedHashMap<>();
        final String body = parse( noteText, meta, warnings );
        dropKeys( meta );
        mergeTags( meta, body, warnings );
        mergeAliases( meta, ctx, warnings );
        applyTitle( meta, ctx );
        applyType( meta, ctx, warnings );
        applyCluster( meta, ctx );
        return new MappedNote( meta, body, List.copyOf( warnings ) );
    }

    private static String parse( final String text, final Map< String, Object > meta, final List< String > warnings ) {
        try {
            final ParsedPage page = FrontmatterParser.parseStrict( text );
            meta.putAll( page.metadata() );
            return page.body();
        } catch ( final FrontmatterParseException e ) {
            LOG.warn( "Vault note frontmatter is malformed; keeping it as a code block: {}", e.getMessage() );
            warnings.add( "frontmatter: malformed YAML kept as a code block (" + e.getMessage() + ")" );
            return RawFrontmatter.asCodeBlock( text );
        }
    }

    private void dropKeys( final Map< String, Object > meta ) {
        meta.keySet().removeAll( ALWAYS_DROPPED );
        for ( final FieldSpec f : schema.fields() ) {
            if ( f.widget() == Widget.READONLY ) {
                meta.remove( f.key() );
            }
        }
    }

    private static void mergeTags( final Map< String, Object > meta, final String body, final List< String > warnings ) {
        final Set< String > tags = new LinkedHashSet<>();
        for ( final String t : listOf( meta.remove( "tags" ), "tags", true, warnings ) ) {
            final String n = normaliseTag( t );
            if ( !n.isEmpty() ) {
                tags.add( n );
            }
        }
        // A malformed-YAML block is kept as a fenced code block in the body, so its tags are deliberately not scanned.
        tags.addAll( InlineTags.scan( body ) );
        if ( !tags.isEmpty() ) {
            meta.put( "tags", new ArrayList<>( tags ) );
        }
    }

    private static void mergeAliases( final Map< String, Object > meta, final NoteContext ctx, final List< String > warnings ) {
        final Set< String > aliases = new LinkedHashSet<>();
        aliases.addAll( listOf( meta.remove( "aliases" ), "aliases", false, warnings ) );
        aliases.addAll( listOf( meta.remove( "alias" ), "alias", false, warnings ) );
        if ( !ctx.pageName().equals( ctx.basename() ) ) {
            aliases.add( ctx.basename() );
        }
        if ( !aliases.isEmpty() ) {
            meta.put( "aliases", new ArrayList<>( aliases ) );
        }
    }

    private static void applyTitle( final Map< String, Object > meta, final NoteContext ctx ) {
        if ( !ctx.pageName().equals( ctx.basename() ) && !meta.containsKey( "title" ) ) {
            meta.put( "title", ctx.basename() );
        }
    }

    private void applyType( final Map< String, Object > meta, final NoteContext ctx, final List< String > warnings ) {
        final Object raw = meta.remove( "type" );
        if ( ctx.hub() ) {
            meta.put( "type", "hub" );
            return;
        }
        if ( raw == null ) {
            return;
        }
        final String v = String.valueOf( raw );
        final String lower = v.toLowerCase( Locale.ROOT );
        if ( "hub".equals( lower ) ) {
            meta.put( "type", "article" );
            warnings.add( "type: hub downgraded to article (not this folder's hub)" );
        } else if ( canonicalTypes().contains( lower ) ) {
            meta.put( "type", lower );
        } else {
            warnings.add( "type: '" + v + "' is not a wiki page type; dropped" );
        }
    }

    private List< String > canonicalTypes() {
        return schema.field( "type" ).map( FieldSpec::canonicalValues ).orElse( List.of() );
    }

    private static void applyCluster( final Map< String, Object > meta, final NoteContext ctx ) {
        meta.remove( "cluster" );
        if ( ctx.cluster() != null ) {
            meta.put( "cluster", ctx.cluster() );
        }
    }

    /**
     * A string or a list, flattened to trimmed non-empty strings. A scalar splits on commas, and also on whitespace
     * when {@code splitOnSpace} (tags); list items stay whole. Any other value (a map) is skipped with a warning.
     */
    private static List< String > listOf( final Object value, final String key, final boolean splitOnSpace,
                                          final List< String > warnings ) {
        final List< String > out = new ArrayList<>();
        if ( value instanceof Iterable< ? > items ) {
            for ( final Object o : items ) {
                if ( o != null && !( o instanceof Map ) && !String.valueOf( o ).isBlank() ) {
                    out.add( String.valueOf( o ).strip() );
                }
            }
        } else if ( value instanceof Map ) {
            LOG.warn( "Vault note frontmatter '{}' is a map, not a string or list; skipped", key );
            warnings.add( "frontmatter: '" + key + "' is not a string or list; ignored" );
        } else if ( value != null ) {
            final Pattern splitter = splitOnSpace ? LIST_SPLIT : COMMA_SPLIT;
            for ( final String part : splitter.split( String.valueOf( value ).strip() ) ) {
                if ( !part.isBlank() ) {
                    out.add( part.strip() );
                }
            }
        }
        return out;
    }

    /** Renders a malformed frontmatter block as a fenced YAML block ahead of the rest of the note. */
    private static final class RawFrontmatter {

        private RawFrontmatter() {
        }

        static String asCodeBlock( final String text ) {
            if ( !text.startsWith( "---\n" ) && !text.startsWith( "---\r\n" ) ) {
                return text;
            }
            final int yamlStart = text.indexOf( '\n' ) + 1;
            int pos = yamlStart;
            while ( pos < text.length() ) {
                int eol = text.indexOf( '\n', pos );
                if ( eol < 0 ) {
                    eol = text.length();
                }
                if ( "---".equals( text.substring( pos, eol ).trim() ) ) {
                    final String yaml = text.substring( yamlStart, Math.max( yamlStart, pos - 1 ) ).stripTrailing();
                    final String rest = eol < text.length() ? text.substring( eol + 1 ) : "";
                    return fenced( yaml ) + rest;
                }
                pos = eol + 1;
            }
            return fenced( text.substring( yamlStart ).stripTrailing() );
        }

        /** A YAML code block whose fence is longer than any backtick run inside, so the content cannot close it. */
        private static String fenced( final String yaml ) {
            int longest = 0;
            int run = 0;
            for ( int i = 0; i < yaml.length(); i++ ) {
                run = yaml.charAt( i ) == '`' ? run + 1 : 0;
                longest = Math.max( longest, run );
            }
            final String fence = "`".repeat( Math.max( 3, longest + 1 ) );
            return fence + "yaml\n" + yaml + "\n" + fence + "\n\n";
        }
    }
}
