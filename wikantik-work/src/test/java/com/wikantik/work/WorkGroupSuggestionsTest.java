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
package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkGroupSuggestionsTest {

    private static final Project PLAT = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, Instant.EPOCH, null, null );
    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final Actor MIA = Actor.human( "mia", Set.of(), false );
    private static final Actor ZED = Actor.human( "zed", Set.of(), false );

    private WorkFeatures features;

    @BeforeEach
    void setUp() throws Exception {
        final Set< String > names = new java.util.HashSet<>( Set.of( "Engineering", "Admin", "Aaa-Admin", "Eng-Leads", "Reviewers" ) );
        for ( int i = 10; i < 22; i++ ) {
            names.add( "Team-" + i );
        }
        final WorkDirectory directory = new WorkDirectory() {
            @Override public Set< String > groupNames() { return names; }
            @Override public boolean exists( final String login ) { return true; }
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public String displayName( final String login ) { return login; }
        };
        final WorkScopes scopes = mock( WorkScopes.class );
        when( scopes.scope( any(), eq( "PLAT" ) ) ).thenAnswer( inv -> {
            final Actor who = inv.getArgument( 0 );
            if ( who == ZED ) {
                throw new WorkNotFoundException( "No such project." );
            }
            return new WorkScopes.Scope( who, PLAT, who == LENA ? WorkRole.LEAD : WorkRole.MEMBER, a -> { } );
        } );
        features = new WorkFeatures( mock( WorkStore.class ), scopes, null, Clock.systemUTC(),
            new FeatureDeps( null, new GuardedListener( new WorkListener() { } ), null, null, null, directory, null ) );
    }

    @Test
    void matchesCaseInsensitivelyPrefixFirstThenAlphabeticalCappedAtTen() throws Exception {
        assertEquals( List.of( "Eng-Leads", "Engineering" ), features.groupSuggestions( LENA, "PLAT", "eng" ) );
        final List< String > team = features.groupSuggestions( LENA, "PLAT", "team" );
        assertEquals( 10, team.size() );
        assertEquals( "Team-10", team.get( 0 ) );
        assertEquals( "Team-19", team.get( 9 ) );
        // a substring match that is not a prefix sorts after the prefix matches, whatever the alphabet says
        assertEquals( List.of( "Admin", "Aaa-Admin" ), features.groupSuggestions( LENA, "PLAT", " ADMIN " ) );
    }

    @Test
    void blankQueryReturnsNothing() throws Exception {
        assertEquals( List.of(), features.groupSuggestions( LENA, "PLAT", "" ) );
        assertEquals( List.of(), features.groupSuggestions( LENA, "PLAT", "   " ) );
        assertEquals( List.of(), features.groupSuggestions( LENA, "PLAT", null ) );
    }

    @Test
    void memberWhoIsNotLeadIsDeniedStrangerGets404() {
        assertThrows( WorkDeniedException.class, () -> features.groupSuggestions( MIA, "PLAT", "eng" ) );
        assertThrows( WorkNotFoundException.class, () -> features.groupSuggestions( ZED, "PLAT", "eng" ) );
    }
}
