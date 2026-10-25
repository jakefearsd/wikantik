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
package com.wikantik.markdown.extensions.fences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Raw-text scanner for CommonMark fenced code blocks, with no Flexmark involved: it exists so reference
 * collection ({@code DefaultReferenceManager.scanWikiLinks}, deliberately regex-fast) can read diagram link
 * targets without a full parse. It must agree with Flexmark on the {@code documents} cases of the shared
 * {@code renderedFences.json} fixture.
 *
 * <p>Handles backtick and tilde fences, closers of at least the opener's length, unclosed fences running to EOF,
 * CRLF, and the two container kinds that matter in practice: blockquote {@code >} markers and list-item content
 * indentation. Runs in a single pass, linear in the body length.
 */
public final class RenderedFenceScanner {

    private RenderedFenceScanner() {
    }

    /** Every fenced block, any info string, in document order. */
    public static List< ScannedFence > scan( final String body ) {
        if ( body == null || ( body.indexOf( "```" ) < 0 && body.indexOf( "~~~" ) < 0 ) ) {
            return new ArrayList<>();
        }
        return new FenceScanRun( FenceLines.splitLines( body ) ).run();
    }

    /**
     * Local page names linked from rendered (registered, non-{@code example}) fences. Empty when the body has no
     * fence markers at all, which is the overwhelmingly common case.
     */
    public static Set< String > pageLinks( final String body, final RenderedFenceRegistry registry ) {
        final Set< String > links = new LinkedHashSet<>();
        for ( final ScannedFence f : scan( body ) ) {
            registry.forInfo( f.info() ).ifPresent( r -> links.addAll( r.pageLinks( f.source() ) ) );
        }
        return links;
    }
}
