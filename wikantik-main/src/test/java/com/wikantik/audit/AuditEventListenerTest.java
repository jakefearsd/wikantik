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
package com.wikantik.audit;

import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.Test;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditEventListenerTest {

    /** Capturing AuditService stub. */
    private static final class CapturingService implements AuditService {
        final List<AuditEntry> recorded = new ArrayList<>();
        public void record( AuditEntry e ) { recorded.add( e ); }
        public List<PersistedAuditEntry> query( AuditQuery q ) { return List.of(); }
        public java.util.Optional<Long> verifyChain( long a, long b ) { return java.util.Optional.empty(); }
        public long droppedCount() { return 0; }
    }

    // NOTE: WikiSecurityEvent has two constructors:
    //   (src, type, principal, target)  — 4-arg, principal is the dedicated field
    //   (src, type, target)             — 3-arg, delegates to 4-arg with principal=null
    //
    // The plan's test uses the 3-arg form: new WikiSecurityEvent(this, TYPE, principal).
    // In that form, the Principal is passed as `target` (not the `principal` field).
    // AuditEventListener.principalName() therefore checks getPrincipal() first,
    // then falls back to getTarget() — which is how it picks up the name here.

    @Test
    void loginFailedMapsToAuthnFailure() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );

        Principal alice = mock( Principal.class );
        when( alice.getName() ).thenReturn( "alice" );
        // 3-arg: target = alice (principal field stays null)
        WikiSecurityEvent evt = new WikiSecurityEvent( this, WikiSecurityEvent.LOGIN_FAILED, alice );

        listener.actionPerformed( evt );

        assertEquals( 1, svc.recorded.size() );
        AuditEntry e = svc.recorded.get( 0 );
        assertEquals( AuditCategory.AUTHN, e.category() );
        assertEquals( "login.failed", e.eventType() );
        assertEquals( AuditOutcome.FAILURE, e.outcome() );
        assertEquals( "alice", e.actorPrincipal() );
    }

    @Test
    void accessDeniedMapsToAuthzDenied() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );
        Principal bob = mock( Principal.class );
        when( bob.getName() ).thenReturn( "bob" );
        WikiSecurityEvent evt = new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, bob );

        listener.actionPerformed( evt );

        AuditEntry e = svc.recorded.get( 0 );
        assertEquals( AuditCategory.AUTHZ, e.category() );
        assertEquals( AuditOutcome.DENIED, e.outcome() );
    }

    @Test
    void unmappedEventTypeIsIgnored() {
        CapturingService svc = new CapturingService();
        AuditEventListener listener = new AuditEventListener( svc );
        // PRINCIPAL_ADD (35) is not an audited transition.
        Principal p = mock( Principal.class );
        when( p.getName() ).thenReturn( "x" );
        listener.actionPerformed( new WikiSecurityEvent( this, WikiSecurityEvent.PRINCIPAL_ADD, p ) );
        assertTrue( svc.recorded.isEmpty() );
    }
}
