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
package com.wikantik.ontology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.ontology.projection.ConceptProjector;
import com.wikantik.ontology.projection.EntityProjector;
import com.wikantik.ontology.projection.PageProjector;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Materializes the full A-Box from in-memory inputs: clears existing named
 * graphs, then projects every entity (with its outgoing edges), page, and
 * concept into its own named graph. Pure orchestration — the caller (Phase 1b)
 * supplies the lists from the repositories.
 */
public final class OntologyRebuildService {

    private static final Logger LOG = LogManager.getLogger( OntologyRebuildService.class );

    /** @return the number of named graphs written. */
    public int rebuild( final OntologyModelManager mgr,
                        final List< KgNode > nodes,
                        final List< KgEdge > edges,
                        final List< PageRecord > pages ) {
        mgr.clearAbox();

        // Index outgoing edges by source node, and pages' slug -> canonical_id.
        final Map< UUID, List< KgEdge > > outgoing = new HashMap<>();
        for ( final KgEdge e : edges ) {
            outgoing.computeIfAbsent( e.sourceId(), k -> new ArrayList<>() ).add( e );
        }
        final Map< String, String > slugToCanonical = new HashMap<>();
        for ( final PageRecord p : pages ) {
            if ( p.slug() != null ) {
                slugToCanonical.put( p.slug(), p.canonicalId() );
            }
        }

        int written = 0;
        for ( final KgNode node : nodes ) {
            final Model g = EntityProjector.project(
                    node, outgoing.getOrDefault( node.id(), List.of() ), slugToCanonical::get );
            mgr.replaceNamedGraph( Iris.entity( node.id() ), g );
            written++;
        }
        for ( final PageRecord page : pages ) {
            mgr.replaceNamedGraph( Iris.page( page.canonicalId() ), PageProjector.project( page ) );
            written++;
        }
        for ( final Map.Entry< String, Model > c : ConceptProjector.project( pages ).entrySet() ) {
            mgr.replaceNamedGraph( c.getKey(), c.getValue() );
            written++;
        }
        LOG.info( "ontology rebuild: {} named graphs ({} nodes, {} edges, {} pages)",
                written, nodes.size(), edges.size(), pages.size() );
        return written;
    }
}
