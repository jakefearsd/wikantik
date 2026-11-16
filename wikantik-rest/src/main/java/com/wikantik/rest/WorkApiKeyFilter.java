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

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.apikeys.ApiKeyServiceHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Turns an {@code Authorization: Bearer} API key on {@code /api/work/*} into a request carrying the verified key
 * record (the request attribute {@code ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD}) and <b>no user principal</b>
 * ({@link WorkKeyRequest}); {@link WorkResourceBase} then derives the actor from that record and from nothing else.
 * Exposing the key owner as the request's principal would let the cookie-based filters downstream log the owner in.
 *
 * <ul>
 *   <li>No bearer header: untouched (session authentication applies, as before).</li>
 *   <li>Work management off: untouched (the resource answers 404).</li>
 *   <li>Unknown, malformed, empty or revoked key: 401 {@code {"error":"Invalid API key"}}.</li>
 *   <li>A valid key whose scope does not cover {@code work}: 403 {@code {"error":"Key not authorized for work"}}.</li>
 *   <li>The key store unreachable: 503 {@code {"error":"Service temporarily unavailable"}} (retryable; never cached).</li>
 *   <li>A valid {@code work} or {@code all} key: the request, wrapped.</li>
 * </ul>
 *
 * <p>A request that carries a bearer header is never handled as a session request after this point: a bad key is
 * refused here rather than falling through to a logged-in cookie. Revocation is immediate because
 * {@link ApiKeyService#revoke} evicts the verify cache entry of the same service instance this filter uses.
 * Denials go to the {@code SecurityLog} logger (key id and scope only, never the token).
 */
public class WorkApiKeyFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( WorkApiKeyFilter.class );
    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );
    private static final String BEARER = "Bearer ";

    /** The one body for a key that is unknown, malformed, empty, revoked or bound to no actor. */
    static final String INVALID_KEY_BODY = "{\"error\":\"Invalid API key\"}";
    static final String SCOPE_BODY = "{\"error\":\"Key not authorized for work\"}";
    /** A key that could not be checked because the key store is unreachable: retryable. */
    static final String UNAVAILABLE_BODY = "{\"error\":\"Service temporarily unavailable\"}";

    private Supplier< ApiKeyService > keys = () -> null;
    private BooleanSupplier enabled = () -> false;

    /** The container's constructor; {@link #init} wires the key service and the feature flag from the engine. */
    public WorkApiKeyFilter() {
    }

    WorkApiKeyFilter( final Supplier< ApiKeyService > keys, final BooleanSupplier enabled ) {
        this.keys = keys;
        this.enabled = enabled;
    }

    @Override
    public void init( final FilterConfig filterConfig ) {
        final Engine engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        keys = () -> ApiKeyServiceHolder.get( engine.getWikiProperties() );
        enabled = () -> engine instanceof WikiEngine we && we.workService() != null;
        LOG.info( "WorkApiKeyFilter initialized" );
    }

    /** Whether the request carries a bearer credential (the scheme is case-insensitive, like the CSRF filter's). */
    static boolean hasBearer( final HttpServletRequest request ) {
        final String auth = request.getHeader( "Authorization" );
        return auth != null && auth.regionMatches( true, 0, BEARER, 0, BEARER.length() )
            || auth != null && auth.trim().equalsIgnoreCase( "Bearer" );
    }

    /** Writes a fixed JSON denial; shared with {@link WorkResourceBase} for a key that resolves to no actor. */
    static void deny( final HttpServletResponse response, final int status, final String body ) throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.getWriter().write( body );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;
        if ( !hasBearer( req ) || !enabled.getAsBoolean() ) {
            chain.doFilter( request, response );
            return;
        }
        final String auth = req.getHeader( "Authorization" );
        final String token = auth.length() > BEARER.length() ? auth.substring( BEARER.length() ).trim() : "";
        final ApiKeyService service = keys.get();
        final Optional< ApiKeyService.Record > found;
        try {
            found = service == null ? Optional.empty() : service.verify( token );
        } catch ( final ApiKeyService.LookupFailedException e ) {
            // Unknown is not invalid: an agent told "Invalid API key" would believe its key is bad.
            LOG.warn( "Work API key lookup failed; answered 503: {}", e.getCause() == null ? e.getMessage()
                : e.getCause().getClass().getSimpleName() );
            deny( resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, UNAVAILABLE_BODY );
            return;
        }
        if ( found.isEmpty() ) {
            SECURITY.warn( "Work API access denied: invalid key, ip={}", req.getRemoteAddr() );
            deny( resp, HttpServletResponse.SC_UNAUTHORIZED, INVALID_KEY_BODY );
            return;
        }
        final ApiKeyService.Record record = found.get();
        if ( !record.scope().matches( ApiKeyService.Scope.WORK ) ) {
            SECURITY.warn( "Work API access denied: key id={} scope={} does not cover work, ip={}", record.id(),
                record.scope().wire(), req.getRemoteAddr() );
            deny( resp, HttpServletResponse.SC_FORBIDDEN, SCOPE_BODY );
            return;
        }
        chain.doFilter( new WorkKeyRequest( req, record ), response );
    }
}
