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
package org.apache.wiki.auth.login;

import org.apache.wiki.api.core.Engine;
import org.apache.wiki.auth.UserManager;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;


/**
 * Handles logins made from inside the wiki application, rather than via the web container. This handler is instantiated in
 * {@link org.apache.wiki.auth.AuthenticationManager#login(org.apache.wiki.api.core.Session,HttpServletRequest, String, String)}.
 * If container-managed authentication is used, the {@link WebContainerCallbackHandler}is used instead. This callback handler is
 * designed to be used with {@link UserDatabaseLoginModule}.
 *
 * @since 2.3
 */
public class WikiCallbackHandler implements CallbackHandler {

    private final HttpServletRequest request;
    private final Engine engine;
    private final String password;
    private final String username;

    /**
     *  Create a new callback handler.
     *
     * @param engine the Engine
     * @param request the user's HTTP request. If passed as <code>null</code>, later requests for {@link HttpRequestCallback} will return an UnsupportedCallbackException
     * @param username the username
     * @param password the password
     */
    public WikiCallbackHandler( final Engine engine, final HttpServletRequest request, final String username, final String password ) {
        this.request = request;
        this.engine = engine;
        this.username = username;
        this.password = password;
    }

    /**
     * @see javax.security.auth.callback.CallbackHandler#handle(javax.security.auth.callback.Callback[])
     * 
     * {@inheritDoc}
     */
    @Override public void handle( final Callback[] callbacks ) throws IOException, UnsupportedCallbackException {
        for( final Callback callback : callbacks ) {
            if( callback instanceof HttpRequestCallback httpRequestCallback ) {
                httpRequestCallback.setRequest( request );
            } else if( callback instanceof WikiEngineCallback wikiEngineCallback ) {
                wikiEngineCallback.setEngine( engine );
            } else if( callback instanceof UserDatabaseCallback userDatabaseCallback ) {
                userDatabaseCallback.setUserDatabase( engine.getManager( UserManager.class ).getUserDatabase() );
            } else if( callback instanceof NameCallback nameCallback ) {
                nameCallback.setName( username );
            } else if( callback instanceof PasswordCallback passwordCallback ) {
                passwordCallback.setPassword( password.toCharArray() );
            } else {
                throw new UnsupportedCallbackException( callback );
            }
        }
    }
}
