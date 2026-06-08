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

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/** Projects distinct tags + clusters across all pages into per-concept SKOS graphs. */
public final class ConceptProjector {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private ConceptProjector() {}

    /** Returns a map of concept-IRI -> its named-graph Model (one entry per distinct tag/cluster). */
    public static Map< String, Model > project( final List< PageRecord > pages ) {
        // Collect distinct raw values (preserving the original label for skos:prefLabel).
        final Set< String > values = new LinkedHashSet<>();
        for ( final PageRecord p : pages ) {
            values.addAll( p.tags() );
            if ( p.cluster() != null && !p.cluster().isBlank() ) {
                values.add( p.cluster() );
            }
        }
        final Map< String, Model > out = new HashMap<>();
        for ( final String value : values ) {
            final String iri = Iris.concept( value );
            final Model m = ModelFactory.createDefaultModel();
            final Resource concept = m.createResource( iri );
            m.add( concept, RDF.type, m.createResource( SKOS + "Concept" ) );
            m.add( concept, m.createProperty( SKOS + "inScheme" ), m.createResource( Iris.term( "WikiConcepts" ) ) );
            m.add( concept, m.createProperty( SKOS + "prefLabel" ), m.createLiteral( value ) );
            m.add( concept, RDFS.label, m.createLiteral( value ) );
            // Sub-cluster "parent/sub" -> skos:broader parent.
            final int slash = value.lastIndexOf( '/' );
            if ( slash > 0 ) {
                final String parent = value.substring( 0, slash );
                m.add( concept, m.createProperty( SKOS + "broader" ), m.createResource( Iris.concept( parent ) ) );
            }
            out.put( iri, m );
        }
        return out;
    }
}
