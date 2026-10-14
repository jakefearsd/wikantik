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
package com.wikantik.auth;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.content.PageRenamer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The main-module authorship writers record the login (admin's full name is "Administrator"). */
class AuthorKeyWritersTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    @AfterEach
    void cleanup() {
        engine.deleteQuietly( "AuthorKeyPage", "AuthorKeyRenameFrom", "AuthorKeyRenameTo" );
    }

    private PageManager pages() {
        return engine.getManager( PageManager.class );
    }

    /** Logs the shared mock session in as the given account (re-logging whoever was on it) and returns a context over {@code page}. */
    private Context contextAs( final String login, final String password, final Page page ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Session session = SessionMonitor.getInstance( engine ).find( request.getSession() );
        engine.getManager( AuthenticationManager.class ).login( session, request, login, password );
        return Wiki.context().create( engine, request, page );
    }

    @Test
    void aSavedPageDefaultsItsAuthorToTheSavingUsersLogin() throws Exception {
        engine.saveText( "AuthorKeyPage", "hello" );   // logs the shared session in as admin
        assertEquals( Users.ADMIN, pages().getPage( "AuthorKeyPage" ).getAuthor() );
    }

    @Test
    void renameRecordsTheRenamersLogin() throws Exception {
        engine.saveTextAsJanne( "AuthorKeyRenameFrom", "body" );   // the previous version's author is "janne"
        final Context context = contextAs( Users.ADMIN, Users.ADMIN_PASS, pages().getPage( "AuthorKeyRenameFrom" ) );

        engine.getManager( PageRenamer.class ).renamePage( context, "AuthorKeyRenameFrom", "AuthorKeyRenameTo", false );

        assertEquals( Users.ADMIN, pages().getPage( "AuthorKeyRenameTo" ).getAuthor() );
    }

    @Test
    void aGroupRecordsItsCreatorAndModifierLogin() throws Exception {
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group group = groups.parseGroup( "AuthorKeyGroup", "janne", true );
        groups.setGroup( WikiSessionTest.adminSession( engine ), group );
        try {
            assertEquals( Users.ADMIN, groups.getGroup( "AuthorKeyGroup" ).getCreator() );
            assertEquals( Users.ADMIN, groups.getGroup( "AuthorKeyGroup" ).getModifier() );
        } finally {
            groups.removeGroup( "AuthorKeyGroup" );
        }
    }
}
