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
package com.wikantik.collab.adapter;

import com.google.gson.Gson;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import com.wikantik.collab.CollabAudit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link CollabAudit} over the wiki's tamper-evident {@link AuditService} ({@code /admin/audit}, docs/MultiEdit.MD
 * section 9). Entries are category {@code CONTENT} ({@code ADMIN} for a purge or expiry), target type {@code page} with
 * the page name as target id, actor principal the author key with actor type {@code user} ({@code system}, with no
 * principal, for retention's expiry and an orphaned draft). The {@code detail} JSON carries the session id and the
 * event's own fields; a publish lists its contributors' author keys (the audit log is an administrator surface: section
 * 0's "collab audit entries name the publisher and the contributors").
 *
 * <p>The audit service is read through {@code audit} at each entry: the engine builds it after the collaboration
 * wiring. A missing service or a failing {@link AuditService#record} logs at WARN and never throws.</p>
 */
public final class AuditServiceCollabAudit implements CollabAudit {

    private static final Logger LOG = LogManager.getLogger( AuditServiceCollabAudit.class );
    private static final Gson GSON = new Gson();
    private static final String USER = "user";
    private static final String SYSTEM = "system";

    private final Supplier< AuditService > audit;

    public AuditServiceCollabAudit( final Supplier< AuditService > audit ) {
        this.audit = audit;
    }

    /** One entry about {@code page}; {@code fields} follow the session id in the detail. */
    private void record( final String type, final AuditCategory category, final String actor, final String actorType,
                         final UUID session, final String page, final Map< String, Object > fields ) {
        final Map< String, Object > detail = new LinkedHashMap<>();
        detail.put( "session", String.valueOf( session ) );
        detail.putAll( fields );
        try {
            final AuditService service = audit.get();
            if( service == null ) {
                LOG.warn( "collab session {}: no audit service; the {} entry for {} was not recorded", session, type,
                    page );
                return;
            }
            service.record( AuditEntry.builder()
                .eventTime( Instant.now() )
                .category( category )
                .eventType( type )
                .outcome( AuditOutcome.SUCCESS )
                .actorPrincipal( actor )
                .actorType( actorType )
                .targetType( "page" )
                .targetId( page )
                .targetLabel( page )
                .detail( GSON.toJson( detail ) )
                .build() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the {} entry for {} was not recorded: {}", session, type, page, e.toString() );
        }
    }

    private void content( final String type, final String actor, final UUID session, final String page,
                          final Map< String, Object > fields ) {
        record( type, AuditCategory.CONTENT, actor, USER, session, page, fields );
    }

    @Override
    public void sessionOpened( final UUID session, final String page, final String authorKey ) {
        content( "collab.session.open", authorKey, session, page, Map.of() );
    }

    @Override
    public void joined( final UUID session, final String page, final String authorKey, final String participantId ) {
        content( "collab.join", authorKey, session, page, Map.of( "participantId", String.valueOf( participantId ) ) );
    }

    @Override
    public void published( final UUID session, final String page, final String publisherKey, final int pageVersion,
                           final int collabVersion, final Set< String > contributorKeys ) {
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( "pageVersion", pageVersion );
        f.put( "collabVersion", collabVersion );
        f.put( "contributors", List.copyOf( contributorKeys ) );
        content( "collab.publish", publisherKey, session, page, f );
    }

    @Override
    public void discarded( final UUID session, final String page, final String authorKey ) {
        content( "collab.discard", authorKey, session, page, Map.of() );
    }

    @Override
    public void evicted( final UUID session, final String page, final String authorKey, final String reason ) {
        content( "collab.evict", authorKey, session, page, Map.of( "reason", String.valueOf( reason ) ) );
    }

    @Override
    public void purged( final UUID session, final String page, final String adminKey, final boolean expired ) {
        if( expired ) {
            record( "collab.expire", AuditCategory.ADMIN, null, SYSTEM, session, page, Map.of() );
        } else {
            record( "collab.purge", AuditCategory.ADMIN, adminKey, USER, session, page, Map.of() );
        }
    }

    @Override
    public void orphaned( final UUID session, final String page ) {
        record( "collab.orphaned", AuditCategory.CONTENT, null, SYSTEM, session, page, Map.of() );
    }
}
