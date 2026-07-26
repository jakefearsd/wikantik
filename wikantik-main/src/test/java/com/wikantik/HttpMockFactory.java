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
package com.wikantik;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.net.MalformedURLException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Mocks {@link TestEngine}'s http interactions. As this is a general usage class, tests using {@code MockitoExtension}
 * and {@link TestEngine} should also use {@code @MockitoSettings( strictness = Strictness.LENIENT )}.
 */
public class HttpMockFactory {

    public static FilterChain createFilterChain() {
        return Mockito.mock( FilterChain.class );
    }

    public static FilterConfig createFilterConfig() {
        return createFilterConfig( createServletContext( "/JSPWiki" ) );
    }

    public static FilterConfig createFilterConfig( final ServletContext sc ) {
        final FilterConfig fc = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( sc ).when( fc ).getServletContext();
        return fc;
    }

    public static ServletConfig createServletConfig( final String contextName ) {
        final ServletConfig sc = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( createServletContext( contextName ) ).when( sc ).getServletContext();
        return sc;
    }

    public static ServletContext createServletContext( final String contextName ) {
        final ServletContext sc = Mockito.mock( ServletContext.class );

        // Add attribute storage map for proper getAttribute/setAttribute support
        final Map< String, Object > attributes = new ConcurrentHashMap<>();

        Mockito.doReturn( 6 ).when( sc ).getMajorVersion();
        Mockito.doReturn( 0 ).when( sc ).getMinorVersion();
        Mockito.doReturn( "/" + contextName ).when( sc ).getContextPath();
        Mockito.doReturn( contextName ).when( sc ).getServletContextName();
        Mockito.doAnswer( ( Answer< ServletContext > ) invocationOnMock -> {
            final String uriPath = invocationOnMock.getArgument( 0 );
            if( uriPath.startsWith( contextName ) ) {
                return sc;
            } else {
                return null;
            }
        } ).when( sc ).getContext( Mockito.anyString() );
        try {
            Mockito.doAnswer( invocationOnMock -> {
                String name = invocationOnMock.getArguments()[0].toString();
                while( name.startsWith( "/" ) ) {
                    name = name.substring( 1 );
                }
                return Thread.currentThread().getContextClassLoader().getResource( name );
            } ).when( sc ).getResource( Mockito.anyString() );
        } catch( MalformedURLException mue ) {
            throw new RuntimeException( mue );
        }

        // Stub setAttribute to store in map
        Mockito.doAnswer( invocation -> {
            final String name = invocation.getArgument( 0 );
            final Object value = invocation.getArgument( 1 );
            if( value == null ) {
                attributes.remove( name );
            } else {
                attributes.put( name, value );
            }
            return null;
        } ).when( sc ).setAttribute( Mockito.anyString(), Mockito.any() );

        // Stub getAttribute to retrieve from map
        Mockito.doAnswer( invocation -> {
            final String name = invocation.getArgument( 0 );
            return attributes.get( name );
        } ).when( sc ).getAttribute( Mockito.anyString() );

        // Stub removeAttribute
        Mockito.doAnswer( invocation -> {
            final String name = invocation.getArgument( 0 );
            attributes.remove( name );
            return null;
        } ).when( sc ).removeAttribute( Mockito.anyString() );

        // Stub getAttributeNames for completeness
        Mockito.doAnswer( invocation -> Collections.enumeration( attributes.keySet() ) ).when( sc ).getAttributeNames();

        return sc;
    }

    /**
     * Session id shared by every mock produced by the no-arg {@link #createHttpSession()} /
     * {@link #createHttpRequest()} helpers. {@code SessionMonitor} keys {@code WikiSession}s
     * by this id, so separate mock requests resolve to the SAME WikiSession — which is what
     * lets a test log in once (e.g. {@code TestEngine.adminSession()}) and act authenticated
     * on later requests.
     *
     * <p><b>Pollution hazard:</b> that same linkage leaks authentication state between tests
     * in one class (an admin login in setUp bleeds into an "anonymous" 403 test). Tests that
     * need a genuinely anonymous/fresh session must use {@link #createIsolatedHttpRequest(String)}
     * or evict this id from the {@code SessionMonitor} in tearDown.</p>
     */
    public static final String SHARED_SESSION_ID = "mock-session";

