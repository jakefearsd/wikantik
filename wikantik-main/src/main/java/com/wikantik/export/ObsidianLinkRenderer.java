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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.InlineLinkNode;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.util.ast.Node;

/**
 * Renders a single flexmark {@code Link}/{@code Image} node into its Obsidian markdown form
 * (wikilink, embed, citation footnote, or a fallback for an out-of-export/unresolvable target).
 * Split out of {@link ObsidianPageConverter} purely to keep each class's size in check; the two
 * are one algorithm sharing {@link ConversionState}. Package-private: an implementation detail,
 * not part of the export package's public surface.
 */
final class ObsidianLinkRenderer {

    private static final Pattern EXTERNAL_SCHEME = Pattern.compile( "^[A-Za-z][A-Za-z0-9+.-]*:" );
    private static final Set< String > IMAGE_EXTENSIONS =
            Set.of( "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "avif" );

    private ObsidianLinkRenderer() {}

    /** @return the replacement markdown, or {@code null} if the link should be left unchanged. */
    static String render( final InlineLinkNode link, final boolean isImageNode, final String alias,
                           final boolean inTable, final ConversionState state ) {
        final String url = link.getUrl().toString();
        if ( url.startsWith( "cite://" ) ) {
            state.citeCounter++;
            return renderCitation( url, link.getTitle().toString(), alias, inTable, state );
        }
        if ( EXTERNAL_SCHEME.matcher( url ).find() ) {
            return null;
        }
        if ( url.startsWith( "#" ) ) {
            return renderSamePageAnchor( url.substring( 1 ), alias, inTable, state );
        }
        final int slash = url.lastIndexOf( '/' );
        if ( slash >= 0 ) {
            return renderSlashTarget( url, slash, alias, isImageNode, inTable, state );
        }
        if ( looksLikeFile( url ) ) {
            final Optional< String > target = state.ctx.attachmentTarget( state.currentPage, url );
            if ( target.isPresent() ) {
                state.attachments.add( new AttachmentRef( state.currentPage, url ) );
                return renderAttachment( target.get(), alias, isImageNode, inTable );
            }
        }
        return renderPageOrAnchor( url, alias, inTable, state );
    }

    private static String renderSlashTarget( final String url, final int slash, final String alias,
                                              final boolean isImageNode, final boolean inTable, final ConversionState state ) {
        final String pagePart = url.substring( 0, slash );
        final String filePart = url.substring( slash + 1 );
        final Optional< String > target = state.ctx.attachmentTarget( pagePart, filePart );
        if ( target.isPresent() ) {
            state.attachments.add( new AttachmentRef( pagePart, filePart ) );
            return renderAttachment( target.get(), alias, isImageNode, inTable );
        }
        return renderPageOrAnchor( url, alias, inTable, state );
    }

    private static String renderPageOrAnchor( final String rawUrl, final String alias, final boolean inTable,
                                               final ConversionState state ) {
        final String decoded = URLDecoder.decode( rawUrl, StandardCharsets.UTF_8 );
        final int hash = decoded.indexOf( '#' );
        final String page = hash < 0 ? decoded : decoded.substring( 0, hash );
        final String slug = hash < 0 ? null : decoded.substring( hash + 1 );
        if ( slug != null ) {
            final Optional< String > heading = state.ctx.headingText( page, slug );
            if ( heading.isPresent() ) {
                return wikiLink( linkTarget( page, state ) + "#" + heading.get(), alias, inTable );
            }
        }
        return renderPageLink( page, alias, inTable, state );
    }

    private static String renderSamePageAnchor( final String rawSlug, final String alias, final boolean inTable,
                                                 final ConversionState state ) {
        final String slug = URLDecoder.decode( rawSlug, StandardCharsets.UTF_8 );
        final Optional< String > heading = state.ctx.headingText( state.currentPage, slug );
        return heading.map( h -> wikiLink( "#" + h, alias, inTable ) ).orElse( null );
    }

    private static String renderPageLink( final String page, final String alias, final boolean inTable,
                                           final ConversionState state ) {
        if ( !state.ctx.inExport( page ) && state.ctx.unresolvedMode() == UnresolvedLinkMode.URL ) {
            return "[" + stripAliasChars( alias ) + "](" + state.ctx.liveUrl( page ) + ")";
        }
        return wikiLink( linkTarget( page, state ), alias, inTable );
    }

    /**
     * Obsidian link target for a page: the vault file basename the layout chose when the page is
     * in the export (it differs after sanitising or a case-collision {@code ~N} suffix), else the
     * page name itself.
     */
    static String linkTarget( final String page, final ConversionState state ) {
        return state.ctx.vaultBasename( page ).orElse( page );
    }

    private static String renderCitation( final String url, final String span, final String alias,
                                           final boolean inTable, final ConversionState state ) {
        final int citeNumber = state.citeCounter;
        final String withoutScheme = url.substring( "cite://".length() );
        final int slash = withoutScheme.indexOf( '/' );
        final String canonicalId = slash < 0 ? withoutScheme : withoutScheme.substring( 0, slash );
        final String encodedPath = slash < 0 ? "" : withoutScheme.substring( slash + 1 );
        final String decodedPath = URLDecoder.decode( encodedPath, StandardCharsets.UTF_8 );
        final int lastSlash = decodedPath.lastIndexOf( '/' );
        final String headingText = lastSlash < 0 ? decodedPath : decodedPath.substring( lastSlash + 1 );
        final String cleanAlias = stripAliasChars( alias );
        final String footnoteRef = "[^c" + citeNumber + "]";
        state.footnoteLines.add( "[^c" + citeNumber + "]: " + span );

        final Optional< String > pageSlug = state.ctx.slugForCanonicalId( canonicalId );
        if ( pageSlug.isPresent() ) {
            return "[[" + linkTarget( pageSlug.get(), state ) + "#" + headingText + ( inTable ? "\\|" : "|" )
                    + cleanAlias + "]]" + footnoteRef;
        }
        state.warnings.add( state.currentPage + ": citation target " + canonicalId + " not found" );
        return cleanAlias + footnoteRef;
    }

    private static String renderAttachment( final String target, final String alias, final boolean isImageNode,
                                             final boolean inTable ) {
        if ( isImageNode || isImageExtension( target ) ) {
            return "![[" + target + "]]";
        }
        return wikiLink( target, alias, inTable );
    }

    private static String wikiLink( final String target, final String alias, final boolean inTable ) {
        final String clean = stripAliasChars( alias );
        if ( clean.equals( target ) ) {
            return "[[" + target + "]]";
        }
        return "[[" + target + ( inTable ? "\\|" : "|" ) + clean + "]]";
    }

    private static String stripAliasChars( final String alias ) {
        final StringBuilder sb = new StringBuilder( alias.length() );
        for ( int i = 0; i < alias.length(); i++ ) {
            final char c = alias.charAt( i );
            if ( c != '[' && c != ']' && c != '|' ) {
                sb.append( c );
            }
        }
        return sb.toString();
    }

    private static boolean isImageExtension( final String filename ) {
        final int dot = filename.lastIndexOf( '.' );
        return dot >= 0 && IMAGE_EXTENSIONS.contains( filename.substring( dot + 1 ).toLowerCase( Locale.ROOT ) );
    }

    private static boolean looksLikeFile( final String s ) {
        final int dot = s.lastIndexOf( '.' );
        if ( dot <= 0 || dot == s.length() - 1 ) {
            return false;
        }
        final String ext = s.substring( dot + 1 );
        return ext.length() <= 10 && ext.chars().allMatch( Character::isLetterOrDigit );
    }

    static boolean inTableCell( final Node n ) {
        for ( Node p = n.getParent(); p != null; p = p.getParent() ) {
            if ( p instanceof TableCell ) {
                return true;
            }
        }
        return false;
    }
}
