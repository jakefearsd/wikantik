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
package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipTypeVocabularyTest {

    @Test
    void isValid_acceptsCanonicalEntries() {
        assertTrue( RelationshipTypeVocabulary.isValid( "is_a" ) );
        assertTrue( RelationshipTypeVocabulary.isValid( "located_in" ) );
        assertTrue( RelationshipTypeVocabulary.isValid( "generalizes" ) );
    }

    @Test
    void isValid_rejectsUnknownNullBlank() {
        assertFalse( RelationshipTypeVocabulary.isValid( "fixes" ) );
        assertFalse( RelationshipTypeVocabulary.isValid( "plans" ) );
        assertFalse( RelationshipTypeVocabulary.isValid( "" ) );
        assertFalse( RelationshipTypeVocabulary.isValid( null ) );
    }

    @Test
    void closestMatches_returnsRankedSuggestionsForTypos() {
        // "instanceof" → instance_of is the nearest by Levenshtein distance.
        final List< String > suggestions = RelationshipTypeVocabulary.closestMatches( "instanceof", 3 );
        assertEquals( 3, suggestions.size() );
        assertEquals( "instance_of", suggestions.get( 0 ) );
    }

    @Test
    void closestMatches_handlesEmptyAndNullCandidate() {
        assertTrue( RelationshipTypeVocabulary.closestMatches( null, 3 ).isEmpty() );
        assertTrue( RelationshipTypeVocabulary.closestMatches( "", 3 ).isEmpty() );
        assertTrue( RelationshipTypeVocabulary.closestMatches( "   ", 3 ).isEmpty() );
    }

    @Test
    void closestMatches_respectsLimit() {
        assertEquals( 1, RelationshipTypeVocabulary.closestMatches( "fixes", 1 ).size() );
        assertEquals( 5, RelationshipTypeVocabulary.closestMatches( "fixes", 5 ).size() );
    }
}
