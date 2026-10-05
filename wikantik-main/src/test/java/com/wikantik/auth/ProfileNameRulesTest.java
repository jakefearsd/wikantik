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
    void newNameSharedWithAnotherUsersFullOrWikiNameIsAllowed() {
        // Two people may share a full name; privileges follow the login name only.
        assertTrue( change( "Mallory", "Mallory", "The Boss", "TheBoss" ).isEmpty() );
        assertTrue( change( "Mallory", "Mallory", "Mallory", "TheBoss" ).isEmpty(), "shared wiki name" );
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
}
