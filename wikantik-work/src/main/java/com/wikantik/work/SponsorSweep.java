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

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps claims in step with the sponsors of the agents that hold them; split from {@link WorkClaimsService} to
 * keep it under the complexity limits. A sponsor's standing is always looked up, never stored, so a change made
 * elsewhere in the wiki (an account, a group, the admin role) is noticed here.
 */
final class SponsorSweep {

    private static final Logger LOG = LogManager.getLogger( SponsorSweep.class );

    private final WorkStore store;
    private final WorkScopes scopes;
    private final GuardedListener listener;
    /** Whether the last sweep met a directory that did not answer (so the WARN is written once per outage). */
    private volatile boolean sponsorLookupDown;

    SponsorSweep( final WorkStore store, final WorkScopes scopes, final GuardedListener listener ) {
        this.store = store;
        this.scopes = scopes;
        this.listener = listener;
    }

    /**
     * The sponsors of the agents that hold a claim in the project, each resolved as they are now, for the member
     * transaction to check again once it has changed a row. A sponsor who cannot be resolved (no account, or the
     * directory did not answer) is left out: {@link #sweepStanding} ends those claims, and a lookup that did not
     * answer must end nothing.
     */
    List< Actor > sponsorsHoldingClaimsIn( final long projectId ) throws SQLException {
        final Map< String, Actor > sponsors = new java.util.LinkedHashMap<>();
        for ( final WorkClaimStore.Holder h : store.claims().claimHolders( projectId ) ) {
            if ( h.sponsor() != null && !sponsors.containsKey( h.sponsor() ) ) {
                try {
                    scopes.person( h.sponsor() ).ifPresent( p -> sponsors.put( p.login(),
                        Actor.human( p.login(), p.groups(), p.admin() ) ) );
                } catch ( final WorkUnavailableException e ) {
                    LOG.warn( "A sponsor could not be looked up during a membership change; the claim sweep will "
                        + "re-check their agents: {}", e.getMessage() );
                }
            }
        }
        return List.copyOf( sponsors.values() );
    }

    /**
     * Ends every claim its holder may no longer hold because of a change the work module was not told about: the
     * sponsor's account is gone, the agent has no sponsor, or the sponsor (through a group, or no longer an admin)
     * has less than {@code member} standing in the project. Each ending is a {@code released} event with the reason
     * {@code sponsor access removed}, attributed to the agent as an expiry is, and counted RELEASED. A sponsor whose
     * lookup fails is skipped, with one WARN per outage: a lookup that did not answer is never lost standing.
     *
     */
    int sweep() throws SQLException {
        final Map< String, java.util.Optional< Actor > > resolved = new java.util.HashMap<>();
        final Set< String > failed = new java.util.HashSet<>();
        int ended = 0;
        for ( final WorkClaimStore.Holder h : store.claims().claimHolders( null ) ) {
            if ( h.sponsor() != null && !resolved.containsKey( h.sponsor() ) && !failed.contains( h.sponsor() ) ) {
                try {
                    resolved.put( h.sponsor(), scopes.person( h.sponsor() )
                        .map( p -> Actor.human( p.login(), p.groups(), p.admin() ) ) );
                } catch ( final WorkUnavailableException e ) {
                    failed.add( h.sponsor() );
                }
            }
            if ( h.sponsor() != null && failed.contains( h.sponsor() ) ) {
                continue;
            }
            final Actor sponsor = h.sponsor() == null ? null : resolved.get( h.sponsor() ).orElse( null );
            if ( sponsor == null || !sponsor.instanceAdmin() && !scopes.standing().roleIn( h.projectId(), sponsor )
                    .map( r -> r.atLeast( WorkRole.MEMBER ) ).orElse( false ) ) {
                ended += store.claims().releaseForStanding( h.projectId(), h.agentId(), h.sponsor(),
                    Actor.agent( h.agentId(), h.agentName() ), JdbcWorkClaims.SPONSOR_ACCESS_REMOVED );
            }
        }
        warnOncePerOutage( !failed.isEmpty() );
        listener.released( ended );
        return ended;
    }

    private void warnOncePerOutage( final boolean failedNow ) {
        if ( failedNow && !sponsorLookupDown ) {
            LOG.warn( "The user directory did not answer while re-checking agents' sponsors; their claims are left "
                + "as they are until it does" );
        } else if ( !failedNow && sponsorLookupDown ) {
            LOG.info( "The user directory answers again; agents' sponsors are being re-checked" );
        }
        sponsorLookupDown = failedNow;
    }
}
