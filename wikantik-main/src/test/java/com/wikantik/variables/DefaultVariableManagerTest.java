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
import com.wikantik.api.exceptions.NoSuchVariableException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.modules.InternalModule;
import com.wikantik.api.spi.Wiki;
import com.wikantik.filters.FilterManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Properties;


public class DefaultVariableManagerTest {

    static VariableManager m_variableManager;
    static Context m_context;

    static final String PAGE_NAME = "TestPage";

    @BeforeAll
    public static void setUp() {
        final TestEngine testEngine = TestEngine.build();
        m_variableManager = new DefaultVariableManager( TestEngine.getTestProperties() );
        m_context = Wiki.context().create( testEngine, Wiki.contents().page( testEngine, PAGE_NAME ) );
    }

    @Test
    public void testIllegalInsert1() {
        Assertions.assertThrows( IllegalArgumentException.class, () -> m_variableManager.parseAndGetValue( m_context, "" ) );
    }

    /**
     * SECURITY: page markup ([{$wikantik.…}]) must never expand a secret-bearing
     * property (OIDC secret, connector AES key, SCIM token, DB/SAML passwords).
     */
    @Test
    public void secretBearingPropertyNamesAreRefused() {
        for ( final String secret : new String[] {
                "wikantik.connectors.crypto.key",
                "wikantik.auth.masterpassword",
                "wikantik.scim.token",
                "wikantik.sso.saml.keystorePassword",
                "wikantik.sso.saml.privateKeyPassword",
                "wikantik.userdatabase.password",
                "wikantik.sso.oidc.clientSecret",
                "wikantik.datasource.credential" } ) {
            Assertions.assertTrue( DefaultVariableManager.isSecretProperty( secret ),
                    "must be treated as secret: " + secret );
        }
    }

    @Test
    public void ordinaryPropertyNamesAreNotRefused() {
        for ( final String ok : new String[] {
                "wikantik.baseURL",
                "wikantik.applicationName",
                "wikantik.frontPage",
                "wikantik.chunker.max_tokens",
                "wikantik.search.dense.backend" } ) {
            Assertions.assertFalse( DefaultVariableManager.isSecretProperty( ok ),
                    "must not be treated as secret: " + ok );
        }
    }

    @Test
    public void testIllegalInsert2() {
        Assertions.assertThrows( IllegalArgumentException.class, () -> m_variableManager.parseAndGetValue( m_context, "{$" ) );
    }

    @Test
    public void testIllegalInsert3() {
        Assertions.assertThrows( IllegalArgumentException.class, () -> m_variableManager.parseAndGetValue( m_context, "{$pagename" ) );
    }

    @Test
    public void testIllegalInsert4() {
        Assertions.assertThrows( IllegalArgumentException.class, () -> m_variableManager.parseAndGetValue( m_context, "{$}" ) );
    }

    @Test
    public void testNonExistantVariable() {
        Assertions.assertThrows( NoSuchVariableException.class, () -> m_variableManager.parseAndGetValue( m_context, "{$no_such_variable}" ) );
    }

    @Test
    public void testPageName() throws Exception {
        final String res = m_variableManager.getValue( m_context, "pagename" );
        Assertions.assertEquals( PAGE_NAME, res );
    }

    @Test
    public void testPageName2() throws Exception {
        final String res =  m_variableManager.parseAndGetValue( m_context, "{$  pagename  }" );
        Assertions.assertEquals( PAGE_NAME, res );
    }

    @Test
    public void testMixedCase() throws Exception {
        final String res =  m_variableManager.parseAndGetValue( m_context, "{$PAGeNamE}" );
        Assertions.assertEquals( PAGE_NAME, res );
    }

    @Test
    public void testExpand1() {
        final String res = m_variableManager.expandVariables( m_context, "Testing {$pagename}..." );
        Assertions.assertEquals( "Testing "+PAGE_NAME+"...", res );
    }

    @Test
    public void testExpand2() {
        final String res = m_variableManager.expandVariables( m_context, "{$pagename} tested..." );
        Assertions.assertEquals( PAGE_NAME+" tested...", res );
    }

    @Test
    public void testExpand3() {
        final String res = m_variableManager.expandVariables( m_context, "Testing {$pagename}, {$applicationname}" );
        Assertions.assertEquals( "Testing "+PAGE_NAME+", Wikantik", res );
    }

    @Test
    public void testExpand4() {
        final String res = m_variableManager.expandVariables( m_context, "Testing {}, {{{}" );
        Assertions.assertEquals( "Testing {}, {{{}", res );
    }

