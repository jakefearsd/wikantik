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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoSuchVariableException;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Second set of coverage tests for {@link DefaultVariableManager}.
 *
 * <p>Covers the lines left uncovered by {@link DefaultVariableManagerTest}
 * and {@link DefaultVariableManagerCITest}:
 * <ul>
 *   <li>{@code metadataToString} — Date and List branches (resolvers 4 and 5)</li>
 *   <li>{@code expandVariables} — unclosed {@code {$} expression (no closing "}")</li>
 *   <li>SystemVariables getters that delegate to the live engine: {@code getInterwikilinks},
 *       {@code getInlinedimages}, {@code getPluginpath}, {@code getUptime},
 *       {@code getBaseurl}, {@code getEncoding}, {@code getJspwikiversion},
 *       {@code getApplicationname}, {@code getRequestcontext}, {@code getLoginstatus}</li>
 * </ul>
 */
class DefaultVariableManagerCITest2 {

    private static TestEngine engine;
    private static VariableManager variableManager;
    private static Context context;
    private static final String PAGE_NAME = "TestPage";

    @BeforeAll
    static void setUpEngine() {
        final Properties props = TestEngine.getTestProperties();
        engine = TestEngine.build( props );
        variableManager = new DefaultVariableManager( props );
        context = Wiki.context().create( engine, Wiki.contents().page( engine, PAGE_NAME ) );
    }

    // =========================================================================
    // metadataToString — Date attribute on a page (resolver 4)
    // =========================================================================

    @Test
    void testPageAttributeDateIsFormattedAsIso() throws NoSuchVariableException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "DateAttributePage" );
        // Use a known date: 2025-06-15
        final Date date = new java.util.GregorianCalendar( 2025, java.util.Calendar.JUNE, 15 ).getTime();
        when( page.getAttribute( "releaseDate" ) ).thenReturn( date );

        final Context ctx = mock( Context.class );
        when( ctx.getEngine() ).thenReturn( engine );
        when( ctx.getPage() ).thenReturn( page );

        // Re-create the manager with injected mocks for clean wiring
        final DefaultVariableManager vm = new DefaultVariableManager( new Properties(), null, null, null );
        final String result = vm.getValue( ctx, "releaseDate" );
        assertEquals( "2025-06-15", result,
                "Date page attribute should be formatted as yyyy-MM-dd" );
    }

    // =========================================================================
    // metadataToString — List attribute on a page (resolver 4)
    // =========================================================================

    @Test
    void testPageAttributeListIsJoinedWithComma() throws NoSuchVariableException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "ListAttributePage" );
        when( page.getAttribute( "tags" ) ).thenReturn( List.of( "java", "wiki", "search" ) );

        final Context ctx = mock( Context.class );
        when( ctx.getEngine() ).thenReturn( engine );
        when( ctx.getPage() ).thenReturn( page );

        final DefaultVariableManager vm = new DefaultVariableManager( new Properties(), null, null, null );
        final String result = vm.getValue( ctx, "tags" );
        assertEquals( "java, wiki, search", result,
                "List page attribute should be joined with \", \"" );
    }

    // =========================================================================
    // metadataToString — plain Object (toString) attribute (resolver 4)
    // =========================================================================

    @Test
    void testPageAttributePlainObjectUsesToString() throws NoSuchVariableException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "ObjectAttributePage" );
        when( page.getAttribute( "myNumber" ) ).thenReturn( 42 );

        final Context ctx = mock( Context.class );
        when( ctx.getEngine() ).thenReturn( engine );
        when( ctx.getPage() ).thenReturn( page );

        final DefaultVariableManager vm = new DefaultVariableManager( new Properties(), null, null, null );
        final String result = vm.getValue( ctx, "myNumber" );
        assertEquals( "42", result, "Plain Object page attribute should use toString()" );
    }

    // =========================================================================
    // metadataToString — List attribute via realPage (resolver 5)
    // =========================================================================

    @Test
    void testRealPageAttributeListIsJoinedWithComma() throws NoSuchVariableException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "RealPageListPage" );
        // page.getAttribute returns null so resolver 4 skips this
        when( page.getAttribute( "categories" ) ).thenReturn( null );

        final Page realPage = mock( Page.class );
        when( realPage.getAttribute( "categories" ) ).thenReturn( List.of( "alpha", "beta" ) );

        final Context ctx = mock( Context.class );
        when( ctx.getEngine() ).thenReturn( engine );
        when( ctx.getPage() ).thenReturn( page );
        when( ctx.getRealPage() ).thenReturn( realPage );

        final DefaultVariableManager vm = new DefaultVariableManager( new Properties(), null, null, null );
        final String result = vm.getValue( ctx, "categories" );
        assertEquals( "alpha, beta", result,
                "List real-page attribute should be joined with \", \"" );
    }

    // =========================================================================
    // expandVariables — unclosed {$ expression (no closing '}')
    // =========================================================================

    @Test
    void testExpandVariablesUnclosedExpressionIsSkipped() {
        // "{$pagename" has no closing "}" so indexOf('}') returns -1.
        // The implementation swallows the '{' and then appends the rest character-by-character.
        final String result = variableManager.expandVariables( context, "{$pagename" );
        // The '{' is consumed but not appended; remaining chars are emitted verbatim.
        assertEquals( "$pagename", result,
                "Unclosed {$ expression: '{' is consumed, rest is passed through as-is" );
    }

    @Test
    void testExpandVariablesOpenBraceWithoutDollarIsLiteral() {
        final String result = variableManager.expandVariables( context, "{novar}" );
        assertEquals( "{novar}", result,
                "A '{' not followed by '$' should be treated as a literal character" );
    }

    @Test
    void testExpandVariablesUnknownVariableEmbedsErrorMessage() {
        // expandVariables catches NoSuchVariableException and embeds the message
        final String result = variableManager.expandVariables( context, "value={$no_such_xyz}" );
        assertFalse( result.equals( "value={$no_such_xyz}" ),
                "Unresolved variable in expandVariables should embed the error message" );
        assertTrue( result.startsWith( "value=" ),
                "Prefix before the variable expression should be preserved" );
    }

    // =========================================================================
    // SystemVariables accessed via live TestEngine
    // =========================================================================

    @Test
    void testGetBaseurl() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "baseurl" );
        assertNotNull( result, "baseurl should not be null" );
        // The value is the engine's base URL — just verify it's a non-empty string
        assertFalse( result.isEmpty(), "baseurl should be non-empty" );
    }

    @Test
    void testGetEncoding() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "encoding" );
        assertNotNull( result, "encoding should not be null" );
        assertFalse( result.isEmpty(), "encoding should be non-empty" );
    }

    @Test
    void testGetJspwikiversion() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "jspwikiversion" );
        assertNotNull( result, "jspwikiversion should not be null" );
        assertFalse( result.isEmpty(), "jspwikiversion should be non-empty" );
    }

    @Test
    void testGetApplicationname() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "applicationname" );
        assertNotNull( result, "applicationname should not be null" );
        assertFalse( result.isEmpty(), "applicationname should be non-empty" );
    }

    @Test
    void testGetRequestcontext() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "requestcontext" );
        assertNotNull( result, "requestcontext should not be null" );
    }

    @Test
    void testGetUptime() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "uptime" );
        assertNotNull( result, "uptime should not be null" );
        // Expected format: "Nd, Hh Mm Ss"
        assertTrue( result.contains( "d," ) && result.contains( "h" ) && result.contains( "m" ),
                "uptime should be formatted as 'Nd, Hh Mm Ss', got: " + result );
    }

    @Test
    void testGetPluginpathReturnsStringOrDash() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "pluginpath" );
        assertNotNull( result, "pluginpath should not be null" );
        // Either a path string or the sentinel "-"
    }

    @Test
    void testGetInlinedimages() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "inlinedimages" );
        assertNotNull( result, "inlinedimages should not be null" );
    }

    @Test
    void testGetInterwikilinks() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "interwikilinks" );
        assertNotNull( result, "interwikilinks should not be null" );
        // Either "(none configured)" or an HTML table
    }

    @Test
    void testGetLoginstatus() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "loginstatus" );
        assertNotNull( result, "loginstatus should not be null" );
        assertFalse( result.isEmpty(), "loginstatus should be non-empty" );
    }

    @Test
    void testGetUsername() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "username" );
        assertNotNull( result, "username should not be null" );
        assertFalse( result.isEmpty(), "username should be non-empty" );
    }

    // =========================================================================
    // parseAndGetValue — verify the happy path is reachable
    // =========================================================================

    @Test
    void testParseAndGetValueReturnsExpectedResult() throws Exception {
        // parseAndGetValue is already tested in DefaultVariableManagerTest but
        // calling it here exercises the path through the production manager
        // wired to a live engine.
        final String result = variableManager.parseAndGetValue( context, "{$pagename}" );
        assertEquals( PAGE_NAME, result,
                "parseAndGetValue should return the page name for {$pagename}" );
    }

    @Test
    void testParseAndGetValueWithWhitespace() throws Exception {
        final String result = variableManager.parseAndGetValue( context, "{$  pagename  }" );
        assertEquals( PAGE_NAME, result,
                "parseAndGetValue should trim whitespace from the variable name" );
    }

}
