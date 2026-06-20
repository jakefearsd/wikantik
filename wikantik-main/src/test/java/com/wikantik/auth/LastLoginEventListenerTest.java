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

import com.wikantik.auth.user.UserDatabase;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link LastLoginEventListener}: a LOGIN_AUTHENTICATED event must
 * stamp the authenticating account's last-login timestamp via the user database,
 * and nothing else should trigger a write.
 */
class LastLoginEventListenerTest {

    @Test
    void recordsLastLoginForAuthenticatedPrincipal() throws Exception {
        final UserDatabase db = mock( UserDatabase.class );
        final LastLoginEventListener listener = new LastLoginEventListener( db );

        final long before = System.currentTimeMillis();
        listener.actionPerformed( new WikiSecurityEvent( this,
                WikiSecurityEvent.LOGIN_AUTHENTICATED, new WikiPrincipal( "alice" ), null ) );
        final long after = System.currentTimeMillis();

        final ArgumentCaptor< Date > when = ArgumentCaptor.forClass( Date.class );
        verify( db, times( 1 ) ).recordLastLogin( eq( "alice" ), when.capture() );
        final long stamped = when.getValue().getTime();
        org.junit.jupiter.api.Assertions.assertTrue( stamped >= before && stamped <= after,
                "timestamp should be 'now'" );
    }

    @Test
    void recordsWhenPrincipalIsCarriedInTargetSlot() throws Exception {
        // The 3-arg WikiSecurityEvent constructor leaves principal=null and stores
        // the Principal as the target; the listener must look in both slots.
        final UserDatabase db = mock( UserDatabase.class );
        final LastLoginEventListener listener = new LastLoginEventListener( db );

        listener.actionPerformed( new WikiSecurityEvent( this,
                WikiSecurityEvent.LOGIN_AUTHENTICATED, new WikiPrincipal( "bob" ) ) );

        verify( db, times( 1 ) ).recordLastLogin( eq( "bob" ), any( Date.class ) );
    }

    @Test
    void ignoresNonLoginSecurityEvents() throws Exception {
        final UserDatabase db = mock( UserDatabase.class );
        final LastLoginEventListener listener = new LastLoginEventListener( db );

        listener.actionPerformed( new WikiSecurityEvent( this,
                WikiSecurityEvent.LOGOUT, new WikiPrincipal( "alice" ), null ) );

        verify( db, never() ).recordLastLogin( any(), any() );
    }

    @Test
    void swallowsDatabaseFailuresSoLoginIsNeverBroken() throws Exception {
        final UserDatabase db = mock( UserDatabase.class );
        doThrow( new WikiSecurityException( "db down" ) ).when( db ).recordLastLogin( any(), any() );
        final LastLoginEventListener listener = new LastLoginEventListener( db );

        // A failed last-login stamp is telemetry; it must not propagate out of the
        // event dispatch and abort the authentication flow.
        assertDoesNotThrow( () -> listener.actionPerformed( new WikiSecurityEvent( this,
                WikiSecurityEvent.LOGIN_AUTHENTICATED, new WikiPrincipal( "alice" ), null ) ) );
    }

    @Test
    void ignoresEventsWithoutAResolvablePrincipalName() throws Exception {
        final UserDatabase db = mock( UserDatabase.class );
        final LastLoginEventListener listener = new LastLoginEventListener( db );

        // No principal and no Principal target → nothing to stamp.
        listener.actionPerformed( new WikiSecurityEvent( this,
                WikiSecurityEvent.LOGIN_AUTHENTICATED, "not-a-principal" ) );

        verify( db, never() ).recordLastLogin( any(), any() );
    }
}
