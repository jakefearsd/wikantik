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
package com.wikantik.collab.model;

import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionTest {

    private static final int N = Contribution.NONE;
    private static final ChangeSet CS = ChangeSet.empty( 1 );

    @Test
    void aValidContributionHasBothBoundsOfAtLeastOneKind() {
        assertDoesNotThrow( () -> new Contribution( 1, 4, N, N ) );
        assertDoesNotThrow( () -> new Contribution( N, N, 2, 2 ) );
        assertDoesNotThrow( () -> new Contribution( 3, 3, 1, 7 ) );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( N, N, N, N ), "both kinds unset" );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( -1, 2, N, N ), "negative version" );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( 1, 2, -1, 3 ), "negative seq" );
    }

    @Test
    void firstAfterLastIsRejected() {
        assertThrows( IllegalArgumentException.class, () -> new Contribution( 5, 4, N, N ) );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( N, N, 9, 2 ) );
    }

    @Test
    void aHalfSetKindIsRejected() {
        assertThrows( IllegalArgumentException.class, () -> new Contribution( 1, N, N, N ) );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( N, 3, 1, 1 ) );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( 1, 1, 2, N ) );
        assertThrows( IllegalArgumentException.class, () -> new Contribution( 1, 1, N, 2 ) );
    }

    @Test
    void aSnapshotKeepsContributorsInInsertionOrderAndUnmodifiable() {
        final Map< String, Contribution > in = new LinkedHashMap<>();
        in.put( "zed", new Contribution( 1, 1, N, N ) );
        in.put( "amy", new Contribution( N, N, 1, 2 ) );
        final Snapshot s = new Snapshot( 2, 2, 1, "b", Map.of(), CS, in );
        in.put( "late", new Contribution( 2, 2, N, N ) );
        assertEquals( List.of( "zed", "amy" ), List.copyOf( s.contributors().keySet() ) );
        assertThrows( UnsupportedOperationException.class,
            () -> s.contributors().put( "x", new Contribution( 1, 1, N, N ) ) );
        assertTrue( new Snapshot( 0, 0, 0, "b", Map.of(), CS ).contributors().isEmpty(),
            "the six-argument constructor has no contributors" );
    }

    @Test
    void aSnapshotRefusesABlankAuthorKeyOrANullContribution() {
        final Map< String, Contribution > blank = new LinkedHashMap<>();
        blank.put( " ", new Contribution( 1, 1, N, N ) );
        assertThrows( IllegalArgumentException.class, () -> new Snapshot( 1, 0, 0, "b", Map.of(), CS, blank ) );
        final Map< String, Contribution > nul = new LinkedHashMap<>();
        nul.put( "amy", null );
        assertThrows( NullPointerException.class, () -> new Snapshot( 1, 0, 0, "b", Map.of(), CS, nul ) );
    }
}
