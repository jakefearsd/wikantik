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
package com.wikantik.citation;

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.StructuralIndexService;
import java.util.Optional;
import java.util.function.Function;

/** Grades a citation against the target's current content. Span drift = STALE; missing target = TARGET_MISSING. */
public final class CitationStalenessGrader {

    private final StructuralIndexService structuralIndex;
    private final Function< String, Optional< String > > bodyLoader;   // slug -> raw body
    private final MarkdownSectionExtractor sectionExtractor;

    public CitationStalenessGrader( final StructuralIndexService structuralIndex,
                                    final Function< String, Optional< String > > bodyLoader,
                                    final MarkdownSectionExtractor sectionExtractor ) {
        this.structuralIndex = structuralIndex;
        this.bodyLoader = bodyLoader;
        this.sectionExtractor = sectionExtractor;
    }

    public CitationStatus grade( final String targetCanonicalId, final String headingPath, final String spanText ) {
        final Optional< String > slug = structuralIndex.resolveSlugFromCanonicalId( targetCanonicalId );
        if ( slug.isEmpty() ) { return CitationStatus.TARGET_MISSING; }
        final Optional< String > body = bodyLoader.apply( slug.get() );
        if ( body.isEmpty() ) { return CitationStatus.TARGET_MISSING; }
        final Optional< String > section = sectionExtractor.sectionText( body.get(), headingPath );
        if ( section.isEmpty() ) { return CitationStatus.STALE; }       // heading moved / gone
        if ( spanText == null || spanText.isBlank() ) { return CitationStatus.CURRENT; }
        final String hay = Spans.normalize( section.get() );
        final String needle = Spans.normalize( spanText );
        return hay.contains( needle ) ? CitationStatus.CURRENT : CitationStatus.STALE;
    }
}
