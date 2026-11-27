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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limits of person references: how many mentions are looked up, what a token is keyed by, how a snapshot name
 * is cut. Every person here is "pN" with login "LN" and mention id "idN", so a count of lookups is a count of people.
 */
class WorkPeopleLimitsTest {

    private final List< String > nameLookups = new ArrayList<>();
    private final List< String > idLookups = new ArrayList<>();
    private String longName;
    private Set< String > groupMembers;

    private final WorkDirectory directory = new WorkDirectory() {
        @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
        @Override public boolean exists( final String login ) { return login.startsWith( "L" ); }
        @Override public String displayName( final String login ) {
            return "L0".equals( login ) && longName != null ? longName : "Person " + login.substring( 1 );
        }
        @Override public Optional< String > loginForDisplayName( final String name ) {
            nameLookups.add( name );
            return name.matches( "p\\d+" ) ? Optional.of( "L" + name.substring( 1 ) ) : Optional.empty();
        }
        @Override public Optional< String > loginForMentionId( final String id ) {
            idLookups.add( id );
            return id.matches( "id\\d+" ) ? Optional.of( "L" + id.substring( 2 ) ) : Optional.empty();
        }
        @Override public Optional< String > mentionId( final String login ) {
            return login.startsWith( "L" ) ? Optional.of( "id" + login.substring( 1 ) ) : Optional.empty();
        }
        @Override public Set< String > groupMembers( final String group ) { return groupMembers; }
    };

    private final WorkPeople people = new WorkPeople( directory );
    private final Actor member = Actor.human( "someone", Set.of(), false );

    private static String bare( final int from, final int to ) {
        return IntStream.range( from, to ).mapToObj( i -> "@p" + i ).collect( Collectors.joining( " " ) );
    }

    private static String tokens( final int from, final int to ) {
        return IntStream.range( from, to ).mapToObj( i -> "@[N" + i + "](user:id" + i + ")" )
            .collect( Collectors.joining( " " ) );
    }

    @Test
    void aGroupWithNoDirectoryEntryHasNoMembers() {
        groupMembers = null;
        assertEquals( Set.of(), people.groupMembers( "ghosts" ) );
        groupMembers = Set.of( "a", "b" );
        assertEquals( Set.of( "a", "b" ), people.groupMembers( "devs" ) );
    }

    @Test
    void anAssigneeThatIsNotATextIsLeftAloneAndATextOneIsResolved() {
        final java.util.Map< String, Object > nullAssignee = new java.util.HashMap<>();
        nullAssignee.put( "assignee", null );
        assertEquals( nullAssignee, people.withAssigneeLogin( member, nullAssignee ) );
        assertEquals( java.util.Map.of( "assignee", 5 ), people.withAssigneeLogin( member, java.util.Map.of( "assignee", 5 ) ) );
        assertEquals( java.util.Map.of( "assignee", " " ), people.withAssigneeLogin( member, java.util.Map.of( "assignee", " " ) ),
            "a blank reference means unassigned, so it is not looked up" );
        assertTrue( nameLookups.isEmpty() );
        assertEquals( java.util.Map.of( "assignee", "L3", "title", "t" ),
            people.withAssigneeLogin( member, new java.util.LinkedHashMap<>( java.util.Map.of( "assignee", "p3", "title", "t" ) ) ) );
        assertEquals( "assignee", assertThrowsValidation( () -> people.withAssigneeLogin( member,
            java.util.Map.of( "assignee", "nobody" ) ) ) );
    }

    private static String assertThrowsValidation( final Runnable call ) {
        return org.junit.jupiter.api.Assertions.assertThrows( WorkValidationException.class, call::run ).field();
    }

    @Test
    void exactlyFiftyDistinctBareMentionsResolveAndTheFiftyFirstDoesNot() {
        final WorkPeople.Mentions m = people.mentions( member, bare( 0, 51 ) );
        assertEquals( 50, m.logins().size() );
        assertEquals( "L0", m.logins().get( 0 ) );
        assertEquals( "L49", m.logins().get( 49 ) );
        assertTrue( m.body().endsWith( " @p50" ), "the 51st stays as typed" );
        assertEquals( 50, nameLookups.size(), "and is never looked up" );
    }

    @Test
    void aMentionAlreadySeenStillResolvesAfterTheCap() {
        final WorkPeople.Mentions m = people.mentions( member, bare( 0, 50 ) + " @p0 @p51" );
        assertEquals( 50, m.logins().size() );
        assertEquals( 50, nameLookups.size(), "the repeat is answered from the first lookup" );
        assertEquals( 2, m.body().split( java.util.regex.Pattern.quote( "@[Person 0]" ), -1 ).length - 1,
            "the repeat of p0 became a token too" );
        assertTrue( m.body().endsWith( " @p51" ) );
    }

