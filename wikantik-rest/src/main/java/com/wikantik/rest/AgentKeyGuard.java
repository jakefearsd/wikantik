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
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.work.WorkService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Agent keys are managed only through the work registry (rotate or retire the agent), never through the generic key
 * endpoints: a person rotating an agent's key there would get an unbound key that acts as that person, and revoking it
 * would silently disable an agent the registry still shows as active. The self-service and admin key resources ask the
 * registry, through {@link com.wikantik.work.WorkRegistryService#agentNameForKey}, whether a key is or ever was an
 * agent's before they rotate or revoke it.
 */
final class AgentKeyGuard {

    /** The one refusal for a rotate or revoke of an agent key. */
    static final String REFUSAL = "This key belongs to a work agent. Rotate or retire the agent on the Agents page.";

    /** The refusal to rotate a {@code work} key while the registry that knows agent keys is not running (P32). */
    static final String WORK_NOT_RUNNING =
        "Work management is not running, so a key with the work scope cannot be rotated right now.";

    private static final Logger LOG = LogManager.getLogger( AgentKeyGuard.class );

    /** Looks up the agent a key belongs to (the resources' test seam). */
    @FunctionalInterface
    interface Lookup {
        Optional< String > agentOf( int keyId ) throws SQLException;
    }

    private AgentKeyGuard() {
    }

    /** The work service, or null when work is disabled or failed to wire (then no agent key can exist). */
    static WorkService work( final Engine engine ) {
        return engine instanceof WikiEngine we ? we.workService() : null;
    }

    /** The agent the key is or was bound to; empty for any other key, and whenever work is off. */
    static Optional< String > agentOf( final Engine engine, final int keyId ) throws SQLException {
        final WorkService svc = work( engine );
        return svc == null ? Optional.empty() : svc.registry().agentNameForKey( keyId );
    }

    /**
     * Sends 409 and returns true when the key is an agent's; sends 503 and returns true when that cannot be told
     * (the check fails closed: nothing is rotated or revoked); returns false, sending nothing, for any other key.
     */
    static boolean refused( final RestServletBase servlet, final Lookup lookup, final int keyId,
                            final HttpServletResponse response ) throws IOException {
        try {
            if ( lookup.agentOf( keyId ).isEmpty() ) {
                return false;
            }
            LOG.info( "Refused a generic rotate or revoke of agent key id={}", keyId );
            servlet.sendError( response, HttpServletResponse.SC_CONFLICT, REFUSAL );
        } catch ( final SQLException e ) {
            LOG.warn( "Could not tell whether key id={} is a work agent's; refusing to change it: {} ({})", keyId,
                e.getClass().getSimpleName(), e.getSQLState() );
            servlet.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service temporarily unavailable" );
        }
        return true;
    }

    /**
     * Sends 503 and returns true when a key of the {@code work} scope is to be rotated while work management is not
     * running (flag off, or wiring failed): agent keys and their history persist in the database, but nothing can
     * tell them apart now, and rotating one would hand its owner a fresh key that acts as the owner. Revoking stays
     * allowed in that state (it disables, it never grants). Returns false, sending nothing, otherwise.
     */
    static boolean rotationRefusedWhileWorkIsDown( final RestServletBase servlet, final boolean workRunning,
                                                   final ApiKeyService.Scope scope,
                                                   final HttpServletResponse response ) throws IOException {
        if ( workRunning || scope != ApiKeyService.Scope.WORK ) {
            return false;
        }
        LOG.info( "Refused a generic rotation of a work key while work management is not running" );
        servlet.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, WORK_NOT_RUNNING );
        return true;
    }

    /**
     * The agent a listed key belongs to, for marking the row; empty (and a WARN) when the lookup fails, so a listing
     * still answers. The rotate and revoke paths check again and fail closed.
     */
    static Optional< String > agentForListing( final Lookup lookup, final int keyId ) {
        try {
            return lookup.agentOf( keyId );
        } catch ( final SQLException e ) {
            LOG.warn( "Could not tell whether key id={} is a work agent's; listed without the mark: {} ({})", keyId,
                e.getClass().getSimpleName(), e.getSQLState() );
            return Optional.empty();
        }
    }
}