    // ---- SystemVariables getters (reflection-dispatched via getValue()) ----

    @Test
    public void testJspwikiversion() throws Exception {
        final String res = m_variableManager.getValue( m_context, "jspwikiversion" );
        Assertions.assertNotNull( res );
        Assertions.assertFalse( res.isBlank() );
    }

    @Test
    public void testEncoding() throws Exception {
        final String res = m_variableManager.getValue( m_context, "encoding" );
        Assertions.assertEquals( m_context.getEngine().getContentEncoding().displayName(), res );
    }

    @Test
    public void testInterwikilinksListsConfiguredLinks() throws Exception {
        // TestEngine's default properties declare several wikantik.interWikiRef.* entries.
        final String res = m_variableManager.getValue( m_context, "interwikilinks" );
        Assertions.assertTrue( res.contains( "<table" ), "expected a rendered table, got: " + res );
        Assertions.assertTrue( res.contains( "Wikipedia" ), "expected a known InterWiki name, got: " + res );
    }

    @Test
    public void testInterwikilinksEmptyWhenNoneConfigured() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        for ( final String key : new ArrayList<>( props.stringPropertyNames() ) ) {
            if ( key.startsWith( "wikantik.interWikiRef." ) ) {
                props.remove( key );
            }
        }
        final TestEngine bareEngine = TestEngine.build( props );
        try {
            final VariableManager vm = new DefaultVariableManager( props );
            final Context ctx = Wiki.context().create( bareEngine, Wiki.contents().page( bareEngine, PAGE_NAME ) );
            final String res = vm.getValue( ctx, "interwikilinks" );
            Assertions.assertEquals( "(none configured)", res );
        } finally {
            bareEngine.stop();
        }
    }

    @Test
    public void testInlinedimages() throws Exception {
        final String res = m_variableManager.getValue( m_context, "inlinedimages" );
        Assertions.assertNotNull( res );
    }

    @Test
    public void testPluginpath() throws Exception {
        final String res = m_variableManager.getValue( m_context, "pluginpath" );
        Assertions.assertNotNull( res );
    }

    @Test
    public void testBaseurl() throws Exception {
        final String res = m_variableManager.getValue( m_context, "baseurl" );
        Assertions.assertNotNull( res );
    }

    @Test
    public void testUptimeFormatsElapsedTime() throws Exception {
        final String res = m_variableManager.getValue( m_context, "uptime" );
        Assertions.assertTrue( res.matches( "\\d+d, \\d+h \\d+m \\d+s" ), "unexpected uptime format: " + res );
    }

    @Test
    public void testLoginstatusForAnonymousSession() throws Exception {
        final String res = m_variableManager.getValue( m_context, "loginstatus" );
        Assertions.assertNotNull( res );
        Assertions.assertFalse( res.isBlank() );
    }

    @Test
    public void testUsername() throws Exception {
        final String res = m_variableManager.getValue( m_context, "username" );
        Assertions.assertNotNull( res );
    }

    @Test
    public void testRequestcontext() throws Exception {
        final String res = m_variableManager.getValue( m_context, "requestcontext" );
        Assertions.assertNotNull( res );
    }

    /** Marker-free test filter: should appear in the {@code pagefilters} listing. */
    public static class PlainTestFilter implements PageFilter {
    }

    /** {@link InternalModule}-marked test filter: must be excluded from the listing. */
    public static class InternalTestFilter implements PageFilter, InternalModule {
    }

    @Test
    public void testPagefiltersListsPlainFiltersAndSkipsInternalModules() throws Exception {
        final com.wikantik.WikiEngine engine = ( com.wikantik.WikiEngine ) m_context.getEngine();
        final FilterManager fm = engine.getManager( FilterManager.class );
        fm.addPageFilter( new PlainTestFilter(), 0 );
        fm.addPageFilter( new PlainTestFilter(), 0 );
        fm.addPageFilter( new InternalTestFilter(), 0 );

        final String res = m_variableManager.getValue( m_context, "pagefilters" );

        Assertions.assertTrue( res.contains( "PlainTestFilter" ), "expected the plain filter, got: " + res );
        Assertions.assertFalse( res.contains( "InternalTestFilter" ), "InternalModule filter must be excluded: " + res );
        // Two PlainTestFilter entries -> the comma-separator branch must have fired.
        Assertions.assertTrue( res.contains( "," ), "expected a comma-separated list, got: " + res );
    }

}
