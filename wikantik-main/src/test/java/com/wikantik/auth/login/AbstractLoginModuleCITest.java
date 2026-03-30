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
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.user.XMLUserDatabase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.security.Principal;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for uncovered branches in {@link AbstractLoginModule}:
 * <ul>
 *   <li>{@code commit()} when login failed — should return false and not add principals</li>
 *   <li>{@code commit()} when login succeeded — should add principals and return true</li>
 *   <li>{@code abort()} removes committed principals</li>
 *   <li>{@code initialize()} guards: null subject and null handler</li>
 *   <li>{@code logout()} clears principals after a successful login and commit</li>
 * </ul>
 * We exercise these via the concrete {@link AnonymousLoginModule} and
 * {@link CookieAssertionLoginModule}, which both extend {@code AbstractLoginModule}.
 */
class AbstractLoginModuleCITest {

    private TestEngine engine;
    private Subject subject;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml" );
        engine = new TestEngine( props );
        subject = new Subject();
    }

    // --- initialize() null-subject guard ---

    @Test
    void testInitializeWithNullSubjectThrows() {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new AnonymousLoginModule();
        assertThrows( IllegalStateException.class,
                () -> module.initialize( null, handler, new HashMap<>(), new HashMap<>() ) );
    }

    // --- initialize() null-handler guard ---

    @Test
    void testInitializeWithNullHandlerThrows() {
        final LoginModule module = new AnonymousLoginModule();
        assertThrows( IllegalStateException.class,
                () -> module.initialize( subject, null, new HashMap<>(), new HashMap<>() ) );
    }

    // --- commit() returns false when login failed (principals set is empty) ---

    @Test
    void testCommitReturnsFalseWhenLoginFailed() throws LoginException {
        // CookieAssertionLoginModule.login() throws FailedLoginException when no cookie
        // is present, so principals stays empty.
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        // No cookies → login() will throw FailedLoginException
        org.mockito.Mockito.doReturn( new jakarta.servlet.http.Cookie[0] )
                .when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( null, request );
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        try {
            module.login();
        } catch ( final FailedLoginException ignored ) {
            // expected; principals is still empty
        }

        assertFalse( module.commit() );
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    // --- commit() returns true and adds principals when login succeeded ---

    @Test
    void testCommitReturnsTrueAndAddsPrincipalsWhenLoginSucceeded() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        // No getCookies() stub → returns null → AnonymousLoginModule succeeds using IP
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new AnonymousLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        module.login();
        assertTrue( module.commit() );

        final Set<Principal> principals = subject.getPrincipals();
        assertFalse( principals.isEmpty() );
    }

    // --- abort() removes principals previously added to Subject ---

    @Test
    void testAbortRemovesPrincipalsAfterCommit() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new AnonymousLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        module.login();
        module.commit();
        assertFalse( subject.getPrincipals().isEmpty() );

        module.abort();
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    // --- removePrincipals path when principal is NOT in subject (no-op, no exception) ---

    @Test
    void testAbortWhenNoPrincipalsCommittedIsNoOp() throws LoginException {
        // login() throws, so we never committed; abort() should not throw
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        org.mockito.Mockito.doReturn( new jakarta.servlet.http.Cookie[0] )
                .when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( null, request );
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        try {
            module.login();
        } catch ( final FailedLoginException ignored ) {
        }

        // abort without prior commit — should not throw
        assertDoesNotThrow( module::abort );
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    // --- logout() after commit removes principals ---

    @Test
    void testLogoutAfterCommitClearsPrincipals() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new AnonymousLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        module.login();
        module.commit();
        assertFalse( subject.getPrincipals().isEmpty() );

        assertTrue( module.logout() );
        assertTrue( subject.getPrincipals().isEmpty() );
    }
}
