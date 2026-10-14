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
package com.wikantik.auth;

import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileNameRulesTest {

    private TestEngine engine;
    private UserDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
        db = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile boss = db.newProfile();
        boss.setLoginName( "boss" );
        boss.setFullname( "The Boss" );
        boss.setEmail( "boss@example.test" );
        boss.setPassword( "correct-horse-battery-staple-3" );
        db.save( boss );
    }

    @AfterEach
    void tearDown() throws Exception {
        db.deleteByLoginName( "boss" );
        engine.stop();
    }

    private Optional< String > change( final String oldFull, final String oldWiki, final String newFull, final String newWiki ) {
        return ProfileNameRules.nameChangeError( engine, db, "mallory", oldFull, oldWiki, newFull, newWiki );
    }

    @Test
    void newNameEqualToAnotherUsersLoginNameIsRefused() {
        assertTrue( change( "Mallory", "Mallory", "boss", "boss" ).orElseThrow().contains( "login name" ) );
        assertTrue( change( "Mallory", "Mallory", "Mallory", "boss" ).isPresent(), "wiki name equal to a login" );
    }

    @Test
    void newNameSharedWithAnotherUsersFullNameIsRefusedButASharedWikiNameIsAllowed() {
        // Display names are unique (case-insensitive); only the *derived wiki name* may still be shared, and the
        // write paths number it (availableWikiName).
        final ProfileNameRules.NameViolation clash = ProfileNameRules
                .nameChangeViolation( engine, db, "mallory", "Mallory", "Mallory", "The Boss", "TheBoss" ).orElseThrow();
        assertTrue( clash.isConflict(), "a duplicate display name is a conflict, not malformed input" );
        assertTrue( change( "Mallory", "Mallory", "Mallory", "TheBoss" ).isEmpty(), "shared wiki name alone is fine" );
    }

    @Test
    void availableWikiNameSuffixesOnlyWhenAnotherAccountHoldsIt() {
        assertEquals( "Mallory", ProfileNameRules.availableWikiName( engine, db, "mallory", "Mallory", null ) );
        assertEquals( "TheBoss", ProfileNameRules.availableWikiName( engine, db, "boss", "TheBoss", "TheBoss" ),
                "an account's own wiki name is available to it" );
        assertEquals( "TheBoss2", ProfileNameRules.availableWikiName( engine, db, "mallory", "TheBoss", null ) );
        assertEquals( "TheBoss7", ProfileNameRules.availableWikiName( engine, db, "mallory", "TheBoss", "TheBoss7" ),
                "an existing suffixed wiki name is kept, so a re-sent name does not churn" );
        assertEquals( "boss2", ProfileNameRules.availableWikiName( engine, db, "mallory", "boss", null ),
                "another account's login name is not available as a wiki name" );
        assertEquals( "Admin2", ProfileNameRules.availableWikiName( engine, db, "mallory", "Admin", null ),
                "a reserved name is not available as a wiki name" );
        assertNull( ProfileNameRules.availableWikiName( engine, db, "mallory", null, "Old" ) );
    }

    @Test
    void secondAccountWithTheSameFullNameCanBeSaved() throws Exception {
        final UserProfile twin = db.newProfile();
        twin.setLoginName( "boss-twin" );
        twin.setFullname( "The Boss" );
        twin.setWikiName( ProfileNameRules.availableWikiName( engine, db, "boss-twin", twin.getWikiName(), null ) );
        twin.setEmail( "twin@example.test" );
        twin.setPassword( "correct-horse-battery-staple-4" );
        try {
            db.save( twin );
            assertEquals( "The Boss", db.findByLoginName( "boss-twin" ).getFullname() );
            assertEquals( "boss-twin", db.findByWikiName( "TheBoss2" ).getLoginName() );
        } finally {
            db.deleteByLoginName( "boss-twin" );
        }
    }

    @Test
    void newReservedNameIsRefused() {
        assertTrue( change( "Mallory", "Mallory", "Admin", "Admin" ).orElseThrow().contains( "reserved" ) );
    }

    @Test
    void unchangedNamesAreNeverRefused() {
        // A name that became reserved or taken after it was chosen must not lock its owner out.
        assertTrue( change( "Admin", "Admin", "Admin", "Admin" ).isEmpty() );
        assertTrue( change( "boss", "boss", " boss ", "boss" ).isEmpty() );
    }

    @Test
    void ownLoginNameAndOrdinaryNamesAreAllowed() {
        assertTrue( change( "Old Name", "OldName", "mallory", "mallory" ).isEmpty() );
        assertTrue( change( "Old Name", "OldName", "Mallory Jones", "MalloryJones" ).isEmpty() );
        assertTrue( change( "Old Name", "OldName", null, null ).isEmpty() );
    }

    @Test
    void newGroupNamedAfterAUserIsRefused() throws Exception {
        final GroupManager groups = engine.getManager( GroupManager.class );
        for ( final String name : new String[] { "boss", "The Boss", "TheBoss" } ) {
            assertThrows( WikiSecurityException.class, () -> groups.parseGroup( name, "", true ), name );
        }
    }

    @Test
    void existingGroupCanStillBeEditedAndOrdinaryGroupCreated() throws Exception {
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group g = groups.parseGroup( "Reviewers", "boss", true );
        groups.setGroup( WikiSessionTest.adminSession( engine ), g );
        try {
            final Group again = groups.parseGroup( "Reviewers", "boss\nmallory", false );
            assertTrue( again.isMember( new WikiPrincipal( "mallory" ) ) );
        } finally {
            groups.removeGroup( "Reviewers" );
        }
    }

    // ---- display-name rules ----

    private Optional< String > displayError( final String login, final String candidate ) {
        return ProfileNameRules.displayNameError( engine, db, login, candidate );
    }

    @Test
    void displayNameIsRequired() {
        assertTrue( displayError( "mallory", null ).orElseThrow().contains( "required" ) );
        assertTrue( displayError( "mallory", "   " ).orElseThrow().contains( "required" ) );
    }

    @Test
    void displayNameLengthIsBoundedAfterTrimming() {
        assertTrue( displayError( "mallory", "A" ).orElseThrow().contains( "2 to 64" ) );
        assertTrue( displayError( "mallory", " A " ).isPresent(), "the trimmed length counts" );
        assertTrue( displayError( "mallory", "x".repeat( ProfileNameRules.DISPLAY_NAME_MAX + 1 ) ).orElseThrow().contains( "2 to 64" ) );
        assertTrue( displayError( "mallory", "Al" ).isEmpty(), "two characters is the minimum" );
        assertTrue( displayError( "mallory", "y".repeat( ProfileNameRules.DISPLAY_NAME_MAX ) ).isEmpty(), "64 is the maximum" );
        assertEquals( 2, ProfileNameRules.DISPLAY_NAME_MIN );
        assertEquals( 64, ProfileNameRules.DISPLAY_NAME_MAX );
    }

    @Test
    void displayNameCannotContainControlCharacters() {
        for ( final String name : new String[] { "Bad\tName", "Bad\nName", "Bad\u0007Name", "Bad\u007fName" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "control" ), name );
        }
    }

    @Test
    void displayNameCannotContainAnAtSign() {
        for ( final String name : new String[] { "jane@example.com", "@jane", "jane@" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "'@'" ), name );
        }
    }

    @Test
    void reservedDisplayNamesAreRefused() {
        for ( final String name : new String[] { "Admin", "A dmin", "authenticated" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "reserved" ), name );
        }
        assertFalse( ProfileNameRules.displayNameViolation( engine, db, "mallory", "Admin" ).orElseThrow().isConflict(),
                "reserved is malformed input, not a collision" );
    }

    @Test
    void anotherAccountsLoginIsRefusedCaseInsensitively() {
        for ( final String name : new String[] { "boss", "BOSS", " Boss ", "bOb" } ) {   // "Bob" is a seeded login
            final ProfileNameRules.NameViolation v = ProfileNameRules.displayNameViolation( engine, db, "mallory", name ).orElseThrow();
            assertTrue( v.message().contains( "login name" ), name + ": " + v.message() );
            assertTrue( v.isConflict(), name );
        }
        assertTrue( displayError( "boss", "BOSS" ).isEmpty(), "an account's own login is not 'another account's'" );
    }

    @Test
    void duplicateDisplayNameIsRefusedIgnoringCaseAndSurroundingWhitespace() {
        for ( final String name : new String[] { "The Boss", "the boss", "  THE BOSS  " } ) {
            final ProfileNameRules.NameViolation v = ProfileNameRules.displayNameViolation( engine, db, "mallory", name ).orElseThrow();
            assertTrue( v.message().contains( "already in use" ), name + ": " + v.message() );
            assertTrue( v.isConflict(), name );
        }
        assertTrue( displayError( "boss", "THE BOSS" ).isEmpty(), "the owner may re-case their own name" );
        assertTrue( displayError( "mallory", "Mallory Jones" ).isEmpty() );
    }

    @Test
    void fullNameChangeAppliesTheRulesOnlyWhenTheNameChanges() throws Exception {
        final UserProfile bossProfile = db.findByLoginName( "boss" );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "The Boss" ).isEmpty(), "unchanged" );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "x" ).orElseThrow().contains( "2 to 64" ) );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "" ).orElseThrow().contains( "required" ) );
        assertTrue( ProfileNameRules.fullNameChangeError( engine, db, bossProfile, "Bob" ).orElseThrow().contains( "login name" ) );

        // A pre-existing duplicate (production had one) must not lock its owner out of unrelated saves.
        saveAccount( "boss-twin", "The Boss" );
        try {
            final UserProfile stored = db.findByLoginName( "boss-twin" );
            assertTrue( ProfileNameRules.fullNameChangeError( engine, db, stored, "The Boss" ).isEmpty(),
                    "an unchanged duplicate is not re-validated" );
            assertTrue( ProfileNameRules.fullNameChangeError( engine, db, stored, "the boss" ).isPresent(),
                    "a changed spelling of the duplicate is validated and refused" );
        } finally {
            db.deleteByLoginName( "boss-twin" );
        }
    }

    @Test
    void nullNewFullNameMeansNoChangeRequested() {
        assertTrue( change( "Old Name", "OldName", null, null ).isEmpty() );
    }

    // ---- availableDisplayName ----

    @Test
    void availableDisplayNameKeepsAFreePreferredNameTrimmed() {
        assertEquals( "Mallory Jones", ProfileNameRules.availableDisplayName( engine, db, "mallory", "  Mallory Jones " ) );
        assertEquals( "The Boss", ProfileNameRules.availableDisplayName( engine, db, "boss", "The Boss" ),
                "an account's own current name is available to it" );
    }

    @Test
    void availableDisplayNameNumbersATakenNameFromTwo() throws Exception {
        assertEquals( "The Boss 2", ProfileNameRules.availableDisplayName( engine, db, "mallory", "The Boss" ) );
        saveAccount( "boss-2", "The Boss 2" );
        try {
            assertEquals( "The Boss 3", ProfileNameRules.availableDisplayName( engine, db, "mallory", "The Boss" ) );
            assertEquals( "the boss 3", ProfileNameRules.availableDisplayName( engine, db, "mallory", "the boss" ),
                    "the clash is case-insensitive; the claim keeps its own spelling in the numbered form" );
        } finally {
            db.deleteByLoginName( "boss-2" );
        }
    }

    @Test
    void availableDisplayNameSuffixesANameEqualToAnotherLogin() {
        assertEquals( "BOSS 2", ProfileNameRules.availableDisplayName( engine, db, "mallory", "BOSS" ) );
    }

    @Test
    void availableDisplayNameFallsBackToAPlaceholderNeverTheEmail() throws Exception {
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", null ) );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "   " ) );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "jane@example.com" ),
                "an email-shaped claim is unusable" );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "Admin" ),
                "a reserved claim is unusable" );
        assertEquals( "Member 1", ProfileNameRules.availableDisplayName( engine, db, "mallory", "x" ),
                "a too-short claim is unusable" );
        saveAccount( "member-one", "Member 1" );
        try {
            assertEquals( "Member 2", ProfileNameRules.availableDisplayName( engine, db, "mallory", null ) );
        } finally {
            db.deleteByLoginName( "member-one" );
        }
    }

    @Test
    void availableDisplayNameKeepsTheSuffixedResultWithinTheLengthLimit() throws Exception {
        final String longName = "z".repeat( ProfileNameRules.DISPLAY_NAME_MAX );
        saveAccount( "long-owner", longName );
        try {
            final String chosen = ProfileNameRules.availableDisplayName( engine, db, "mallory", longName );
            assertTrue( chosen.length() <= ProfileNameRules.DISPLAY_NAME_MAX, chosen );
            assertTrue( chosen.endsWith( " 2" ), chosen );
            assertTrue( displayError( "mallory", chosen ).isEmpty(), "the result must itself satisfy the rules" );
        } finally {
            db.deleteByLoginName( "long-owner" );
        }
    }

    /** Saves an account the way the write paths do (a free wiki name is derived), bypassing the display-name rules. */
    private void saveAccount( final String login, final String fullName ) throws Exception {
        final UserProfile p = db.newProfile();
        p.setLoginName( login );
        ProfileNameRules.applyFullName( engine, db, p, fullName );
        p.setEmail( login + "@example.test" );
        p.setPassword( "correct-horse-battery-staple-5" );
        db.save( p );
    }

    @Test
    void displayNameCannotContainInvisibleFormattingCharacters() {
        for ( final String name : new String[] { "Jake\u200bFear", "Jake\u202eFear", "\u2060Jake Fear", "Jake\ufeffFear" } ) {
            assertTrue( displayError( "mallory", name ).orElseThrow().contains( "invisible" ), name );
        }
    }

    @Test
    void uniquenessIgnoresCaseAndRunsOfWhitespace() {
        assertTrue( displayError( "mallory", "the   boss" ).isPresent(), "collapsed whitespace + case must still collide" );
        assertTrue( displayError( "mallory", "THE BOSS" ).isPresent() );
    }

    @Test
    void uniquenessSeesThroughCompatibilityForms() {
        assertTrue( displayError( "mallory", "\uff34\uff48\uff45 \uff22\uff4f\uff53\uff53" ).isPresent(), "full-width letters" );
        assertTrue( displayError( "mallory", "The\u00a0Boss" ).isPresent(), "a no-break space" );
    }

    @Test
    void displayNameKeyFoldsCaseWhitespaceAndCompatibilityForms() {
        assertEquals( "the boss", ProfileNameRules.displayNameKey( "  The \t Boss " ) );
        assertEquals( "the boss", ProfileNameRules.displayNameKey( "\uff34\uff48\uff45\u00a0boss" ) );
        assertEquals( "the boss", ProfileNameRules.displayNameKey( "\u00a0The Boss" ), "a no-break space at the edge is trimmed" );
        assertEquals( "", ProfileNameRules.displayNameKey( "   " ) );
        assertNull( ProfileNameRules.displayNameKey( null ) );
    }
}
