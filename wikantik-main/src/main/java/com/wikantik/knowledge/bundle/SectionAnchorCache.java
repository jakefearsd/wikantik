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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.frontmatter.FrontmatterParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Each page's heading-path → section-anchor map, kept while the page's text is unchanged, so
 * bundles link sections without re-parsing pages they have already seen — uncached, that parse
 * was ~40% of bundle-assembly CPU (loadtest/results/CAMPAIGN-2026-10-22.md). Keyed by slug plus
 * the text's length and {@code String.hashCode}, so an edit, or a delete and re-create, misses —
 * except an edit that keeps both, vanishingly unlikely unless crafted, whose sections would then
 * link to stale anchors (landing at the page top) until evicted; holding every cached page's
 * text to compare would cost far more memory than that risk is worth. Least recently used pages
 * are evicted beyond {@code capacity}.
 */
final class SectionAnchorCache {

    private record Key( String slug, int length, int hash ) {}

    private final Function< String, Map< List< String >, String > > anchorsOfBody;
    private final Map< Key, Map< List< String >, String > > entries;

    /** @param anchorsOfBody heading-path → anchor map of a page body (frontmatter already removed) */
    SectionAnchorCache( final int capacity, final Function< String, Map< List< String >, String > > anchorsOfBody ) {
        this.anchorsOfBody = anchorsOfBody;
        this.entries = Collections.synchronizedMap( new LinkedHashMap<>( 16, 0.75f, true ) {
            @Override
            protected boolean removeEldestEntry( final Map.Entry< Key, Map< List< String >, String > > eldest ) {
                return size() > capacity;
            }
        } );
    }

    /** The section anchors of a page's current text; empty when the page has none or is missing. */
    Map< List< String >, String > anchorsFor( final String slug, final String pageText ) {
        if ( pageText == null ) {
            return Map.of();
        }
        final Key key = new Key( slug, pageText.length(), pageText.hashCode() );
        final Map< List< String >, String > cached = entries.get( key );
        if ( cached != null ) {
            return cached;
        }
        // Parsed outside the lock: two bundles racing on an uncached page may both parse it, harmlessly.
        final Map< List< String >, String > anchors =
                Map.copyOf( anchorsOfBody.apply( pageText.substring( FrontmatterParser.bodyOffset( pageText ) ) ) );
        entries.put( key, anchors );
        return anchors;
    }
}
