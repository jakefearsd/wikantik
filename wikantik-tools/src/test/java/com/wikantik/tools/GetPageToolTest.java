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
package com.wikantik.tools;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class GetPageToolTest {

    @Mock Engine engine;
    @Mock PageManager pageManager;
    @Mock Page page;
    @Mock HttpServletRequest request;

    /**
     * Builds a tool whose permission gate always allows. Mirrors SearchWikiToolTest's
     * stub-context pattern so existing body/frontmatter assertions don't require a
     * real engine wiring.
     */
    private GetPageTool allowingTool( final Engine engine, final ToolsConfig config ) {
        return new GetPageTool( engine, config ) {
            @Override
            boolean canView( final HttpServletRequest request, final String pageName ) {
                return true;
            }
        };
    }

    @Test
    void returnsNullWhenPageMissing() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "Missing" ) ).thenReturn( null );

        final GetPageTool tool = allowingTool( engine, new ToolsConfig( new Properties() ) );
        assertNull( tool.execute( "Missing", 0, request ) );
    }

    @Test
    void stripsFrontmatterAndIncludesSummary() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "Main" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "Main" );
        when( pageManager.getPureText( "Main", -1 ) ).thenReturn(
                "---\nsummary: The front page\ntags: [home]\n---\nBody content" );

        final Properties props = new Properties();
        props.setProperty( "wikantik.public.baseURL", "https://wiki.example.com" );

        final GetPageTool tool = allowingTool( engine, new ToolsConfig( props ) );
        final Map< String, Object > out = tool.execute( "Main", 0, request );

        assertEquals( "Main", out.get( "name" ) );
        assertEquals( "https://wiki.example.com/wiki/Main", out.get( "url" ) );
        assertEquals( "The front page", out.get( "summary" ) );
        assertEquals( Boolean.FALSE, out.get( "truncated" ) );
        assertTrue( out.get( "text" ).toString().contains( "Body content" ) );
        assertFalse( out.get( "text" ).toString().contains( "summary:" ),
                "Frontmatter should have been stripped from text" );
    }

    @Test
    void truncatesLongBodies() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "Big" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "Big" );
        final String body = "x".repeat( 500 );
        when( pageManager.getPureText( "Big", -1 ) ).thenReturn( body );

        final GetPageTool tool = allowingTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > out = tool.execute( "Big", 100, request );

        assertEquals( Boolean.TRUE, out.get( "truncated" ) );
        assertEquals( 500, out.get( "totalChars" ) );
        assertEquals( 100, out.get( "truncatedAt" ) );
        assertTrue( out.get( "text" ).toString().endsWith( "…" ) );
    }

    @Test
    void clampsMaxCharsToHardCap() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "Big" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "Big" );
        final String body = "x".repeat( 30_000 );
        when( pageManager.getPureText( "Big", -1 ) ).thenReturn( body );

        final GetPageTool tool = allowingTool( engine, new ToolsConfig( new Properties() ) );
        final Map< String, Object > out = tool.execute( "Big", 50_000, request );

        assertEquals( Boolean.TRUE, out.get( "truncated" ) );
        assertEquals( 20_000, out.get( "truncatedAt" ) );
    }

    @Test
    void throwsPageAccessDeniedWhenPermissionCheckFails() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "SecretAdminRunbook" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "SecretAdminRunbook" );

        final GetPageTool tool = new GetPageTool( engine, new ToolsConfig( new Properties() ) ) {
            @Override
            boolean canView( final HttpServletRequest request, final String pageName ) {
                return false;
            }
        };

        final PageAccessDeniedException thrown = assertThrows( PageAccessDeniedException.class,
                () -> tool.execute( "SecretAdminRunbook", 0, request ) );
        assertEquals( "SecretAdminRunbook", thrown.getMessage() );
        verify( pageManager, never() ).getPureText( anyString(), anyInt() );
    }

    @Test
    void permissionCheckedBeforeBodyLoadedUsingResolvedPageName() {
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( pageManager.getPage( "some-alias" ) ).thenReturn( page );
        when( page.getName() ).thenReturn( "CanonicalName" );

        final AtomicBoolean calledWithCanonical = new AtomicBoolean( false );
        final GetPageTool tool = new GetPageTool( engine, new ToolsConfig( new Properties() ) ) {
            @Override
            boolean canView( final HttpServletRequest request, final String pageName ) {
                calledWithCanonical.set( "CanonicalName".equals( pageName ) );
                return false;
            }
        };

        assertThrows( PageAccessDeniedException.class, () -> tool.execute( "some-alias", 0, request ) );
        assertTrue( calledWithCanonical.get(),
                "ACL check should run against the resolved page name, not the request alias, "
                        + "so rename aliases can't bypass ACLs." );
    }
}
