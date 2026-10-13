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
package com.wikantik.rest;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;
import java.time.Instant;

/**
 * Shared audit-recording helpers for the admin surfaces.
 *
 * <p>Originally extracted from the byte-identical {@code recordAudit}/{@code currentLogin}
 * methods duplicated across {@link ConnectorCredentialsResource} and
 * {@code ConnectorAdminWriteHandlers}; the same skeleton had since been hand-copied into
 * the policy-grant and API-key resources too, so {@link #recordAdminAudit} now serves all
 * of them and {@link #recordConnectorAudit} is a thin alias for the connector target type.
 */
final class RestAuditSupport {

    private static final Logger LOG = LogManager.getLogger( RestAuditSupport.class );

    private RestAuditSupport() {
    }

    /**
     * Record a successful connector mutation to the audit log, wrapped in try/catch so a
     * broken audit backend never fails the mutation itself (the mutation already succeeded
     * by the time this runs). Only a {@link WikiEngine} exposes an {@link AuditService};
     * any other {@link Engine} is a silent no-op.
     *
     * @param engine      the resource's engine (from {@code getEngine()})
     * @param eventType   audit event type, e.g. {@code "connector.create"}
     * @param actorLogin  acting user login, or {@code null}
     * @param connectorId the connector the mutation targeted (audit {@code targetId})
     * @param targetLabel optional human label; recorded only when non-null
     */
    static void recordConnectorAudit( final Engine engine, final String eventType, final String actorLogin,
                                      final String connectorId, final String targetLabel ) {
        recordAdminAudit( engine, eventType, actorLogin, "connector", connectorId, targetLabel );
    }

    /**
     * Record a successful admin mutation to the audit log, wrapped in try/catch so a broken
     * audit backend never fails the mutation itself (the mutation already succeeded by the
     * time this runs). Only a {@link WikiEngine} exposes an {@link AuditService}; any other
     * {@link Engine} is a silent no-op.
     *
     * <p>Every admin mutation records the same envelope — ADMIN category, SUCCESS outcome,
     * {@code "user"} actor type, {@code Instant.now()} — so those are fixed here rather than
     * re-specified (and re-mistyped) at each call site.
     *
     * @param engine      the resource's engine (from {@code getEngine()})
     * @param eventType   audit event type, e.g. {@code "apikey.issue"}
     * @param actorLogin  acting user login, or {@code null}
     * @param targetType  the kind of thing mutated, e.g. {@code "policy"}, {@code "apikey"}
     * @param targetId    stable identifier of the thing mutated — use the SAME identifier
     *                    for every operation on it, or its events will not group together
     * @param targetLabel optional human label; recorded only when non-null
     */
    static void recordAdminAudit( final Engine engine, final String eventType, final String actorLogin,
                                  final String targetType, final String targetId, final String targetLabel ) {
        try {
            final AuditService audit = engine instanceof WikiEngine wikiEngine
                    ? wikiEngine.getAuditService() : null;
            if ( audit != null ) {
                final AuditEntry.Builder entry = AuditEntry.builder()
                        .eventTime( Instant.now() )
                        .category( AuditCategory.ADMIN )
                        .eventType( eventType )
                        .outcome( AuditOutcome.SUCCESS )
                        .actorPrincipal( actorLogin )
                        .actorType( "user" )
                        .targetType( targetType )
                        .targetId( targetId );
                if ( targetLabel != null ) entry.targetLabel( targetLabel );
                audit.record( entry.build() );
            }
        } catch ( final Exception auditEx ) {
            LOG.warn( "Failed to record audit entry for {} {} '{}': {}",
                targetType, eventType, targetId, auditEx.getMessage(), auditEx );
        }
    }

    /**
     * Record an API-key lifecycle event ({@code apikey.issue}, {@code apikey.revoke},
     * {@code apikey.rotate}) with the one target convention shared by the self-service and admin
     * surfaces: target type {@code "apikey"}, target id the numeric key id, target label the key's
     * label (or the id when it has none). Keeping it in one place is what makes an admin revocation
     * and a self-service revocation indistinguishable in the log apart from the actor.
     *
     * @param engine     the resource's engine (from {@code getEngine()})
     * @param eventType  audit event type, e.g. {@code "apikey.revoke"}
     * @param actorLogin acting user login, or {@code null}
     * @param keyId      the key the event concerns
     * @param label      the key's label, or {@code null} to fall back to the id
     */
    static void recordApiKeyAudit( final Engine engine, final String eventType, final String actorLogin,
                                   final int keyId, final String label ) {
        final String id = String.valueOf( keyId );
        recordAdminAudit( engine, eventType, actorLogin, "apikey", id, label != null ? label : id );
    }

    /** The acting user's login for {@code request}, or {@code null} if unauthenticated. */
    static String currentLogin( final HttpServletRequest request ) {
        final Principal p = request.getUserPrincipal();
        return p != null ? p.getName() : null;
    }
}
