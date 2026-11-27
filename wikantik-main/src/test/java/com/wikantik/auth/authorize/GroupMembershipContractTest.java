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
package com.wikantik.auth.authorize;

import com.wikantik.auth.WikiPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membership and equality of {@link Group}: who is a member after add/remove, and when two groups are the same group.
 */
class GroupMembershipContractTest {

    private static final WikiPrincipal ALICE = new WikiPrincipal( "alice" );
    private static final WikiPrincipal BOB = new WikiPrincipal( "bob" );
    private static final WikiPrincipal CAROL = new WikiPrincipal( "carol" );

    private static Group group( final String name, final WikiPrincipal... members ) {
        final Group g = new Group( name, "wiki" );
        for( final WikiPrincipal m : members ) {
            g.add( m );
        }
        return g;
    }

    @Test
    void addingAMemberReportsTrueOnceAndFalseWhenAlreadyAMember() {
        final Group g = group( "Team" );

        assertTrue( g.add( ALICE ) );
        assertFalse( g.add( ALICE ), "a second add of the same principal changes nothing" );
        assertEquals( 1, g.members().length );
    }

    @Test
    void removingAMemberReportsTrueAndTheMemberIsGoneButAnUnknownOneReportsFalse() {
        final Group g = group( "Team", ALICE, BOB );

        assertTrue( g.remove( ALICE ) );
        assertFalse( g.isMember( ALICE ) );
        assertTrue( g.isMember( BOB ) );
        assertFalse( g.remove( CAROL ), "removing a non-member is a no-op that says so" );
        assertFalse( g.remove( ALICE ), "already removed" );
        assertEquals( Set.of( "bob" ),
                java.util.Arrays.stream( g.members() ).map( p -> p.getName() ).collect( Collectors.toSet() ) );
    }

    @Test
    void groupsWithTheSameNameAndMembersAreEqualRegardlessOfInsertionOrder() {
        final Group a = group( "Team", ALICE, BOB );
        final Group b = group( "Team", BOB, ALICE );

        assertEquals( a, b );
        assertEquals( a.hashCode(), b.hashCode() );
    }

    @Test
    void groupsDifferingInNameMembersOrMemberCountAreNotEqual() {
        final Group base = group( "Team", ALICE, BOB );

        assertNotEquals( base, group( "Other", ALICE, BOB ), "different name" );
        assertNotEquals( base, group( "Team", ALICE, CAROL ), "same size, different member" );
        assertNotEquals( base, group( "Team", ALICE ), "fewer members" );
        assertNotEquals( base, group( "Team", ALICE, BOB, CAROL ), "more members" );
        assertNotEquals( base, null );
        assertNotEquals( base, "Team" );
    }

    @Test
    void theHashCodeDependsOnTheMembership() {
        assertNotEquals( group( "Team", ALICE ).hashCode(), group( "Team", BOB ).hashCode() );
        assertNotEquals( group( "Team", ALICE ).hashCode(), group( "Team" ).hashCode() );
        assertEquals( ALICE.hashCode() ^ BOB.hashCode(), group( "Team", ALICE, BOB ).hashCode() );
    }

    @Test
    void theWikiAndNameAreReportedAndShownInToString() {
        final Group g = group( "Team" );

        assertEquals( "wiki", g.getWiki() );
        assertEquals( "Team", g.getName() );
        assertEquals( "(Group Team)", g.toString() );
    }
}
