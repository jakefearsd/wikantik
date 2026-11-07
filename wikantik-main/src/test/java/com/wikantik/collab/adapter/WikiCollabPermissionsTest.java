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
package com.wikantik.collab.adapter;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Permission;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WikiCollabPermissionsTest {

    private AuthorizationManager authMgr;
    private Session session;
    private WikiCollabPermissions permissions;

    @BeforeEach
    void setUp() {
        final WikiEngine engine = mock( WikiEngine.class );
        authMgr = mock( AuthorizationManager.class );
        session = mock( Session.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( mock( PageManager.class ) );
        when( engine.getManager( AuthorizationManager.class ) ).thenReturn( authMgr );
        when( engine.getApplicationName() ).thenReturn( "TestWiki" );
        permissions = new WikiCollabPermissions( engine );
    }

    @Test
    void editGrantedAndDeniedFollowTheAuthorizationManager() {
        when( authMgr.checkPermission( eq( session ), any( PagePermission.class ) ) ).thenReturn( true, false );
        assertTrue( permissions.canEdit( session, "Page" ) );
        assertFalse( permissions.canEdit( session, "Page" ) );
    }

    @Test
    void editAsksForTheEditActionOnThePage() {
        when( authMgr.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        permissions.canEdit( session, "Page" );
        verify( authMgr ).checkPermission( eq( session ), eq( new PagePermission( "TestWiki:Page", "edit" ) ) );
    }

    @Test
    void createChecksTheWikiCreatePagesPermission() {
        when( authMgr.checkPermission( eq( session ), any( WikiPermission.class ) ) ).thenReturn( true );
        assertTrue( permissions.canCreate( session, "NewPage" ) );
        verify( authMgr ).checkPermission( eq( session ), eq( new WikiPermission( "TestWiki", "createPages" ) ) );
    }

    @Test
    void everyCallAsksAgain() {
        when( authMgr.checkPermission( eq( session ), any( Permission.class ) ) ).thenReturn( true );
        permissions.canEdit( session, "Page" );
        permissions.canEdit( session, "Page" );
        verify( authMgr, times( 2 ) ).checkPermission( eq( session ), any( Permission.class ) );
    }

    @Test
    void signedInFollowsTheWikiSessionsAuthentication() {   // A13: a logout invalidates the session before LOGOUT
        when( session.isAuthenticated() ).thenReturn( true, false );
        assertTrue( permissions.signedIn( session ) );
        assertFalse( permissions.signedIn( session ) );
        assertFalse( permissions.signedIn( "not a session" ) );
        assertFalse( permissions.signedIn( null ) );
    }

    @Test
    void aSessionOfTheWrongTypeIsDenied() {
        assertFalse( permissions.canEdit( "not a session", "Page" ) );
        assertFalse( permissions.canCreate( null, "Page" ) );
    }
}
