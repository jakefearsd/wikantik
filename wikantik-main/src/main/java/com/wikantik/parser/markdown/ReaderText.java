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
import com.wikantik.api.markdown.ObsidianComments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.UnaryOperator;

/**
 * The one funnel for <em>derived</em> text: page text as readers and derived stores (excerpts, summaries, chunks,
 * embeddings, the search index, KG extraction input, {@code ?format=md|json}) see it, as opposed to page <em>source</em>,
 * which is returned as stored (spec 1.11a). Obsidian {@code %%comments%%} ({@link ObsidianComments}) and block markers
 * ({@code ^id}) are stripped here, in that order, so no consumer ever needs to change again. Comments are hidden, not
 * secret: this never feeds the access-rule reader, which reads the stored text.
 *
 * <p>Derived text is never written back, and deriving it must never fail a render, index or chunk run: any internal
 * failure is logged and the input is returned unchanged.</p>
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
            return ObsidianComments.strip( fullText );
        }
        final String body = fullText.substring( bodyStart );
        final String stripped = ObsidianComments.strip( body );
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
        return derived( body, text -> BlockIds.stripBody( ObsidianComments.strip( text ) ) );
    }

    /** Test seam: lets a test inject a failing derivation. */
    static String derived( final String fullText, final UnaryOperator< String > derivation ) {
        if ( fullText == null ) {
            return null;
        }
        try {
            return derivation.apply( fullText );
        } catch ( final RuntimeException e ) {
            LOG.warn( "ReaderText.derived failed on {} chars of text; using it unchanged: {}",
                    fullText.length(), e.toString(), e );
            return fullText;
        }
    }
}
