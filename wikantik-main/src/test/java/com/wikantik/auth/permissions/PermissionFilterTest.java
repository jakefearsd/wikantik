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
package com.wikantik.auth.permissions;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Permission;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PermissionFilter} that verify delegation to the
 * engine's managers without requiring a full wiki engine. End-to-end ACL
 * enforcement is covered by {@code SearchPermissionTest} in this module.
 */
class PermissionFilterTest {

    private Engine engine;
    private PageManager pageManager;
    private AuthorizationManager authMgr;
    private Session session;
    private PermissionFilter filter;

    @BeforeEach
    void setUp() {
        engine = mock( Engine.class );
        pageManager = mock( PageManager.class );
        authMgr = mock( AuthorizationManager.class );
        session = mock( Session.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( engine.getManager( AuthorizationManager.class ) ).thenReturn( authMgr );
        when( engine.getApplicationName() ).thenReturn( "TestWiki" );
        filter = new PermissionFilter( engine );
    }

    @Test
    void canAccessUsesPagePermissionFactoryWhenPageExists() {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Real" );
        when( page.getWiki() ).thenReturn( "TestWiki" );
        when( pageManager.getPage( "Real" ) ).thenReturn( page );
        when( authMgr.checkPermission( eq( session ), any( Permission.class ) ) )
                .thenReturn( true );

        assertTrue( filter.canAccess( session, "Real", "view" ) );
        verify( authMgr ).checkPermission( eq( session ), any( Permission.class ) );
    }

    @Test
    void canAccessFallsBackToPolicyGrantWhenPageMissing() {
        when( pageManager.getPage( "Nope" ) ).thenReturn( null );
        when( authMgr.checkPermission( eq( session ), any( Permission.class ) ) )
                .thenReturn( false );

        assertFalse( filter.canAccess( session, "Nope", "view" ) );
        verify( authMgr ).checkPermission( eq( session ), any( Permission.class ) );
    }

    @Test
    void canAccessReturnsAuthManagerDecision() {
        when( pageManager.getPage( "X" ) ).thenReturn( null );
        when( authMgr.checkPermission( eq( session ), any( Permission.class ) ) )
                .thenReturn( true, false );

        assertTrue( filter.canAccess( session, "X", "view" ) );
        assertFalse( filter.canAccess( session, "X", "edit" ) );
    }

    @Test
    void filterAccessiblePreservesOrderAndDropsForbidden() {
        when( pageManager.getPage( anyString() ) ).thenReturn( null );
        when( authMgr.checkPermission( eq( session ), any( Permission.class ) ) )
                .thenReturn( true, false, true );

        final List< String > filtered = filter.filterAccessible(
                session, List.of( "a", "b", "c" ), "view" );
        assertEquals( List.of( "a", "c" ), filtered );
    }

    @Test
    void filterAccessibleHandlesEmptyInputWithoutInvokingAuth() {
        assertEquals( List.of(),
                filter.filterAccessible( session, List.of(), "view" ) );
        verify( authMgr, never() ).checkPermission( any(), any() );
    }
}
