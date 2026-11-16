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

import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.work.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.function.Function;

/** Who is calling a work servlet, and the request-shape questions {@link WorkResourceBase} asks about the call. */
final class WorkCallers {

    private static final Logger LOG = LogManager.getLogger( WorkCallers.class );

    private WorkCallers() {
    }

    /**
     * The caller, or null once an error has been written: 401 for no actor ({@code Invalid API key}, the one body of
     * {@link WorkApiKeyFilter}, when the request carried a bearer credential, so an unknown, revoked and retired-agent
     * key look the same; {@code Authentication required.} for an anonymous session) and 500 when the key lookup failed.
     */
    static Actor authenticate( final Function< HttpServletRequest, Actor > resolve, final HttpServletRequest request,
                               final HttpServletResponse response ) throws IOException {
        final Actor actor;
        try {
            actor = resolve.apply( request );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Work API could not resolve the caller: {}", e.getMessage(), e );
            RestJson.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error." );
            return null;
        }
        if ( actor != null ) {
            return actor;
        }
        if ( WorkApiKeyFilter.hasBearer( request )
                || request.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) != null ) {
            WorkApiKeyFilter.deny( response, HttpServletResponse.SC_UNAUTHORIZED, WorkApiKeyFilter.INVALID_KEY_BODY );
        } else {
            RestJson.sendError( response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required." );
        }
        return null;
    }

    /** An item PATCH or a hand-off: the two writes that carry the version the caller saw. */
    static boolean mergesAgainstCurrent( final HttpServletRequest request, final String[] segs ) {
        final String method = request.getMethod();
        return segs.length == 2 && "items".equals( segs[ 0 ] ) && "PATCH".equalsIgnoreCase( method )
            || segs.length == 3 && "items".equals( segs[ 0 ] ) && "handoff".equals( segs[ 2 ] )
                && "POST".equalsIgnoreCase( method );
    }
}
