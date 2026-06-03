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

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimAccessFilterTest {

    @Test
    void validBearerPasses() throws Exception {
        ScimAccessFilter f = new ScimAccessFilter( "secret-token" );
        HttpServletRequest req = mock( HttpServletRequest.class );
        HttpServletResponse resp = mock( HttpServletResponse.class );
        FilterChain chain = mock( FilterChain.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer secret-token" );
        f.doFilter( req, resp, chain );
        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( 401 );
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        ScimAccessFilter f = new ScimAccessFilter( "secret-token" );
        for ( String h : new String[]{ null, "Bearer nope", "secret-token" } ) {
            HttpServletRequest req = mock( HttpServletRequest.class );
            HttpServletResponse resp = mock( HttpServletResponse.class );
            FilterChain chain = mock( FilterChain.class );
            StringWriter sw = new StringWriter();
            when( req.getHeader( "Authorization" ) ).thenReturn( h );
            when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
            f.doFilter( req, resp, chain );
            verify( chain, never() ).doFilter( any(), any() );
            verify( resp ).setStatus( 401 );
        }
    }
}
