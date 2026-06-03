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

import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditQuery;
import com.wikantik.audit.AuditService;
import com.wikantik.audit.PersistedAuditEntry;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLifecycleServiceTest {

    private static final class CapturingAudit implements AuditService {
        final List<AuditEntry> entries = new ArrayList<>();
        public void record( AuditEntry e ) { entries.add( e ); }
        public List<PersistedAuditEntry> query( AuditQuery q ) { return List.of(); }
        public Optional<Long> verifyChain( long a, long b ) { return Optional.empty(); }
        public long droppedCount() { return 0; }
    }

    @Test
    void deactivateSetsIndefiniteLockAndAudits() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-1" );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( db.findByLoginName( "alice" ) ).thenReturn( p );
        CapturingAudit audit = new CapturingAudit();

        UserLifecycleService svc = new UserLifecycleService( db, audit );
        svc.deactivate( "alice", "admin-bob", "scim" );

        // Lock set far in the future (indefinite), profile saved.
        verify( p ).setLockExpiry( argThat( d -> d != null && d.after( new Date() ) ) );
        verify( db ).save( p );
        // Audit event recorded with the trigger-agnostic type + source in detail.
        assertEquals( 1, audit.entries.size() );
        AuditEntry e = audit.entries.get( 0 );
        assertEquals( AuditCategory.ADMIN, e.category() );
        assertEquals( "user.deactivate", e.eventType() );
        assertEquals( "user", e.targetType() );
        assertEquals( "alice", e.targetId() );
        assertEquals( "admin-bob", e.actorPrincipal() );
        assertTrue( e.detail() != null && e.detail().contains( "scim" ), "source in detail" );
    }

    @Test
    void reactivateClearsLockAndAudits() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( db.findByLoginName( "alice" ) ).thenReturn( p );
        CapturingAudit audit = new CapturingAudit();

        UserLifecycleService svc = new UserLifecycleService( db, audit );
        svc.reactivate( "alice", "admin-bob", "admin-ui" );

        verify( p ).setLockExpiry( null );
        verify( db ).save( p );
        assertEquals( "user.reactivate", audit.entries.get( 0 ).eventType() );
    }

    @Test
    void timedDeactivateSetsExpiryAndAuditsWithUntilField() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getUid() ).thenReturn( "u-2" );
        when( p.getLoginName() ).thenReturn( "bob" );
        when( db.findByLoginName( "bob" ) ).thenReturn( p );
        CapturingAudit audit = new CapturingAudit();

        final Date until = new Date( System.currentTimeMillis() + 86_400_000L * 30 ); // ~30 days
        UserLifecycleService svc = new UserLifecycleService( db, audit );
        svc.deactivate( "bob", until, "admin-alice", "admin-ui" );

        // Lock set to the supplied expiry date, profile saved.
        verify( p ).setLockExpiry( until );
        verify( db ).save( p );
        // Audit event recorded with user.deactivate + source + until in detail.
        assertEquals( 1, audit.entries.size() );
        AuditEntry e = audit.entries.get( 0 );
        assertEquals( AuditCategory.ADMIN, e.category() );
        assertEquals( "user.deactivate", e.eventType() );
        assertEquals( "user", e.targetType() );
        assertEquals( "bob", e.targetId() );
        assertEquals( "admin-alice", e.actorPrincipal() );
        assertTrue( e.detail() != null && e.detail().contains( "admin-ui" ), "source in detail" );
        assertTrue( e.detail() != null && e.detail().contains( "until" ), "until field in detail" );
    }

    @Test
    void auditFailureNeverBlocksTheLifecycleChange() throws Exception {
        UserDatabase db = mock( UserDatabase.class );
        UserProfile p = mock( UserProfile.class );
        when( p.getLoginName() ).thenReturn( "alice" );
        when( db.findByLoginName( "alice" ) ).thenReturn( p );
        AuditService throwing = mock( AuditService.class );
        doThrow( new RuntimeException( "audit down" ) ).when( throwing ).record( any() );

        UserLifecycleService svc = new UserLifecycleService( db, throwing );
        // Must not throw — the save already happened; auditing is best-effort.
        assertDoesNotThrow( () -> svc.deactivate( "alice", "x", "scim" ) );
        verify( db ).save( p );
    }
}
