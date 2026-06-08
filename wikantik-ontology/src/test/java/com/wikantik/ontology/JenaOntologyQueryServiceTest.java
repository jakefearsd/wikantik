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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.wikantik.api.ontology.OntologyQueryService;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;

class JenaOntologyQueryServiceTest {

    /** Real T-Box + a focused fixture (subclass chain / SKOS narrower chain / SKOS cycle). */
    private OntologyModelManager mgrWith( final String extraTurtle ) {
        final OntologyModelManager mgr = OntologyModelManager.inMemory();
        mgr.loadTBox();
        final Model m = ModelFactory.createDefaultModel();
        final String ttl = """
            @prefix wk:   <https://wiki.wikantik.com/ns/wikantik#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            """ + extraTurtle;
        RDFDataMgr.read( m, new java.io.ByteArrayInputStream(
                ttl.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ), Lang.TURTLE );
        mgr.replaceNamedGraph( "urn:test:fixture", m );
        return mgr;
    }

    @Test
    void expandsTransitiveSubclassesOfAMatchedClass() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith( """
            wk:Algorithm        a owl:Class ; rdfs:label "Algorithm" .
            wk:SortingAlgorithm a owl:Class ; rdfs:subClassOf wk:Algorithm ; rdfs:label "Sorting Algorithm" .
            wk:QuickSort        a owl:Class ; rdfs:subClassOf wk:SortingAlgorithm ; rdfs:label "QuickSort" .
            """ ) );
        final List< String > exp = svc.expandQuery( "tell me about algorithm internals" );
        assertTrue( exp.contains( "Sorting Algorithm" ), "direct subclass label expanded" );
        assertTrue( exp.contains( "QuickSort" ), "TRANSITIVE subclass label expanded (RDFS entailment)" );
        assertFalse( exp.contains( "Algorithm" ), "the matched term itself is not re-added" );
    }

    @Test
    void expandsSkosNarrowerAndTerminatesOnCycles() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith( """
            <urn:c:databases> a skos:Concept ; skos:prefLabel "databases" ; skos:narrower <urn:c:graphdb> .
            <urn:c:graphdb>   a skos:Concept ; skos:prefLabel "graph databases" ; skos:narrower <urn:c:pg> .
            <urn:c:pg>        a skos:Concept ; skos:prefLabel "property graph" .
            <urn:c:loopA>     a skos:Concept ; skos:prefLabel "loop a" ; skos:narrower <urn:c:loopB> .
            <urn:c:loopB>     a skos:Concept ; skos:prefLabel "loop b" ; skos:narrower <urn:c:loopA> .
            """ ) );
        final List< String > exp = svc.expandQuery( "databases" );
        assertTrue( exp.contains( "graph databases" ) && exp.contains( "property graph" ),
                "transitive SKOS narrower expanded" );
        final List< String > loop = svc.expandQuery( "loop a" );
        assertTrue( loop.contains( "loop b" ), "cycle still expands one hop" );
        assertEquals( loop.stream().distinct().count(), loop.size(), "no duplicates from the cycle" );
    }

    @Test
    void noMatchYieldsEmptyExpansion() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith(
                "wk:Algorithm a owl:Class ; rdfs:label \"Algorithm\" ." ) );
        assertTrue( svc.expandQuery( "completely unrelated zzzqqq" ).isEmpty() );
    }

    @Test
    void matchingIsCaseInsensitiveAndDeduplicates() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith( """
            wk:Algorithm        a owl:Class ; rdfs:label "Algorithm" .
            wk:SortingAlgorithm a owl:Class ; rdfs:subClassOf wk:Algorithm ; rdfs:label "Sorting Algorithm" .
            """ ) );
        final List< String > exp = svc.expandQuery( "ALGORITHM algorithm Algorithm" );
        assertEquals( List.of( "Sorting Algorithm" ), exp );
    }

    @Test
    void nullOrBlankQueryIsSafe() {
        final OntologyQueryService svc = new JenaOntologyQueryService( mgrWith(
                "wk:Algorithm a owl:Class ; rdfs:label \"Algorithm\" ." ) );
        assertTrue( svc.expandQuery( null ).isEmpty() );
        assertTrue( svc.expandQuery( "   " ).isEmpty() );
    }
}
