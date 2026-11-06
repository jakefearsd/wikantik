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
package com.wikantik.api.markdown;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Obsidian inline {@code #tags}: the one place that decides what is a tag in prose. Shared by the renderer's inline
 * parser (which applies the same boundary rule), the indexers, the importer and the editor (which mirrors it through the
 * {@code inline-tags.json} fixture).
 *
 * <ul>
 *   <li>{@code #} preceded by start-of-line or whitespace;</li>
 *   <li>tag characters {@code [\p{L}\p{N}_/-]}, at least one of them not a digit (so {@code #123} is not a tag, and
 *       {@code #fff} is);</li>
 *   <li>never in code (fenced, indented, inline), in inline or block math, in a URL, a wikilink or a link
 *       destination, nor on an ATX heading line.</li>
 * </ul>
 *
 * <p>A tag's identity is {@link #normalise(String)}; the text keeps what the author wrote. Which tags a page has is
 * decided by the reader's parse ({@code ReaderTags}, in wikantik-main); this class keeps only the identity rule and the
 * character rules the parse's inline tag parser applies.
 */
public final class InlineTagSyntax {

    private static final Pattern NON_DIGIT = Pattern.compile( "[\\p{L}_-]" );

    private InlineTagSyntax() {
    }

    /** Strip leading {@code #}s, turn {@code /} into {@code -}, lowercase: a tag's identity wherever tags are matched or listed. */
    public static String normalise( final String raw ) {
        String t = raw.strip();
        while ( t.startsWith( "#" ) ) {
            t = t.substring( 1 );
        }
        return t.replace( '/', '-' ).toLowerCase( Locale.ROOT );
    }

    /** Whether {@code candidate} (the characters after a {@code #}) can be a tag: it holds a letter, underscore or dash. */
    public static boolean isTagText( final String candidate ) {
        return NON_DIGIT.matcher( candidate ).find();
    }

    /** Whether {@code c} may appear in a tag. */
    public static boolean isTagChar( final char c ) {
        return Character.isLetterOrDigit( c ) || c == '_' || c == '/' || c == '-';
    }

    /**
     * A page's tags for <em>finding</em>: its frontmatter tags then its inline tags, each normalised, duplicates removed.
     * (SEO, feeds, the sitemap and JSON-LD deliberately keep reading the frontmatter alone.)
     *
     * @param frontmatterTags the frontmatter {@code tags} as written; may be {@code null}
     * @param inlineTags      the page's inline tags as the reader's parse finds them; may be {@code null}
     */
    public static List< String > findableTags( final List< String > frontmatterTags, final List< String > inlineTags ) {
        final Set< String > tags = new LinkedHashSet<>();
        addNormalised( tags, frontmatterTags );
        addNormalised( tags, inlineTags );
        return List.copyOf( tags );
    }

    private static void addNormalised( final Set< String > into, final List< String > tags ) {
        if ( tags == null ) {
            return;
        }
        for ( final String t : tags ) {
            final String n = t == null ? "" : normalise( t );
            if ( !n.isEmpty() ) {
                into.add( n );
            }
        }
    }
}
