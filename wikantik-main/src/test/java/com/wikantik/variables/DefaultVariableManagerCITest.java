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
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.AttachmentProvider;
import com.wikantik.filters.FilterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultVariableManager} using constructor injection.
 * Covers the branches that {@link DefaultVariableManagerTest} leaves uncovered,
 * targeting the 18 uncovered lines identified in the 83%-coverage baseline.
 *
 * <p>Lenient strictness is required because the shared {@code setUp()} wires stubs
 * (engine, page) that not every single test exercises — they are still useful for
 * the majority and keeping them centralised is cleaner than per-test setup.
 */
@ExtendWith( MockitoExtension.class )
@MockitoSettings( strictness = Strictness.LENIENT )
class DefaultVariableManagerCITest {

    @Mock
    private Context context;

    @Mock
    private Page page;

    @Mock
    private PageManager pageManager;

    @Mock
    private AttachmentManager attachmentManager;

    @Mock
    private FilterManager filterManager;

    private DefaultVariableManager variableManager;
    private Engine engine;

    @BeforeEach
    void setUp() {
        variableManager = new DefaultVariableManager( new Properties(), pageManager, attachmentManager, filterManager );
        engine = MockEngineBuilder.engine().build();
        when( context.getEngine() ).thenReturn( engine );
        when( context.getPage() ).thenReturn( page );
        when( page.getName() ).thenReturn( "TestPage" );
    }

    // -----------------------------------------------------------------------
    // getValue guard clauses
    // -----------------------------------------------------------------------

    @Test
    void testGetValueNullNameThrows() {
        assertThrows( IllegalArgumentException.class,
                () -> variableManager.getValue( context, null ),
                "Null variable name should throw IllegalArgumentException" );
    }

    @Test
    void testGetValueEmptyNameThrows() {
        assertThrows( IllegalArgumentException.class,
                () -> variableManager.getValue( context, "" ),
                "Empty variable name should throw IllegalArgumentException" );
    }

    // -----------------------------------------------------------------------
    // Prohibited variable list
    // -----------------------------------------------------------------------

