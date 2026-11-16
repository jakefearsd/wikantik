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
package com.wikantik.auth.apikeys;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.security.Principal;
import java.util.Optional;

/**
 * How the access filters handle a work agent's own token without knowing the work module. An agent token is not an
 * API key: it carries its own prefix, it is verified by the work module through {@link Verifier}, and it is never
 * sent to {@link ApiKeyService}. It names an agent and nothing else, so a request that carries one has no user
 * principal and never becomes a session.
 */
public final class AgentTokenAuth {

    /** What every agent token starts with ({@code com.wikantik.work.AgentTokens.PREFIX}); API keys start {@code wkk_}. */
    public static final String PREFIX = "wka_";

    /** Request attribute holding the verified {@link AgentRef}. */
    public static final String ATTR_AGENT_REF = "wikantik.work.agentRef";

    private static final String BEARER = "Bearer ";

    private AgentTokenAuth() {
    }

    /** A verified agent token: the credential it is and the agent it names. */
    public record AgentRef( long credentialId, long agentId ) {

        /** The identity rate limits and MCP session bindings are kept under; never collides with {@code key:<id>}. */
        public String clientId() {
            return "agent:" + agentId;
        }
    }

    /** Verifies an agent token; supplied by the work module's wiring. */
    @FunctionalInterface
    public interface Verifier {

        /**
         * The agent the token names; empty when it is unknown or revoked.
         *
         * @throws ApiKeyService.LookupFailedException when the credential store could not answer: the caller
         *         answers a retryable 503, never "invalid".
         */
        Optional< AgentRef > verify( String token );
    }

    /**
     * Whether an access filter has already vouched for this request's credential: a verified API key or a verified
     * agent token. Such a request acts as that credential and nothing else, so no cookie on it may log anyone in,
     * join a session or open one. The one test every session-making code path uses.
     */
    public static boolean isCredentialRequest( final HttpServletRequest request ) {
        return request.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) != null
            || request.getAttribute( ATTR_AGENT_REF ) != null;
    }

    /** Whether a bearer value is shaped like an agent token; says nothing about validity. */
    public static boolean isAgentToken( final String bearer ) {
        return bearer != null && bearer.startsWith( PREFIX );
    }

    /** The bearer value of the {@code Authorization} header, or null when the request carries none. */
    public static String bearer( final HttpServletRequest request ) {
        final String header = request.getHeader( "Authorization" );
        return header == null || !header.startsWith( BEARER ) ? null : header.substring( BEARER.length() );
    }

    /**
     * The request to forward for an agent token, or empty when the token gives no access here: an agent token means
     * something only on a surface that requires the {@code work} scope and has a verifier, so anywhere else it is
     * not even verified, and an unknown or revoked one is nobody. The caller then gives its ordinary refusal.
     *
     * @throws ApiKeyService.LookupFailedException when the credential store could not answer
     */
    public static Optional< Request > accept( final Verifier verifier, final boolean workSurface,
                                              final HttpServletRequest request, final String token ) {
        if ( verifier == null || !workSurface ) {
            return Optional.empty();
        }
        return verifier.verify( token ).map( ref -> new Request( request, ref ) );
    }

    /** The request as forwarded for a verified agent: it carries the {@link AgentRef} and no principal. */
    public static final class Request extends HttpServletRequestWrapper {

        private final AgentRef ref;

        public Request( final HttpServletRequest request, final AgentRef ref ) {
            super( request );
            this.ref = ref;
        }

        /** The verified agent this request acts as. */
        public AgentRef ref() {
            return ref;
        }

        @Override
        public Object getAttribute( final String name ) {
            return ATTR_AGENT_REF.equals( name ) ? ref : super.getAttribute( name );
        }

        @Override
        public Principal getUserPrincipal() {
            return null;
        }

        @Override
        public String getRemoteUser() {
            return null;
        }

        @Override
        public boolean isUserInRole( final String role ) {
            return false;
        }

        @Override
        public String getAuthType() {
            return ApiKeyPrincipalRequest.AUTH_TYPE;
        }
    }
}
