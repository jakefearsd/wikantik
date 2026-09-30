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

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.wikantik.api.pagegraph.PageDescriptor;

/**
 * Plans the on-disk (in-zip) layout of an exported Obsidian vault: which folder each page's
 * markdown file lands in (its primary cluster, or {@link #UNCLUSTERED_DIR}), the file basename
 * (case-insensitive collisions get a {@code ~N} suffix), and where each attachment lands under
 * {@link #ATTACHMENTS_DIR}. Immutable once {@link #plan} returns — the plan is computed once
 * over the whole page/attachment set so collisions and ambiguity are resolved deterministically.
 */
public final class VaultLayout {
    public static final String ATTACHMENTS_DIR = "_attachments";
    public static final String UNCLUSTERED_DIR = "_unclustered";

    private static final String ILLEGAL_CHARS = ":*?\"<>|\\";

    private final Map< String, String > pathBySlug;
    private final Map< String, String > basenameBySlug;
    private final Map< String, String > aliasBySlug;
    private final Map< String, String > linkTargetByRefKey;

    private VaultLayout( final Map< String, String > pathBySlug, final Map< String, String > basenameBySlug,
                          final Map< String, String > aliasBySlug, final Map< String, String > linkTargetByRefKey ) {
        this.pathBySlug = pathBySlug;
        this.basenameBySlug = basenameBySlug;
        this.aliasBySlug = aliasBySlug;
        this.linkTargetByRefKey = linkTargetByRefKey;
    }

    public static VaultLayout plan( final List< PageDescriptor > pages, final Collection< AttachmentRef > attachments ) {
        final Map< String, String > pathBySlug = new LinkedHashMap<>();
        final Map< String, String > basenameBySlug = new LinkedHashMap<>();
        final Map< String, String > aliasBySlug = new LinkedHashMap<>();
        final Map< String, Integer > basenameCounts = new HashMap<>();

        pages.stream()
                .sorted( ( a, b ) -> a.slug().compareTo( b.slug() ) )
                .forEach( page -> {
                    final String slug = page.slug();
                    final String folder = page.cluster() == null || page.cluster().isBlank()
                            ? UNCLUSTERED_DIR
                            : sanitizeSegments( page.cluster() );
                    final String sanitizedBase = sanitizeBasename( slug );
                    final String lowerKey = sanitizedBase.toLowerCase( Locale.ROOT );
                    final int count = basenameCounts.merge( lowerKey, 1, Integer::sum );

                    final String basename = count == 1 ? sanitizedBase : sanitizedBase + "~" + count;
                    pathBySlug.put( slug, folder + "/" + basename + ".md" );
                    basenameBySlug.put( slug, basename );

                    if ( count > 1 || !basename.equals( slug ) ) {
                        aliasBySlug.put( slug, slug );
                    }
                } );

        final Map< String, Integer > fileNameCounts = new HashMap<>();
        for ( final AttachmentRef ref : attachments ) {
            fileNameCounts.merge( sanitizeBasename( ref.fileName() ).toLowerCase( Locale.ROOT ), 1, Integer::sum );
        }
        final Map< String, String > linkTargetByRefKey = new LinkedHashMap<>();
        for ( final AttachmentRef ref : attachments ) {
            final String path = attachmentPathFor( ref );
            final String fileBase = sanitizeBasename( ref.fileName() );
            final boolean ambiguous = fileNameCounts.getOrDefault( fileBase.toLowerCase( Locale.ROOT ), 0 ) > 1;
            linkTargetByRefKey.put( refKey( ref ), ambiguous ? path : fileBase );
        }

        return new VaultLayout( pathBySlug, basenameBySlug, aliasBySlug, linkTargetByRefKey );
    }

    public String pagePath( final String slug ) {
        return pathBySlug.get( slug );
    }

    /** File basename (no {@code .md}) chosen for {@code slug}, or {@code null} if it is not in the plan. */
    public String basename( final String slug ) {
        return basenameBySlug.get( slug );
    }

    /** Extra alias to add when the file basename differs from the page name (collision suffix / sanitising). */
    public Optional< String > aliasFor( final String slug ) {
        return Optional.ofNullable( aliasBySlug.get( slug ) );
    }

    public String attachmentPath( final AttachmentRef ref ) {
        return attachmentPathFor( ref );
    }

    /** Obsidian link target: bare file name, or the full path when the basename is ambiguous vault-wide. */
    public String attachmentLinkTarget( final AttachmentRef ref ) {
        return linkTargetByRefKey.get( refKey( ref ) );
    }

    private static String attachmentPathFor( final AttachmentRef ref ) {
        return ATTACHMENTS_DIR + "/" + sanitizeBasename( ref.pageName() ) + "/" + sanitizeBasename( ref.fileName() );
    }

    private static String refKey( final AttachmentRef ref ) {
        return ref.pageName() + "\u0000" + ref.fileName();
    }

    /**
     * Sanitises each {@code /}-separated cluster segment. {@code cluster:} values are only
     * WARNING-validated, so a segment may be {@code ..}, {@code .} or empty — each becomes
     * {@code _} so the folder can never climb out of (or collapse inside) the vault (zip-slip).
     */
    private static String sanitizeSegments( final String cluster ) {
        final String[] segments = cluster.split( "/", -1 );
        final StringBuilder sb = new StringBuilder();
        for ( int i = 0; i < segments.length; i++ ) {
            if ( i > 0 ) {
                sb.append( '/' );
            }
            sb.append( neutraliseDotSegment( sanitize( segments[ i ] ) ) );
        }
        return sb.toString();
    }

    /** A single path segment: {@link #sanitize} plus {@code /} → {@code _} and no {@code .}/{@code ..}/empty name. */
    static String sanitizeBasename( final String name ) {
        return neutraliseDotSegment( sanitize( name ).replace( '/', '_' ) );
    }

    private static String neutraliseDotSegment( final String segment ) {
        return segment.isEmpty() || ".".equals( segment ) || "..".equals( segment ) ? "_" : segment;
    }

    /** Replaces {@code : * ? " < > | \} and control characters with {@code '_'}. */
    static String sanitize( final String name ) {
        final StringBuilder sb = new StringBuilder( name.length() );
        for ( int i = 0; i < name.length(); i++ ) {
            final char c = name.charAt( i );
            if ( ILLEGAL_CHARS.indexOf( c ) >= 0 || Character.isISOControl( c ) ) {
                sb.append( '_' );
            } else {
                sb.append( c );
            }
        }
        return sb.toString();
    }
}
