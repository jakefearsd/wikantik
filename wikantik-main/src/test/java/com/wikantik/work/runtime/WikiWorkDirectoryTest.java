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
package com.wikantik.work.runtime;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiWorkDirectoryTest {

    private TestEngine engine;
    private GroupManager groups;
    private Session admin;
    private WikiWorkDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        groups = engine.getManager( GroupManager.class );
        admin = engine.adminSession();
        directory = new WikiWorkDirectory( engine );
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            groups.removeGroup( "DirCrew" );
        } catch ( final com.wikantik.auth.NoSuchPrincipalException e ) {
            // the group was never created by this test
        }
        engine.stop();
    }

    private void setMembers( final String members ) throws Exception {
        final Group group = groups.parseGroup( "DirCrew", members, true );
        groups.setGroup( admin, group );
    }

    @Test
    void groupsOfFollowsMembershipOnEveryCall() throws Exception {
        assertFalse( directory.groupsOf( "janne" ).contains( "DirCrew" ) );
        setMembers( "janne" );
        assertTrue( directory.groupsOf( "janne" ).contains( "DirCrew" ) );
        setMembers( "other" );
        assertFalse( directory.groupsOf( "janne" ).contains( "DirCrew" ) );
    }

    @Test
    void anUnknownLoginHasNoGroupsAndDoesNotExist() throws Exception {
        setMembers( "janne" );
        assertTrue( directory.groupsOf( "nobody-here" ).isEmpty() );
        assertFalse( directory.exists( "nobody-here" ) );
        assertTrue( directory.groupsOf( null ).isEmpty() );
        assertFalse( directory.exists( null ) );
    }

    @Test
    void aKnownLoginExists() {
        assertTrue( directory.exists( "janne" ) );
    }

    @Test
    void displayNameIsThePublicNameNotTheLogin() {
        assertEquals( "Janne Jalkanen", directory.displayName( "janne" ) );
        assertNotEquals( "janne", directory.displayName( "janne" ) );
    }

    @Test
    void aDisplayNameResolvesToItsLogin() {
        assertEquals( java.util.Optional.of( "janne" ), directory.loginForDisplayName( "Janne Jalkanen" ) );
        assertEquals( java.util.Optional.of( "janne" ), directory.loginForDisplayName( " janne jalkanen " ) );
        assertTrue( directory.loginForDisplayName( "janne" ).isEmpty(), "a login is not a display name" );
    }

    @Test
    void aMentionIdRoundTripsWhenTheAccountHasOne() {
        directory.mentionId( "janne" ).ifPresent( id ->
            assertEquals( java.util.Optional.of( "janne" ), directory.loginForMentionId( id ) ) );
        assertTrue( directory.loginForMentionId( "no-such-id" ).isEmpty() );
        assertTrue( directory.mentionId( "nobody-here" ).isEmpty() );
    }

    @Test
    void aValueThatNamesNoAccountIsNeverEchoed() {
        assertEquals( com.wikantik.api.identity.PublicIdentity.UNKNOWN_CONTRIBUTOR,
            directory.displayName( "nobody-here" ) );
    }
}
