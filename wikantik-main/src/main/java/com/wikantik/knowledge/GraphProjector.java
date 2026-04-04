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

import com.wikantik.api.knowledge.*;
import com.wikantik.knowledge.FrontmatterRelationshipDetector.DetectionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Projects wiki page frontmatter into the knowledge graph. Called on every page save
 * that includes frontmatter. Upserts the page's node, resolves relationships to edges,
 * creates stub nodes for unresolved references, and diffs to remove stale edges.
 */
public class GraphProjector {

    private static final Logger LOG = LogManager.getLogger( GraphProjector.class );

    private final KnowledgeGraphService service;
    private final FrontmatterRelationshipDetector detector;

    public GraphProjector( final KnowledgeGraphService service ) {
        this.service = service;
        this.detector = new FrontmatterRelationshipDetector();
    }

    /**
     * Projects a page's frontmatter into the knowledge graph.
     *
     * @param pageName the wiki page name (used as node name and source_page)
     * @param frontmatter the parsed frontmatter metadata map
     */
    public void projectPage( final String pageName, final Map< String, Object > frontmatter ) {
        if ( frontmatter == null || frontmatter.isEmpty() ) {
            return;
        }

        final DetectionResult detection = detector.detect( frontmatter );

        // 1. Upsert the page's node
        final String nodeType = detection.properties().containsKey( "type" )
                ? String.valueOf( detection.properties().get( "type" ) ) : null;
        final KgNode pageNode = service.upsertNode( pageName, nodeType, pageName,
                Provenance.HUMAN_AUTHORED, detection.properties() );

        LOG.debug( "Projected node for page '{}': type={}, properties={}",
                pageName, nodeType, detection.properties().size() );

        // 2. Resolve relationships to edges
        final Set< Map.Entry< String, String > > currentEdges = new HashSet<>();
        for ( final Map.Entry< String, List< String > > rel : detection.relationships().entrySet() ) {
            final String relationshipType = rel.getKey();
            for ( final String targetName : rel.getValue() ) {
                // Ensure target node exists (create stub if needed)
                KgNode existing = service.getNodeByName( targetName );
                if ( existing == null ) {
                    existing = service.upsertNode( targetName, null, null,
                            Provenance.HUMAN_AUTHORED, Map.of() );
                    LOG.debug( "Created stub node for '{}'", targetName );
                }
                final KgNode target = existing;

                // Check if edge already exists with ai-reviewed provenance (promotion write-back)
                final List< KgEdge > existingEdges = service.getEdgesForNode( pageNode.id(), "outbound" );
                final boolean alreadyReviewed = existingEdges.stream().anyMatch( e ->
                        e.targetId().equals( target.id() )
                        && e.relationshipType().equals( relationshipType )
                        && e.provenance() == Provenance.AI_REVIEWED );

                if ( !alreadyReviewed ) {
                    service.upsertEdge( pageNode.id(), target.id(), relationshipType,
                            Provenance.HUMAN_AUTHORED, Map.of() );
                }

                currentEdges.add( Map.entry( targetName, relationshipType ) );
            }
        }

        // 3. Diff: remove human-authored edges no longer in frontmatter
        service.diffAndRemoveStaleEdges( pageNode.id(), currentEdges );

        LOG.debug( "Projection complete for '{}': {} relationships", pageName, currentEdges.size() );
    }
}
