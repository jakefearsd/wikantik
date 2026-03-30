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
package com.wikantik.ui;

import com.wikantik.HttpMockFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link WikiServletFilter} covering uncovered branches:
 * null chain throws ServletException, engine-not-started outputs HTML error page,
 * destroy() is a no-op, getWikiContext returns null for missing attribute.
 */
class WikiServletFilterAdditionalTest {

    // -----------------------------------------------------------------------
    // destroy() is a no-op — should not throw
    // -----------------------------------------------------------------------

    @Test
    void destroyDoesNotThrow() {
        final WikiServletFilter filter = new WikiServletFilter();
        assertDoesNotThrow( filter::destroy );
    }

    // -----------------------------------------------------------------------
    // doFilter with null chain throws ServletException
    // -----------------------------------------------------------------------

    @Test
    void doFilterNullChainThrowsServletException() throws Exception {
        final WikiServletFilter filter = new WikiServletFilter();
        // Manually set engine = null so we hit the chain-null check first
        // (engine is checked after chain, but null chain is checked first)
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp" );
        final ServletResponse resp = Mockito.mock( ServletResponse.class );

        // chain == null is the first guard
        assertThrows( ServletException.class, () -> filter.doFilter( req, resp, null ) );
    }

    // -----------------------------------------------------------------------
    // doFilter when engine == null writes HTML error page
    // -----------------------------------------------------------------------

    @Test
    void doFilterWithNullEngineWritesErrorHtml() throws Exception {
        final WikiServletFilter filter = new WikiServletFilter();
        // engine field is null (not initialised)

        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp" );
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final PrintWriter writer = new PrintWriter( baos, true );

        // We need a response that has getWriter() but also supports the character-encoding
        // call made before the engine null check (actually the encoding is set on the request)
        final jakarta.servlet.http.HttpServletResponse resp =
                Mockito.mock( jakarta.servlet.http.HttpServletResponse.class );
        Mockito.doReturn( writer ).when( resp ).getWriter();

        final FilterChain chain = HttpMockFactory.createFilterChain();

        // Should NOT throw — just write the error page
        assertDoesNotThrow( () -> filter.doFilter( req, resp, chain ) );

        writer.flush();
        final String output = baos.toString();
        assertTrue( output.contains( "JSPWiki has not been started" ),
                    "Error page should mention that JSPWiki has not been started; got: " + output );
        // Chain must NOT have been called
        Mockito.verify( chain, Mockito.never() ).doFilter( Mockito.any(), Mockito.any() );
    }

    // -----------------------------------------------------------------------
    // getWikiContext returns null when attribute absent
    // -----------------------------------------------------------------------

    @Test
    void getWikiContextReturnsNullWhenAttributeMissing() {
        final WikiServletFilter filter = new WikiServletFilter();
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/Wiki.jsp" );
        // No ATTR_CONTEXT set on the request
        assertNull( filter.getWikiContext( req ) );
    }
}
