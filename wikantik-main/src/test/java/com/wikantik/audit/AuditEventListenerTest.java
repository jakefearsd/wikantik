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

import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.event.WikiSecurityEvent;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    private final CapturingService svc = new CapturingService();
    private final AuditEventListener listener = new AuditEventListener( svc );

    private static Principal named( final String name ) {
        final Principal p = mock( Principal.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    private AuditEntry onlyEntry() {
        assertEquals( 1, svc.recorded.size(), "exactly one audit entry expected" );
        return svc.recorded.get( 0 );
    }

    // ---------------------------------------------------------------- security events

    // NOTE: WikiSecurityEvent has two constructors:
    //   (src, type, principal, target)  — 4-arg, principal is the dedicated field
    //   (src, type, target)             — 3-arg, delegates with principal=null (Principal arrives as target)
    // AuditEventListener.principalName() checks getPrincipal() first, then falls back to getTarget().

    static Stream<Arguments> securityMappings() {
        return Stream.of(
            Arguments.of( WikiSecurityEvent.LOGIN_AUTHENTICATED, AuditCategory.AUTHN, AuditOutcome.SUCCESS, "login.ok" ),
            Arguments.of( WikiSecurityEvent.LOGIN_FAILED,        AuditCategory.AUTHN, AuditOutcome.FAILURE, "login.failed" ),
            Arguments.of( WikiSecurityEvent.LOGOUT,              AuditCategory.AUTHN, AuditOutcome.SUCCESS, "logout" ),
            Arguments.of( WikiSecurityEvent.SESSION_EXPIRED,     AuditCategory.AUTHN, AuditOutcome.SUCCESS, "session.expired" ),
            Arguments.of( WikiSecurityEvent.ACCESS_DENIED,       AuditCategory.AUTHZ, AuditOutcome.DENIED,  "access.denied" ),
            Arguments.of( WikiSecurityEvent.GROUP_ADD,           AuditCategory.ADMIN, AuditOutcome.SUCCESS, "group.member.add" ),
            Arguments.of( WikiSecurityEvent.GROUP_REMOVE,        AuditCategory.ADMIN, AuditOutcome.SUCCESS, "group.member.remove" ),
            Arguments.of( WikiSecurityEvent.PROFILE_SAVE,        AuditCategory.ADMIN, AuditOutcome.SUCCESS, "profile.save" )
        );
    }

    @ParameterizedTest
    @MethodSource( "securityMappings" )
    void securityEventMapsToExpectedAudit( final int type, final AuditCategory category,
                                           final AuditOutcome outcome, final String eventType ) {
        listener.actionPerformed( new WikiSecurityEvent( this, type, named( "alice" ) ) );

        final AuditEntry e = onlyEntry();
        assertEquals( category, e.category() );
        assertEquals( outcome, e.outcome() );
        assertEquals( eventType, e.eventType() );
        assertEquals( "alice", e.actorPrincipal() );
        assertEquals( "user", e.actorType() );
    }

    @Test
    void principalFromDedicatedFieldIsUsed() {
        // 4-arg form: principal lives in its dedicated field, target is null.
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.LOGIN_AUTHENTICATED, named( "carol" ), null ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "carol", e.actorPrincipal() );
        assertEquals( "user", e.actorType() );
    }

    @Test
    void missingPrincipalRecordsAnonymous() {
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.LOGOUT, null, null ) );

        final AuditEntry e = onlyEntry();
        assertNull( e.actorPrincipal() );
        assertEquals( "anonymous", e.actorType() );
    }

    @Test
    void unmappedSecurityEventIsIgnored() {
        // PRINCIPAL_ADD is not an audited transition.
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.PRINCIPAL_ADD, named( "x" ) ) );

        assertTrue( svc.recorded.isEmpty() );
    }

    // ---------------------------------------------------------------- page events

    @Test
    void pageSaveMapsToContentSave() {
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.POST_SAVE, "MyPage" ) );

        final AuditEntry e = onlyEntry();
        assertEquals( AuditCategory.CONTENT, e.category() );
        assertEquals( "page.save", e.eventType() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
        assertEquals( "system", e.actorType() );
        assertEquals( "page", e.targetType() );
        assertEquals( "MyPage", e.targetId() );
    }

    @Test
    void pageDeleteMapsToContentDelete() {
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Gone" ) );

        final AuditEntry e = onlyEntry();
        assertEquals( AuditCategory.CONTENT, e.category() );
        assertEquals( "page.delete", e.eventType() );
        assertEquals( "Gone", e.targetId() );
    }

    @Test
    void unmappedPageEventIsIgnored() {
        // PAGE_LOCK is not an audited page transition.
        listener.actionPerformed( new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "Whatever" ) );

        assertTrue( svc.recorded.isEmpty() );
    }

    // ---------------------------------------------------------------- access.denied target

    @AfterEach
    void clearMdc() { ThreadContext.clearAll(); }

    @Test
    void accessDeniedWithPagePermissionMapsPageTarget() {
        final PagePermission perm = new PagePermission( "*:SecretPage", "edit" );
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, named( "alice" ), perm ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "access.denied", e.eventType() );
        assertEquals( AuditOutcome.DENIED, e.outcome() );
        assertEquals( "alice", e.actorPrincipal() );
        assertEquals( "page", e.targetType() );
        assertEquals( "SecretPage", e.targetId() );
        assertEquals( "edit → SecretPage", e.targetLabel() );
        assertTrue( e.detail().contains( "\"permission\":\"*:SecretPage\"" ),
            "detail should carry the permission name: " + e.detail() );
    }

    @Test
    void accessDeniedWithWikiPermissionMapsWikiTarget() {
        // WikiPermission.getActions() always returns the action lower-cased (per its Javadoc),
        // so the target id/label are "createpages", not "createPages".
        final WikiPermission perm = new WikiPermission( "*", "createPages" );
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, named( "bob" ), perm ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "wiki", e.targetType() );
        assertEquals( "createpages", e.targetId() );
        assertEquals( "createpages", e.targetLabel() );
    }

    @Test
    void accessDeniedWithNullPermissionDoesNotThrowAndStillRecords() {
        // Mirrors DefaultAuthorizationManager's session==null branch: principal and target both null.
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, null, null ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "access.denied", e.eventType() );
        assertNull( e.targetType() );
        assertNull( e.targetId() );
        assertNull( e.detail() );
    }

    @Test
    void accessDeniedWithAllPermissionMapsAllTarget() {
        // AdminAuthFilter denies with an AllPermission; its getActions() returns null,
        // and the mapping deliberately uses fixed "*"/label values (never calls getActions()).
        final AllPermission perm = new AllPermission( "testwiki" );
        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.ACCESS_DENIED, named( "carol" ), perm ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "all", e.targetType() );
        assertEquals( "*", e.targetId() );
        assertEquals( "admin (AllPermission)", e.targetLabel() );
        assertTrue( e.detail().contains( "\"permission\":\"testwiki\"" ), e.detail() );
    }

    @Test
    void securityEntryStampsRequestContextFromMdc() {
        ThreadContext.put( "remoteAddr", "203.0.113.7" );
        ThreadContext.put( "userAgent",  "curl/8.4.0" );
        ThreadContext.put( "requestId",  "req-xyz" );

        listener.actionPerformed(
            new WikiSecurityEvent( this, WikiSecurityEvent.LOGIN_FAILED, named( "mallory" ) ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "203.0.113.7", e.sourceIp() );
        assertEquals( "curl/8.4.0",  e.userAgent() );
        assertEquals( "req-xyz",      e.correlationId() );
    }

    // ---------------------------------------------------------------- rename events

    @Test
    void pageRenameRecordsFromToDetail() {
        listener.actionPerformed( new WikiPageRenameEvent( this, "Old", "New" ) );

        final AuditEntry e = onlyEntry();
        assertEquals( AuditCategory.CONTENT, e.category() );
        assertEquals( "page.rename", e.eventType() );
        assertEquals( AuditOutcome.SUCCESS, e.outcome() );
        assertEquals( "page", e.targetType() );
        assertEquals( "New", e.targetId() );
        assertEquals( "{\"from\":\"Old\",\"to\":\"New\"}", e.detail() );
    }

    @Test
    void pageRenameEscapesQuotesAndBackslashesInDetail() {
        // old = a"b  (embedded double-quote)   new = c\d  (embedded backslash)
        listener.actionPerformed( new WikiPageRenameEvent( this, "a\"b", "c\\d" ) );

        final AuditEntry e = onlyEntry();
        assertEquals( "{\"from\":\"a\\\"b\",\"to\":\"c\\\\d\"}", e.detail() );
    }
}
