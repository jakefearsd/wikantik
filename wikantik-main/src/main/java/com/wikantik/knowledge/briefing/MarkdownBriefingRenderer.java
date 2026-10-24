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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ContextBriefing} to injection-ready markdown: a coverage summary followed by
 * retrieval-driven sections, standing (full-content) context, the contracts the project cites, the
 * out-of-scope pages found elsewhere in the wiki (grouped by cluster), budget-trimmed pointers, and
 * any assembly warnings. Every heading is omitted when it would be empty. Pure function — no I/O, no
 * dependencies beyond the api types.
 */
public final class MarkdownBriefingRenderer {

    private static final String UNCLUSTERED = "(unclustered)";

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

        final Tiers tiers = Tiers.of( briefing.items() );
        appendStanding( sb, tiers.standing() );
        appendCited( sb, tiers.cited() );
        appendElsewhere( sb, tiers.elsewhere() );
        appendAvailableOnRequest( sb, tiers.pointers() );

        if ( !briefing.warnings().isEmpty() ) {
            sb.append( '\n' ).append( "> Briefing warnings: " )
                .append( String.join( "; ", briefing.warnings() ) ).append( '\n' );
        }

        return sb.toString();
    }

    /** The briefing's items split by origin — the one place origin decides which heading an item lands under. */
    private record Tiers( List< BriefingItem > standing, List< BriefingItem > cited,
                          List< BriefingItem > elsewhere, List< BriefingItem > pointers ) {

        static Tiers of( final List< BriefingItem > items ) {
            final List< BriefingItem > standing = new ArrayList<>();
            final List< BriefingItem > cited = new ArrayList<>();
            final List< BriefingItem > elsewhere = new ArrayList<>();
            final List< BriefingItem > pointers = new ArrayList<>();
            for ( final BriefingItem item : items ) {
                if ( ItemAssembler.ORIGIN_CITED.equals( item.origin() ) ) {
                    cited.add( item );
                } else if ( ItemAssembler.ORIGIN_ELSEWHERE.equals( item.origin() ) ) {
                    elsewhere.add( item );
                } else if ( item.included() ) {
                    standing.add( item );
                } else {
                    pointers.add( item );
                }
            }
            return new Tiers( standing, cited, elsewhere, pointers );
        }
    }

    private static void appendStanding( final StringBuilder sb, final List< BriefingItem > standing ) {
        if ( standing.isEmpty() ) return;
        sb.append( "\n## Standing context\n" );
        standing.forEach( item -> appendBody( sb, item ) );
    }

    /** Cited bodies first, each as a standing-context entry, then the trimmed ones as pointer lines. */
    private static void appendCited( final StringBuilder sb, final List< BriefingItem > cited ) {
        if ( cited.isEmpty() ) return;
        sb.append( "\n## Contracts this project cites\n" );
        cited.stream().filter( BriefingItem::included ).forEach( item -> appendBody( sb, item ) );
        final List< BriefingItem > pointers = cited.stream().filter( item -> !item.included() ).toList();
        if ( !pointers.isEmpty() ) {
            sb.append( '\n' );
            pointers.forEach( item -> sb.append( pointerLine( item ) ).append( '\n' ) );
        }
    }

    private static void appendElsewhere( final StringBuilder sb, final List< BriefingItem > elsewhere ) {
        if ( elsewhere.isEmpty() ) return;
        final Map< String, List< BriefingItem > > byCluster = new LinkedHashMap<>();
        for ( final BriefingItem item : elsewhere ) {
            final String cluster = item.cluster() == null || item.cluster().isBlank() ? UNCLUSTERED : item.cluster();
            byCluster.computeIfAbsent( cluster, k -> new ArrayList<>() ).add( item );
        }
        sb.append( "\n## Elsewhere in the wiki\n\n" );
        byCluster.forEach( ( cluster, members ) -> {
            sb.append( "- **" ).append( cluster ).append( "**\n" );
            members.forEach( item -> sb.append( elsewhereLine( item ) ).append( '\n' ) );
        } );
    }

    private static void appendAvailableOnRequest( final StringBuilder sb, final List< BriefingItem > pointers ) {
        if ( pointers.isEmpty() ) return;
        sb.append( "\n## Available on request\n\n" );
        pointers.forEach( item -> sb.append( pointerLine( item ) ).append( '\n' ) );
    }

    private static void appendBody( final StringBuilder sb, final BriefingItem item ) {
        sb.append( '\n' ).append( "### " ).append( item.title() )
            .append( " (`" ).append( item.slug() ).append( "`)\n\n" )
            .append( item.content() ).append( '\n' );
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

    /** {@code   - <slug> › <headingPath> (`canonicalId`)}; the heading path and the id are dropped when absent. */
    private static String elsewhereLine( final BriefingItem item ) {
        final StringBuilder sb = new StringBuilder( "  - " ).append( item.slug() );
        final String headingPath = item.summary();
        if ( headingPath != null && !headingPath.isBlank() ) {
            sb.append( " › " ).append( headingPath );
        }
        if ( item.canonicalId() != null ) {
            sb.append( " (`" ).append( item.canonicalId() ).append( "`)" );
        }
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
