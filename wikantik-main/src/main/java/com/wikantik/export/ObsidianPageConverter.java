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
package com.wikantik.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.InlineLinkNode;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;

/**
 * Rewrites a wiki page's markdown body into Obsidian-compatible form: wikilinks for in-export
 * page links, {@code ![[...]]} embeds for images/attachments, footnoted citations for
 * {@code cite://} links, and best-effort fallbacks for wiki plugin markup Obsidian has no
 * equivalent for. Frontmatter is preserved verbatim (see {@link FrontmatterPatcher}) and only
 * extended with the caller-supplied keys. Per-node link/image rendering lives in
 * {@link ObsidianLinkRenderer}; this class owns document parsing, wiki plugin markup, and edit
 * application. See {@code docs/superpowers/sdd/2026-09-29-obsidian-export} for the design this
 * implements (spec §5).
 */
public final class ObsidianPageConverter {

    private static final Parser PARSER = Parser.builder().extensions( List.of( TablesExtension.create() ) ).build();

    private static final Pattern PLUGIN_PATTERN = Pattern.compile( "\\[\\{([^}]*)\\}\\](\\(\\))?" );
    private static final Pattern PAGE_PARAM = Pattern.compile( "page\\s*=\\s*(['\"]?)([^'\"\\s]+)\\1" );
    private static final Set< String > REMOVED_PLUGIN_NAMES = Set.of( "ALLOW", "DENY", "SET", "TABLEOFCONTENTS" );

    /** One text-span rewrite applied to the body, keyed by its original source offsets. */
    private record Edit( int start, int end, String replacement ) {}

    /** @param extraFrontmatter keys replaced/added via FrontmatterPatcher (related, aliases, wikantik_url, wikantik_version) */
    public ConvertedPage convert( final String pageName, final String rawText, final Map< String, Object > extraFrontmatter,
                                  final ExportLinkContext ctx ) {
        final ParsedPage parsed = FrontmatterParser.parse( rawText );
        final String body = parsed.body();
        final String frontmatterPrefix = rawText.substring( 0, rawText.length() - body.length() );

        final Document doc = PARSER.parse( body );
        final List< int[] > codeRanges = codeRegions( doc );
        final List< Edit > edits = new ArrayList<>();
        final ConversionState state = new ConversionState( pageName, ctx );

        collectPluginEdits( body, codeRanges, state, edits );
        collectLinkEdits( doc, codeRanges, edits, state );

        final String newBody = applyEdits( body, edits, state.footnoteLines );
        final String markdown = FrontmatterPatcher.patch( frontmatterPrefix + newBody, extraFrontmatter );
        return new ConvertedPage( markdown, List.copyOf( state.attachments ), List.copyOf( state.warnings ) );
    }

    // ---- wiki plugin markup --------------------------------------------------------------

    private static void collectPluginEdits( final String body, final List< int[] > codeRanges, final ConversionState state,
                                             final List< Edit > edits ) {
        final Matcher m = PLUGIN_PATTERN.matcher( body );
        while ( m.find() ) {
            if ( isInsideCode( codeRanges, m.start() ) ) {
                continue;
            }
            edits.add( pluginEdit( body, m, state ) );
        }
    }

    private static Edit pluginEdit( final String body, final Matcher m, final ConversionState state ) {
        final String inner = m.group( 1 );
        final String name = firstToken( inner );
        final String upper = name.toUpperCase( Locale.ROOT );
        final int lineStart = body.lastIndexOf( '\n', m.start() - 1 ) + 1;
        final int nlAfter = body.indexOf( '\n', m.end() );
        final int lineEnd = nlAfter < 0 ? body.length() : nlAfter;
        final boolean alone = body.substring( lineStart, m.start() ).isBlank()
                && body.substring( m.end(), lineEnd ).isBlank();

        if ( REMOVED_PLUGIN_NAMES.contains( upper ) || name.startsWith( "$" ) ) {
            return alone
                    ? new Edit( lineStart, nlAfter < 0 ? body.length() : nlAfter + 1, "" )
                    : new Edit( m.start(), m.end(), "" );
        }
        if ( "INSERTPAGE".equals( upper ) ) {
            return new Edit( m.start(), m.end(), "![[" + ObsidianLinkRenderer.linkTarget( extractPageParam( inner ), state ) + "]]" );
        }
        return alone
                ? new Edit( m.start(), m.end(), "> [!note] Wiki plugin omitted: " + name
                        + " — [view on the wiki](" + state.ctx.liveUrl( state.currentPage ) + ")" )
                : new Edit( m.start(), m.end(), "*(wiki plugin omitted: " + name + ")*" );
    }

