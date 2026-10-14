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
package com.wikantik.rest;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The admin writers record the admin's login as the revision author, never the full name ("Administrator"). */
class AdminAuthorResolutionTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @BeforeEach
    void anonymous() {
        SessionMonitor.getInstance( engine ).remove( HttpMockFactory.SHARED_SESSION_ID );
    }

    private static void loginAdmin() throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = WikiSession.getWikiSession( engine, request );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );
    }

    private static < T extends GenericServlet > T initialised( final T servlet ) throws Exception {
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
        return servlet;
    }

    @Test
    void adminClusterRenameAuthorIsTheAdminsLogin() throws Exception {
        loginAdmin();
        assertEquals( Users.ADMIN, initialised( new AdminClusterResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
    }

    @Test
    void adminDerivedReflowAuthorIsTheAdminsLogin() throws Exception {
        loginAdmin();
        assertEquals( Users.ADMIN, initialised( new AdminDerivedResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
    }

    @Test
    void anUnauthenticatedCallerStillFallsBackToSystem() throws Exception {
        assertEquals( "system", initialised( new AdminClusterResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
        assertEquals( "system", initialised( new AdminDerivedResource() ).resolveAdminAuthor( HttpMockFactory.createHttpRequest() ) );
    }
}
