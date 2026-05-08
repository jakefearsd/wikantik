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
package com.wikantik.knowledge;

import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads hub nodes and member sets from the Knowledge Graph and page frontmatter.
 *
 * <p>Factored out of {@link HubOverviewService} as part of Phase 11 Ckpt 6
 * god-class decomposition. Handles the two-source merge (KG nodes typed
 * {@code type=hub} + wiki pages with {@code type: hub} frontmatter) and the
 * hub-member index construction from {@code related} KG edges and frontmatter
 * {@code related:} lists.
 */
class HubMemberLoader {

    private static final Logger LOG = LogManager.getLogger( HubMemberLoader.class );

    private final KgNodeRepository kgNodes;
    private final KgEdgeRepository kgEdges;
    private final PageManager     pageManager;

    HubMemberLoader( final KgNodeRepository kgNodes,
                     final KgEdgeRepository kgEdges,
                     final PageManager pageManager ) {
        this.kgNodes     = kgNodes;
        this.kgEdges     = kgEdges;
        this.pageManager = pageManager;
    }

    /**
     * Collect every page that should appear as a hub. Two sources are merged:
     * KG nodes typed {@code type=hub} (populated by the extractor), and wiki
     * pages whose YAML frontmatter declares {@code type: hub}.
     */
    Map< String, com.wikantik.api.knowledge.KgNode > loadHubNodes() {
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgNodes.queryNodes( null, null, 100_000, 0 );
        final Map< String, com.wikantik.api.knowledge.KgNode > hubsByName = new LinkedHashMap<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubsByName.put( node.name(), node );
            }
        }
        for ( final Map.Entry< String, List< String > > e : loadFrontmatterHubs().entrySet() ) {
            hubsByName.putIfAbsent( e.getKey(), synthesizeHubNode( e.getKey() ) );
        }
        return hubsByName;
    }

    /**
     * Scan every wiki page for a {@code type: hub} frontmatter declaration.
     * Returns a map of hub name to its declared {@code related:} member list.
     */
    Map< String, List< String > > loadFrontmatterHubs() {
        final Map< String, List< String > > hubs = new LinkedHashMap<>();
        if ( pageManager == null ) return hubs;
        final java.util.Collection< com.wikantik.api.core.Page > pages;
        try {
            pages = pageManager.getAllPages();
        } catch ( final ProviderException e ) {
            LOG.warn( "HubMemberLoader.loadFrontmatterHubs: getAllPages failed: {}", e.getMessage() );
            return hubs;
        }
        for ( final com.wikantik.api.core.Page p : pages ) {
            final String name = p.getName();
            final String raw;
            try {
                raw = pageManager.getPureText( name, PageProvider.LATEST_VERSION );
            } catch ( final Exception e ) {
                LOG.info( "HubMemberLoader: skipping '{}' — getPureText failed: {}",
                    name, e.getMessage() );
                continue;
            }
            if ( raw == null || raw.isEmpty() ) continue;
            final ParsedPage parsed;
            try {
                parsed = FrontmatterParser.parse( raw );
            } catch ( final Exception e ) {
                LOG.info( "HubMemberLoader: skipping '{}' — frontmatter parse failed: {}",
                    name, e.getMessage() );
                continue;
            }
            final Map< String, Object > meta = parsed.metadata();
            if ( meta == null || !"hub".equals( meta.get( "type" ) ) ) continue;
            final List< String > related = coerceStringList( meta.get( "related" ) );
            hubs.put( name, related );
        }
        return hubs;
    }

    /**
     * Builds the {@code hub → members} index from both the KG and page
     * frontmatter. KG {@code related} edges are the authoritative source when
     * present; pages declaring {@code type: hub} in frontmatter contribute
     * their {@code related:} list.
     */
    Map< String, Set< String > > loadAllHubMembers() {
        final List< com.wikantik.api.knowledge.KgNode > allNodes =
            kgNodes.queryNodes( null, null, 100_000, 0 );
        final Set< String > hubNames = new HashSet<>();
        for ( final var node : allNodes ) {
            if ( node.properties() != null && "hub".equals( node.properties().get( "type" ) ) ) {
                hubNames.add( node.name() );
            }
        }
        final Map< String, List< String > > frontmatterHubs = loadFrontmatterHubs();
        hubNames.addAll( frontmatterHubs.keySet() );

        final Map< String, Set< String > > out = new LinkedHashMap<>();
        for ( final String hub : hubNames ) out.put( hub, new HashSet<>() );

        final List< Map< String, Object > > edges =
            kgEdges.queryEdgesWithNames( "related", null, 100_000, 0 );
        for ( final Map< String, Object > edge : edges ) {
            final String src = (String) edge.get( "source_name" );
            final String tgt = (String) edge.get( "target_name" );
            if ( src == null || tgt == null ) continue;
            if ( !hubNames.contains( src ) ) continue;
            out.get( src ).add( tgt );
        }
        for ( final Map.Entry< String, List< String > > e : frontmatterHubs.entrySet() ) {
            out.get( e.getKey() ).addAll( e.getValue() );
        }
        return out;
    }

    /**
     * Is {@code hubName} either a KG node of type=hub or a wiki page whose
     * frontmatter declares {@code type: hub}?
     */
    boolean hubNodeExists( final String hubName ) {
        final List< com.wikantik.api.knowledge.KgNode > hubNodes =
            kgNodes.queryNodes( Map.of( "node_type", "hub" ), null, 100_000, 0 );
        for ( final var n : hubNodes ) {
            if ( hubName.equals( n.name() ) ) return true;
        }
        return loadFrontmatterHubs().containsKey( hubName );
    }

    /**
     * Synthesize a placeholder KgNode for a hub that exists only in page
     * frontmatter.
     */
    static com.wikantik.api.knowledge.KgNode synthesizeHubNode( final String hubName ) {
        return new com.wikantik.api.knowledge.KgNode(
            java.util.UUID.nameUUIDFromBytes(
                ( "frontmatter-hub:" + hubName ).getBytes( StandardCharsets.UTF_8 ) ),
            hubName,
            "hub",
            hubName,
            com.wikantik.api.knowledge.Provenance.HUMAN_AUTHORED,
            Map.of( "type", "hub" ),
            null,
            null,
            "human",
            null
        );
    }

    @SuppressWarnings( "unchecked" )
    static List< String > coerceStringList( final Object value ) {
        if ( !( value instanceof List< ? > list ) ) return List.of();
        final List< String > out = new ArrayList<>( list.size() );
        for ( final Object item : list ) {
            if ( item != null ) out.add( item.toString() );
        }
        return out;
    }
}
