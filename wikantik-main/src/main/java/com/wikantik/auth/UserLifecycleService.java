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
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Calendar;
import java.util.Date;

/**
 * The single audited mechanism for deactivating / reactivating a user account.
 * Deactivation uses the existing indefinite-lock mechanism (a far-future
 * {@code lockExpiry}); the row is retained so audit and ownership references stay
 * intact. Shared by the admin UI and SCIM so decommission behaves identically
 * regardless of trigger.
 */
public final class UserLifecycleService {

    private static final Logger LOG = LogManager.getLogger( UserLifecycleService.class );

    /** Year-9999 sentinel — "locked indefinitely" without TIMESTAMP overflow. */
    static final Date INDEFINITE_LOCK_EXPIRY = indefinite();

    private static Date indefinite() {
        final Calendar c = Calendar.getInstance();
        c.clear();
        c.set( 9999, Calendar.DECEMBER, 31, 23, 59, 59 );
        return c.getTime();
    }

    private final UserDatabase db;
    private final AuditService audit;

    public UserLifecycleService( final UserDatabase db, final AuditService audit ) {
        this.db = db;
        this.audit = audit;
    }

    /** Deactivate (indefinite lock). {@code actor} = who initiated; {@code source}
     *  = trigger (e.g. "scim", "admin-ui"). */
    public void deactivate( final String loginName, final String actor, final String source )
            throws NoSuchPrincipalException, WikiSecurityException {
        final UserProfile p = db.findByLoginName( loginName );
        p.setLockExpiry( INDEFINITE_LOCK_EXPIRY );
        db.save( p );
        emit( "user.deactivate", p, actor, source );
    }

    /** Reactivate (clear the lock). */
    public void reactivate( final String loginName, final String actor, final String source )
            throws NoSuchPrincipalException, WikiSecurityException {
        final UserProfile p = db.findByLoginName( loginName );
        p.setLockExpiry( null );
        db.save( p );
        emit( "user.reactivate", p, actor, source );
    }

    private void emit( final String eventType, final UserProfile p, final String actor, final String source ) {
        try {
            audit.record( AuditEntry.builder()
                    .eventTime( Instant.now() )
                    .category( AuditCategory.ADMIN )
                    .eventType( eventType )
                    .outcome( AuditOutcome.SUCCESS )
                    .actorPrincipal( actor )
                    .actorType( actor == null ? "system" : "user" )
                    .targetType( "user" )
                    .targetId( p.getLoginName() )
                    .targetLabel( p.getLoginName() )
                    .detail( "{\"source\":\"" + ( source == null ? "" : source ) + "\"}" )
                    .build() );
        } catch ( final RuntimeException e ) {
            // Auditing is best-effort; the lifecycle change already persisted.
            LOG.warn( "Failed to record {} audit for user={}: {}", eventType, p.getLoginName(), e.getMessage(), e );
        }
    }
}
