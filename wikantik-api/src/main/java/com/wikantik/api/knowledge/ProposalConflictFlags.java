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

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProposalConflictFlags {

    private static final Logger LOG = LogManager.getLogger( ProposalConflictFlags.class );

    private ProposalConflictFlags() { }

    public static Map< String, Object > forProposal( final KnowledgeGraphService svc, final KgProposal p ) {
        final Map< String, Object > flags = new LinkedHashMap<>();
        if ( p == null || p.proposedData() == null ) return flags;
        try {
            if ( "new-node".equals( p.proposalType() ) ) {
                final Object name = p.proposedData().get( "name" );
                if ( name instanceof String s && !s.isBlank() ) {
                    final KgNode existing = svc.getNodeByName( s );
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
}
