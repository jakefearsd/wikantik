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
package com.wikantik.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2: Throttle counter must reset on a successful login so legitimate logins are
 * not penalised by the brute-force-prevention exponential delay.
 */
class AuthThrottleTest {

    @Test
    void registeringFailuresAccumulatesCount() throws Exception {
        final DefaultAuthenticationManager mgr = new DefaultAuthenticationManager();
        // 3 failures should leave count==3
        invoke( mgr, "registerFailedLogin", "alice" );
        invoke( mgr, "registerFailedLogin", "alice" );
        invoke( mgr, "registerFailedLogin", "alice" );

        assertEquals( 3, mgr.loginAttemptCount( "alice" ),
                "After 3 failed logins, throttle bucket should hold 3 entries" );
    }

    @Test
    void clearLoginAttemptsResetsCountForUser() throws Exception {
        final DefaultAuthenticationManager mgr = new DefaultAuthenticationManager();
        invoke( mgr, "registerFailedLogin", "alice" );
        invoke( mgr, "registerFailedLogin", "alice" );
        invoke( mgr, "registerFailedLogin", "bob" );
        assertEquals( 2, mgr.loginAttemptCount( "alice" ) );

        invoke( mgr, "clearLoginAttempts", "alice" );

        assertEquals( 0, mgr.loginAttemptCount( "alice" ),
                "Successful login must clear the throttle bucket — without this, every "
                        + "subsequent legitimate login is delayed by 2^count ms (capped at 20s)." );
        assertEquals( 1, mgr.loginAttemptCount( "bob" ),
                "Clearing attempts for one user must not affect another user's bucket" );
    }

    @Test
    void delayLoginIsFastWhenNoFailures() throws Exception {
        final DefaultAuthenticationManager mgr = new DefaultAuthenticationManager();
        final long start = System.currentTimeMillis();
        invoke( mgr, "delayLogin", "newuser" );
        final long elapsed = System.currentTimeMillis() - start;
        // No failed attempts — should not sleep at all. 200ms gives generous slack.
        assertTrue( elapsed < 200,
                "First-time login throttle delay was " + elapsed + " ms, must be < 200ms" );
    }

    private static void invoke( final DefaultAuthenticationManager mgr, final String name,
                                 final String arg ) throws Exception {
        final Method m = DefaultAuthenticationManager.class.getDeclaredMethod( name, String.class );
        m.setAccessible( true );
        m.invoke( mgr, arg );
    }
}