    @Test
    void aTokenAndABareNameWithTheSameTextAreDifferentPeople() {
        final WorkDirectory twin = new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return "abc".equals( name ) ? Optional.of( "byName" ) : Optional.empty();
            }
            @Override public Optional< String > loginForMentionId( final String id ) {
                return "abc".equals( id ) ? Optional.of( "byId" ) : Optional.empty();
            }
        };
        final WorkPeople.Mentions m = new WorkPeople( twin ).mentions( member, "@[x](user:abc) and @abc" );
        assertEquals( List.of( "byId", "byName" ), m.logins() );
        assertEquals( "@[x](user:abc) and @byName", m.body() );
    }

    @Test
    void aTokenIdKeepsItsTrailingPunctuationButABareNameLosesIt() {
        final WorkDirectory dashes = new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForMentionId( final String id ) {
                return "abc-".equals( id ) ? Optional.of( "dashed" ) : Optional.empty();
            }
        };
        assertEquals( List.of( "dashed" ), new WorkPeople( dashes ).mentions( member, "@[x](user:abc-)" ).logins() );
        assertEquals( List.of(), people.mentions( member, "@zzz-." ).logins() );
        assertEquals( List.of( "zzz" ), nameLookups, "the bare name is looked up without its trailing punctuation" );
    }

    @Test
    void aSnapshotNameIsCutToSixtyFourCharactersWithoutBracketsOrControls() {
        longName = "a".repeat( 64 );
        assertEquals( "@[" + "a".repeat( 64 ) + "](user:id0)", tokenFor( "@p0" ) );
        longName = "b".repeat( 65 );
        assertEquals( "@[" + "b".repeat( 64 ) + "](user:id0)", tokenFor( "@p0" ) );
        longName = "[Ann]\u0007 [Lee]";
        assertEquals( "@[Ann Lee](user:id0)", tokenFor( "@p0" ) );
        longName = " [] ";
        assertEquals( "@[user](user:id0)", tokenFor( "@p0" ) );
    }

    private String tokenFor( final String mention ) {
        return people.mentions( member, mention ).body();
    }

    @Test
    void anAccountWithoutAMentionIdIsWrittenAsItsSnapshotName() {
        final WorkDirectory noIds = new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return " "; }
            @Override public Optional< String > loginForDisplayName( final String name ) { return Optional.of( "x" ); }
        };
        assertEquals( "hi @user", new WorkPeople( noIds ).mentions( member, "hi @q" ).body() );
    }

    @Test
    void displayLooksUpAtMostFiftyDistinctIdsAndLeavesTheRestAsTyped() {
        final WorkPeople.Display d = people.display( tokens( 0, 51 ) + " " + tokens( 0, 1 ) );
        assertEquals( 50, idLookups.size(), "the 51st id is never looked up" );
        assertEquals( 50, d.names().size() );
        assertTrue( d.markdown().contains( "@[N50](user:id50)" ), "past the cap the token stays as typed" );
        assertTrue( d.markdown().endsWith( "@Person 0" ), "an id already seen resolves after the cap too" );
    }

    @Test
    void displayForAReporterNeverLooksPastTheCapAndNeverShowsAnId() {
        final WorkPeople.Display d = people.displayForReporter( tokens( 0, 52 ) + " " + tokens( 1, 2 ) );
        assertEquals( 50, idLookups.size() );
        assertTrue( d.markdown().contains( "@N50 @N51 @Person 1" ), d.markdown() );
        assertTrue( !d.markdown().contains( "user:" ), "no id survives for a reporter" );
        assertTrue( !d.markdown().contains( "id50" ) );
    }

    @Test
    void displayForAReporterShowsAMalformedTokenByItsSnapshotWithoutALookup() {
        final WorkPeople.Display d = people.displayForReporter( "x @[*Eve*](user:bad id!) @[Ok](user:) y" );
        assertEquals( "x @\\*Eve\\* @Ok y", d.markdown() );
        assertTrue( idLookups.isEmpty(), "a malformed id is never looked up" );
        assertEquals( "x @[*Eve*](user:bad id!) y".replace( "@[*Eve*](user:bad id!)", "@\\*Eve\\*" ),
            people.displayForReporter( "x @[*Eve*](user:bad id!) y" ).markdown() );
    }

    @Test
    void displayOfOrdinaryTextIsItself() {
        assertEquals( "no mentions here", people.display( "no mentions here" ).markdown() );
        assertEquals( "", people.display( "" ).markdown() );
        assertEquals( "", people.displayForReporter( null ).markdown() );
    }
}
