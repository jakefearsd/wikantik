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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;

/** Validates a materialized ontology graph against the bundled SHACL shapes ({@code shapes.ttl}). */
public final class OntologyShaclValidator {

    /** One SHACL violation, flattened to plain strings for JSON/agent consumption. */
    public record Violation( String focusNode, String path, String message ) {}

    private final Shapes shapes;

    public OntologyShaclValidator() {
        final Model shapesModel = ModelFactory.createDefaultModel();
        try ( InputStream in = OntologyShaclValidator.class.getResourceAsStream( "/ontology/shapes.ttl" ) ) {
            if ( in == null ) {
                throw new IllegalStateException( "/ontology/shapes.ttl not found on classpath" );
            }
            RDFDataMgr.read( shapesModel, in, Lang.TURTLE );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( "failed loading shapes.ttl", e );
        }
        this.shapes = Shapes.parse( shapesModel.getGraph() );
    }

    /**
     * Validates a single candidate edge against the SHACL shapes by building a typed mini-graph
     * ({@code src a wk:<srcClass> ; tgt a wk:<tgtClass> ; src wk:<pred> tgt}). Empty when conformant;
     * predicates with no shape always conform (no false positives).
     */
    public List< Violation > validateEdge( final String sourceNodeType, final String relationshipType,
                                           final String targetNodeType ) {
        final Model m = ModelFactory.createDefaultModel();
        final org.apache.jena.rdf.model.Resource src = m.createResource( "urn:edge:src" );
        final org.apache.jena.rdf.model.Resource tgt = m.createResource( "urn:edge:tgt" );
        src.addProperty( org.apache.jena.vocabulary.RDF.type,
                m.createResource( Iris.term( NodeTypeMapping.classLocalName( sourceNodeType ) ) ) );
        tgt.addProperty( org.apache.jena.vocabulary.RDF.type,
                m.createResource( Iris.term( NodeTypeMapping.classLocalName( targetNodeType ) ) ) );
        src.addProperty( m.createProperty( Iris.term( propertyLocalName( relationshipType ) ) ), tgt );
        return validate( m );
    }

    /** snake_case relationship_type -> wk: lowerCamel property local name. */
    private static String propertyLocalName( final String relationshipType ) {
        final String[] parts = relationshipType.split( "_" );
        final StringBuilder sb = new StringBuilder( parts[ 0 ] );
        for ( int i = 1; i < parts.length; i++ ) {
            sb.append( Character.toUpperCase( parts[ i ].charAt( 0 ) ) ).append( parts[ i ].substring( 1 ) );
        }
        return sb.toString();
    }

    /** Returns the SHACL violations in {@code data}; empty when it conforms. */
    public List< Violation > validate( final Model data ) {
        final ValidationReport report = ShaclValidator.get().validate( shapes, data.getGraph() );
        if ( report.conforms() ) {
            return List.of();
        }
        final List< Violation > out = new ArrayList<>();
        for ( final ReportEntry e : report.getEntries() ) {
            out.add( new Violation(
                    e.focusNode() == null ? null : e.focusNode().toString(),
                    e.resultPath() == null ? null : e.resultPath().toString(),
                    e.message() ) );
        }
        return out;
    }
}
