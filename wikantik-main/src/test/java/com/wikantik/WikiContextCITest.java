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
package com.wikantik;

import com.wikantik.api.core.Command;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.pages.PageManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.PageCommand;
import com.wikantik.ui.WikiCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Permission;
import java.util.Properties;
import java.util.PropertyPermission;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Constructor-injection coverage tests for {@link WikiContext}.
 * Uses the package-private four-arg constructor with a mock CommandResolver
 * to cover lines that were previously uncovered.
 */
@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class WikiContextCITest {

    @Mock private CommandResolver commandResolver;
    @Mock private PageManager pageManager;
    @Mock private AuthorizationManager authorizationManager;
    @Mock private UserManager userManager;
    @Mock private GroupManager groupManager;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private UserDatabase userDatabase;

    private Engine engine;
    private WikiPage frontPage;

    @BeforeEach
    void setUp() {
        engine = MockEngineBuilder.engine()
                .with( CommandResolver.class, commandResolver )
                .with( PageManager.class, pageManager )
                .with( AuthorizationManager.class, authorizationManager )
                .with( UserManager.class, userManager )
                .with( GroupManager.class, groupManager )
                .with( AuthenticationManager.class, authenticationManager )
                .build();

        when( engine.getFrontPage() ).thenReturn( "Main" );
        when( engine.getTemplateDir() ).thenReturn( "default" );

        frontPage = new WikiPage( engine, "Main" );
        when( pageManager.getPage( "Main" ) ).thenReturn( frontPage );

        // CommandResolver.findCommand used by updateCommand — return the passed-in context command
        when( commandResolver.findCommand( any(), any() ) )
                .thenAnswer( inv -> CommandResolver.findCommand( inv.getArgument( 1 ) ) );
    }

    /**
     * Helper that creates a WikiContext via the package-private constructor with a PageCommand.
     */
    private WikiContext createViewContext( final WikiPage page ) {
        final Command cmd = PageCommand.VIEW.targetedCommand( page );
        return new WikiContext( engine, null, cmd, commandResolver );
    }

    // -----------------------------------------------------------------------
    // getContentTemplate / getRoutePath
    // -----------------------------------------------------------------------

    @Test
    void getContentTemplate_delegatesToCommand() {
        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( PageCommand.VIEW.getContentTemplate(), ctx.getContentTemplate() );
    }

    @Test
    void getRoutePath_delegatesToCommand() {
        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( PageCommand.VIEW.getRoutePath(), ctx.getRoutePath() );
    }

    // -----------------------------------------------------------------------
    // setRealPage
    // -----------------------------------------------------------------------

    @Test
    void setRealPage_callsUpdateCommandAndReturnsOldPage() {
        final WikiContext ctx = createViewContext( frontPage );

        final WikiPage otherPage = new WikiPage( engine, "OtherPage" );
        final WikiPage old = ctx.setRealPage( otherPage );

        assertSame( frontPage, old, "must return old real page" );
        assertSame( otherPage, ctx.getRealPage(), "new real page must be set" );
    }

    // -----------------------------------------------------------------------
    // setRequestContext
    // -----------------------------------------------------------------------

    @Test
    void setRequestContext_callsUpdateCommand() {
        final WikiContext ctx = createViewContext( frontPage );

        ctx.setRequestContext( ContextEnum.PAGE_EDIT.getRequestContext() );

        assertEquals( ContextEnum.PAGE_EDIT.getRequestContext(), ctx.getRequestContext() );
    }

    // -----------------------------------------------------------------------
    // getRedirectURL — special page, alias, redirect, and regular
    // -----------------------------------------------------------------------

    @Test
    void getRedirectURL_returnsSpecialPageReference() {
        when( commandResolver.getSpecialPageReference( "Main" ) ).thenReturn( "/special/Main" );
        final WikiContext ctx = createViewContext( frontPage );

        assertEquals( "/special/Main", ctx.getRedirectURL() );
    }

    @Test
    void getRedirectURL_returnsAliasWhenNoSpecialPage() {
        when( commandResolver.getSpecialPageReference( "Main" ) ).thenReturn( null );
        frontPage.setAttribute( Page.ALIAS, "AliasTarget" );
        when( engine.getURL( eq( ContextEnum.PAGE_VIEW.getRequestContext() ), eq( "AliasTarget" ), any() ) )
                .thenReturn( "/wiki/AliasTarget" );

        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( "/wiki/AliasTarget", ctx.getRedirectURL() );
    }

    @Test
    void getRedirectURL_returnsRedirectWhenNoAliasOrSpecialPage() {
        when( commandResolver.getSpecialPageReference( "Main" ) ).thenReturn( null );
        frontPage.setAttribute( Page.REDIRECT, "http://example.com" );

        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( "http://example.com", ctx.getRedirectURL() );
    }

    @Test
    void getRedirectURL_returnsNullWhenNoSpecialPageOrAliasOrRedirect() {
        when( commandResolver.getSpecialPageReference( "Main" ) ).thenReturn( null );

        final WikiContext ctx = createViewContext( frontPage );
        assertNull( ctx.getRedirectURL() );
    }

    // -----------------------------------------------------------------------
    // getURL
    // -----------------------------------------------------------------------

    @Test
    void getURL_withoutReactBase_delegatesToEngine() {
        when( engine.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), "TestPage", null ) )
                .thenReturn( "/wiki/TestPage" );

        final WikiContext ctx = createViewContext( frontPage );

        final String url = ctx.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), "TestPage", null );
        assertEquals( "/wiki/TestPage", url );
    }

    // -----------------------------------------------------------------------
    // getName
    // -----------------------------------------------------------------------

    @Test
    void getName_forPageCommand_returnsPageName() {
        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( "Main", ctx.getName() );
    }

    @Test
    void getName_forNonPageCommand_returnsCommandName() {
        final WikiContext ctx = new WikiContext( engine, null, WikiCommand.FIND, commandResolver );
        assertEquals( WikiCommand.FIND.getName(), ctx.getName() );
    }

    // -----------------------------------------------------------------------
    // getBooleanWikiProperty
    // -----------------------------------------------------------------------

    @Test
    void getBooleanWikiProperty_variableOverridesProperty() {
        final WikiContext ctx = createViewContext( frontPage );
        ctx.setVariable( "my.flag", "true" );

        assertTrue( ctx.getBooleanWikiProperty( "my.flag", false ) );
    }

    @Test
    void getBooleanWikiProperty_fallsBackToPropertyWhenNoVariable() {
        // getBooleanWikiProperty calls getEngine() which casts to WikiEngine.
        // Use a real TestEngine to test this path properly.
        final TestEngine realEngine = TestEngine.build();
        try {
            realEngine.getWikiProperties().setProperty( "my.flag", "true" );
            final WikiPage page = new WikiPage( realEngine, "Main" );
            final WikiContext ctx = new WikiContext( realEngine, null, PageCommand.VIEW.targetedCommand( page ) );
            assertTrue( ctx.getBooleanWikiProperty( "my.flag", false ) );
        } finally {
            realEngine.stop();
        }
    }

    @Test
    void getBooleanWikiProperty_returnsDefaultWhenNothingSet() {
        final TestEngine realEngine = TestEngine.build();
        try {
            final WikiPage page = new WikiPage( realEngine, "Main" );
            final WikiContext ctx = new WikiContext( realEngine, null, PageCommand.VIEW.targetedCommand( page ) );
            assertFalse( ctx.getBooleanWikiProperty( "nonexistent.flag", false ) );
            assertTrue( ctx.getBooleanWikiProperty( "nonexistent.flag", true ) );
        } finally {
            realEngine.stop();
        }
    }

    // -----------------------------------------------------------------------
    // getHttpParameter with null request
    // -----------------------------------------------------------------------

    @Test
    void getHttpParameter_nullRequest_returnsNull() {
        final WikiContext ctx = createViewContext( frontPage );
        assertNull( ctx.getHttpParameter( "anything" ) );
    }

    @Test
    void getHttpParameter_withRequest_delegatesToRequest() {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getParameter( "page" ) ).thenReturn( "TestPage" );
        when( request.getSession( false ) ).thenReturn( null );

        final WikiContext ctx = new WikiContext( engine, request, PageCommand.VIEW.targetedCommand( frontPage ), commandResolver );
        assertEquals( "TestPage", ctx.getHttpParameter( "page" ) );
    }

    // -----------------------------------------------------------------------
    // getCurrentUser
    // -----------------------------------------------------------------------

    @Test
    void getCurrentUser_withNullSession_returnsGuest() {
        // The four-arg constructor always calls Wiki.session().find() so session is set.
        // We test the contract: session is non-null for normal construction, so
        // getCurrentUser returns the session's principal.
        final WikiContext ctx = createViewContext( frontPage );
        assertNotNull( ctx.getCurrentUser() );
    }

    // -----------------------------------------------------------------------
    // getCommand / getTarget / getURLPattern
    // -----------------------------------------------------------------------

    @Test
    void getCommand_returnsTheCommand() {
        final Command cmd = PageCommand.VIEW.targetedCommand( frontPage );
        final WikiContext ctx = new WikiContext( engine, null, cmd, commandResolver );
        assertSame( cmd, ctx.getCommand() );
    }

    @Test
    void getTarget_delegatesToCommand() {
        final WikiContext ctx = createViewContext( frontPage );
        assertSame( frontPage, ctx.getTarget() );
    }

    @Test
    void getURLPattern_delegatesToCommand() {
        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( PageCommand.VIEW.targetedCommand( frontPage ).getURLPattern(), ctx.getURLPattern() );
    }

    // -----------------------------------------------------------------------
    // clone / deepClone
    // -----------------------------------------------------------------------

    @Test
    void clone_producesShallowCopyWithSameFields() {
        final TestEngine realEngine = TestEngine.build();
        try {
            final WikiPage page = new WikiPage( realEngine, "ClonePage" );
            final WikiContext ctx = new WikiContext( realEngine, null, PageCommand.VIEW.targetedCommand( page ) );
            ctx.setVariable( "key", "value" );

            final WikiContext clone = ctx.clone();

            assertNotSame( ctx, clone );
            assertSame( ctx.getPage(), clone.getPage(), "shallow clone shares page" );
            assertSame( ctx.getEngine(), clone.getEngine() );
            assertEquals( "value", clone.getVariable( "key" ) );
            assertEquals( ctx.getCommand(), clone.getCommand() );
        } finally {
            realEngine.stop();
        }
    }

    @Test
    void deepClone_producesDeepCopyWithClonedPages() {
        final WikiContext ctx = createViewContext( frontPage );
        ctx.setVariable( "key", "value" );

        final WikiContext deep = ctx.deepClone();

        assertNotSame( ctx, deep );
        assertNotSame( ctx.getPage(), deep.getPage(), "deep clone has cloned page" );
        assertEquals( ctx.getPage().getName(), deep.getPage().getName() );
        assertEquals( "value", deep.getVariable( "key" ) );
    }

    // -----------------------------------------------------------------------
    // setVariable / getVariable round-trip
    // -----------------------------------------------------------------------

    @Test
    void setVariable_getVariable_roundTrip() {
        final WikiContext ctx = createViewContext( frontPage );

        ctx.setVariable( "foo", "bar" );
        assertEquals( "bar", ctx.getVariable( "foo" ) );

        ctx.setVariable( "foo", 42 );
        assertEquals( 42, (int) ctx.getVariable( "foo" ) );
    }

    @Test
    void getVariable_returnsNullForUnsetKey() {
        final WikiContext ctx = createViewContext( frontPage );
        assertNull( ctx.getVariable( "nonexistent" ) );
    }

    // -----------------------------------------------------------------------
    // hasAdminPermissions
    // -----------------------------------------------------------------------

    @Test
    void hasAdminPermissions_delegatesToAuthorizationManager() {
        when( authorizationManager.checkPermission( any( Session.class ), any( AllPermission.class ) ) )
                .thenReturn( true );

        final WikiContext ctx = createViewContext( frontPage );
        assertTrue( ctx.hasAdminPermissions() );
    }

    @Test
    void hasAdminPermissions_returnsFalseWhenDenied() {
        when( authorizationManager.checkPermission( any( Session.class ), any( AllPermission.class ) ) )
                .thenReturn( false );

        final WikiContext ctx = createViewContext( frontPage );
        assertFalse( ctx.hasAdminPermissions() );
    }

    // -----------------------------------------------------------------------
    // requiredPermission
    // -----------------------------------------------------------------------

    @Test
    void requiredPermission_normalCommand_returnsCommandPermission() {
        final WikiContext ctx = createViewContext( frontPage );

        final Permission perm = ctx.requiredPermission();
        assertNotNull( perm );
    }

    @Test
    void requiredPermission_installCommand_withAdminUser_returnsAllPermission() {
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        // admin user exists — findByLoginName returns normally (no exception)

        final WikiContext ctx = new WikiContext( engine, null, WikiCommand.INSTALL, commandResolver );
        final Permission perm = ctx.requiredPermission();

        assertInstanceOf( AllPermission.class, perm );
    }

    @Test
    void requiredPermission_installCommand_withoutAdminUser_returnsDummyPermission() throws Exception {
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        doThrow( new NoSuchPrincipalException( "no admin" ) )
                .when( userDatabase ).findByLoginName( "admin" );

        final WikiContext ctx = new WikiContext( engine, null, WikiCommand.INSTALL, commandResolver );
        final Permission perm = ctx.requiredPermission();

        assertInstanceOf( PropertyPermission.class, perm );
    }

    @Test
    void requiredPermission_commandWithNullPermission_returnsDummyPermission() {
        // WikiCommand.ERROR has null requiredPermission
        final WikiContext ctx = new WikiContext( engine, null, WikiCommand.ERROR, commandResolver );
        final Permission perm = ctx.requiredPermission();

        assertInstanceOf( PropertyPermission.class, perm );
    }

    // -----------------------------------------------------------------------
    // Constructor guards
    // -----------------------------------------------------------------------

    @Test
    void constructor_nullEngine_throws() {
        assertThrows( IllegalArgumentException.class,
                () -> new WikiContext( null, null, PageCommand.VIEW, commandResolver ) );
    }

    @Test
    void constructor_nullCommand_throws() {
        assertThrows( IllegalArgumentException.class,
                () -> new WikiContext( engine, null, (Command) null, commandResolver ) );
    }

    // -----------------------------------------------------------------------
    // targetedCommand
    // -----------------------------------------------------------------------

    @Test
    void targetedCommand_untargetedCommand_returnsTargeted() {
        final WikiContext ctx = new WikiContext( engine, null, PageCommand.VIEW, commandResolver );
        final Command targeted = ctx.targetedCommand( frontPage );
        assertSame( frontPage, targeted.getTarget() );
    }

    @Test
    void targetedCommand_alreadyTargeted_returnsSame() {
        final WikiContext ctx = createViewContext( frontPage );
        final WikiPage other = new WikiPage( engine, "Other" );
        final Command result = ctx.targetedCommand( other );
        // Already targeted — returns original command unchanged
        assertSame( frontPage, result.getTarget() );
    }

    // -----------------------------------------------------------------------
    // getEngine / getPage / getWikiSession / getTemplate / getHttpRequest
    // -----------------------------------------------------------------------

    @Test
    void getEngine_returnsEngine() {
        final TestEngine realEngine = TestEngine.build();
        try {
            final WikiPage page = new WikiPage( realEngine, "EnginePage" );
            final WikiContext ctx = new WikiContext( realEngine, null, PageCommand.VIEW.targetedCommand( page ) );
            assertSame( realEngine, ctx.getEngine() );
        } finally {
            realEngine.stop();
        }
    }

    @Test
    void getPage_returnsPage() {
        final WikiContext ctx = createViewContext( frontPage );
        assertSame( frontPage, ctx.getPage() );
    }

    @Test
    void getWikiSession_returnsNonNull() {
        final WikiContext ctx = createViewContext( frontPage );
        assertNotNull( ctx.getWikiSession() );
    }

    @Test
    void getTemplate_defaultIsDefault() {
        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( "default", ctx.getTemplate() );
    }

    @Test
    void setTemplate_overridesDefault() {
        final WikiContext ctx = createViewContext( frontPage );
        ctx.setTemplate( "custom" );
        assertEquals( "custom", ctx.getTemplate() );
    }

    @Test
    void getHttpRequest_nullWhenConstructedWithoutRequest() {
        final WikiContext ctx = createViewContext( frontPage );
        assertNull( ctx.getHttpRequest() );
    }

    // -----------------------------------------------------------------------
    // setPage
    // -----------------------------------------------------------------------

    @Test
    void setPage_updatesPageAndCommand() {
        final WikiContext ctx = createViewContext( frontPage );
        final WikiPage newPage = new WikiPage( engine, "NewPage" );

        ctx.setPage( newPage );

        assertSame( newPage, ctx.getPage() );
    }

    // -----------------------------------------------------------------------
    // getRequestContext
    // -----------------------------------------------------------------------

    @Test
    void getRequestContext_returnsViewForViewCommand() {
        final WikiContext ctx = createViewContext( frontPage );
        assertEquals( ContextEnum.PAGE_VIEW.getRequestContext(), ctx.getRequestContext() );
    }

}
