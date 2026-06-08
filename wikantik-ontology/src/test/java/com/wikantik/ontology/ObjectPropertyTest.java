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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.wikantik.api.knowledge.RelationshipTypeVocabulary;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

class ObjectPropertyTest {

    private final Model tbox = TBoxVocabulary.loadTBox();

    /** snake_case vocab term -> lowerCamelCase local name (related_to -> relatedTo). */
    static String toLocalName( final String snake ) {
        final String[] parts = snake.split( "_" );
        final StringBuilder sb = new StringBuilder( parts[ 0 ] );
        for ( int i = 1; i < parts.length; i++ ) {
            sb.append( Character.toUpperCase( parts[ i ].charAt( 0 ) ) ).append( parts[ i ].substring( 1 ) );
        }
        return sb.toString();
    }

    private Set< String > declaredObjectPropertyLocalNames() {
        return tbox.listSubjectsWithProperty( RDF.type, OWL.ObjectProperty )
                .toList().stream()
                .filter( r -> r.getURI() != null && r.getURI().startsWith( TBoxVocabulary.WK ) )
                .map( r -> r.getURI().substring( TBoxVocabulary.WK.length() ) )
                .collect( Collectors.toCollection( TreeSet::new ) );
    }

    @Test
    void objectPropertiesExactlyMirrorTheClosedVocabulary() {
        final Set< String > expected = RelationshipTypeVocabulary.CLOSED_VOCAB.stream()
                .map( ObjectPropertyTest::toLocalName )
                .collect( Collectors.toCollection( TreeSet::new ) );
        assertEquals( expected, declaredObjectPropertyLocalNames(),
                "wk: object properties must exactly mirror RelationshipTypeVocabulary.CLOSED_VOCAB" );
    }

    @Test
    void everyObjectPropertyHasDomainAndRange() {
        for ( final String local : declaredObjectPropertyLocalNames() ) {
            final Resource p = ResourceFactory.createResource( TBoxVocabulary.WK + local );
            assertTrue( tbox.contains( p, RDFS.domain, (org.apache.jena.rdf.model.RDFNode) null ),
                    "wk:" + local + " must declare rdfs:domain" );
            assertTrue( tbox.contains( p, RDFS.range, (org.apache.jena.rdf.model.RDFNode) null ),
                    "wk:" + local + " must declare rdfs:range" );
        }
    }

    @Test
    void publicMappingsArePresent() {
        assertTrue( hasSubProperty( "replaces",  "http://purl.org/dc/terms/replaces" ),
                "wk:replaces must be rdfs:subPropertyOf dct:replaces" );
        assertTrue( hasSubProperty( "partOf",    "http://purl.org/dc/terms/isPartOf" ),
                "wk:partOf must be rdfs:subPropertyOf dct:isPartOf" );
        assertTrue( hasSubProperty( "relatedTo", "http://www.w3.org/2004/02/skos/core#related" ),
                "wk:relatedTo must be rdfs:subPropertyOf skos:related" );
        assertTrue( hasSubProperty( "locatedIn", "https://schema.org/containedInPlace" ),
                "wk:locatedIn must be rdfs:subPropertyOf schema:containedInPlace" );
    }

    private boolean hasSubProperty( final String childLocal, final String parentIri ) {
        return tbox.contains(
                ResourceFactory.createResource( TBoxVocabulary.WK + childLocal ),
                RDFS.subPropertyOf,
                ResourceFactory.createResource( parentIri ) );
    }
}
