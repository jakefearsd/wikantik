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

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The wiki side of work management when it is handed nothing, or half of something: a session with no name, a
 * blank login, a group that was deleted while its members were being read. None of these is anybody.
 */
class WikiWorkRuntimeEdgesTest {

    @Test
    void aBlankLoginIsInNoGroupAndTheGroupsAreNotRead() {
        final GroupManager groups = mock( GroupManager.class );
        assertEquals( Set.of(), WikiWorkGroups.groupsOf( groups, null ) );
        assertEquals( Set.of(), WikiWorkGroups.groupsOf( groups, "  " ) );
        verifyNoInteractions( groups );
    }

    @Test
    void aGroupThatVanishedOrIsMissingIsSkippedAndTheRestStillCount() throws Exception {
        final GroupManager groups = mock( GroupManager.class );
        final Group dev = mock( Group.class );
        when( dev.getName() ).thenReturn( "Dev" );
        when( dev.isMember( any( Principal.class ) ) ).thenReturn( true );
        final Group ops = mock( Group.class );
        when( ops.isMember( any( Principal.class ) ) ).thenReturn( false );
        when( groups.getRoles() ).thenReturn( new Principal[] { new WikiPrincipal( "Dev" ), new WikiPrincipal( "Ops" ),
            new WikiPrincipal( "Gone" ), new WikiPrincipal( "Null" ) } );
        when( groups.getGroup( "Dev" ) ).thenReturn( dev );
        when( groups.getGroup( "Ops" ) ).thenReturn( ops );
        when( groups.getGroup( "Gone" ) ).thenThrow( new NoSuchPrincipalException( "Gone was deleted" ) );
        when( groups.getGroup( "Null" ) ).thenReturn( null );
        assertEquals( Set.of( "Dev" ), WikiWorkGroups.groupsOf( groups, "lena" ) );
    }

    @Test
    void aSessionWithNoNameIsNobody() {
        final WikiEngine engine = mock( WikiEngine.class );
        assertNull( WikiWorkActors.actorFor( engine, null ) );
        final Session anonymous = mock( Session.class );
        when( anonymous.isAuthenticated() ).thenReturn( false );
        assertNull( WikiWorkActors.actorFor( engine, anonymous ) );
        final Session nameless = mock( Session.class );
        when( nameless.isAuthenticated() ).thenReturn( true );
        when( nameless.getLoginPrincipal() ).thenReturn( null );
        assertNull( WikiWorkActors.actorFor( engine, nameless ) );
        for ( final String name : new String[] { null, "", "  " } ) {
            final Session blank = mock( Session.class );
            final Principal p = mock( Principal.class );
            when( p.getName() ).thenReturn( name );
            when( blank.isAuthenticated() ).thenReturn( true );
            when( blank.getLoginPrincipal() ).thenReturn( p );
            assertNull( WikiWorkActors.actorFor( engine, blank ), "name: " + name );
        }
        verifyNoInteractions( engine );
    }

    @Test
    void aBlankLoginIsNoAccountAndNothingIsLookedUp() {
        final WikiEngine engine = mock( WikiEngine.class );
        final WikiWorkDirectory directory = new WikiWorkDirectory( engine );
        assertFalse( directory.exists( null ) );
        assertFalse( directory.exists( " " ) );
        assertFalse( directory.ownerExists( null ) );
        assertFalse( directory.ownerExists( " " ) );
    }
}
