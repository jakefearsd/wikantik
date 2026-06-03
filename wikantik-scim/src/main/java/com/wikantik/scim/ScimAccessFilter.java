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
package com.wikantik.scim;

import com.google.gson.Gson;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ScimAccessFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( ScimAccessFilter.class );
    private static final String BEARER = "Bearer ";
    private static final Gson GSON = new Gson();

    private volatile String token;

    public ScimAccessFilter() {}
    public ScimAccessFilter( final String token ) { this.token = token; }

    @Override
    public void init( final FilterConfig cfg ) {
        if ( token == null ) {
            String t = System.getProperty( "wikantik.scim.token" );
            if ( ( t == null || t.isBlank() ) && cfg != null ) t = cfg.getInitParameter( "wikantik.scim.token" );
            this.token = t;
            if ( t == null || t.isBlank() ) {
                LOG.warn( "ScimAccessFilter: no wikantik.scim.token configured — all SCIM requests will be denied." );
            }
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;
        final String auth = req.getHeader( "Authorization" );
        if ( token != null && !token.isBlank() && auth != null && auth.startsWith( BEARER )
                && constantTimeEquals( auth.substring( BEARER.length() ), token ) ) {
            chain.doFilter( request, response );
            return;
        }
        resp.setStatus( 401 );
        resp.setContentType( "application/scim+json" );
        resp.getWriter().write( GSON.toJson( ScimError.body( 401, null, "invalid or missing bearer token" ) ) );
    }

    private static boolean constantTimeEquals( final String a, final String b ) {
        return MessageDigest.isEqual( a.getBytes( StandardCharsets.UTF_8 ), b.getBytes( StandardCharsets.UTF_8 ) );
    }
}
