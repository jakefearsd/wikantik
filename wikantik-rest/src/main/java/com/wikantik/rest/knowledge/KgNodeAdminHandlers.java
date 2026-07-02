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
package com.wikantik.rest.knowledge;

import com.google.gson.JsonObject;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.rest.KnowledgeJsonMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Node-curation admin handlers extracted verbatim from {@code AdminKnowledgeResource}:
 * querying/reading nodes (including the by-id, by-id+mentions, and by-name+similar branches),
 * the mention-similarity lookup, and the upsert/merge/delete curation actions.
 * <p>
 * Package-private-in-spirit: the type is {@code public} only because it must be constructed and
 * invoked from {@code com.wikantik.rest.AdminKnowledgeResource}'s dispatch table, which lives in a
 * different package — it is not part of any documented public API of {@code wikantik-rest}.
 */
public final class KgNodeAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( KgNodeAdminHandlers.class );

    private final Supplier< KgCurationOps > curationOps;
    private final Supplier< NodeMentionSimilarity > similarity;
    private final Supplier< PageManager > pageManager;
    private final Supplier< Engine > engine;

    public KgNodeAdminHandlers( final Supplier< KgCurationOps > curationOps,
                                 final Supplier< NodeMentionSimilarity > similarity,
                                 final Supplier< PageManager > pageManager,
                                 final Supplier< Engine > engine ) {
        this.curationOps = curationOps;
        this.similarity = similarity;
        this.pageManager = pageManager;
        this.engine = engine;
    }

    // --- GET handlers ---

    public void handleGetNodes( final KnowledgeGraphService service,
                                 final HttpServletRequest request,
                                 final HttpServletResponse response,
                                 final String[] segments ) throws IOException {
        if ( segments.length >= 3 && "similar".equals( segments[2] ) ) {
            // GET /admin/knowledge-graph/nodes/{name}/similar?limit=10
            handleGetSimilarNodes( request, response, segments[1] );
            return;
        }
        if ( segments.length >= 3 && "by-id".equals( segments[1] ) ) {
            // GET /admin/knowledge-graph/nodes/by-id/{uuid}
            // GET /admin/knowledge-graph/nodes/by-id/{uuid}/mentions?limit=N
            // ID-based lookup. Required because Tomcat rejects encoded slashes
            // in path segments by default, so a name like
            // "Automated Storage and Retrieval System (AS/RS)" can't be passed
            // through the /nodes/{name} variant — the request 400s before it
            // reaches this servlet.
            final UUID id = AdminKnowledgeIo.parseUuid( segments[2], response );
            if ( id == null ) return;
            if ( segments.length >= 4 && "mentions".equals( segments[3] ) ) {
                final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 3 );
                final List< com.wikantik.api.knowledge.NodeMention > mentions =
                    service.getMentionsForNode( id, limit );
                final List< Map< String, Object > > rows = mentions.stream()
                    .map( KnowledgeJsonMapper::mentionToMap )
                    .toList();
                final Map< String, Object > result = new LinkedHashMap<>();
                result.put( "mentions", rows );
                AdminKnowledgeIo.sendJson( response, result );
                return;
            }
            final KgNode node = service.getNode( id, true );
            if ( node == null ) {
                AdminKnowledgeIo.sendNotFound( response, "Node not found: " + id );
                return;
            }
            final Map< String, Object > result = KnowledgeJsonMapper.nodeToMap( node );
            final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
            final Map< UUID, String > nameMap = AdminKnowledgeIo.resolveEdgeNames( service, edges );
            result.put( "edges", edges.stream().map( e -> KnowledgeJsonMapper.enrichEdge( e, nameMap ) ).toList() );
            AdminKnowledgeIo.sendJson( response, result );
            return;
        }
        if ( segments.length >= 2 ) {
            // GET /admin/knowledge-graph/nodes/{name}
            final String name = segments[1];
            final KgNode node = service.getNodeByName( name, true );
            if ( node == null ) {
                AdminKnowledgeIo.sendNotFound( response, "Node not found: " + name );
            } else {
                final Map< String, Object > result = KnowledgeJsonMapper.nodeToMap( node );
                final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
                final Map< UUID, String > nameMap = AdminKnowledgeIo.resolveEdgeNames( service, edges );
                result.put( "edges", edges.stream().map( e -> KnowledgeJsonMapper.enrichEdge( e, nameMap ) ).toList() );
                AdminKnowledgeIo.sendJson( response, result );
            }
        } else {
            // GET /admin/knowledge-graph/nodes?node_type=...&name=...&limit=...&offset=...
            final Map< String, Object > filters = new LinkedHashMap<>();
            if ( request.getParameter( "node_type" ) != null ) {
                filters.put( "node_type", request.getParameter( "node_type" ) );
            }
            if ( request.getParameter( "name" ) != null ) {
                filters.put( "name", request.getParameter( "name" ) );
            }
            if ( request.getParameter( "status" ) != null ) {
                filters.put( "status", request.getParameter( "status" ) );
            }
            final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 50 );
            final int offset = AdminKnowledgeIo.parseIntParam( request, "offset", 0 );
            final List< KgNode > nodes = service.queryNodes( filters, null, limit, offset, true );
            final long total = service.countNodes( filters, null );
            final Map< String, Object > result = new LinkedHashMap<>();
            result.put( "nodes", nodes.stream().map( KnowledgeJsonMapper::nodeToMap ).toList() );
            result.put( "total", total );
            AdminKnowledgeIo.sendJson( response, result );
        }
    }

    private void handleGetSimilarNodes( final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String nodeName ) throws IOException {
        final NodeMentionSimilarity sim = similarity.get();
        if ( sim == null || !sim.isReady() ) {
            AdminKnowledgeIo.sendJson( response, Map.of( "similar", List.of() ) );
            return;
        }
        final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 10 );
        final var ranked = sim.similarTo( nodeName, limit );
        AdminKnowledgeIo.sendJson( response, Map.of( "similar", ranked.stream().map( s -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "name", s.name() );
            m.put( "similarity", s.score() );
            return m;
        } ).toList() ) );
    }

    // --- POST handlers ---

    public void handlePostNode( final KnowledgeGraphService service,
                                final HttpServletRequest request,
                                final HttpServletResponse response,
                                final String[] segments ) throws IOException {
        final KgCurationOps ops = curationOps.get();
        if ( segments.length >= 2 && "merge".equals( segments[1] ) ) {
            // POST /admin/knowledge-graph/nodes/merge
            final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
            if ( body == null ) return;
            final String sourceIdStr = AdminKnowledgeIo.getJsonString( body, "sourceId" );
            final String targetIdStr = AdminKnowledgeIo.getJsonString( body, "targetId" );
            if ( sourceIdStr == null || targetIdStr == null ) {
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "sourceId and targetId are required" );
                return;
            }
            final UUID sourceId = AdminKnowledgeIo.parseUuid( sourceIdStr, response );
            if ( sourceId == null ) return;
            final UUID targetId = AdminKnowledgeIo.parseUuid( targetIdStr, response );
            if ( targetId == null ) return;

            // Resolve names and update frontmatter BEFORE merge (edges are deleted during merge)
            final KgNode sourceNode = service.getNode( sourceId, true );
            final KgNode targetNode = service.getNode( targetId, true );
            int pagesUpdated = 0;
            if ( sourceNode != null && targetNode != null ) {
                pagesUpdated = renameFrontmatterReferences(
                        service, sourceNode.name(), targetNode.name(), sourceId );
            }

            final java.util.Optional< String > mergeErr = ops.tryMergeNodes( sourceId, targetId, AdminKnowledgeIo.actor( request ) );
            if ( mergeErr.isPresent() ) {
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, mergeErr.get() );
                return;
            }
            AdminKnowledgeIo.sendJson( response, Map.of( "merged", true, "targetId", targetId.toString(),
                    "pages_updated", pagesUpdated ) );
        } else {
            // POST /admin/knowledge-graph/nodes — upsert
            final JsonObject body = AdminKnowledgeIo.parseJsonBody( request, response );
            if ( body == null ) return;
            final String name = AdminKnowledgeIo.getJsonString( body, "name" );
            if ( name == null || name.isBlank() ) {
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "name is required" );
                return;
            }
            final String nodeType = AdminKnowledgeIo.getJsonString( body, "node_type" );
            final String sourcePage = AdminKnowledgeIo.getJsonString( body, "source_page" );
            final Map< String, Object > properties = body.has( "properties" )
                    ? AdminKnowledgeIo.GSON.fromJson( body.get( "properties" ), AdminKnowledgeIo.MAP_TYPE ) : Map.of();
            final KgCurationOps.NodeResult nodeResult = ops.tryUpsertNode( name, nodeType, sourcePage,
                    properties, AdminKnowledgeIo.actor( request ) );
            if ( nodeResult.error().isPresent() ) {
                LOG.warn( "tryUpsertNode: name='{}' sourcePage='{}': {}", name, sourcePage, nodeResult.error().get() );
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_CONFLICT, nodeResult.error().get() );
                return;
            }
            final KgNode node = service.getNode( nodeResult.nodeId().get(), true );
            if ( node == null ) {
                AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_CONFLICT,
                    "node not visible after insert (excluded source page or other policy filter)" );
                return;
            }
            AdminKnowledgeIo.sendJson( response, KnowledgeJsonMapper.nodeToMap( node ) );
        }
    }

    // --- DELETE handlers ---

    public void handleDeleteNode( final KnowledgeGraphService service,
                                  final HttpServletResponse response,
                                  final String idStr ) throws IOException {
        final UUID id = AdminKnowledgeIo.parseUuid( idStr, response );
        if ( id == null ) return;
        final java.util.Optional< String > err = curationOps.get().tryDeleteNode( id, null );
        if ( err.isPresent() ) {
            LOG.warn( "handleDeleteNode: failed for node {}: {}", id, err.get() );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, err.get() );
            return;
        }
        LOG.info( "Knowledge graph node deleted: {}", id );
        AdminKnowledgeIo.sendJson( response, Map.of( "deleted", true ) );
    }

    /**
     * Renames all frontmatter references from {@code oldName} to {@code newName}
     * across pages that have inbound edges to the given node.
     *
     * @return the number of pages whose frontmatter was updated
     */
    @SuppressWarnings( "unchecked" )
    private int renameFrontmatterReferences( final KnowledgeGraphService service,
                                             final String oldName, final String newName,
                                             final UUID oldNodeId ) {
        final PageManager pm = pageManager.get();
        final List< KgEdge > inbound = service.getEdgesForNode( oldNodeId, "inbound" );

        // Collect unique source pages to update (multiple edges may come from the same page)
        final Set< String > pagesToUpdate = new LinkedHashSet<>();
        for ( final KgEdge edge : inbound ) {
            final KgNode srcNode = service.getNode( edge.sourceId(), true );
            if ( srcNode != null && srcNode.sourcePage() != null ) {
                pagesToUpdate.add( srcNode.sourcePage().replace( ".md", "" ) );
            }
        }

        int updated = 0;
        for ( final String pageName : pagesToUpdate ) {
            try {
                final String pageText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
                if ( pageText == null ) {
                    continue;
                }

                final ParsedPage parsed = FrontmatterParser.parse( pageText );
                final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
                boolean changed = false;

                for ( final Map.Entry< String, Object > entry : metadata.entrySet() ) {
                    if ( entry.getValue() instanceof List ) {
                        final List< String > list = new ArrayList<>( ( List< String > ) entry.getValue() );
                        final int idx = list.indexOf( oldName );
                        if ( idx >= 0 ) {
                            list.set( idx, newName );
                            // Remove duplicates if newName was already in the list
                            final List< String > deduped = list.stream().distinct().collect( Collectors.toList() );
                            metadata.put( entry.getKey(), deduped );
                            changed = true;
                        }
                    }
                }

                if ( changed ) {
                    final String updatedText = FrontmatterWriter.write( metadata, parsed.body() );
                    final PageSaveHelper saveHelper = new PageSaveHelper( engine.get(), pm );
                    final SaveOptions options = SaveOptions.builder()
                            .author( "Knowledge Admin" )
                            .changeNote( "Merge: renamed " + oldName + " → " + newName )
                            .build();
                    saveHelper.saveText( pageName, updatedText, options );
                    updated++;
                    LOG.info( "Merge frontmatter update: renamed {} → {} in page '{}'",
                            oldName, newName, pageName );
                }
            } catch ( final WikiException e ) {
                LOG.error( "Failed to update frontmatter for merge in page '{}': {}",
                        pageName, e.getMessage(), e );
            }
        }
        return updated;
    }
}
