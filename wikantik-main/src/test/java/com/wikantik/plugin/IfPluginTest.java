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
package com.wikantik.plugin;

import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiContext;
import com.wikantik.WikiSession;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.Users;
import com.wikantik.pages.PageManager;
import com.wikantik.render.RenderingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class IfPluginTest {

    static TestEngine testEngine = TestEngine.build();

    @AfterEach
    public void tearDown() throws Exception {
        testEngine.getManager( PageManager.class ).deletePage( "Test" );
    }

    /**
     * Returns a {@link WikiContext} for the given page, with user {@link Users#JANNE} logged in.
     *
     * @param page given {@link Page}.
     * @return {@link WikiContext} associated to given {@link Page}.
     * @throws WikiException problems while logging in.
     */
    Context getJanneBasedWikiContextFor( final Page page ) throws WikiException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session =  WikiSession.getWikiSession( testEngine, request );
        testEngine.getManager( AuthenticationManager.class ).login( session, request, Users.JANNE, Users.JANNE_PASS );
        return Wiki.context().create( testEngine, request, page );
    }

    /**
     * Checks that user access is granted.
     *
     * @throws WikiException test Assertions.failing.
     */
    @Test
    void testIfPluginUserAllowed() throws WikiException {
        final String src = "[{IfPlugin user='Janne Jalkanen'\n\nContent visible for Janne Jalkanen}]";
        final String expected = "<p>Content visible for Janne Jalkanen</p>\n";

        testEngine.saveText( "Test", src );
        final Page page = testEngine.getManager( PageManager.class ).getPage( "Test", PageProvider.LATEST_VERSION );
        final Context context = getJanneBasedWikiContextFor( page );

        final String res = testEngine.getManager( RenderingManager.class ).getHTML( context, page );
        Assertions.assertEquals( expected, res );
    }

    /**
     * Checks that user access is forbidden.
     *
     * @throws WikiException test Assertions.failing.
     */
    @Test
    void testIfPluginUserNotAllowed() throws WikiException {
        final String src = "[{IfPlugin user='!Janne Jalkanen'\n\nContent NOT visible for Janne Jalkanen}]";
        final String expected = "\n";

        testEngine.saveText( "Test", src );
        final Page page = testEngine.getManager( PageManager.class ).getPage( "Test", PageProvider.LATEST_VERSION );
        final Context context = getJanneBasedWikiContextFor( page );

        final String res = testEngine.getManager( RenderingManager.class ).getHTML( context, page );
        Assertions.assertEquals( expected, res );
    }

    /**
     * Checks that IP address is granted.
     *
     * @throws WikiException test Assertions.failing.
     */
    @Test
    void testIfPluginIPAllowed() throws WikiException {
        final String src = "[{IfPlugin ip='127.0.0.1'\n\nContent visible for 127.0.0.1}]";
        final String expected = "<p>Content visible for 127.0.0.1</p>\n";

        testEngine.saveText( "Test", src );
        final Page page = testEngine.getManager( PageManager.class ).getPage( "Test", PageProvider.LATEST_VERSION );
        final Context context = getJanneBasedWikiContextFor( page );

        final String res = testEngine.getManager( RenderingManager.class ).getHTML( context, page );
        Assertions.assertEquals( expected, res );
    }
    
     /**
     * Checks that IP address is granted with netmask
     *
     * @throws WikiException test Assertions.failing.
     */
    @Test
    void testIfPluginIPAllowedMask() throws WikiException {
        final String src = "[{IfPlugin ip='127.0.0.0/24'\n\nContent visible for 127.0.0.0/24}]";
        final String expected = "<p>Content visible for 127.0.0.0/24</p>\n";

        testEngine.saveText( "Test", src );
        final Page page = testEngine.getManager( PageManager.class ).getPage( "Test", PageProvider.LATEST_VERSION );
        final Context context = getJanneBasedWikiContextFor( page );

        final String res = testEngine.getManager( RenderingManager.class ).getHTML( context, page );
        Assertions.assertEquals( expected, res );
    }
    
    /**
     * Checks that IP address is granted with netmask
     *
     * @throws WikiException test Assertions.failing.
     */
    @Test
    void testIfPluginIPAllowedMaskDeny() throws WikiException {
        final String src = "[{IfPlugin ip='192.168.1.1/24'\n\nContent visible for 192.168.1.1/24}]";
        final String expected = "\n";

        testEngine.saveText( "Test", src );
        final Page page = testEngine.getManager( PageManager.class ).getPage( "Test", PageProvider.LATEST_VERSION );
        final Context context = getJanneBasedWikiContextFor( page );

        final String res = testEngine.getManager( RenderingManager.class ).getHTML( context, page );
        Assertions.assertEquals( expected, res );
    }

    /**
     * Checks that IP address is granted.
     *
     * @throws WikiException test Assertions.failing.
     */
    @Test
    void testIfPluginIPNotAllowed() throws WikiException {
        final String src = "[{IfPlugin ip='!127.0.0.1'\n\nContent NOT visible for 127.0.0.1}]";
        final String expected = "\n";

        testEngine.saveText( "Test", src );
        final Page page = testEngine.getManager( PageManager.class ).getPage( "Test", PageProvider.LATEST_VERSION );
        final Context context = getJanneBasedWikiContextFor( page );

        final String res = testEngine.getManager( RenderingManager.class ).getHTML( context, page );
        Assertions.assertEquals( expected, res );
    }

}
