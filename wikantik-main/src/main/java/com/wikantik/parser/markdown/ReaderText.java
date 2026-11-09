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
package com.wikantik.parser.markdown;

import com.wikantik.api.frontmatter.FrontmatterParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.UnaryOperator;

/**
 * The one funnel for <em>derived</em> text: page text as readers and derived stores (excerpts, summaries, chunks,
 * embeddings, the search index, {@code ?format=md|json}) see it, as opposed to page <em>source</em>,
 * which is returned as stored (spec 1.11a). Obsidian {@code %%comments%%} ({@link ReaderComments}) and block markers
 * ({@code ^id}) are stripped here, in that order, so no consumer ever needs to change again. Comments are hidden, not
 * secret: this never feeds the access-rule reader, which reads the stored text.
 *
 * <p>Derived text is never written back, and deriving it must never fail a render, index or chunk run. It fails
 * <b>closed</b> for comments: on an internal failure (logged) the text comes back without every {@code %%…%%} region and
 * without anything after an unclosed {@code %%}, code or not ({@link #conservativelyWithoutComments}); only its block
 * markers, which hide nothing, may then remain.</p>
 */
public final class ReaderText {

    private static final Logger LOG = LogManager.getLogger( ReaderText.class );

    private ReaderText() {
    }

    /**
     * @param fullText full page text, frontmatter included (a body goes to {@link #derivedBody}); may be {@code null}
     * @return the text with comments (body only: a {@code %%} in frontmatter is a value) and markers removed (line count
     *         preserved); never throws
     */
    public static String derived( final String fullText ) {
        return derived( fullText, text -> BlockIds.strip( withoutComments( text ) ) );
    }

    /**
     * The per-caller rule for a <em>source</em> surface (spec 1.11a): whoever may edit the page gets its text as stored (the
     * editor round-trips it, comments included), everyone else gets it without its comments. Block markers are source and
     * stay for both. Mirrors {@code AclDirectiveText.forViewer}, which is applied first.
     *
     * @param canEdit  whether the caller holds {@code edit} on the page the text belongs to
     * @param fullText page text, frontmatter included; may be {@code null}
     * @return {@code fullText} itself for an editor, {@link #withoutComments} of it for everyone else
     */
    public static String forViewer( final boolean canEdit, final String fullText ) {
        return canEdit ? fullText : withoutComments( fullText );
    }

    /**
     * @param fullText page text, frontmatter included (a {@code %%} in frontmatter is a value, not a comment); may be {@code null}
     * @return the text with the body's comments removed (newlines kept); the same instance when it has none
     */
    public static String withoutComments( final String fullText ) {
        if ( fullText == null ) {
            return null;
        }
        final int bodyStart = FrontmatterParser.bodyOffset( fullText );
        if ( bodyStart == 0 ) {
            return ReaderComments.strip( fullText );
        }
        final String body = fullText.substring( bodyStart );
        final String stripped = ReaderComments.strip( body );
        return stripped == body ? fullText : fullText.substring( 0, bodyStart ) + stripped;
    }

    /**
     * {@link #derived} for a page <em>body</em> — frontmatter already split off, as excerpt, chunk, export, agent and
     * embed callers hold it. A leading {@code ---} pair in a body is two thematic breaks, never frontmatter, so the
     * markers inside it are stripped too (reading it as a full text would skip them).
     *
     * @param body a page body; may be {@code null}
     * @return the body with comments and markers removed (line count preserved); never throws
     */
    public static String derivedBody( final String body ) {
        return derived( body, text -> BlockIds.stripBody( ReaderComments.strip( text ) ), true );
    }

    /** Test seam: lets a test inject a failing derivation of a full text. */
    static String derived( final String fullText, final UnaryOperator< String > derivation ) {
        return derived( fullText, derivation, false );
    }

    private static String derived( final String text, final UnaryOperator< String > derivation, final boolean body ) {
        if ( text == null ) {
            return null;
        }
        try {
            return derivation.apply( text );
        } catch ( final RuntimeException e ) {
            LOG.warn( "ReaderText.derived failed on {} chars of text; failing closed (every %%…%% region and anything after "
                    + "an unclosed %% removed, block markers kept): {}", text.length(), e.toString(), e );
            return conservativelyWithoutComments( text, body );
        }
    }

    /**
     * The fail-closed fallback: no code or math is recognised, so every {@code %%…%%} region goes, and so does everything
     * after an unclosed {@code %%} — newlines kept, so line numbers hold. A full text keeps its frontmatter, whose
     * {@code %%} are values.
     */
    static String conservativelyWithoutComments( final String text, final boolean body ) {
        int bodyStart = 0;
        if ( !body ) {
            try {
                bodyStart = FrontmatterParser.bodyOffset( text );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Frontmatter of a {}-char text could not be located ({}); treating it all as body", text.length(),
                        e.toString() );
            }
        }
        final StringBuilder out = new StringBuilder( text.length() ).append( text, 0, bodyStart );
        int copied = bodyStart;
        int at = text.indexOf( "%%", bodyStart );
        while ( at >= 0 ) {
            out.append( text, copied, at );
            final int close = text.indexOf( "%%", at + 2 );
            final int end = close < 0 ? text.length() : close + 2;
            for ( int p = at; p < end; p++ ) {
                if ( text.charAt( p ) == '\n' ) {
                    out.append( '\n' );
                }
            }
            copied = end;
            at = close < 0 ? -1 : text.indexOf( "%%", end );
        }
        return out.append( text, copied, text.length() ).toString();
    }
}
