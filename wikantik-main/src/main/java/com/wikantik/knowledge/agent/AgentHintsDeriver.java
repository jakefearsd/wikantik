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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.AgentHintsBlock;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.agent.PreferredPage;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.WikiProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the {@link AgentHintsBlock} for a wiki page from existing
 * graph/metadata signals: the page's authored {@code mcp_tool_hints}, the
 * cluster hub's authored hints, and (in the prefer_pages half — Task 5)
 * intra-cluster wikilink centrality.
 *
 * <p>Stateless. Never throws — internal failures degrade to empty fields, and
 * the caller (the projection service) wraps {@link #derive} in a try/catch
 * so any escape still yields {@code agent_hints: null} on the projection.</p>
 */
public final class AgentHintsDeriver {

    private static final Logger LOG = LogManager.getLogger( AgentHintsDeriver.class );

    private static final int PREFER_TOOLS_CAP = 5;
    private static final int PREFER_PAGES_CAP = 5;
    private static final double VERIFIED_AUTHORITATIVE_BONUS = 1.5;

    private final StructuralIndexService index;
    private final PageManager pageManager;
    private final ReferenceManager refs;
    private final McpToolHintsResolver toolHints = new McpToolHintsResolver();

    public AgentHintsDeriver( final StructuralIndexService index,
                              final PageManager pageManager,
                              final ReferenceManager refs ) {
        this.index = index;
        this.pageManager = pageManager;
        this.refs = refs;
    }

    public AgentHintsBlock derive( final String canonicalId ) {
        try {
            final Optional< PageDescriptor > maybe = index.getByCanonicalId( canonicalId );
            if ( maybe.isEmpty() ) {
                return AgentHintsBlock.empty();
            }
            final PageDescriptor self = maybe.get();
            final Optional< ClusterDetails > cluster = self.cluster() == null
                    ? Optional.empty()
                    : index.getCluster( self.cluster() );

            final List< String > tools = derivePreferTools( self, cluster.orElse( null ) );
            final List< PreferredPage > pages = List.of();   // Task 5 fills this in

            return new AgentHintsBlock( tools, pages );
        } catch ( final Exception ex ) {
            LOG.warn( "agent-hints: derive({}) threw — returning empty block: {}",
                      canonicalId, ex.getMessage() );
            return AgentHintsBlock.empty();
        }
    }

    /* ------------------------------------------------------------ prefer_tools */

    private List< String > derivePreferTools( final PageDescriptor self, final ClusterDetails cluster ) {
        final List< String > selfTools = toolNamesFor( self );
        final List< String > hubTools = ( cluster != null
                                          && cluster.hubPage() != null
                                          && !cluster.hubPage().slug().equals( self.slug() ) )
                ? toolNamesFor( cluster.hubPage() )
                : List.of();
        // Frequency-rank; insertion order (self before hub) is the tie-break.
        // LinkedHashMap preserves encounter order, so a stable sort on frequency alone
        // keeps self-page tools ahead of hub-only tools when counts are equal.
        final Map< String, Integer > counts = new LinkedHashMap<>();
        for ( final String t : selfTools ) counts.merge( t, 1, Integer::sum );
        for ( final String t : hubTools )  counts.merge( t, 1, Integer::sum );
        return counts.entrySet().stream()
                .sorted( Comparator.< Map.Entry< String, Integer > >comparingInt( Map.Entry::getValue )
                                   .reversed() )
                .limit( PREFER_TOOLS_CAP )
                .map( Map.Entry::getKey )
                .toList();
    }

    private List< String > toolNamesFor( final PageDescriptor d ) {
        try {
            final String raw = pageManager.getPureText( d.slug(), WikiProvider.LATEST_VERSION );
            if ( raw == null || raw.isEmpty() ) return List.of();
            final ParsedPage parsed = FrontmatterParser.parse( raw );
            final List< McpToolHint > hints = toolHints.resolve( parsed.metadata(), d.tags(), d.cluster() );
            final List< String > out = new ArrayList<>( hints.size() );
            for ( final McpToolHint h : hints ) {
                final String name = bareToolName( h.tool() );
                if ( name != null && !name.isBlank() ) out.add( name );
            }
            return out;
        } catch ( final Exception e ) {
            LOG.warn( "agent-hints: toolNamesFor({}) failed: {}", d.slug(), e.getMessage() );
            return List.of();
        }
    }

    /** Strips a leading {@code /knowledge-mcp/}, {@code /wikantik-admin-mcp/}, or any path prefix to a bare snake_case name. */
    private static String bareToolName( final String raw ) {
        if ( raw == null ) return null;
        final String trimmed = raw.trim();
        final int lastSlash = trimmed.lastIndexOf( '/' );
        return lastSlash < 0 ? trimmed : trimmed.substring( lastSlash + 1 );
    }
}
