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

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.SessionMonitor;
import com.wikantik.auth.Users;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Test helper: saves a page whose version author is exactly the string given, the way a legacy writer
 * (a full name), another account (a login) or an MCP client (an agent label) would have recorded it.
 *
 * <p>Unlike {@code TestEngine#saveText} this authenticates a throw-away <em>isolated</em> mock session, so it
 * never changes who the shared {@code HttpMockFactory.SHARED_SESSION_ID} session is logged in as.</p>
 */
public final class AuthoredPages {

    private AuthoredPages() {
    }

    public static void save( final TestEngine engine, final String pageName, final String content,
                             final String storedAuthor ) throws WikiException {
        final HttpServletRequest request = HttpMockFactory.createIsolatedHttpRequest( "/wiki/" + pageName );
        final Session session = SessionMonitor.getInstance( engine ).find( request.getSession() );
        engine.getManager( AuthenticationManager.class ).login( session, request, Users.ADMIN, Users.ADMIN_PASS );

        final Page page = Wiki.contents().page( engine, pageName );
        page.setAuthor( storedAuthor );
        final Context context = Wiki.context().create( engine, request, page );
        engine.getManager( PageManager.class ).saveText( context, content );
    }
}
