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
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Queues, agents and the identity an API key stands for. Package-private collaborator of {@link WorkService}, which
 * delegates one line per method. Only a human wiki admin manages any of it; the check comes before every lookup, so
 * a caller who may not manage gets the denial whether or not the queue or agent exists.
 *
 * <p>Registry writes leave no {@code work_event} (the event table requires a project); each is logged at INFO with
 * who did what, never with a key or token.
 */
final class WorkRegistry {

    private static final Logger LOG = LogManager.getLogger( WorkRegistry.class );
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NO_SUCH_USER = "No such user.";

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;
    private final AgentKeys keys;
    private final Clock clock;

    WorkRegistry( final WorkStore store, final WorkScopes scopes, final WorkPeople people, final AgentKeys keys,
                  final Clock clock ) {
        this.store = store;
        this.scopes = scopes;
        this.people = people;
        this.keys = keys;
        this.clock = clock;
    }

    // ---- queues

    List< Queue > listQueues( final Actor actor ) throws SQLException {
        return canSeeRegistry( actor ) ? store.queues() : List.of();
    }

    Queue createQueue( final Actor actor, final String key, final String name, final String description )
            throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireQueueKey( key );
        WorkInputs.requireName( name );
        if ( store.queue( key ).isPresent() ) {
            throw exists( "Queue " + key + " already exists." );
        }
        try {
            final Queue q = store.createQueue( key, name.trim(), description == null ? "" : description );
            LOG.info( "Work queue '{}' created by {}", key, actor.login() );
            return q;
        } catch ( final SQLException e ) {
            throw duplicateOrRethrow( e, "Queue " + key + " already exists." );
        }
    }

    void updateQueue( final Actor actor, final String key, final String name, final String description )
            throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireQueueKey( key );
        WorkInputs.requireName( name );
        requireQueue( key );
        store.updateQueue( key, name.trim(), description == null ? "" : description );
        LOG.info( "Work queue '{}' updated by {}", key, actor.login() );
    }

    void retireQueue( final Actor actor, final String key ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireQueueKey( key );
        requireQueue( key );
        store.retireQueue( key, clock.instant() );
        LOG.info( "Work queue '{}' retired by {}", key, actor.login() );
    }

    private Queue requireQueue( final String key ) throws SQLException {
        return store.queue( key ).orElseThrow( () -> new WorkNotFoundException( "No such queue: " + key ) );
    }

    // ---- agents

    /** A wiki admin sees every agent with its key id; anyone who belongs to a project sees them without it. */
    List< Agent > listAgents( final Actor actor ) throws SQLException {
        if ( actor.instanceAdmin() ) {
            return store.agents();
        }
        if ( !canSeeRegistry( actor ) ) {
            return List.of();
        }
        return store.agents().stream().map( a -> new Agent( a.id(), a.name(), a.vendor(), a.runtime(), null,
            a.queues(), a.createdAt(), a.retiredAt() ) ).toList();
    }

    WorkService.AgentWithToken registerAgent( final Actor actor, final AgentDraft draft ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final String name = agentName( draft.name() );
        final List< String > queues = servedQueues( draft.queues(), Set.of() );
        if ( store.agentByName( name ).isPresent() ) {
            throw exists( "Agent " + name + " already exists." );
        }
        final String owner = people.login( actor, draft.owner() == null || draft.owner().isBlank()
                ? actor.login() : draft.owner() )
            .orElseThrow( () -> new WorkValidationException( "owner", NO_SUCH_USER ) );
        final AgentKeys.Minted minted = keys.mint( owner, "work-agent:" + name );
        try {
            final Agent agent = store.registerAgent( name, orEmpty( draft.vendor() ), orEmpty( draft.runtime() ),
                minted.keyId(), queues );
            LOG.info( "Work agent '{}' registered by {}", name, actor.login() );
            return new WorkService.AgentWithToken( agent, minted.token() );
        } catch ( final SQLException e ) {
            revokeQuietly( minted.keyId(), actor );
            throw duplicateOrRethrow( e, "Agent " + name + " already exists." );
        } catch ( final RuntimeException e ) {
            revokeQuietly( minted.keyId(), actor );
            throw e;
        }
    }

    void updateAgent( final Actor actor, final long agentId, final AgentDraft draft ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Agent existing = requireActiveAgent( agentId );
        final String name = agentName( draft.name() );
        final List< String > queues = servedQueues( draft.queues(), new LinkedHashSet<>( existing.queues() ) );
        final Agent sameName = store.agentByName( name ).orElse( null );
        if ( sameName != null && sameName.id() != agentId ) {
            throw exists( "Agent " + name + " already exists." );
        }
        try {
            store.updateAgent( agentId, name, orEmpty( draft.vendor() ), orEmpty( draft.runtime() ), queues );
        } catch ( final SQLException e ) {
            throw duplicateOrRethrow( e, "Agent " + name + " already exists." );
        }
        LOG.info( "Work agent '{}' updated by {}", existing.name(), actor.login() );
    }

    /** Mint, rebind, then revoke the old key: the agent is never left on a revoked key nor a live key orphaned. */
    WorkService.AgentWithToken rotateAgentKey( final Actor actor, final long agentId ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Agent existing = requireActiveAgent( agentId );
        final AgentKeys.Minted minted = keys.mint( actor.login(), "work-agent:" + existing.name() );
        try {
            store.rebindAgentKey( agentId, minted.keyId() );
        } catch ( final SQLException | RuntimeException e ) {
            revokeQuietly( minted.keyId(), actor );
            throw e;
        }
        if ( existing.apiKeyId() != null ) {
            revokeQuietly( existing.apiKeyId(), actor );
        }
        LOG.info( "Work agent '{}' key rotated by {}", existing.name(), actor.login() );
        return new WorkService.AgentWithToken( new Agent( existing.id(), existing.name(), existing.vendor(),
            existing.runtime(), minted.keyId(), existing.queues(), existing.createdAt(), null ), minted.token() );
    }

    void retireAgent( final Actor actor, final long agentId ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Agent existing = store.agent( agentId )
            .orElseThrow( () -> new WorkNotFoundException( "No such agent: " + agentId ) );
        if ( !existing.active() ) {
            return;
        }
        store.claims().retireAgent( agentId, clock.instant(), actor );
        if ( existing.apiKeyId() != null ) {
            revokeQuietly( existing.apiKeyId(), actor );
        }
        LOG.info( "Work agent '{}' retired by {}", existing.name(), actor.login() );
    }

    /**
     * The only place an actor's kind is derived: a key bound to an active agent is that agent; a key bound to a
     * retired agent is no actor (null, never its owner); any other key is its owner as a person, never admin.
     */
    Actor actorForApiKey( final int apiKeyId, final String ownerLogin ) throws SQLException {
        final Agent bound = store.agentByApiKey( apiKeyId ).orElse( null );
        if ( bound == null ) {
            return Actor.human( ownerLogin, scopes.groupsOf( ownerLogin ), false );
        }
        return bound.active() ? Actor.agent( bound.id(), bound.name() ) : null;
    }

    /**
     * The principal as stored: a group name as given; for a user, the login the reference names (a display name, or
     * from a wiki admin a login), with one message for an unknown user whether or not the text happens to be a login;
     * for an agent, its id.
     */
    String memberPrincipal( final Actor actor, final String principal, final String principalKind,
                            final boolean mustBeActive ) throws SQLException {
        if ( "agent".equals( principalKind ) ) {
            return agentMemberPrincipal( principal, mustBeActive );
        }
        if ( !"user".equals( principalKind ) ) {
            return principal.trim();
        }
        return people.login( actor, principal )
            .orElseThrow( () -> new WorkValidationException( "principal", NO_SUCH_USER ) );
    }

    /**
     * The stored principal of an agent member: the agent's id as text. An agent to add must be active; one to
     * remove may be retired, so its membership rows can still be cleared.
     */
    private String agentMemberPrincipal( final String name, final boolean mustBeActive ) throws SQLException {
        final Agent agent = store.agentByName( name.trim().toLowerCase( Locale.ROOT ) ).orElse( null );
        if ( agent == null || mustBeActive && !agent.active() ) {
            throw new WorkValidationException( "principal", "No such agent." );
        }
        return String.valueOf( agent.id() );
    }

    // ---- helpers

    private boolean canSeeRegistry( final Actor actor ) throws SQLException {
        return actor.instanceAdmin() || !store.visibleProjects( actor ).isEmpty();
    }

    private Agent requireActiveAgent( final long agentId ) throws SQLException {
        final Agent a = store.agent( agentId ).orElse( null );
        if ( a == null || !a.active() ) {
            throw new WorkNotFoundException( "No such agent: " + agentId );
        }
        return a;
    }

    private static String agentName( final String raw ) {
        final String name = raw == null ? null : raw.trim().toLowerCase( Locale.ROOT );
        WorkInputs.requireAgentName( name );
        return name;
    }

    /** The queue keys, each of which must exist and be active, or already be served ({@code alreadyServed}). */
    private List< String > servedQueues( final List< String > requested, final Set< String > alreadyServed )
            throws SQLException {
        final List< String > out = new ArrayList<>();
        for ( final String key : new LinkedHashSet<>( requested == null ? List.< String >of() : requested ) ) {
            final Queue q = key == null ? null : store.queue( key ).orElse( null );
            if ( q == null || q.retiredAt() != null && !alreadyServed.contains( key ) ) {
                throw new WorkValidationException( "queues", "No such queue: " + key );
            }
            out.add( key );
        }
        return out;
    }

    /** A failed revoke must not mask the outcome that caused it; the key then lives on, so say so. */
    private void revokeQuietly( final int keyId, final Actor by ) {
        try {
            keys.revoke( keyId, by.login() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not revoke a work-agent API key (requested by {}); it may still be live: {}", by.login(),
                e.getMessage(), e );
        }
    }

    private static String orEmpty( final String s ) {
        return s == null ? "" : s.trim();
    }

    private static WorkConflictException exists( final String message ) {
        return new WorkConflictException( message, WorkConflictException.EXISTS );
    }

    private static SQLException duplicateOrRethrow( final SQLException e, final String message ) {
        if ( UNIQUE_VIOLATION.equals( e.getSQLState() ) ) {
            throw exists( message );
        }
        return e;
    }
}
