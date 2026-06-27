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
package com.wikantik.variables;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoSuchVariableException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.modules.InternalModule;
import com.wikantik.filters.FilterManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Third set of branch-coverage tests for {@link DefaultVariableManager}.
 *
 * <p>Targets branches left uncovered by CITest and CITest2:
 * <ul>
 *   <li>Resolver 3 (session attributes): ClassCastException path and req-with-null-session path</li>
 *   <li>SystemVariables.getInterwikilinks(): the HTML-table branch (links configured)</li>
 *   <li>SystemVariables.getPagefilters(): InternalModule filter is skipped</li>
 * </ul>
 */
@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class DefaultVariableManagerCITest3 {

    @Mock
    private Context context;

    @Mock
    private Page page;

    @Mock
    private FilterManager filterManager;

    private DefaultVariableManager variableManager;
    private Engine engine;

    @BeforeEach
    void setUp() {
        variableManager = new DefaultVariableManager( new Properties(), null, null, filterManager );
        engine = MockEngineBuilder.engine().build();
        when( context.getEngine() ).thenReturn( engine );
        when( context.getPage() ).thenReturn( page );
        when( page.getName() ).thenReturn( "TestPage" );
    }

    // =========================================================================
    // Resolver 3 — session attributes: ClassCastException path
    // =========================================================================

    /**
     * If {@code session.getAttribute(name)} returns a non-String value,
     * casting it to String throws a {@code ClassCastException}. The resolver
     * catches it, logs at DEBUG, and returns null so the next resolver can try.
     * Because no subsequent resolver handles "intAttr", a {@link NoSuchVariableException}
     * is thrown — proving the CCE path was traversed without propagating the exception.
     */
    @Test
    void sessionAttributeClassCastException_fallsThroughToNextResolver() {
        final HttpSession session = mock( HttpSession.class );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( session );
        when( context.getHttpRequest() ).thenReturn( req );
        // Return an Integer — casting to String will throw ClassCastException
        when( session.getAttribute( "intAttr" ) ).thenReturn( 42 );

        // Resolver 3 CCE → falls through; no other resolver handles "intAttr"
        // so NoSuchVariableException is thrown (not ClassCastException)
        try {
            variableManager.getValue( context, "intAttr" );
        } catch ( final NoSuchVariableException expected ) {
            // correct: CCE swallowed, variable not found
            assertTrue( expected.getMessage().contains( "intAttr" ),
                "NoSuchVariableException must name the variable" );
        }
    }

    /**
     * When {@code req.getSession()} returns null (session expired / not created),
     * the resolver skips the session lookup entirely and returns null.
     * Again, no other resolver handles "orphanVar" → NoSuchVariableException is thrown.
     */
    @Test
    void sessionIsNull_resolverSkipsSessionLookup() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( null );
        when( context.getHttpRequest() ).thenReturn( req );

        try {
            variableManager.getValue( context, "orphanVar" );
        } catch ( final NoSuchVariableException expected ) {
            assertTrue( expected.getMessage().contains( "orphanVar" ),
                "NoSuchVariableException must name the variable" );
        }
    }

    /**
     * When a session attribute IS a String, resolver 3 must return it directly.
     * This asserts the happy path of resolver 3 as a baseline.
     */
    @Test
    void sessionStringAttribute_isResolved() throws NoSuchVariableException {
        final HttpSession session = mock( HttpSession.class );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( session );
        when( context.getHttpRequest() ).thenReturn( req );
        when( session.getAttribute( "mySessionVar" ) ).thenReturn( "sessionValue" );

        assertEquals( "sessionValue", variableManager.getValue( context, "mySessionVar" ),
            "String session attribute must be returned by resolver 3" );
    }

    // =========================================================================
    // SystemVariables.getInterwikilinks() — HTML-table branch
    // =========================================================================

    /**
     * When the engine has interwiki links configured, getInterwikilinks() must return
     * an HTML table (not the "(none configured)" string). This exercises the sorted-stream
     * and URL-lookup branches in the HTML builder.
     *
     * <p>We use a direct Engine mock (not MockEngineBuilder) so we can stub
     * getAllInterWikiLinks() and getInterWikiURL() independently.
     */
    @Test
    void getInterwikilinks_withConfiguredLinks_returnsHtmlTable() throws NoSuchVariableException {
        final Engine engineMock = mock( Engine.class );
        when( engineMock.getAllInterWikiLinks() ).thenReturn( List.of( "Wikipedia", "GitHub" ) );
        when( engineMock.getInterWikiURL( "Wikipedia" ) ).thenReturn( "https://en.wikipedia.org/wiki/%s" );
        when( engineMock.getInterWikiURL( "GitHub" ) ).thenReturn( "https://github.com/%s" );
        when( context.getEngine() ).thenReturn( engineMock );
        when( context.getPage() ).thenReturn( page );
        when( page.getName() ).thenReturn( "TestPage" );

        final DefaultVariableManager vm = new DefaultVariableManager( new Properties() );
        final String result = vm.getValue( context, "interwikilinks" );

        assertNotNull( result );
        assertTrue( result.contains( "<table" ),
            "interwikilinks with configured links must return an HTML table, got: " + result );
        assertTrue( result.contains( "Wikipedia" ),
            "HTML table must contain the configured interwiki name 'Wikipedia'" );
        assertTrue( result.contains( "https://en.wikipedia.org/wiki/%s" ),
            "HTML table must contain the configured URL pattern" );
        assertTrue( result.contains( "GitHub" ),
            "HTML table must contain the second interwiki name 'GitHub'" );
    }

    /**
     * Interwiki link with a null URL (engine.getInterWikiURL returns null for a registered name):
     * the {@code url != null ? url : ""} guard in the HTML builder must emit an empty string
     * rather than NPE.
     */
    @Test
    void getInterwikilinks_nullUrl_emitsEmptyStringInTable() throws NoSuchVariableException {
        final Engine engineMock = mock( Engine.class );
        when( engineMock.getAllInterWikiLinks() ).thenReturn( List.of( "NullLink" ) );
        when( engineMock.getInterWikiURL( "NullLink" ) ).thenReturn( null );
        when( context.getEngine() ).thenReturn( engineMock );
        when( context.getPage() ).thenReturn( page );
        when( page.getName() ).thenReturn( "TestPage" );

        final DefaultVariableManager vm = new DefaultVariableManager( new Properties() );
        final String result = vm.getValue( context, "interwikilinks" );

        assertNotNull( result );
        assertTrue( result.contains( "NullLink" ),
            "Table must list the link name even when URL is null" );
        // The null-guard emits "" for the URL cell
        assertTrue( result.contains( "<td><code>NullLink</code></td><td><code></code></td>" ),
            "null URL must be rendered as empty <code> cell, got: " + result );
    }

    // =========================================================================
    // SystemVariables.getPagefilters() — InternalModule filters are skipped
    // =========================================================================

    /**
     * Filters that implement {@link InternalModule} must be skipped (not listed).
     * When ALL filters are InternalModules, the result must be an empty string.
     */
    @Test
    void getPagefilters_internalModuleFiltersAreSkipped() throws NoSuchVariableException {
        // Create a mock that implements both PageFilter and InternalModule
        final PageFilter internalFilter = mock( PageFilter.class, org.mockito.Mockito.withSettings()
            .extraInterfaces( InternalModule.class ) );
        when( filterManager.getFilterList() ).thenReturn( List.of( internalFilter ) );

        final String result = variableManager.getValue( context, "pagefilters" );

        assertEquals( "", result,
            "InternalModule filters must be excluded from the pagefilters listing" );
    }

    /**
     * Mix of InternalModule and regular filters: only the non-internal ones appear.
     */
    @Test
    void getPagefilters_mixedFilters_onlyRegularFiltersListed() throws NoSuchVariableException {
        final PageFilter internalFilter = mock( PageFilter.class, org.mockito.Mockito.withSettings()
            .extraInterfaces( InternalModule.class ) );
        final PageFilter regularFilter = mock( PageFilter.class );
        when( filterManager.getFilterList() ).thenReturn( List.of( internalFilter, regularFilter ) );

        final String result = variableManager.getValue( context, "pagefilters" );

        assertEquals( regularFilter.getClass().getName(), result,
            "Only non-InternalModule filters must be listed in pagefilters output" );
    }

    /**
     * Two regular filters produce a comma-separated list — proves the separator branch
     * (the {@code if (sb.length() > 0) sb.append(", ")} guard is exercised).
     */
    @Test
    void getPagefilters_twoRegularFilters_commaJoined() throws NoSuchVariableException {
        final PageFilter f1 = mock( PageFilter.class );
        final PageFilter f2 = mock( PageFilter.class );
        when( filterManager.getFilterList() ).thenReturn( List.of( f1, f2 ) );

        final String result = variableManager.getValue( context, "pagefilters" );

        final String expected = f1.getClass().getName() + ", " + f2.getClass().getName();
        assertEquals( expected, result,
            "Two regular filters must be listed comma-separated" );
    }
}
