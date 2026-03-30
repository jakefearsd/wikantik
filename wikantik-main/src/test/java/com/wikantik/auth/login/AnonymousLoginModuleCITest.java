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
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.util.HashMap;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link AnonymousLoginModule} targeting the
 * IOException and UnsupportedCallbackException branches not reached by
 * the existing {@code AnonymousLoginModuleTest}.
 */
class AnonymousLoginModuleCITest {

    private Subject subject;

    @BeforeEach
    void setUp() throws Exception {
        subject = new Subject();
    }

    // --- IOException from handler → login() returns false ---

    @Test
    void testLoginReturnsFalseOnIOException() throws LoginException {
        final CallbackHandler handler = callbacks -> {
            throw new IOException( "simulated IO error" );
        };
        final LoginModule module = new AnonymousLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login() );
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    // --- UnsupportedCallbackException from handler → LoginException ---

    @Test
    void testLoginThrowsLoginExceptionOnUnsupportedCallback() {
        final CallbackHandler handler = callbacks -> {
            throw new UnsupportedCallbackException( callbacks[0] );
        };
        final LoginModule module = new AnonymousLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( LoginException.class, module::login );
    }
}
