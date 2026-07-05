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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingItem;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;

import java.util.List;

/**
 * Renders a {@link ContextBriefing} to injection-ready markdown: a coverage summary followed by
 * retrieval-driven sections, standing (full-content) context, budget-trimmed pointers, and any
 * assembly warnings. Pure function — no I/O, no dependencies beyond the api types.
 */
public final class MarkdownBriefingRenderer {

    private MarkdownBriefingRenderer() {
    }

    public static String render( final ContextBriefing briefing ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "# Wiki context briefing\n" );
        sb.append( coverageLine( briefing.coverage() ) ).append( '\n' );

        if ( !briefing.sections().isEmpty() ) {
            sb.append( "\n## Task-relevant sections\n" );
            for ( final BundleSection section : briefing.sections() ) {
                sb.append( '\n' ).append( sectionHeading( section ) ).append( "\n\n" )
                    .append( section.text() ).append( '\n' );
            }
        }

        final List< BriefingItem > standing = briefing.items().stream()
            .filter( BriefingItem::included ).toList();
        if ( !standing.isEmpty() ) {
            sb.append( "\n## Standing context\n" );
            for ( final BriefingItem item : standing ) {
                sb.append( '\n' ).append( "### " ).append( item.title() )
                    .append( " (`" ).append( item.slug() ).append( "`)\n\n" )
                    .append( item.content() ).append( '\n' );
            }
        }

        final List< BriefingItem > pointers = briefing.items().stream()
            .filter( item -> !item.included() ).toList();
        if ( !pointers.isEmpty() ) {
            sb.append( "\n## Available on request\n\n" );
            for ( final BriefingItem item : pointers ) {
                sb.append( pointerLine( item ) ).append( '\n' );
            }
        }

        if ( !briefing.warnings().isEmpty() ) {
            sb.append( '\n' ).append( "> Briefing warnings: " )
                .append( String.join( "; ", briefing.warnings() ) ).append( '\n' );
        }

        return sb.toString();
    }

    private static String coverageLine( final BundleCoverage coverage ) {
        return "_Coverage: " + coverage.confidence() + " — " + coverage.sectionCount()
            + " sections across " + coverage.distinctPageCount() + " pages. Deepen with "
            + "`assemble_bundle(\"<question>\")`; fetch full pages with `read_pages`._";
    }

    private static String sectionHeading( final BundleSection section ) {
        final StringBuilder sb = new StringBuilder( "### " ).append( section.slug() );
        if ( !section.headingPath().isEmpty() ) {
            sb.append( " › " ).append( String.join( " › ", section.headingPath() ) );
        }
        final CitationHandle citation = section.citation();
        sb.append( " (" ).append( citation.canonicalId() ).append( " @ v" )
            .append( citation.version() ).append( ')' );
        return sb.toString();
    }

    private static String pointerLine( final BriefingItem item ) {
        final StringBuilder sb = new StringBuilder( "- **" ).append( item.title() )
            .append( "** (`" ).append( item.slug() ).append( "`)" );
        final String summary = item.summary();
        if ( summary != null && !summary.isBlank() ) {
            sb.append( " — " ).append( summary );
        }
        return sb.toString();
    }
}
