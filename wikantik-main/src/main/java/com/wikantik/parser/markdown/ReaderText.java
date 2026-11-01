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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.UnaryOperator;

/**
 * The one funnel for <em>derived</em> text: page text as readers and derived stores (excerpts, summaries, chunks,
 * embeddings, the search index, KG extraction input, {@code ?format=md|json}) see it, as opposed to page <em>source</em>,
 * which is returned as stored (spec 1.11a). Block markers ({@code ^id}) are stripped here; a later phase adds comment
 * stripping in this same place, so no consumer ever needs to change again.
 *
 * <p>Derived text is never written back, and deriving it must never fail a render, index or chunk run: any internal
 * failure is logged and the input is returned unchanged.</p>
 */
public final class ReaderText {

    private static final Logger LOG = LogManager.getLogger( ReaderText.class );

    private ReaderText() {
    }

    /**
     * @param fullText page text (or a body cut from it); may be {@code null}
     * @return the text with markers removed (line count preserved); never throws
     */
    public static String derived( final String fullText ) {
        return derived( fullText, BlockIds::strip );
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
