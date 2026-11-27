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
package com.wikantik.insights;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedOpportunityActionsTest {

    private static final List< String > TYPES =
            List.of( "striking_distance", "ctr_gap", "content_gap", "cannibalization", "decay" );

    @Test
    void everyDetectorTypeGetsItsOwnNonBlankAction() {
        final Set< String > distinct = new HashSet<>();
        for ( final String type : TYPES ) {
            final String action = ImportedOpportunityActions.suggestedAction( type );
            assertFalse( action.isBlank(), type );
            distinct.add( action );
        }
        assertEquals( TYPES.size(), distinct.size(), "no two detector types may share advice" );
    }

    @Test
    void anUnknownTypeGetsTheGenericReviewAdviceDistinctFromEveryKnownType() {
        final String fallback = ImportedOpportunityActions.suggestedAction( "something_new" );

        assertTrue( fallback.startsWith( "Review" ) );
        for ( final String type : TYPES ) {
            assertFalse( fallback.equals( ImportedOpportunityActions.suggestedAction( type ) ), type );
        }
    }
}
