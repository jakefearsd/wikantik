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

import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.api.markdown.InlineTagSyntax;
import com.wikantik.markdown.extensions.inlinetags.InlineTag;
import com.wikantik.markdown.extensions.inlinetags.InlineTagNodes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A page body's inline {@code #tags} as the reader's parse finds them: the {@link InlineTag} nodes the render draws as tag
 * links, after {@code %%comments%%} are hidden. Same preparation, structure and inline-math parser as the render, under the
 * {@link BudgetedText} parse budget. A body that cannot be read yields no tags (a tag missing from the index is the safe
 * failure).
 */
public final class ReaderTags {

    private static final Logger LOG = LogManager.getLogger( ReaderTags.class );

    /** Per-thread parse count: a test seam (tests run in parallel forks and threads). */
    private static final ThreadLocal< long[] > PARSES = ThreadLocal.withInitial( () -> new long[ 1 ] );

    private ReaderTags() {
    }

    /** @return how many bodies this thread has parsed for tags (a test seam) */
    static long parses() {
        return PARSES.get()[ 0 ];
    }

    /**
     * @param body page body; may be {@code null}
     * @return the first written form of each tag, one per normalised tag, in first-seen order; empty when the body has no
     *         {@code #} (no parse is run)
     */
    public static List< String > written( final String body ) {
        return List.copyOf( byNormalised( body, -1L ).values() );
    }

    /**
     * @param body page body; may be {@code null}
     * @return the normalised tags in first-seen order, duplicates removed; empty when the body has no {@code #}
     */
    public static Set< String > normalised( final String body ) {
        return new LinkedHashSet<>( byNormalised( body, -1L ).keySet() );
    }

    /** As {@link #normalised(String)} with an explicit parse budget (a test seam). */
    static Set< String > normalised( final String body, final long budget ) {
        return new LinkedHashSet<>( byNormalised( body, budget ).keySet() );
    }

    private static Map< String, String > byNormalised( final String body, final long budget ) {
        final Map< String, String > out = new LinkedHashMap<>();
        if ( body == null || body.indexOf( '#' ) < 0 ) {
            return out;
        }
        final String stripped = ReaderComments.strip( body );
        // a comment can hold every '#', so look again once it is gone: no '#' left, no parse
        if ( stripped.indexOf( '#' ) < 0 ) {
            return out;
        }
        PARSES.get()[ 0 ]++;
        try {
            final String text = MarkdownBodySource.prepare( stripped ).text();
            for ( final Node node : ReaderParser.parse( text, budget ).getDescendants() ) {
                if ( node instanceof InlineTag tag && InlineTagNodes.shown( tag ) ) {
                    out.putIfAbsent( InlineTagSyntax.normalise( tag.written() ), tag.written() );
                }
            }
        } catch ( final RuntimeException | StackOverflowError e ) {
            LOG.warn( "inline tags read as none: the parse of a {}-character body failed: {}", body.length(), e.toString() );
            return new LinkedHashMap<>();
        }
        return out;
    }
}
