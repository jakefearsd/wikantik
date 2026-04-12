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
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.PermissionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.*;

/**
 * Default implementation of {@link KnowledgeGraphService}. Delegates data access
 * to {@link JdbcKnowledgeRepository} and adds BFS graph traversal, schema discovery,
 * node merging, and proposal approval/rejection logic.
 *
 * @since 1.0
 */
public class DefaultKnowledgeGraphService implements KnowledgeGraphService {

    private static final Logger LOG = LogManager.getLogger( DefaultKnowledgeGraphService.class );

    /** Default provenance filter when none is provided. */
    private static final Set< Provenance > DEFAULT_PROVENANCE_FILTER =
            Set.of( Provenance.HUMAN_AUTHORED, Provenance.AI_REVIEWED );

    private final JdbcKnowledgeRepository repo;
    private Engine engine;

    private volatile GraphSnapshot cachedSnapshot;
    private volatile Instant cacheTimestamp;
    private static final long CACHE_TTL_SECONDS = 60;

    public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo ) {
        this( repo, null );
    }

    public DefaultKnowledgeGraphService( final JdbcKnowledgeRepository repo, final Engine engine ) {
        this.repo = repo;
        this.engine = engine;
    }

    public void setEngine( final Engine engine ) {
        this.engine = engine;
    }

    // --- Schema discovery ---

    @Override
    public SchemaDescription discoverSchema() {
        final List< String > nodeTypes = repo.getDistinctNodeTypes();
        final List< String > relTypes = repo.getDistinctRelationshipTypes();

        // Scan all nodes to build property key stats and collect distinct status values
        final Map< String, Long > propCounts = new LinkedHashMap<>();
        final Map< String, List< String > > propSamples = new LinkedHashMap<>();
        final Set< String > statusSet = new TreeSet<>();
        final List< KgNode > allNodes = repo.queryNodes( null, null, Integer.MAX_VALUE, 0 );
        for( final KgNode node : allNodes ) {
            if( node.properties() != null ) {
                for( final Map.Entry< String, Object > entry : node.properties().entrySet() ) {
                    final String key = entry.getKey();
                    propCounts.merge( key, 1L, Long::sum );
                    final List< String > samples = propSamples.computeIfAbsent( key, k -> new ArrayList<>() );
                    if( samples.size() < 5 && entry.getValue() != null ) {
                        samples.add( entry.getValue().toString() );
                    }
                    if( "status".equals( key ) && entry.getValue() != null ) {
                        statusSet.add( entry.getValue().toString() );
                    }
                }
            }
        }

        final Map< String, SchemaDescription.PropertyInfo > propertyKeys = new LinkedHashMap<>();
        for( final String key : propCounts.keySet() ) {
            propertyKeys.put( key, new SchemaDescription.PropertyInfo(
                    propCounts.get( key ),
                    propSamples.getOrDefault( key, List.of() )
            ) );
        }

        final long nodeCount = repo.countNodes();
        final long edgeCount = repo.countEdges();
        final long pendingCount = repo.countPendingProposals();

        return new SchemaDescription(
                nodeTypes,
                relTypes,
                new ArrayList<>( statusSet ),
                propertyKeys,
                new SchemaDescription.Stats( nodeCount, edgeCount, pendingCount )
        );
    }

    // --- Node operations ---

    @Override
    public KgNode getNode( final UUID id ) {
        return repo.getNode( id );
    }

    @Override
    public KgNode getNodeByName( final String name ) {
        return repo.getNodeByName( name );
    }

    @Override
    public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final KgNode result = repo.upsertNode( name, nodeType, sourcePage, provenance, properties );
        invalidateSnapshotCache();
        return result;
    }

    @Override
    public void deleteNode( final UUID id ) {
        repo.deleteNode( id );
        invalidateSnapshotCache();
    }

    @Override
    public void mergeNodes( final UUID sourceId, final UUID targetId ) {
        // Move all outbound edges from sourceId to targetId
        final List< KgEdge > outbound = repo.getEdgesForNode( sourceId, "outbound" );
        for( final KgEdge edge : outbound ) {
            repo.upsertEdge( targetId, edge.targetId(), edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }

        // Move all inbound edges to sourceId → point to targetId instead
        final List< KgEdge > inbound = repo.getEdgesForNode( sourceId, "inbound" );
        for( final KgEdge edge : inbound ) {
            repo.upsertEdge( edge.sourceId(), targetId, edge.relationshipType(),
                    edge.provenance(), edge.properties() );
        }

        // Delete the source node (cascade deletes its old edges)
        repo.deleteNode( sourceId );
        invalidateSnapshotCache();
    }

    @Override
    public List< KgNode > queryNodes( final Map< String, Object > filters,
                                      final Set< Provenance > provenanceFilter,
                                      final int limit, final int offset ) {
        return repo.queryNodes( filters, provenanceFilter, limit, offset );
    }

    // --- Edge operations ---

    @Override
    public KgEdge upsertEdge( final UUID sourceId, final UUID targetId, final String relationshipType,
                              final Provenance provenance, final Map< String, Object > properties ) {
        final KgEdge result = repo.upsertEdge( sourceId, targetId, relationshipType, provenance, properties );
        invalidateSnapshotCache();
        return result;
    }

    @Override
    public void deleteEdge( final UUID id ) {
        repo.deleteEdge( id );
        invalidateSnapshotCache();
    }

    @Override
    public void diffAndRemoveStaleEdges( final UUID sourceId,
                                         final Set< Map.Entry< String, String > > currentEdges ) {
        repo.diffAndRemoveStaleEdges( sourceId, currentEdges );
        invalidateSnapshotCache();
    }

    @Override
    public List< KgEdge > getEdgesForNode( final UUID nodeId, final String direction ) {
        return repo.getEdgesForNode( nodeId, direction );
    }

    @Override
    public List< Map< String, Object > > queryEdges( final String relationshipType, final String searchName,
                                                      final int limit, final int offset ) {
        return repo.queryEdgesWithNames( relationshipType, searchName, limit, offset );
    }

    @Override
    public Map< UUID, String > getNodeNames( final Collection< UUID > ids ) {
        return repo.getNodeNames( ids );
    }

    // --- Traversal ---

    @Override
    public TraversalResult traverse( final String startNodeName, final String direction,
                                     final Set< String > relationshipTypes, final int maxDepth,
                                     final Set< Provenance > provenanceFilter ) {
        final KgNode startNode = repo.getNodeByName( startNodeName );
        if( startNode == null ) {
            return new TraversalResult( List.of(), List.of() );
        }

        final Set< Provenance > effectiveProvenance =
                ( provenanceFilter == null || provenanceFilter.isEmpty() )
                        ? DEFAULT_PROVENANCE_FILTER
                        : provenanceFilter;

        // BFS state
        final Map< UUID, KgNode > visited = new LinkedHashMap<>();
        final List< KgEdge > collectedEdges = new ArrayList<>();
        final Queue< UUID > queue = new ArrayDeque<>();
        final Map< UUID, Integer > depthMap = new HashMap<>();

        // Seed with start node
        visited.put( startNode.id(), startNode );
        queue.add( startNode.id() );
        depthMap.put( startNode.id(), 0 );

        while( !queue.isEmpty() ) {
            final UUID currentId = queue.poll();
            final int currentDepth = depthMap.get( currentId );

            if( currentDepth >= maxDepth ) {
                continue;
            }

            final List< KgEdge > edges = repo.getEdgesForNode( currentId, direction );
            for( final KgEdge edge : edges ) {
                // Check provenance filter
                if( !effectiveProvenance.contains( edge.provenance() ) ) {
                    continue;
                }

                // Check relationship type filter
                if( relationshipTypes != null && !relationshipTypes.isEmpty()
                        && !relationshipTypes.contains( edge.relationshipType() ) ) {
                    continue;
                }

                collectedEdges.add( edge );

                // Resolve the neighbor (the node on the other side of the edge)
                final UUID neighborId = edge.sourceId().equals( currentId )
                        ? edge.targetId()
                        : edge.sourceId();

                if( !visited.containsKey( neighborId ) ) {
                    final KgNode neighbor = repo.getNode( neighborId );
                    if( neighbor != null ) {
                        visited.put( neighborId, neighbor );
                        queue.add( neighborId );
                        depthMap.put( neighborId, currentDepth + 1 );
                    }
                }
            }
        }

        return new TraversalResult( new ArrayList<>( visited.values() ), collectedEdges );
    }

    // --- Search ---

    @Override
    public List< KgNode > searchKnowledge( final String query, final Set< Provenance > provenanceFilter,
                                           final int limit ) {
        return repo.searchNodes( query, provenanceFilter, limit );
    }

    // --- Proposal management ---

    @Override
    public KgProposal submitProposal( final String proposalType, final String sourcePage,
                                      final Map< String, Object > proposedData,
                                      final double confidence, final String reasoning ) {
        // For "new-edge" proposals, check if the relationship was previously rejected
        if( "new-edge".equals( proposalType ) && proposedData != null ) {
            final String source = Objects.toString( proposedData.get( "source" ), null );
            final String target = Objects.toString( proposedData.get( "target" ), null );
            final String relationship = Objects.toString( proposedData.get( "relationship" ), null );
            if( source != null && target != null && relationship != null ) {
                if( repo.isRejected( source, target, relationship ) ) {
                    LOG.info( "Proposal rejected: {}->{} [{}] was previously rejected",
                            source, target, relationship );
                    return null;
                }
            }
        }
        return repo.insertProposal( proposalType, sourcePage, proposedData, confidence, reasoning );
    }

    @Override
    public List< KgProposal > listProposals( final String status, final String sourcePage,
                                             final int limit, final int offset ) {
        return repo.listProposals( status, sourcePage, limit, offset );
    }

    @Override
    public KgProposal approveProposal( final UUID proposalId, final String reviewedBy ) {
        repo.updateProposalStatus( proposalId, "approved", reviewedBy );
        return repo.getProposal( proposalId );
    }

    @Override
    public KgProposal rejectProposal( final UUID proposalId, final String reviewedBy, final String reason ) {
        repo.updateProposalStatus( proposalId, "rejected", reviewedBy );

        // For "new-edge" proposals, record the rejection so the same edge won't be proposed again
        final KgProposal proposal = repo.getProposal( proposalId );
        if( proposal != null && "new-edge".equals( proposal.proposalType() ) && proposal.proposedData() != null ) {
            final String source = Objects.toString( proposal.proposedData().get( "source" ), null );
            final String target = Objects.toString( proposal.proposedData().get( "target" ), null );
            final String relationship = Objects.toString( proposal.proposedData().get( "relationship" ), null );
            if( source != null && target != null && relationship != null ) {
                repo.insertRejection( source, target, relationship, reviewedBy, reason );
            }
        }
        return proposal;
    }

    // --- Rejection queries ---

    @Override
    public List< KgRejection > listRejections( final String sourceName, final String targetName,
                                               final String relationshipType ) {
        return repo.listRejections( sourceName, targetName, relationshipType );
    }

    @Override
    public boolean isRejected( final String sourceName, final String targetName,
                               final String relationshipType ) {
        return repo.isRejected( sourceName, targetName, relationshipType );
    }

    @Override
    public void clearAll() {
        repo.clearAll();
        invalidateSnapshotCache();
    }

    // --- Graph visualization ---

    @Override
    public GraphSnapshot snapshotGraph( final Session viewer ) {
        if ( viewer == null || viewer.isAnonymous() ) {
            throw new IllegalArgumentException( "Authenticated session required" );
        }

        GraphSnapshot base = cachedSnapshot;
        final Instant now = Instant.now();
        if ( base == null || cacheTimestamp == null
                || now.isAfter( cacheTimestamp.plusSeconds( CACHE_TTL_SECONDS ) ) ) {
            base = buildUnredactedSnapshot();
            cachedSnapshot = base;
            cacheTimestamp = now;
        }

        return redactForViewer( base, viewer );
    }

    private GraphSnapshot buildUnredactedSnapshot() {
        final List< KgNode > allNodes = repo.getAllNodes();
        final List< KgEdge > allEdges = repo.getAllEdges();

        final Map< UUID, int[] > degrees = new HashMap<>();
        for ( final KgEdge edge : allEdges ) {
            degrees.computeIfAbsent( edge.sourceId(), k -> new int[2] )[1]++;
            degrees.computeIfAbsent( edge.targetId(), k -> new int[2] )[0]++;
        }

        final int hubThreshold = computeHubThreshold( allNodes, degrees );

        final List< SnapshotNode > nodes = new ArrayList<>( allNodes.size() );
        for ( final KgNode node : allNodes ) {
            final int[] deg = degrees.getOrDefault( node.id(), new int[2] );
            final String role = GraphRoleClassifier.classify( node, deg[0], deg[1], hubThreshold, false );
            nodes.add( new SnapshotNode(
                    node.id(), node.name(), node.nodeType(), role,
                    node.provenance(), node.sourcePage(), deg[0], deg[1], false ) );
        }

        final List< SnapshotEdge > edges = allEdges.stream()
                .map( e -> new SnapshotEdge( e.id(), e.sourceId(), e.targetId(),
                        e.relationshipType(), e.provenance() ) )
                .toList();

        return new GraphSnapshot( Instant.now().toString(), nodes.size(), edges.size(),
                hubThreshold, nodes, edges );
    }

    private int computeHubThreshold( final List< KgNode > nodes,
                                      final Map< UUID, int[] > degrees ) {
        if ( nodes.isEmpty() ) return 10;
        final int[] totals = nodes.stream()
                .mapToInt( n -> {
                    final int[] d = degrees.getOrDefault( n.id(), new int[2] );
                    return d[0] + d[1];
                } )
                .sorted()
                .toArray();
        final int p95Index = (int) Math.ceil( totals.length * 0.95 ) - 1;
        final int p95 = totals[Math.max( 0, p95Index )];
        return Math.max( 10, p95 );
    }

    private GraphSnapshot redactForViewer( final GraphSnapshot base, final Session viewer ) {
        if ( engine == null ) {
            return base;
        }
        final AuthorizationManager authMgr = engine.getManager( AuthorizationManager.class );
        final PageManager pageMgr = engine.getManager( PageManager.class );

        final List< SnapshotNode > redacted = new ArrayList<>( base.nodes().size() );
        for ( final SnapshotNode node : base.nodes() ) {
            if ( node.sourcePage() != null && !isViewable( node.sourcePage(), viewer, authMgr, pageMgr ) ) {
                redacted.add( new SnapshotNode(
                        node.id(), null, null, "restricted", null, null,
                        node.degreeIn(), node.degreeOut(), true ) );
            } else {
                redacted.add( node );
            }
        }
        return new GraphSnapshot( base.generatedAt(), base.nodeCount(), base.edgeCount(),
                base.hubDegreeThreshold(), redacted, base.edges() );
    }

    private boolean isViewable( final String pageName, final Session viewer,
                                 final AuthorizationManager authMgr, final PageManager pageMgr ) {
        final Page page = pageMgr.getPage( pageName );
        final java.security.Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, "view" )
                : new PagePermission( engine.getApplicationName() + ":" + pageName, "view" );
        return authMgr.checkPermission( viewer, perm );
    }

    private void invalidateSnapshotCache() {
        cachedSnapshot = null;
        cacheTimestamp = null;
    }
}