    public static HttpSession createHttpSession() {
        return createHttpSession( SHARED_SESSION_ID );
    }

    /**
     * Creates a mock HttpSession with an explicit id — pass a unique id to opt out of the
     * {@link #SHARED_SESSION_ID} WikiSession sharing described above.
     */
    public static HttpSession createHttpSession( final String id ) {
        final HttpSession session = Mockito.mock( HttpSession.class );
        Mockito.doReturn( id ).when( session ).getId();
        return session;
    }

    /**
     * Creates a correctly-instantiated mock HttpServletRequest with an associated
     * HttpSession.
     * @return the new request
     */
    public static HttpServletRequest createHttpRequest() {
        return createHttpRequest( "/JSPWiki", "/wiki/" );
    }

    /**
     * Creates a correctly-instantiated mock HttpServletRequest with an associated
     * HttpSession and path.
     * @param path the path relative to the wiki context, for example "/Wiki.jsp"
     * @return the new request
     */
    public static HttpServletRequest createHttpRequest( final String path ) {
        return createHttpRequest( "/JSPWiki", path );
    }

    /**
     * Creates a correctly-instantiated mock HttpServletRequest with an associated
     * HttpSession and path.
     * @param path the path relative to the wiki context, for example "/Wiki.jsp"
     * @return the new request
     */
    /**
     * Like {@link #createHttpRequest(String)}, but the request carries a unique session id,
     * so it never resolves to the shared {@link #SHARED_SESSION_ID} WikiSession. Use for
     * tests that must be genuinely anonymous regardless of earlier logins in the class.
     */
    public static HttpServletRequest createIsolatedHttpRequest( final String path ) {
        final HttpServletRequest request = createHttpRequest( "/JSPWiki", path );
        final HttpSession session = createHttpSession( SHARED_SESSION_ID + "-" + java.util.UUID.randomUUID() );
        Mockito.doReturn( session ).when( request ).getSession();
        Mockito.doReturn( session ).when( request ).getSession( Mockito.anyBoolean() );
        return request;
    }

    public static HttpServletRequest createHttpRequest( final String contextName, final String path ) {
        final HttpSession session = createHttpSession();
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( createServletContext( contextName ) ).when( request ).getServletContext();
        Mockito.doReturn( Locale.ROOT ).when( request ).getLocale();
        Mockito.doReturn( new Vector<>( List.of( Locale.ROOT ) ).elements() ).when( request ).getLocales();
        Mockito.doReturn( session ).when( request ).getSession();
        Mockito.doReturn( session ).when( request ).getSession( Mockito.anyBoolean() );
        Mockito.doReturn( "/JSPWiki" ).when( request ).getContextPath();
        Mockito.doReturn( "127.0.0.1" ).when( request ).getRemoteAddr();
        Mockito.doReturn( path ).when( request ).getServletPath();
        Mockito.doReturn( "/JSPWiki" + path /* + "/pathinfo" */ ).when( request ).getRequestURI();
        Mockito.doReturn( new StringBuffer( "http://localhost:8080/JSPWiki" + path /* + "/pathinfo" */ ) ).when( request ).getRequestURL();

        final RequestDispatcher rd = Mockito.mock( RequestDispatcher.class );
        Mockito.doReturn( rd ).when( request ).getRequestDispatcher( Mockito.anyString() );

        return request;
    }

    /**
     * Creates a correctly-instantiated mock HttpServletRequest with an associated
     * HttpSession.
     * @return the new request
     */
    public static HttpServletResponse createHttpResponse() {
        return Mockito.mock( HttpServletResponse.class );
    }

}
