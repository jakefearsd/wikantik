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
package com.wikantik.mcp;

import com.wikantik.http.ratelimit.SlidingWindowRateLimiter;
import com.wikantik.auth.apikeys.AbstractApiAccessFilter;
import com.wikantik.auth.apikeys.ApiKeyService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that restricts access to the MCP endpoint.
 *
 * <p>A request is allowed if it satisfies EITHER condition:
 * <ul>
 *   <li>A valid Bearer token matching a <strong>DB-minted</strong> API key (via {@link ApiKeyService})</li>
 *   <li>Source IP within one of the configured CIDR allowlist entries</li>
 * </ul>
 *
 * <p>If none of DB keys, CIDR allowlist, or {@code mcp.access.allowUnrestricted=true} is configured,
 * all traffic is <strong>rejected (fail-closed)</strong> with HTTP 503.
 *
 * <p>All authorization mechanics (bearer verification, CIDR matching, rate limiting,
 * fail-closed handling) live in {@link AbstractApiAccessFilter}; this class only binds
 * the MCP-specific scope, property names, and denial payloads.</p>
 */
public class McpAccessFilter extends AbstractApiAccessFilter {

    private static final Surface SURFACE = new Surface(
            "MCP",
            "mcp.access",
            ApiKeyService.Scope.MCP,
            Outcome.Denied.of( HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "{\"error\":\"mcp_access_unconfigured\","
                  + "\"detail\":\"No DB-minted API key, CIDR allowlist, or mcp.access.allowUnrestricted=true. "
                  + "Mint a key at /admin/apikeys, configure mcp.access.allowedCidrs, or set "
                  + "mcp.access.allowUnrestricted=true in wikantik-custom.properties to enable /wikantik-admin-mcp.\"}",
                    new Outcome.Header( "Retry-After", "86400" ) ),
            "{\"error\":\"Key not authorized for MCP\"}" );

    public McpAccessFilter( final McpConfig config, final SlidingWindowRateLimiter rateLimiter ) {
        this( config, rateLimiter, null );
    }

    public McpAccessFilter( final McpConfig config, final SlidingWindowRateLimiter rateLimiter,
                            final ApiKeyService apiKeyService ) {
        super( SURFACE, config.allowedCidrs(), config.allowUnrestricted(),
                rateLimiter::tryAcquire, apiKeyService );
    }
}
