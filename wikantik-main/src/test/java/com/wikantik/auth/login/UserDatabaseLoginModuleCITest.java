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

import com.wikantik.TestEngine;
import com.wikantik.auth.user.XMLUserDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.util.HashMap;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link UserDatabaseLoginModule} targeting
 * branches not exercised by the existing {@code UserDatabaseLoginModuleTest}.
 */
class UserDatabaseLoginModuleCITest {

    private TestEngine engine;
    private Subject subject;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml" );
        engine = new TestEngine( props );
        subject = new Subject();
    }

    // --- Null UserDatabase → FailedLoginException ---

    @Test
    void testLoginThrowsFailedLoginWhenNullDatabase() {
        // CallbackHandler that supplies a null UserDatabase
        final CallbackHandler handler = callbacks -> {
            for ( final Callback cb : callbacks ) {
                if ( cb instanceof UserDatabaseCallback userDatabaseCallback ) {
                    userDatabaseCallback.setUserDatabase( null ); // null db
                } else if ( cb instanceof NameCallback nc ) {
                    nc.setName( "janne" );
                } else if ( cb instanceof PasswordCallback pc ) {
                    pc.setPassword( "myP@5sw0rd".toCharArray() );
                }
            }
        };

        final LoginModule module = new UserDatabaseLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( FailedLoginException.class, module::login );
    }

    // --- Wrong password → FailedLoginException ---

    @Test
    void testLoginFailsWithWrongPassword() {
        final CallbackHandler handler = new WikiCallbackHandler( engine, null, "janne", "wrongpassword" );
        final LoginModule module = new UserDatabaseLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( FailedLoginException.class, module::login );
    }

    // --- Non-existent user → FailedLoginException (NoSuchPrincipalException path) ---

    @Test
    void testLoginFailsForNonExistentUser() {
        final CallbackHandler handler = new WikiCallbackHandler( engine, null, "nosuchuser", "password" );
        final LoginModule module = new UserDatabaseLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( FailedLoginException.class, module::login );
    }

    // --- IOException from handler → LoginException ---

    @Test
    void testLoginThrowsLoginExceptionOnIOException() {
        final CallbackHandler handler = callbacks -> {
            throw new IOException( "simulated IO error" );
        };
        final LoginModule module = new UserDatabaseLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( LoginException.class, module::login );
    }

    // --- UnsupportedCallbackException from handler → LoginException ---

    @Test
    void testLoginThrowsLoginExceptionOnUnsupportedCallback() {
        final CallbackHandler handler = callbacks -> {
            throw new UnsupportedCallbackException( callbacks[0] );
        };
        final LoginModule module = new UserDatabaseLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( LoginException.class, module::login );
    }

    // --- Commit with empty principals (login failed) returns false ---

    @Test
    void testCommitReturnsFalseWhenLoginFailed() throws LoginException {
        final CallbackHandler handler = new WikiCallbackHandler( engine, null, "nosuchuser", "password" );
        final LoginModule module = new UserDatabaseLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        // login() will throw FailedLoginException; principals set stays empty
        try {
            module.login();
        } catch ( final FailedLoginException ignored ) {
        }
        assertFalse( module.commit() );
    }
}
