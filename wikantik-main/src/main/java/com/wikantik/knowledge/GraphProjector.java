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

import com.wikantik.api.core.Context;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.knowledge.*;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.parser.MarkdownLinkScanner;
import com.wikantik.knowledge.FrontmatterRelationshipDetector.DetectionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Projects wiki page frontmatter into the knowledge graph. Registered as a
 * {@link com.wikantik.api.filters.PageFilter} so it fires on every page save.
 * Upserts the page's node, resolves relationships to edges,
 * creates stub nodes for unresolved references, and diffs to remove stale edges.
 * System pages (CSS themes, navigation fragments, etc.) are excluded.
 */
public class GraphProjector implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( GraphProjector.class );

    private final KnowledgeGraphService service;
    private final SystemPageRegistry systemPageRegistry;
    private final FrontmatterRelationshipDetector detector;

    public GraphProjector( final KnowledgeGraphService service,
                           final SystemPageRegistry systemPageRegistry ) {
        this.service = service;
        this.systemPageRegistry = systemPageRegistry;
        this.detector = new FrontmatterRelationshipDetector();
    }

    /**
     * Projects a page's frontmatter and body links into the knowledge graph.
     *
     * @param pageName the wiki page name (used as node name and source_page)
     * @param frontmatter the parsed frontmatter metadata map (may be empty)
     * @param bodyText the markdown body text (used to extract {@code links_to} edges)
     */
    public void projectPage( final String pageName, final Map< String, Object > frontmatter,
                             final String bodyText ) {

        final boolean hasFrontmatter = frontmatter != null && !frontmatter.isEmpty();
        final DetectionResult detection = hasFrontmatter
                ? detector.detect( frontmatter ) : new DetectionResult( Map.of(), Map.of() );

        // 1. Upsert the page's node
        final String nodeType = detection.properties().containsKey( "type" )
                ? String.valueOf( detection.properties().get( "type" ) ) : null;
        final KgNode pageNode = service.upsertNode( pageName, nodeType, pageName,
                Provenance.HUMAN_AUTHORED, detection.properties() );

        LOG.debug( "Projected node for page '{}': type={}, properties={}",
                pageName, nodeType, detection.properties().size() );

        // Hoist the outbound-edges lookup once for AI-reviewed checks
        final List< KgEdge > outboundEdges = service.getEdgesForNode( pageNode.id(), "outbound" );

        // 2. Resolve frontmatter relationships to edges
        final Set< Map.Entry< String, String > > currentEdges = new HashSet<>();
        for ( final Map.Entry< String, List< String > > rel : detection.relationships().entrySet() ) {
            final String relationshipType = rel.getKey();
            for ( final String targetName : rel.getValue() ) {
                upsertEdgeIfNotReviewed( pageNode, targetName, relationshipType, outboundEdges );
                currentEdges.add( Map.entry( targetName, relationshipType ) );
            }
        }

        // 3. Extract body links and create links_to edges
        final Set< String > bodyLinks = MarkdownLinkScanner.findLocalLinks( bodyText != null ? bodyText : "" );
        for ( final String targetName : bodyLinks ) {
            if ( targetName.equals( pageName ) ) {
                continue; // skip self-links
            }
            upsertEdgeIfNotReviewed( pageNode, targetName, "links_to", outboundEdges );
            currentEdges.add( Map.entry( targetName, "links_to" ) );
        }

        // 4. Diff: remove human-authored edges no longer in frontmatter or body
        service.diffAndRemoveStaleEdges( pageNode.id(), currentEdges );

        LOG.debug( "Projection complete for '{}': {} relationships", pageName, currentEdges.size() );
    }

    private void upsertEdgeIfNotReviewed( final KgNode pageNode, final String targetName,
                                           final String relationshipType,
                                           final List< KgEdge > outboundEdges ) {
        KgNode existing = service.getNodeByName( targetName );
        if ( existing == null ) {
            existing = service.upsertNode( targetName, null, null,
                    Provenance.HUMAN_AUTHORED, Map.of() );
            LOG.debug( "Created stub node for '{}'", targetName );
        }
        final KgNode target = existing;

        final boolean alreadyReviewed = outboundEdges.stream().anyMatch( e ->
                e.targetId().equals( target.id() )
                && e.relationshipType().equals( relationshipType )
                && e.provenance() == Provenance.AI_REVIEWED );

        if ( !alreadyReviewed ) {
            service.upsertEdge( pageNode.id(), target.id(), relationshipType,
                    Provenance.HUMAN_AUTHORED, Map.of() );
        }
    }

    /**
     * PageFilter callback — projects the saved page's frontmatter and body links into the knowledge graph.
     */
    /**
     * Returns true if the given page name is a system page and should be excluded
     * from knowledge graph projection.
     */
    public boolean isSystemPage( final String pageName ) {
        return systemPageRegistry != null && systemPageRegistry.isSystemPage( pageName );
    }

    @Override
    public void postSave( final Context context, final String content ) {
        try {
            final String pageName = context.getPage().getName();
            if ( isSystemPage( pageName ) ) {
                return;
            }
            final ParsedPage parsed = FrontmatterParser.parse( content );
            projectPage( pageName, parsed.metadata(), parsed.body() );
        } catch ( final Exception e ) {
            LOG.warn( "Knowledge graph projection failed for page '{}': {}",
                    context.getPage().getName(), e.getMessage(), e );
        }
    }
}
