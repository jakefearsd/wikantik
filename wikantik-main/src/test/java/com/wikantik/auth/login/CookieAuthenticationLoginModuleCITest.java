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
package com.wikantik.auth.login;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.Users;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.Principal;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CookieAuthenticationLoginModule} covering paths not reached
 * by existing unit tests: {@code setLoginCookie}, {@code clearLoginCookie},
 * a successful cookie login, the IOException path, and the UnsupportedCallbackException path.
 */
class CookieAuthenticationLoginModuleCITest {

    private TestEngine engine;
    private Subject subject;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
        subject = new Subject();
    }

    // --- login() returns false when no WikantikUID cookie is present ---

    @Test
    void testLoginReturnsFalseWhenNoUidCookie() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[0] ).when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login() );
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    // --- login() returns false when cookie file does not exist ---

    @Test
    void testLoginReturnsFalseWhenCookieFileAbsent() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Cookie cookie = new Cookie( "WikantikUID", "nonexistent-uid-that-has-no-file" );
        Mockito.doReturn( new Cookie[]{ cookie } ).when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login() );
    }

    // --- setLoginCookie creates a cookie file and login() can authenticate with it ---

    @Test
    void testSetLoginCookieThenLogin() throws LoginException {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();

        // Capture the cookie added to the response
        final Cookie[] capturedCookie = new Cookie[1];
        Mockito.doAnswer( invocation -> {
            capturedCookie[0] = invocation.getArgument( 0 );
            return null;
        } ).when( response ).addCookie( Mockito.any( Cookie.class ) );

        CookieAuthenticationLoginModule.setLoginCookie( engine, response, "janne" );
        assertNotNull( capturedCookie[0], "addCookie should have been called" );

        // Use the UID from the cookie to log in
        final String uid = capturedCookie[0].getValue();
        assertFalse( uid.isEmpty() );

        final HttpServletRequest loginRequest = HttpMockFactory.createHttpRequest();
        final Cookie uidCookie = new Cookie( "WikantikUID", uid );
        Mockito.doReturn( new Cookie[]{ uidCookie } ).when( loginRequest ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, loginRequest );
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertTrue( module.login() );
        module.commit();

        final Set<Principal> principals = subject.getPrincipals();
        assertEquals( 1, principals.size() );
        assertEquals( "janne", principals.iterator().next().getName() );
    }

    // --- remember-me cookie must not authenticate a locked/deactivated account ---

    @Test
    void testLockedUserCannotLoginViaCookie() throws Exception {
        // Create the remember-me cookie for janne while the account is active.
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final Cookie[] capturedCookie = new Cookie[1];
        Mockito.doAnswer( invocation -> {
            capturedCookie[0] = invocation.getArgument( 0 );
            return null;
        } ).when( response ).addCookie( Mockito.any( Cookie.class ) );

        CookieAuthenticationLoginModule.setLoginCookie( engine, response, Users.JANNE );
        assertNotNull( capturedCookie[0], "addCookie should have been called" );
        final String uid = capturedCookie[0].getValue();

        // Now lock the account (simulate deactivation via indefinite lock).
        final UserDatabase db = engine.getManager( UserManager.class ).getUserDatabase();
        final UserProfile profile = db.findByLoginName( Users.JANNE );
        final Calendar farFuture = Calendar.getInstance();
        farFuture.clear();
        farFuture.set( 9999, Calendar.DECEMBER, 31, 23, 59, 59 );
        profile.setLockExpiry( farFuture.getTime() );
        db.save( profile );

        // Attempt cookie re-auth with the previously valid cookie — must be rejected.
        final HttpServletRequest loginRequest = HttpMockFactory.createHttpRequest();
        final Cookie uidCookie = new Cookie( "WikantikUID", uid );
        Mockito.doReturn( new Cookie[]{ uidCookie } ).when( loginRequest ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, loginRequest );
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( new Subject(), handler, new HashMap<>(), new HashMap<>() );
        assertThrows( FailedLoginException.class, module::login,
            "Remember-me cookie must not authenticate a locked account" );
    }

    // --- the remember-me cookie carries safe, navigation-surviving attributes ---

    @Test
    void testRememberMeCookieHasSafeAttributes() {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final Cookie[] captured = new Cookie[ 1 ];
        Mockito.doAnswer( inv -> { captured[ 0 ] = inv.getArgument( 0 ); return null; } )
                .when( response ).addCookie( Mockito.any( Cookie.class ) );

        // secure=false (plain-HTTP local dev): the cookie must still be issued, and
        // it must be httpOnly + SameSite=Lax + Path=/ so it is sent on top-level
        // navigations (a refresh after session loss) to silently re-authenticate.
        CookieAuthenticationLoginModule.setLoginCookie( engine, response, "janne", false );
        assertNotNull( captured[ 0 ], "addCookie should have been called" );
        assertEquals( "WikantikUID", captured[ 0 ].getName() );
        assertTrue( captured[ 0 ].isHttpOnly(), "remember-me cookie must be httpOnly" );
        assertFalse( captured[ 0 ].getSecure(), "Secure must follow request scheme (false on http)" );
        assertEquals( "/", captured[ 0 ].getPath() );
        assertEquals( "Lax", captured[ 0 ].getAttribute( "SameSite" ),
                "remember-me cookie must be SameSite=Lax, never Strict (Strict is withheld on refresh)" );

        // secure=true (behind HTTPS) sets the Secure flag.
        CookieAuthenticationLoginModule.setLoginCookie( engine, response, "janne", true );
        assertTrue( captured[ 0 ].getSecure(), "Secure must be set behind HTTPS" );
    }

    // --- clearLoginCookie removes the cookie file so subsequent login fails ---

    @Test
    void testClearLoginCookieDisablesSubsequentLogin() throws LoginException {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final Cookie[] capturedCookie = new Cookie[1];
        Mockito.doAnswer( invocation -> {
            capturedCookie[0] = invocation.getArgument( 0 );
            return null;
        } ).when( response ).addCookie( Mockito.any( Cookie.class ) );

        CookieAuthenticationLoginModule.setLoginCookie( engine, response, "janne" );
        final String uid = capturedCookie[0].getValue();

        final HttpServletRequest clearRequest = HttpMockFactory.createHttpRequest();
        final Cookie uidCookie = new Cookie( "WikantikUID", uid );
        Mockito.doReturn( new Cookie[]{ uidCookie } ).when( clearRequest ).getCookies();

        // Reset the captured cookie so we can see what clearLoginCookie sets
        final Cookie[] clearedCookie = new Cookie[1];
        final HttpServletResponse clearResponse = HttpMockFactory.createHttpResponse();
        Mockito.doAnswer( invocation -> {
            clearedCookie[0] = invocation.getArgument( 0 );
            return null;
        } ).when( clearResponse ).addCookie( Mockito.any( Cookie.class ) );

        CookieAuthenticationLoginModule.clearLoginCookie( engine, clearRequest, clearResponse );

        // The logout cookie should have maxAge 0
        assertNotNull( clearedCookie[0] );
        assertEquals( 0, clearedCookie[0].getMaxAge() );

        // Attempting to log in with the old UID should now fail
        final HttpServletRequest loginRequest = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[]{ new Cookie( "WikantikUID", uid ) } )
               .when( loginRequest ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, loginRequest );
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( new Subject(), handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login() );
    }

    // --- IOException from CallbackHandler → LoginException ---

    @Test
    void testLoginThrowsLoginExceptionOnIOException() {
        final CallbackHandler handler = callbacks -> {
            throw new IOException( "simulated IO error" );
        };
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( LoginException.class, module::login );
    }

    // --- UnsupportedCallbackException from CallbackHandler → LoginException ---

    @Test
    void testLoginThrowsLoginExceptionOnUnsupportedCallback() {
        final CallbackHandler handler = callbacks -> {
            throw new UnsupportedCallbackException( callbacks[0] );
        };
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( LoginException.class, module::login );
    }

    // --- login() returns false when the "cookie file" cannot actually be read as content ---

    @Test
    void testLoginReturnsFalseWhenCookieFileIsUnreadable( @TempDir final File workDir ) throws Exception {
        // Force the "cookie file" itself to be a directory rather than a regular file: it
        // exists() and canRead() like a normal file would, but opening it for reading throws
        // IOException — exercising the login() IOException-while-reading branch.
        final Engine engine = mock( Engine.class );
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        final File cookieDir = new File( workDir, CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.mkdirs() );
        final String uid = "directory-not-a-file";
        assertTrue( new File( cookieDir, uid ).mkdir() );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[]{ new Cookie( "WikantikUID", uid ) } ).when( request ).getCookies();

        final CallbackHandler handler = callbacks -> {
            for( final Callback cb : callbacks ) {
                if( cb instanceof HttpRequestCallback hrc ) {
                    hrc.setRequest( request );
                } else if( cb instanceof WikiEngineCallback wec ) {
                    wec.setEngine( engine );
                }
            }
        };
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login(), "an unreadable cookie file must fail login, not throw" );
    }

    // --- getCookieFile: mkdirs() failure (workDir itself isn't a directory) ---

    @Test
    void testGetCookieFile_whenCookieDirCannotBeCreated_returnsNull( @TempDir final File tempDir ) throws Exception {
        final File notADirectory = new File( tempDir, "workdir-is-a-file" );
        assertTrue( notADirectory.createNewFile() );
        final Engine engine = mock( Engine.class );
        when( engine.getWorkDir() ).thenReturn( notADirectory.getAbsolutePath() );

        final File result = invokeGetCookieFile( engine, "anyUid" );
        assertNull( result, "a cookie directory that cannot be created must yield a null cookie file" );
    }

    // --- getCookieFile: cookieDir exists but is not writable ---

    @Test
    void testGetCookieFile_whenCookieDirNotWritable_returnsNull( @TempDir final File workDir ) throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        final File cookieDir = new File( workDir, CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.mkdirs() );
        assertTrue( cookieDir.setWritable( false ), "test setup requires being able to revoke write access" );
        try {
            final File result = invokeGetCookieFile( engine, "anyUid" );
            assertNull( result, "a non-writable cookie directory must yield a null cookie file" );
        } finally {
            cookieDir.setWritable( true );
        }
    }

    // --- getCookieFile: a uid that cannot be canonicalized (e.g. an embedded NUL byte, as a
    //     malicious cookie value might carry) is rejected via the IOException path, not thrown ---

    @Test
    void testGetCookieFile_whenUidCannotBeCanonicalized_ioExceptionYieldsNull( @TempDir final File workDir ) throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        final File cookieDir = new File( workDir, CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.mkdirs() );

        final File result = invokeGetCookieFile( engine, "abc\u0000def" );
        assertNull( result, "a uid whose path cannot be canonicalized must yield null, not throw" );
    }

    // --- getCookieFile: a path-traversal uid whose canonical path escapes cookieDir is rejected ---

    @Test
    void testGetCookieFile_pathTraversalUidIsRejected( @TempDir final File workDir ) throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        final File cookieDir = new File( workDir, CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.mkdirs() );

        final File result = invokeGetCookieFile( engine, "../../../../../../etc/passwd" );
        assertNull( result, "a uid that canonicalizes outside the cookie directory must be rejected" );
    }

    // --- setLoginCookie: the cookie is still set on the response even when the backing
    //     file cannot be written (e.g. a same-named directory already occupies that path) ---

    @Test
    void testSetLoginCookie_whenBackingFileCannotBeWritten_stillSetsResponseCookie( @TempDir final File workDir ) throws Exception {
        final TestEngine testEngine = new TestEngine( TestEngine.getTestProperties() );
        final File cookieDir = new File( testEngine.getWorkDir(), CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.exists() || cookieDir.mkdirs() );
        final UUID fixedUid = UUID.randomUUID();
        // Pre-occupy the exact path setLoginCookie will try to open for writing, as a
        // directory rather than a file, so Files.newOutputStream(...) throws IOException.
        assertTrue( new File( cookieDir, fixedUid.toString() ).mkdir() );

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final Cookie[] captured = new Cookie[ 1 ];
        Mockito.doAnswer( inv -> { captured[ 0 ] = inv.getArgument( 0 ); return null; } )
                .when( response ).addCookie( Mockito.any( Cookie.class ) );

        assertDoesNotThrow( () -> CookieAuthenticationLoginModule.setLoginCookie(
                testEngine, response, "janne", true, fixedUid ) );
        assertNotNull( captured[ 0 ], "the response cookie must still be set even if the backing file write fails" );
        assertEquals( fixedUid.toString(), captured[ 0 ].getValue() );
    }

    // --- clearLoginCookie: a cookie "file" that cannot be deleted (non-empty directory) is logged, not thrown ---

    @Test
    void testClearLoginCookie_whenCookieFileCannotBeDeleted_doesNotThrow( @TempDir final File workDir ) throws Exception {
        final Engine engine = mock( Engine.class );
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        final File cookieDir = new File( workDir, CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.mkdirs() );
        final String uid = "undeletable-uid";
        final File nonEmptyDir = new File( cookieDir, uid );
        assertTrue( nonEmptyDir.mkdir() );
        assertTrue( new File( nonEmptyDir, "nested" ).createNewFile() );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[]{ new Cookie( "WikantikUID", uid ) } ).when( request ).getCookies();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();

        assertDoesNotThrow( () -> CookieAuthenticationLoginModule.clearLoginCookie( engine, request, response ) );
        assertTrue( nonEmptyDir.exists(), "a non-empty directory cannot be deleted and must be left in place" );
    }

    // --- scrub(): a directory listing failure (nonexistent directory) returns early ---

    @Test
    void testScrub_whenDirectoryCannotBeListed_returnsEarlyWithoutThrowing() throws Exception {
        final Method scrub = CookieAuthenticationLoginModule.class.getDeclaredMethod( "scrub", int.class, File.class );
        scrub.setAccessible( true );
        assertDoesNotThrow( () -> scrub.invoke( null, 14, new File( "/no/such/cookie/dir/at-all" ) ) );
    }

    // --- scrub(): obsolete files are deleted; a non-empty directory entry is left in place and logged ---

    @Test
    void testScrub_deletesObsoleteFilesAndSkipsUndeletableEntries( @TempDir final File cookieDir ) throws Exception {
        final long twoDaysAgo = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L;

        final File deletable = new File( cookieDir, "old-deletable" );
        assertTrue( deletable.createNewFile() );
        assertTrue( deletable.setLastModified( twoDaysAgo ) );

        final File undeletable = new File( cookieDir, "old-undeletable-dir" );
        assertTrue( undeletable.mkdir() );
        assertTrue( new File( undeletable, "nested" ).createNewFile() );
        assertTrue( undeletable.setLastModified( twoDaysAgo ) );

        final Method scrub = CookieAuthenticationLoginModule.class.getDeclaredMethod( "scrub", int.class, File.class );
        scrub.setAccessible( true );
        scrub.invoke( null, 0, cookieDir );

        assertFalse( deletable.exists(), "an obsolete, deletable cookie file must be removed" );
        assertTrue( undeletable.exists(), "an obsolete but non-empty (undeletable) entry must be left in place" );
    }

    private static File invokeGetCookieFile( final Engine engine, final String uid ) throws Exception {
        final Method m = CookieAuthenticationLoginModule.class.getDeclaredMethod( "getCookieFile", Engine.class, String.class );
        m.setAccessible( true );
        return ( File ) m.invoke( null, engine, uid );
    }
}
