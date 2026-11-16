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

import com.wikantik.auth.apikeys.AgentTokenAuth;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * A request that authenticated with a work API key. It carries the verified key record as the one request attribute
 * {@link ApiKeyPrincipalRequest#ATTR_API_KEY_RECORD} and exposes <b>no</b> user principal and no remote user: the
 * owner of a key must never be visible to a later filter or login module, or the key could be turned into a login
 * session of its owner. {@link #getAuthType()} says {@code API_KEY} so session lookup treats the request as stateless.
 */
final class WorkKeyRequest extends HttpServletRequestWrapper {

    private final ApiKeyService.Record record;
    private final AgentTokenAuth.AgentRef agent;

    /** A request carrying a verified personal API key with the work scope. */
    WorkKeyRequest( final HttpServletRequest request, final ApiKeyService.Record record ) {
        super( request );
        this.record = record;
        this.agent = null;
    }

    /** A request carrying a verified agent token. */
    WorkKeyRequest( final HttpServletRequest request, final AgentTokenAuth.AgentRef agent ) {
        super( request );
        this.record = null;
        this.agent = agent;
    }

    /** The identity the per-credential rate limit is kept under: {@code key:<id>} or {@code agent:<id>}. */
    String clientId() {
        return agent != null ? agent.clientId() : "key:" + record.id();
    }

    @Override
    public Object getAttribute( final String name ) {
        if ( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD.equals( name ) ) {
            return record;
        }
        return AgentTokenAuth.ATTR_AGENT_REF.equals( name ) ? agent : super.getAttribute( name );
    }

    @Override
    public java.security.Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public String getAuthType() {
        return ApiKeyPrincipalRequest.AUTH_TYPE;
    }
}
