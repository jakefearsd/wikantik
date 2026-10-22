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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class OntologyModelManagerTest {

    private Model entityGraph( final String entityIri, final String classLocal ) {
        final Model m = ModelFactory.createDefaultModel();
        m.add( ResourceFactory.createResource( entityIri ),
               RDF.type,
               ResourceFactory.createResource( Iris.term( classLocal ) ) );
        return m;
    }

    @Test
    void loadsTBoxAndReplacesNamedGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000a1" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Technology" ) );

        assertTrue( mgr.namedGraphExists( iri ) );
        mgr.removeNamedGraph( iri );
        assertFalse( mgr.namedGraphExists( iri ) );
    }

    @Test
    void rdfsInferenceDerivesSchemaThingFromTechnology() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000a2" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Technology" ) );

        final Model inf = mgr.inferenceSnapshot();
        assertTrue( inf.contains(
                ResourceFactory.createResource( iri ),
                RDF.type,
                ResourceFactory.createResource( Iris.SCHEMA + "Thing" ) ),
                "RDFS inference should type the Technology individual as schema:Thing" );
    }

    @Test
    void clearAboxLeavesTBoxIntact() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000a3" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Concept" ) );
        mgr.clearAbox();
        assertFalse( mgr.namedGraphExists( iri ), "A-Box graphs gone after clear" );
        assertTrue( mgr.tboxSnapshot().contains(
                ResourceFactory.createResource( Iris.term( "Concept" ) ),
                RDF.type,
                ResourceFactory.createResource( "http://www.w3.org/2002/07/owl#Class" ) ) );
    }

    @Test
    void unionSnapshotIncludesTBoxAndNamedGraphs() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000b9" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Technology" ) );
        final Model union = mgr.unionSnapshot();
        assertTrue( union.contains(
                ResourceFactory.createResource( Iris.term( "Technology" ) ), RDF.type,
                ResourceFactory.createResource( "http://www.w3.org/2002/07/owl#Class" ) ),
                "T-Box class present in union" );
        assertTrue( union.contains(
                ResourceFactory.createResource( iri ), RDF.type,
                ResourceFactory.createResource( Iris.term( "Technology" ) ) ),
                "A-Box triple present in union" );
    }

    @Test
    void namedGraphSnapshotReturnsJustThatGraph() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final String iri = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000ba" ) );
        mgr.replaceNamedGraph( iri, entityGraph( iri, "Concept" ) );
        assertTrue( mgr.namedGraphSnapshot( iri ).size() == 1, "only the resource's own triples" );
    }

    @Test
    void namedGraphCountTracksReplacementsAndRemovals() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        assertEquals( 0L, mgr.namedGraphCount(), "no A-Box graphs right after loadTBox" );

        final String iriA = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000c1" ) );
        final String iriB = Iris.entity( java.util.UUID.fromString( "00000000-0000-0000-0000-0000000000c2" ) );
        mgr.replaceNamedGraph( iriA, entityGraph( iriA, "Technology" ) );
        mgr.replaceNamedGraph( iriB, entityGraph( iriB, "Concept" ) );
        assertEquals( 2L, mgr.namedGraphCount() );

        mgr.removeNamedGraph( iriA );
        assertEquals( 1L, mgr.namedGraphCount() );
    }

    @Test
    void replaceNamedGraphAbortsAndRethrowsOnNullModel() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        // A null triples model can't be committed; the write must abort cleanly and
        // rethrow rather than leave the transaction open or silently swallow the error.
        assertThrows( RuntimeException.class, () -> mgr.replaceNamedGraph( "urn:test:bad-write", null ) );
        assertFalse( mgr.namedGraphExists( "urn:test:bad-write" ), "failed write left no partial graph" );
    }

    @Test
    void writeBatchCommitsEveryGraphInOneTransaction() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        mgr.replaceNamedGraph( "urn:test:gone", entityGraph( "urn:test:gone", "Concept" ) );
        final long before = mgr.committedWriteTransactions();

        mgr.writeBatch( w -> {
            w.replaceNamedGraph( "urn:test:a", entityGraph( "urn:test:a", "Technology" ) );
            w.replaceNamedGraph( "urn:test:b", entityGraph( "urn:test:b", "Concept" ) );
            w.removeNamedGraph( "urn:test:gone" );
        } );

        assertEquals( 1L, mgr.committedWriteTransactions() - before );
        assertTrue( mgr.namedGraphExists( "urn:test:a" ) );
        assertTrue( mgr.namedGraphExists( "urn:test:b" ) );
        assertFalse( mgr.namedGraphExists( "urn:test:gone" ) );
    }

    @Test
    void writeBatchThatChangesNothingCommitsNothing() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        mgr.replaceNamedGraph( "urn:test:a", entityGraph( "urn:test:a", "Technology" ) );
        final long before = mgr.committedWriteTransactions();

        mgr.writeBatch( w -> {
            w.replaceNamedGraph( "urn:test:a", entityGraph( "urn:test:a", "Technology" ) );
            w.removeNamedGraph( "urn:test:never-existed" );
        } );

        assertEquals( 0L, mgr.committedWriteTransactions() - before, "identical content is not rewritten" );
        assertTrue( mgr.namedGraphExists( "urn:test:a" ) );
    }

    @Test
    void aFailingBatchRollsBackEveryWriteAndRethrows() {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final long before = mgr.committedWriteTransactions();

        assertThrows( IllegalStateException.class, () -> mgr.writeBatch( w -> {
            w.replaceNamedGraph( "urn:test:a", entityGraph( "urn:test:a", "Technology" ) );
            throw new IllegalStateException( "projection failed" );
        } ) );

        assertFalse( mgr.namedGraphExists( "urn:test:a" ), "nothing from the failed batch is visible" );
        assertEquals( 0L, mgr.committedWriteTransactions() - before );
    }
}
