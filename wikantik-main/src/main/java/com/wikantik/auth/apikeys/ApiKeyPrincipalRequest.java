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

/**
 * Wraps a servlet request so that {@link #getUserPrincipal()} and
 * {@link #getRemoteUser()} reflect the principal bound to a verified API key.
 *
 * <p>MCP and tool-server access filters use this wrapper after resolving a
 * Bearer token to an {@link ApiKeyService.Record}. Downstream handlers call
 * {@link Principal#getName()} on the wrapped principal to build their
 * authenticated {@code Session}, which is what JAAS/ACL decisions flow from.
 */
public class ApiKeyPrincipalRequest extends HttpServletRequestWrapper {

    /** Request attribute holding the resolved {@link ApiKeyService.Record}, for auditing. */
    public static final String ATTR_API_KEY_RECORD = "wikantik.apikey.record";

    private final Principal principal;

    public ApiKeyPrincipalRequest( final HttpServletRequest request, final String login ) {
        super( request );
        this.principal = new NamedPrincipal( login );
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public String getRemoteUser() {
        return principal.getName();
    }

    @Override
    public String getAuthType() {
        return "API_KEY";
    }

    private record NamedPrincipal( String name ) implements Principal {
        @Override public String getName() { return name; }
        @Override public String toString() { return "ApiKey[" + name + "]"; }
    }
}
