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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.Iris;
import org.apache.jena.rdf.model.Statement;
import org.junit.jupiter.api.Test;

class EdgeProjectorTest {

    private static final UUID S = UUID.fromString( "00000000-0000-0000-0000-0000000000b1" );
    private static final UUID T = UUID.fromString( "00000000-0000-0000-0000-0000000000b2" );

    private KgEdge edge( final String rel ) {
        return new KgEdge( UUID.randomUUID(), S, T, rel, Provenance.HUMAN_AUTHORED,
                Map.of(), null, null, "human", null );
    }

    @Test
    void mapsRelationshipTypeToWkProperty() {
        final Optional< Statement > st = EdgeProjector.toStatement( edge( "is_a" ) );
        assertTrue( st.isPresent() );
        assertEquals( Iris.entity( S ), st.get().getSubject().getURI() );
        assertEquals( Iris.term( "isA" ),  st.get().getPredicate().getURI() );
        assertEquals( Iris.entity( T ), st.get().getObject().asResource().getURI() );
    }

    @Test
    void multiWordRelationshipBecomesLowerCamel() {
        assertEquals( Iris.term( "alternativeTo" ),
                EdgeProjector.toStatement( edge( "alternative_to" ) ).get().getPredicate().getURI() );
        assertEquals( Iris.term( "locatedIn" ),
                EdgeProjector.toStatement( edge( "located_in" ) ).get().getPredicate().getURI() );
    }

    @Test
    void unknownRelationshipIsSkipped() {
        assertTrue( EdgeProjector.toStatement( edge( "frobnicates" ) ).isEmpty(),
                "predicate outside the closed vocabulary is skipped" );
    }
}