    @Test
    void testProhibitedVariableReturnsEmpty() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "wikantik.auth.masterpassword" );
        assertEquals( "", result, "Prohibited variable must return empty string" );
    }

    // -----------------------------------------------------------------------
    // getValue(context, varName, defValue) — default-value path
    // -----------------------------------------------------------------------

    @Test
    void testGetValueWithDefaultReturnsDefaultWhenNotFound() {
        final String result = variableManager.getValue( context, "no_such_variable_xyz", "fallback" );
        assertEquals( "fallback", result, "Should return the supplied default when the variable does not exist" );
    }

    @Test
    void testGetValueWithDefaultReturnsValueWhenFound() {
        // "pagename" is a known system variable
        final String result = variableManager.getValue( context, "pagename", "fallback" );
        assertEquals( "TestPage", result, "Should return the resolved value, not the default" );
    }

    // -----------------------------------------------------------------------
    // getVariable — null-returning wrapper
    // -----------------------------------------------------------------------

    @Test
    void testGetVariableReturnsNullWhenNotFound() {
        final String result = variableManager.getVariable( context, "no_such_variable_xyz" );
        assertNull( result, "getVariable should return null when the variable does not exist" );
    }

    @Test
    void testGetVariableReturnsValueWhenFound() {
        final String result = variableManager.getVariable( context, "pagename" );
        assertEquals( "TestPage", result, "getVariable should return the resolved value" );
    }

    // -----------------------------------------------------------------------
    // Context variable resolver (resolver 2)
    // -----------------------------------------------------------------------

    @Test
    void testContextVariableIsResolved() throws NoSuchVariableException {
        when( context.getVariable( "myCtxVar" ) ).thenReturn( "ctxValue" );
        final String result = variableManager.getValue( context, "myCtxVar" );
        assertEquals( "ctxValue", result, "Variable stored in the Context should be returned" );
    }

    // -----------------------------------------------------------------------
    // Page attribute resolver (resolver 4)
    // -----------------------------------------------------------------------

    @Test
    void testPageAttributeIsResolved() throws NoSuchVariableException {
        when( page.getAttribute( "myAttr" ) ).thenReturn( "attrValue" );
        final String result = variableManager.getValue( context, "myAttr" );
        assertEquals( "attrValue", result, "Page attribute should be returned via the page-attribute resolver" );
    }

    // -----------------------------------------------------------------------
    // Real-page attribute resolver (resolver 5)
    // -----------------------------------------------------------------------

    @Test
    void testRealPageAttributeIsResolved() throws NoSuchVariableException {
        final Page realPage = mock( Page.class );
        when( context.getRealPage() ).thenReturn( realPage );
        when( realPage.getAttribute( "realAttr" ) ).thenReturn( "realValue" );
        final String result = variableManager.getValue( context, "realAttr" );
        assertEquals( "realValue", result, "Real-page attribute should be returned via the real-page resolver" );
    }

    // -----------------------------------------------------------------------
    // Wiki-properties resolver (resolver 6) — only for "wikantik." prefix
    // -----------------------------------------------------------------------

    @Test
    void testWikiPropertyIsResolved() throws NoSuchVariableException {
        final Properties props = new Properties();
        props.setProperty( "wikantik.custom.setting", "propValue" );
        final Engine engineWithProps = MockEngineBuilder.engine().properties( props ).build();
        when( context.getEngine() ).thenReturn( engineWithProps );

        final DefaultVariableManager vm = new DefaultVariableManager( props, pageManager, attachmentManager, filterManager );
        final String result = vm.getValue( context, "wikantik.custom.setting" );
        assertEquals( "propValue", result, "A wikantik.* property should be resolved from the wiki properties" );
    }

    // -----------------------------------------------------------------------
    // Default empty-value resolver (resolver 7) — "error" and "msg"
    // -----------------------------------------------------------------------

    @Test
    void testDefaultEmptyVariableError() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "error" );
        assertEquals( "", result, "The 'error' variable should resolve to empty string by default" );
    }

    @Test
    void testDefaultEmptyVariableMsg() throws NoSuchVariableException {
        final String result = variableManager.getValue( context, "msg" );
        assertEquals( "", result, "The 'msg' variable should resolve to empty string by default" );
    }

    // -----------------------------------------------------------------------
    // Injected PageManager system variables
    // -----------------------------------------------------------------------

    @Test
    void testTotalpagesUsesInjectedPageManager() throws NoSuchVariableException {
        when( pageManager.getTotalPageCount() ).thenReturn( 42 );
        final String result = variableManager.getValue( context, "totalpages" );
        assertEquals( "42", result, "totalpages should return the page count from the injected PageManager" );
    }

    @Test
    void testPageproviderUsesInjectedPageManager() throws NoSuchVariableException {
        when( pageManager.getCurrentProvider() ).thenReturn( "com.example.MyPageProvider" );
        final String result = variableManager.getValue( context, "pageprovider" );
        assertEquals( "com.example.MyPageProvider", result, "pageprovider should return the provider name from the injected PageManager" );
    }

    @Test
    void testPageproviderdescriptionUsesInjectedPageManager() throws NoSuchVariableException {
        when( pageManager.getProviderDescription() ).thenReturn( "My Page Provider v1" );
        final String result = variableManager.getValue( context, "pageproviderdescription" );
        assertEquals( "My Page Provider v1", result, "pageproviderdescription should return description from the injected PageManager" );
    }

    // -----------------------------------------------------------------------
    // Injected AttachmentManager system variables
    // -----------------------------------------------------------------------

    @Test
    void testAttachmentproviderWithNullProvider() throws NoSuchVariableException {
        when( attachmentManager.getCurrentProvider() ).thenReturn( null );
        final String result = variableManager.getValue( context, "attachmentprovider" );
        assertEquals( "-", result, "attachmentprovider should return '-' when provider is null" );
    }

    @Test
    void testAttachmentproviderWithRealProvider() throws NoSuchVariableException {
        final AttachmentProvider provider = mock( AttachmentProvider.class );
        when( attachmentManager.getCurrentProvider() ).thenReturn( provider );
        final String result = variableManager.getValue( context, "attachmentprovider" );
        assertEquals( provider.getClass().getName(), result, "attachmentprovider should return provider class name when provider is present" );
    }

    @Test
    void testAttachmentproviderdescriptionWithNullProvider() throws NoSuchVariableException {
        when( attachmentManager.getCurrentProvider() ).thenReturn( null );
        final String result = variableManager.getValue( context, "attachmentproviderdescription" );
        assertEquals( "-", result, "attachmentproviderdescription should return '-' when provider is null" );
    }

    @Test
    void testAttachmentproviderdescriptionWithRealProvider() throws NoSuchVariableException {
        final AttachmentProvider provider = mock( AttachmentProvider.class );
        when( provider.getProviderInfo() ).thenReturn( "My Attachment Provider v2" );
        when( attachmentManager.getCurrentProvider() ).thenReturn( provider );
        final String result = variableManager.getValue( context, "attachmentproviderdescription" );
        assertEquals( "My Attachment Provider v2", result, "attachmentproviderdescription should return provider info when provider is present" );
    }

    // -----------------------------------------------------------------------
    // Injected FilterManager system variables
    // -----------------------------------------------------------------------

    @Test
    void testPagefiltersWithNoFilters() throws NoSuchVariableException {
        when( filterManager.getFilterList() ).thenReturn( Collections.emptyList() );
        final String result = variableManager.getValue( context, "pagefilters" );
        assertEquals( "", result, "pagefilters should return empty string when no filters are configured" );
    }

    @Test
    void testPagefiltersWithOneFilter() throws NoSuchVariableException {
        final PageFilter filter = mock( PageFilter.class );
        when( filterManager.getFilterList() ).thenReturn( List.of( filter ) );
        final String result = variableManager.getValue( context, "pagefilters" );
        assertEquals( filter.getClass().getName(), result, "pagefilters should return the class name of the single user filter" );
    }

}
