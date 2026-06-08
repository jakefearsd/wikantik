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
package com.wikantik.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class EntityProjectorTest {

    private static final UUID NID = UUID.fromString( "00000000-0000-0000-0000-0000000000c1" );
    private static final UUID TID = UUID.fromString( "00000000-0000-0000-0000-0000000000c2" );

    private KgNode node( final String type, final String sourcePage ) {
        return new KgNode( NID, "Backpropagation", type, sourcePage, Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
    }

    @Test
    void typesAndLabelsTheEntity() {
        final Model g = EntityProjector.project( node( "technology", null ), List.of(), slug -> null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ), RDF.type,
                ResourceFactory.createResource( Iris.term( "Technology" ) ) ) );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ), RDFS.label,
                g.createLiteral( "Backpropagation" ) ) );
    }

    @Test
    void nullTypeDefaultsToConcept() {
        final Model g = EntityProjector.project( node( null, null ), List.of(), slug -> null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ), RDF.type,
                ResourceFactory.createResource( Iris.term( "Concept" ) ) ) );
    }

    @Test
    void includesOutgoingEdges() {
        final KgEdge e = new KgEdge( UUID.randomUUID(), NID, TID, "enables", Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
        final Model g = EntityProjector.project( node( "technology", null ), List.of( e ), slug -> null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ),
                ResourceFactory.createProperty( Iris.term( "enables" ) ),
                ResourceFactory.createResource( Iris.entity( TID ) ) ) );
    }

    @Test
    void derivesFromPageWhenSourcePageResolves() {
        final Model g = EntityProjector.project(
                node( "technology", "Backprop.md" ), List.of(),
                slug -> "Backprop.md".equals( slug ) ? "01CANONICALID00000000000000" : null );
        assertTrue( g.contains(
                ResourceFactory.createResource( Iris.entity( NID ) ),
                ResourceFactory.createProperty( "http://www.w3.org/ns/prov#wasDerivedFrom" ),
                ResourceFactory.createResource( Iris.page( "01CANONICALID00000000000000" ) ) ) );
    }

    @Test
    void stubNodeHasNoDerivation() {
        final Model g = EntityProjector.project( node( "technology", null ), List.of(), slug -> null );
        assertFalse( g.contains( (org.apache.jena.rdf.model.Resource) null,
                ResourceFactory.createProperty( "http://www.w3.org/ns/prov#wasDerivedFrom" ),
                (org.apache.jena.rdf.model.RDFNode) null ) );
    }
}
