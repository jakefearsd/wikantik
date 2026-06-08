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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
