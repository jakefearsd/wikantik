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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class ConceptProjectorTest {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private PageRecord page( final String cluster, final List< String > tags ) {
        return new PageRecord( "C" + cluster, "Slug" + cluster, "T", "article", cluster, tags,
                null, null, null );
    }

    @Test
    void emitsOneConceptPerDistinctTagAndClusterInScheme() {
        final Map< String, Model > graphs = ConceptProjector.project( List.of(
                page( "graph-databases", List.of( "databases", "nosql" ) ),
                page( "graph-databases", List.of( "databases" ) ) ) );
        // distinct: databases, nosql, graph-databases (cluster)
        assertEquals( 3, graphs.size() );
        final Model dbs = graphs.get( Iris.concept( "databases" ) );
        assertTrue( dbs.contains( ResourceFactory.createResource( Iris.concept( "databases" ) ),
                RDF.type, ResourceFactory.createResource( SKOS + "Concept" ) ) );
        assertTrue( dbs.contains( ResourceFactory.createResource( Iris.concept( "databases" ) ),
                ResourceFactory.createProperty( SKOS + "inScheme" ),
                ResourceFactory.createResource( Iris.term( "WikiConcepts" ) ) ) );
    }

    @Test
    void subClusterGetsSkosBroaderToParent() {
        final Map< String, Model > graphs = ConceptProjector.project( List.of(
                page( "retirement-planning/eu-retirement", List.of() ) ) );
        final Model sub = graphs.get( Iris.concept( "retirement-planning/eu-retirement" ) );
        assertTrue( sub.contains(
                ResourceFactory.createResource( Iris.concept( "retirement-planning/eu-retirement" ) ),
                ResourceFactory.createProperty( SKOS + "broader" ),
                ResourceFactory.createResource( Iris.concept( "retirement-planning" ) ) ) );
    }
}
