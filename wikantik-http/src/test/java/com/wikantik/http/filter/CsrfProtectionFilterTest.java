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
package com.wikantik.http.filter;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfProtectionFilterTest {

    @Test
    void testIsPostReturnsTrueForPost() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "POST" ).when( request ).getMethod();
        assertTrue( CsrfProtectionFilter.isPost( request ) );
    }

    @Test
    void testIsPostReturnsFalseForGet() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "GET" ).when( request ).getMethod();
        assertFalse( CsrfProtectionFilter.isPost( request ) );
    }

    @Test
    void testIsPostReturnsFalseForPut() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "PUT" ).when( request ).getMethod();
        assertFalse( CsrfProtectionFilter.isPost( request ) );
    }

    @Test
    void testIsMcpEndpointReturnsTrue() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/mcp" ).when( request ).getServletPath();
        assertTrue( CsrfProtectionFilter.isMcpEndpoint( request ) );
    }

    @Test
    void testIsMcpEndpointReturnsFalseForOther() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/api/auth" ).when( request ).getServletPath();
        assertFalse( CsrfProtectionFilter.isMcpEndpoint( request ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/api/pages", "/api/auth/login", "/api/search", "/api/history/Main", "/api/backlinks/Main" } )
    void testIsRestApiEndpointReturnsTrue( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertTrue( CsrfProtectionFilter.isRestApiEndpoint( request ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/mcp", "/wiki/Main", "/Login.jsp", "/Edit.jsp" } )
    void testIsRestApiEndpointReturnsFalseForOther( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertFalse( CsrfProtectionFilter.isRestApiEndpoint( request ) );
    }

    @Test
    void testIsRestApiEndpointReturnsFalseForNull() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( request ).getServletPath();
        assertFalse( CsrfProtectionFilter.isRestApiEndpoint( request ) );
    }

    /**
     * Admin endpoints (under /admin/) are exempt from CSRF token checks because they
     * use JSON Content-Type which provides natural CSRF protection via CORS preflight.
     */
    @ParameterizedTest
    @ValueSource( strings = { "/admin/groups", "/admin/policy", "/admin/users", "/admin/content" } )
    void testIsRestApiEndpointReturnsTrueForAdminPaths( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertTrue( CsrfProtectionFilter.isRestApiEndpoint( request ),
                "Admin path '" + servletPath + "' should be recognized as a REST API endpoint" );
    }

}
