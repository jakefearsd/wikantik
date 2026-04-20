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

import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tiny but critical: downstream JAAS and ACL checks read the wrapped
 * principal, so these guarantees must never regress silently.
 */
class ApiKeyPrincipalRequestTest {

    @Test
    void wrapsPrincipalWithProvidedLogin() {
        final HttpServletRequest delegate = mock( HttpServletRequest.class );
        final ApiKeyPrincipalRequest req = new ApiKeyPrincipalRequest( delegate, "alice" );

        final Principal p = req.getUserPrincipal();
        assertNotNull( p );
        assertEquals( "alice", p.getName() );
        assertEquals( "alice", req.getRemoteUser() );
        assertEquals( "API_KEY", req.getAuthType(),
                "Auth type must distinguish API-key auth in access logs" );
    }

    @Test
    void principalNameAppearsInToString() {
        final HttpServletRequest delegate = mock( HttpServletRequest.class );
        final ApiKeyPrincipalRequest req = new ApiKeyPrincipalRequest( delegate, "bob" );

        assertTrue( req.getUserPrincipal().toString().contains( "bob" ),
                "Principal toString should include the login for log readability" );
    }

    @Test
    void delegatesNonOverriddenMethodsToWrappedRequest() {
        final HttpServletRequest delegate = mock( HttpServletRequest.class );
        when( delegate.getMethod() ).thenReturn( "POST" );
        when( delegate.getRequestURI() ).thenReturn( "/mcp/jsonrpc" );

        final ApiKeyPrincipalRequest req = new ApiKeyPrincipalRequest( delegate, "carol" );

        assertEquals( "POST", req.getMethod() );
        assertEquals( "/mcp/jsonrpc", req.getRequestURI() );
    }

    @Test
    void setAttributePassesThroughToDelegate() {
        final HttpServletRequest delegate = mock( HttpServletRequest.class );
        final ApiKeyPrincipalRequest req = new ApiKeyPrincipalRequest( delegate, "dave" );

        req.setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, "sentinel" );

        verify( delegate ).setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, "sentinel" );
    }
}
