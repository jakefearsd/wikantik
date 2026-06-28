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

import java.util.Optional;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.RelationshipTypeVocabulary;
import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Projects a KG edge into a single wk: object-property statement. */
public final class EdgeProjector {

    private static final Logger LOG = LogManager.getLogger( EdgeProjector.class );

    private EdgeProjector() {}

    /** snake_case relationship_type -> wk: lowerCamel property local name. */
    static String propertyLocalName( final String relationshipType ) {
        final String[] parts = relationshipType.split( "_" );
        final StringBuilder sb = new StringBuilder( parts[ 0 ] );
        for ( int i = 1; i < parts.length; i++ ) {
            sb.append( Character.toUpperCase( parts[ i ].charAt( 0 ) ) ).append( parts[ i ].substring( 1 ) );
        }
        return sb.toString();
    }

    /**
     * Returns the triple {@code entity(source) wk:<prop> entity(target)}, or empty if the
     * relationship_type is outside the closed vocabulary (logged at INFO as a guard outcome, never silently dropped).
     */
    public static Optional< Statement > toStatement( final KgEdge edge ) {
        if ( !RelationshipTypeVocabulary.isValid( edge.relationshipType() ) ) {
            LOG.info( "projection: rejected edge {} -> {}: relationship_type '{}' not in closed vocabulary [guard]",
                    edge.sourceId(), edge.targetId(), edge.relationshipType() );
            return Optional.empty();
        }
        return Optional.of( ResourceFactory.createStatement(
                ResourceFactory.createResource( Iris.entity( edge.sourceId() ) ),
                ResourceFactory.createProperty( Iris.term( propertyLocalName( edge.relationshipType() ) ) ),
                ResourceFactory.createResource( Iris.entity( edge.targetId() ) ) ) );
    }
}
