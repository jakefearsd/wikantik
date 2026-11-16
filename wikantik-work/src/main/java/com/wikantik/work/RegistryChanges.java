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
package com.wikantik.work;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;

/**
 * The side effects of a registry change that are not the change itself: which key a rotation mints, revoking a key
 * without masking the outcome that caused it, and the admin audit entry. Kept apart from {@link WorkRegistryService}
 * so that class stays under the complexity limits.
 */
final class RegistryChanges {

    private static final Logger LOG = LogManager.getLogger( RegistryChanges.class );
    private static final String UNIQUE_VIOLATION = "23505";

    private final AgentKeys keys;
    private final WorkAudit audit;

    RegistryChanges( final AgentKeys keys, final WorkAudit audit ) {
        this.keys = keys;
        this.audit = audit;
    }

    /**
     * The key a rotation mints: under {@code owner} when one was named; else from the old key, keeping its owner
     * (never the rotating admin), even when it was revoked; an agent with no key at all gets one owned by the admin.
     */
    AgentKeys.Minted rotationKey( final Actor actor, final Agent existing, final String owner, final String label ) {
        if ( owner != null ) {
            return keys.mint( owner, label, actor.login() );
        }
        return existing.apiKeyId() == null ? keys.mint( actor.login(), label, actor.login() )
            : keys.remint( existing.apiKeyId(), label, actor.login() );
    }

    /** A failed revoke must not mask the outcome that caused it; the key then lives on, so say so. */
    void revokeQuietly( final int keyId, final Actor by ) {
        try {
            keys.revoke( keyId, by.login() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not revoke a work-agent API key (requested by {}); it may still be live: {}", by.login(),
                e.getMessage(), e );
        }
    }

    /** The admin audit trail entry for a registry change; never a token or key id, never failing the change. */
    void audit( final Actor actor, final String action, final String type, final String target, final String detail ) {
        try {
            audit.record( actor.login(), action, type, target, detail );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not audit {} of {} '{}': {}", action, type, target, e.getMessage(), e );
        }
    }

    /** {@code patch} over {@code existing}: each null field of the patch keeps the agent's current value. */
    static AgentDraft merged( final AgentDraft patch, final Agent existing ) {
        return new AgentDraft( patch.name() == null ? existing.name() : patch.name(),
            patch.vendor() == null ? existing.vendor() : patch.vendor(),
            patch.runtime() == null ? existing.runtime() : patch.runtime(),
            patch.queues() == null ? existing.queues() : patch.queues(), patch.owner() );
    }

    static String orEmpty( final String s ) {
        return s == null ? "" : s.trim();
    }

    static WorkConflictException exists( final String message ) {
        return new WorkConflictException( message, WorkConflictException.EXISTS );
    }

    /** A unique violation is the {@code exists} conflict; anything else is returned to be rethrown. */
    static SQLException duplicateOrRethrow( final SQLException e, final String message ) {
        if ( UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
            throw exists( message );
        }
        return e;
    }
}
