/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.api.knowledge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProposalConflictFlags {

    private static final Logger LOG = LogManager.getLogger( ProposalConflictFlags.class );

    private ProposalConflictFlags() { }

    public static Map< String, Object > forProposal( final KnowledgeGraphService svc, final KgProposal p,
                                                      final boolean adminBypass ) {
        final Map< String, Object > flags = new LinkedHashMap<>();
        if ( p == null || p.proposedData() == null ) return flags;
        try {
            if ( "new-node".equals( p.proposalType() ) ) {
                final Object name = p.proposedData().get( "name" );
                if ( name instanceof String s && !s.isBlank() ) {
                    final KgNode existing = svc.getNodeByName( s, adminBypass );
                    flags.put( "node_exists", existing != null );
                    if ( existing != null ) flags.put( "existing_node_id", existing.id().toString() );
                }
            } else if ( "new-edge".equals( p.proposalType() ) ) {
                final Object src = p.proposedData().get( "source" );
                final Object tgt = p.proposedData().get( "target" );
                final Object rel = p.proposedData().get( "relationship" );
                if ( src instanceof String s && tgt instanceof String t && rel instanceof String r ) {
                    flags.put( "edge_previously_rejected", svc.isRejected( s, t, r ) );
                }
            }
        } catch ( final Exception e ) {
            LOG.warn( "Failed to compute conflict flags for proposal {}: {}", p.id(), e.getMessage() );
        }
        return flags;
    }

    /**
     * Computes the flags for a whole page of proposals with two batched lookups (node names, rejection
     * triples) instead of one query per proposal. Result {@code i} equals
     * {@code forProposal( svc, proposals.get( i ), adminBypass )}; if a batch lookup fails the affected
     * proposals fall back to the per-proposal path, which logs and omits the flag as before.
     */
    public static List< Map< String, Object > > forProposals( final KnowledgeGraphService svc,
                                                              final List< KgProposal > proposals,
                                                              final boolean adminBypass ) {
        final Set< String > names = new LinkedHashSet<>();
        final Set< List< String > > triples = new LinkedHashSet<>();
        for ( final KgProposal p : proposals ) {
            if ( p == null || p.proposedData() == null ) continue;
            if ( "new-node".equals( p.proposalType() ) ) {
                if ( p.proposedData().get( "name" ) instanceof String s && !s.isBlank() ) names.add( s );
            } else if ( "new-edge".equals( p.proposalType() ) ) {
                final Object src = p.proposedData().get( "source" );
                final Object tgt = p.proposedData().get( "target" );
                final Object rel = p.proposedData().get( "relationship" );
                if ( src instanceof String s && tgt instanceof String t && rel instanceof String r ) {
                    triples.add( List.of( s, t, r ) );
                }
            }
        }
        Map< String, KgNode > nodesByName = null;
        Set< List< String > > rejected = null;
        try {
            if ( !names.isEmpty() ) nodesByName = svc.getNodesByNames( names, adminBypass );
            if ( !triples.isEmpty() ) rejected = svc.findRejected( triples );
        } catch ( final Exception e ) {
            LOG.warn( "Batched conflict-flag lookup failed, falling back to per-proposal: {}", e.getMessage() );
            final List< Map< String, Object > > fallback = new ArrayList<>( proposals.size() );
            for ( final KgProposal p : proposals ) fallback.add( forProposal( svc, p, adminBypass ) );
            return fallback;
        }
        final List< Map< String, Object > > out = new ArrayList<>( proposals.size() );
        for ( final KgProposal p : proposals ) {
            final Map< String, Object > flags = new LinkedHashMap<>();
            out.add( flags );
            if ( p == null || p.proposedData() == null ) continue;
            if ( "new-node".equals( p.proposalType() ) ) {
                if ( p.proposedData().get( "name" ) instanceof String s && !s.isBlank() ) {
                    final KgNode existing = nodesByName == null ? null : nodesByName.get( s );
                    flags.put( "node_exists", existing != null );
                    if ( existing != null ) flags.put( "existing_node_id", existing.id().toString() );
                }
            } else if ( "new-edge".equals( p.proposalType() ) ) {
                final Object src = p.proposedData().get( "source" );
                final Object tgt = p.proposedData().get( "target" );
                final Object rel = p.proposedData().get( "relationship" );
                if ( src instanceof String s && tgt instanceof String t && rel instanceof String r ) {
                    flags.put( "edge_previously_rejected", rejected != null && rejected.contains( List.of( s, t, r ) ) );
                }
            }
        }
        return out;
    }

    /** Backwards-compatible overload — bypass off. */
    public static Map< String, Object > forProposal( final KnowledgeGraphService svc, final KgProposal p ) {
        return forProposal( svc, p, false );
    }
}
