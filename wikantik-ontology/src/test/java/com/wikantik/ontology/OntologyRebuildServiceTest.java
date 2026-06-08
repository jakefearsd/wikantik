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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.projection.PageRecord;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class OntologyRebuildServiceTest {

    private static final UUID N1 = UUID.fromString( "00000000-0000-0000-0000-0000000000d1" );
    private static final UUID N2 = UUID.fromString( "00000000-0000-0000-0000-0000000000d2" );

    @Test
    void fullRebuildMaterializesEntitiesPagesConceptsAndInfersTypes() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();

        final List< KgNode > nodes = List.of(
                new KgNode( N1, "Backpropagation", "technology", "Backprop.md", Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ),
                new KgNode( N2, "Gradient", "concept", null, Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final List< KgEdge > edges = List.of(
                new KgEdge( UUID.randomUUID(), N1, N2, "uses", Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final List< PageRecord > pages = List.of(
                new PageRecord( "01CANON0000000000000000001", "Backprop.md", "Backprop", "article",
                        "ml", List.of( "neural-networks" ), "x", "2026-01-01", "researcher" ) );

        final OntologyRebuildService svc = new OntologyRebuildService();
        final int graphs = svc.rebuild( mgr, nodes, edges, pages );

        // 2 entities + 1 page + 2 concepts (cluster "ml" + tag "neural-networks") = 5 named graphs.
        assertTrue( graphs >= 5, "expected >= 5 named graphs, got " + graphs );

        assertTrue( mgr.namedGraphExists( Iris.entity( N1 ) ) );

        // subClassOf inference: the Technology entity is a schema:Thing.
        final Model inf = mgr.inferenceSnapshot();
        assertTrue( inf.contains(
                ResourceFactory.createResource( Iris.entity( N1 ) ), RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "Thing" ) ) );
    }

    @Test
    void rebuildIsIdempotent() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final List< KgNode > nodes = List.of(
                new KgNode( N1, "X", "concept", null, Provenance.HUMAN_AUTHORED,
                        Map.of(), null, null, "human", null ) );
        final OntologyRebuildService svc = new OntologyRebuildService();
        final int first  = svc.rebuild( mgr, nodes, List.of(), List.of() );
        final int second = svc.rebuild( mgr, nodes, List.of(), List.of() );
        assertTrue( first == second, "rebuild graph count is stable across runs" );
    }
}
