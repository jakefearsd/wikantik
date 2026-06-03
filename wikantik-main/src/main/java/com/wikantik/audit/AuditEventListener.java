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

import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;

import java.security.Principal;
import java.time.Instant;

/** Translates WikiEvents into AuditEntries and records them via AuditService. */
public final class AuditEventListener implements WikiEventListener {

    private final AuditService audit;

    public AuditEventListener( final AuditService audit ) { this.audit = audit; }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof WikiSecurityEvent se ) {
            final AuditEntry entry = mapSecurity( se );
            if ( entry != null ) audit.record( entry );
        }
        // WikiPageEvent / WikiPageRenameEvent mapping will be added in Task 8.
    }

    private AuditEntry mapSecurity( final WikiSecurityEvent se ) {
        final int t = se.getType();
        final String principal = principalName( se );
        final AuditCategory category;
        final AuditOutcome outcome;
        final String eventType;
        switch ( t ) {
            case WikiSecurityEvent.LOGIN_AUTHENTICATED -> { category = AuditCategory.AUTHN; outcome = AuditOutcome.SUCCESS; eventType = "login.ok"; }
            case WikiSecurityEvent.LOGIN_FAILED        -> { category = AuditCategory.AUTHN; outcome = AuditOutcome.FAILURE; eventType = "login.failed"; }
            case WikiSecurityEvent.LOGOUT              -> { category = AuditCategory.AUTHN; outcome = AuditOutcome.SUCCESS; eventType = "logout"; }
            case WikiSecurityEvent.SESSION_EXPIRED     -> { category = AuditCategory.AUTHN; outcome = AuditOutcome.SUCCESS; eventType = "session.expired"; }
            case WikiSecurityEvent.ACCESS_DENIED       -> { category = AuditCategory.AUTHZ; outcome = AuditOutcome.DENIED;  eventType = "access.denied"; }
            case WikiSecurityEvent.GROUP_ADD           -> { category = AuditCategory.ADMIN; outcome = AuditOutcome.SUCCESS; eventType = "group.member.add"; }
            case WikiSecurityEvent.GROUP_REMOVE        -> { category = AuditCategory.ADMIN; outcome = AuditOutcome.SUCCESS; eventType = "group.member.remove"; }
            case WikiSecurityEvent.PROFILE_SAVE        -> { category = AuditCategory.ADMIN; outcome = AuditOutcome.SUCCESS; eventType = "profile.save"; }
            default -> { return null; }
        }
        return AuditEntry.builder()
            .eventTime( Instant.now() )
            .category( category ).eventType( eventType ).outcome( outcome )
            .actorPrincipal( principal )
            .actorType( principal == null ? "anonymous" : "user" )
            .build();
    }

    /**
     * Extracts a principal name from a security event.
     * WikiSecurityEvent has two fields: {@code principal} (dedicated) and {@code target} (changed object).
     * Many callers use the 3-arg constructor {@code (src, type, target)} which leaves {@code principal=null}
     * and stores the Principal as the target — so we check both in order.
     */
    private String principalName( final WikiSecurityEvent se ) {
        final Object p = se.getPrincipal();
        if ( p instanceof Principal principal ) return principal.getName();
        final Object tgt = se.getTarget();
        if ( tgt instanceof Principal principal ) return principal.getName();
        return null;
    }
}