    private static String firstToken( final String inner ) {
        final String trimmed = inner.stripLeading();
        int i = 0;
        while ( i < trimmed.length() && !Character.isWhitespace( trimmed.charAt( i ) ) ) {
            i++;
        }
        return trimmed.substring( 0, i );
    }

    private static String extractPageParam( final String inner ) {
        final Matcher m = PAGE_PARAM.matcher( inner );
        return m.find() ? m.group( 2 ) : "";
    }

    // ---- links & images -------------------------------------------------------------------

    private static void collectLinkEdits( final Document doc, final List< int[] > codeRanges, final List< Edit > edits,
                                           final ConversionState state ) {
        for ( final Node n : doc.getDescendants() ) {
            if ( !( n instanceof Link ) && !( n instanceof Image ) ) {
                continue;
            }
            final InlineLinkNode link = ( InlineLinkNode ) n;
            final int start = link.getStartOffset();
            final int end = link.getEndOffset();
            if ( isInsideCode( codeRanges, start ) || overlapsAny( edits, start, end ) ) {
                continue;
            }
            final String alias = link.getText().toString();
            if ( alias.startsWith( "{" ) ) {
                continue;
            }
            final String replacement =
                    ObsidianLinkRenderer.render( link, n instanceof Image, alias, ObsidianLinkRenderer.inTableCell( n ), state );
            if ( replacement != null ) {
                edits.add( new Edit( start, end, replacement ) );
            }
        }
    }

    // ---- code regions & edit application ---------------------------------------------------

    /**
     * Computes the source-offset ranges {@code [start, end)} of every inline code span and code
     * block in {@code doc}, mirroring {@code DefaultAclManager.codeRegions} — matches here must
     * never be rewritten by the plugin or link/image passes.
     */
    private static List< int[] > codeRegions( final Document doc ) {
        final List< int[] > ranges = new ArrayList<>();
        final NodeVisitor visitor = new NodeVisitor(
                new VisitHandler<>( Code.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
                new VisitHandler<>( FencedCodeBlock.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
                new VisitHandler<>( IndentedCodeBlock.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) )
        );
        visitor.visit( doc );
        return ranges;
    }

    private static boolean isInsideCode( final List< int[] > ranges, final int offset ) {
        for ( final int[] r : ranges ) {
            if ( offset >= r[ 0 ] && offset < r[ 1 ] ) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAny( final List< Edit > edits, final int start, final int end ) {
        for ( final Edit e : edits ) {
            if ( start < e.end() && end > e.start() ) {
                return true;
            }
        }
        return false;
    }

    private static String applyEdits( final String body, final List< Edit > edits, final List< String > footnoteLines ) {
        final List< Edit > sorted = new ArrayList<>( edits );
        sorted.sort( Comparator.comparingInt( Edit::start ).reversed() );
        final StringBuilder sb = new StringBuilder( body );
        for ( final Edit e : sorted ) {
            sb.replace( e.start(), e.end(), e.replacement() );
        }
        if ( !footnoteLines.isEmpty() ) {
            if ( sb.length() == 0 || sb.charAt( sb.length() - 1 ) != '\n' ) {
                sb.append( '\n' );
            }
            sb.append( '\n' );
            for ( final String line : footnoteLines ) {
                sb.append( line ).append( '\n' );
            }
        }
        return sb.toString();
    }
}
