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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Person references and comment mentions: display names in, logins stored, never a login shown. */
class WorkPeopleTest {

    /** login -> display name; login -> mention id. Logins look nothing like the names on purpose. */
    private static final Map< String, String > NAMES = Map.of( "u-7f3a", "Ana Lima", "u-c01d", "Bo", "nouid", "Cy" );
    private static final Map< String, String > IDS = Map.of( "u-7f3a", "id-ana", "u-c01d", "id-bo" );

    static final WorkDirectory DIRECTORY = new WorkDirectory() {
        @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
        @Override public boolean exists( final String login ) { return NAMES.containsKey( login ); }
        @Override public String displayName( final String login ) { return NAMES.getOrDefault( login, "unknown" ); }
        @Override public Optional< String > loginForDisplayName( final String name ) {
            return NAMES.entrySet().stream().filter( e -> e.getValue().equalsIgnoreCase( name.trim() ) )
                .map( Map.Entry::getKey ).findFirst();
        }
        @Override public Optional< String > loginForMentionId( final String id ) {
            return IDS.entrySet().stream().filter( e -> e.getValue().equals( id ) ).map( Map.Entry::getKey ).findFirst();
        }
        @Override public Optional< String > mentionId( final String login ) {
            return Optional.ofNullable( IDS.get( login ) );
        }
    };

    private final WorkPeople people = new WorkPeople( DIRECTORY );
    private final Actor member = Actor.human( "u-c01d", Set.of(), false );
    private final Actor admin = Actor.human( "root", Set.of(), true );

    @Test
    void aDisplayNameNamesAPersonForAnyone() {
        assertEquals( Optional.of( "u-7f3a" ), people.login( member, "Ana Lima" ) );
        assertEquals( Optional.of( "u-7f3a" ), people.login( member, "  ana lima " ) );
    }

    @Test
    void aLoginNamesAPersonOnlyForAnAdmin() {
        assertEquals( Optional.empty(), people.login( member, "u-7f3a" ), "a member cannot probe a login" );
        assertEquals( Optional.empty(), people.login( member, "no-such-login" ) );
        assertEquals( Optional.of( "u-7f3a" ), people.login( admin, "u-7f3a" ) );
        assertEquals( Optional.empty(), people.login( admin, "no-such-login" ) );
        assertEquals( Optional.empty(), people.login( admin, " " ) );
    }

    @Test
    void tokensResolveByIdAndStayAsTyped() {
        final WorkPeople.Mentions m = people.mentions( member, "ping @[Ana](user:id-ana) and @[Ghost](user:id-x)" );
        assertEquals( List.of( "u-7f3a" ), m.logins() );
        assertEquals( "ping @[Ana](user:id-ana) and @[Ghost](user:id-x)", m.body() );
    }

    @Test
    void aBareOneWordDisplayNameBecomesAToken() {
        final WorkPeople.Mentions m = people.mentions( member, "thanks @Bo." );
        assertEquals( List.of( "u-c01d" ), m.logins() );
        assertEquals( "thanks @[Bo](user:id-bo).", m.body() );
    }

    @Test
    void aMembersBareLoginIsPlainTextButAnAdminsBecomesAToken() {
        final WorkPeople.Mentions byMember = people.mentions( member, "cc @u-7f3a" );
        assertTrue( byMember.logins().isEmpty() );
        assertEquals( "cc @u-7f3a", byMember.body(), "left exactly as typed: nothing confirmed" );

        final WorkPeople.Mentions byAdmin = people.mentions( admin, "cc @u-7f3a, please" );
        assertEquals( List.of( "u-7f3a" ), byAdmin.logins() );
        assertEquals( "cc @[Ana Lima](user:id-ana), please", byAdmin.body(), "the login is not stored" );
        assertFalse( byAdmin.body().contains( "u-7f3a" ) );
    }

    @Test
    void anAccountWithoutAMentionIdIsShownByName() {
        final WorkPeople.Mentions m = people.mentions( admin, "hey @nouid" );
        assertEquals( List.of( "nouid" ), m.logins() );
        assertEquals( "hey @Cy", m.body() );
    }

    @Test
    void anEmailAddressIsNotAMention() {
        assertTrue( people.mentions( admin, "mail a@u-7f3a.com" ).logins().isEmpty() );
    }

    @Test
    void atMostFiftyDistinctMentionsAreResolved() {
        final String many = IntStream.range( 0, 60 ).mapToObj( i -> "@nobody" + i ).collect( Collectors.joining( " " ) );
        final WorkPeople.Mentions m = people.mentions( member, many + " @Bo" );
        assertTrue( m.logins().isEmpty(), "the 61st distinct mention stays plain text" );
        assertEquals( many + " @Bo", m.body() );
        assertEquals( List.of( "u-c01d" ), people.mentions( member, "@Bo @Bo @Bo" ).logins() );
    }

    @Test
    void displayShowsCurrentNamesEscapedAndListsThem() {
        final WorkPeople.Display d = people.display( "hi @[Old Name](user:id-ana) and @[*x*](user:gone) `@[Bo](user:id-bo)`" );
        assertEquals( "hi @Ana Lima and @\\*x\\* `@Bo`", d.markdown() );
        assertEquals( List.of( "Ana Lima", "*x*", "Bo" ), d.names() );
    }

    @Test
    void displayOfNothingIsNothing() {
        assertEquals( "", people.display( "" ).markdown() );
        assertTrue( people.display( null ).names().isEmpty() );
    }
}
