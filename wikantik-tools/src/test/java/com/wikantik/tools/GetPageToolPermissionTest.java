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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code get_page} against a real engine, real inline ACLs and real sessions (counterpart of
 * SearchWikiToolViewPermissionTest): the production view/edit gates, not the test stubs the other get_page tests use.
 * A non-empty ACL is deny-by-default, so {@code [{ALLOW view Admin}]} hides a page from everyone else.
 */
class GetPageToolPermissionTest {

    private static TestEngine testEngine;

    private GetPageTool tool;

    @BeforeAll
    static void startEngine() {
        testEngine = TestEngine.build();
    }

    @AfterAll
    static void cleanWorkDir() {
        TestEngine.emptyWorkDir();
    }

    @BeforeEach
    void setUp() throws Exception {
        testEngine.saveText( "GpOpen", "open body" );
        testEngine.saveText( "GpSecret", "[{ALLOW view Admin}]\n\nclassified body" );
        testEngine.saveText( "GpReadOnly", "[{ALLOW view Anonymous,Authenticated,Admin}]\n[{ALLOW edit Admin}]\n\nread-only body" );
        tool = new GetPageTool( testEngine, new ToolsConfig( new Properties() ) );
    }

    @AfterEach
    void tearDown() {
        testEngine.deleteTestPage( "GpOpen" );
        testEngine.deleteTestPage( "GpSecret" );
        testEngine.deleteTestPage( "GpReadOnly" );
    }

    private static HttpServletRequest anonymousRequest() {
        return HttpMockFactory.createIsolatedHttpRequest( "/tools/page/X" );
    }

    private static HttpServletRequest adminRequest() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/tools/page/X" );
        final Session session = Wiki.session().find( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
        assertTrue( session.isAuthenticated(), "fixture: admin login must succeed" );
        return request;
    }

    @Test
    void anOpenPageIsReadableByAnAnonymousCaller() {
        final Map< String, Object > out = tool.execute( "GpOpen", 0, anonymousRequest() );
        assertNotNull( out );
        assertEquals( "GpOpen", out.get( "name" ) );
        assertEquals( "open body", out.get( "text" ).toString().strip() );
    }

    @Test
    void aViewRestrictedPageIsRefusedToAnAnonymousCallerWithoutLeakingItsBody() {
        final PageAccessDeniedException denied =
                assertThrows( PageAccessDeniedException.class, () -> tool.execute( "GpSecret", 0, anonymousRequest() ) );
        assertFalse( String.valueOf( denied.getMessage() ).contains( "classified" ) );
    }

    @Test
    void aViewRestrictedPageIsReadableByAnAdministrator() throws Exception {
        final Map< String, Object > out = tool.execute( "GpSecret", 0, adminRequest() );
        assertTrue( out.get( "text" ).toString().contains( "classified body" ) );
    }

    @Test
    void accessRuleLinesAreHiddenFromAReaderWhoCannotEditAndKeptForOneWhoCan() throws Exception {
        final String reader = tool.execute( "GpReadOnly", 0, anonymousRequest() ).get( "text" ).toString();
        assertTrue( reader.contains( "read-only body" ) );
        assertFalse( reader.contains( "ALLOW" ), "a reader without edit rights must not see who is on the ACL: " + reader );

        final String editor = tool.execute( "GpReadOnly", 0, adminRequest() ).get( "text" ).toString();
        assertTrue( editor.contains( "[{ALLOW edit Admin}]" ), "an editor sees the rules: " + editor );
    }
}
