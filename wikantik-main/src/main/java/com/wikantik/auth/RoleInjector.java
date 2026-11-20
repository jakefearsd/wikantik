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

import com.wikantik.api.core.Session;
import com.wikantik.auth.authorize.WebAuthorizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.function.Consumer;

/**
 * Decides which of an {@link Authorizer}'s roles a freshly logged-in session holds, and reports each one to a
 * sink (in {@link DefaultAuthenticationManager}, a {@code PRINCIPAL_ADD} event).
 */
final class RoleInjector {

    private static final Logger LOG = LogManager.getLogger( RoleInjector.class );

    private RoleInjector() {
    }

    /**
     * Reports each role the authorizer says the session holds; when the authorizer is a {@link WebAuthorizer}
     * and {@code roleRequest} is non-null, also each role the container reports for that request.
     *
     * @param authorizer the configured authorizer
     * @param session the session being logged in
     * @param roleRequest the request whose container roles are consulted, or {@code null} to consult the
     *        authorizer only
     * @param sink receives every role to add, in the authorizer's role order
     */
    static void inject( final Authorizer authorizer, final Session session, final HttpServletRequest roleRequest,
                        final Consumer< Principal > sink ) {
        for( final Principal role : authorizer.getRoles() ) {
            if( authorizer.isUserInRole( session, role ) ) {
                sink.accept( role );
                LOG.debug( "Added authorizer role {}.", role.getName() );
            } else if( roleRequest != null && authorizer instanceof WebAuthorizer wa && wa.isUserInRole( roleRequest, role ) ) {
                sink.accept( role );
                LOG.debug( "Added container role {}.", role.getName() );
            }
        }
    }
}
