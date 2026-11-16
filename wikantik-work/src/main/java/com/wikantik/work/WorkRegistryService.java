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
 * Queues, agents and the identity an API key stands for: the registry facade, reached through
 * {@link WorkService#registry()}. Only a human wiki admin manages any of it; the check comes before every lookup, so
 * a caller who may not manage gets the denial whether or not the queue or agent exists.
 *
 * <p>Registry writes leave no {@code work_event} (the event table requires a project); each is logged at INFO with
 * who did what, never with a key or token.
 */
public final class WorkRegistryService {

    private static final Logger LOG = LogManager.getLogger( WorkRegistryService.class );
    private static final String NO_SUCH_USER = "No such user.";
    private static final String QUEUE = "work_queue";
    private static final String AGENT = "work_agent";

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;
    private final AgentKeys keys;
    private final GuardedListener listener;
    private final Clock clock;
    private final RegistryChanges changes;

    WorkRegistryService( final WorkStore store, final WorkScopes scopes, final WorkPeople people, final AgentKeys keys,
                  final Clock clock, final GuardedListener listener, final WorkAudit audit ) {
        this.store = store;
        this.changes = new RegistryChanges( keys, audit );
        this.listener = listener;
        this.scopes = scopes;
        this.people = people;
        this.keys = keys;
        this.clock = clock;
    }

    // ---- queues

    public List< Queue > listQueues( final Actor actor ) throws SQLException {
        return canSeeRegistry( actor ) ? store.queues() : List.of();
    }

    public Queue createQueue( final Actor actor, final String key, final String name, final String description )
            throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireQueueKey( key );
        WorkInputs.requireName( name );
        if ( store.queue( key ).isPresent() ) {
            throw RegistryChanges.exists( "Queue " + key + " already exists." );
        }
        try {
            final Queue q = store.createQueue( key, name.trim(), description == null ? "" : description );
            LOG.info( "Work queue '{}' created by {}", key, actor.login() );
            changes.audit( actor, "work.queue.create", QUEUE, key, null );
            return q;
        } catch ( final SQLException e ) {
            throw RegistryChanges.duplicateOrRethrow( e, "Queue " + key + " already exists." );
        }
    }

    public void updateQueue( final Actor actor, final String key, final String name, final String description )
            throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireQueueKey( key );
        WorkInputs.requireName( name );
        requireQueue( key );
        store.updateQueue( key, name.trim(), description == null ? "" : description );
        LOG.info( "Work queue '{}' updated by {}", key, actor.login() );
        changes.audit( actor, "work.queue.update", QUEUE, key, null );
    }

    public void retireQueue( final Actor actor, final String key ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        WorkInputs.requireQueueKey( key );
        requireQueue( key );
        store.retireQueue( key, clock.instant() );
        LOG.info( "Work queue '{}' retired by {}", key, actor.login() );
        changes.audit( actor, "work.queue.retire", QUEUE, key, null );
    }

    private Queue requireQueue( final String key ) throws SQLException {
        return store.queue( key ).orElseThrow( () -> new WorkNotFoundException( "No such queue: " + key ) );
    }

    // ---- agents

    /** A wiki admin sees every agent with its key id; anyone who belongs to a project sees them without it. */
    public List< Agent > listAgents( final Actor actor ) throws SQLException {
        if ( actor.instanceAdmin() ) {
            return store.agents();
        }
        if ( !canSeeRegistry( actor ) ) {
            return List.of();
        }
        return store.agents().stream().map( a -> new Agent( a.id(), a.name(), a.vendor(), a.runtime(), null,
            a.queues(), a.createdAt(), a.retiredAt() ) ).toList();
    }

    /**
     * The calling agent's own record (its key id never included), whether or not it was admitted to any project:
     * it needs to know its own name, vendor, runtime and queues. Empty for a person, for an agent actor without an
     * id, and for a retired agent.
     */
    public java.util.Optional< Agent > self( final Actor actor ) throws SQLException {
        if ( actor.kind() != Actor.Kind.AGENT || actor.agentId() == null ) {
            return java.util.Optional.empty();
        }
        return store.agent( actor.agentId() ).filter( Agent::active ).map( a -> new Agent( a.id(), a.name(),
            a.vendor(), a.runtime(), null, a.queues(), a.createdAt(), a.retiredAt() ) );
    }

    public WorkService.AgentWithToken registerAgent( final Actor actor, final AgentDraft draft ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final String name = agentName( draft.name() );
        final List< String > queues = servedQueues( draft.queues(), Set.of() );
        if ( store.agentByName( name ).isPresent() ) {
            throw RegistryChanges.exists( "Agent " + name + " already exists." );
        }
        final String owner = ownerLogin( actor, draft.owner() == null || draft.owner().isBlank()
                ? actor.login() : draft.owner() );
        final AgentKeys.Minted minted = keys.mint( owner, "work-agent:" + name, actor.login() );
        try {
            final Agent agent = store.registerAgent( name, RegistryChanges.orEmpty( draft.vendor() ), RegistryChanges.orEmpty( draft.runtime() ),
                minted.keyId(), queues );
            LOG.info( "Work agent '{}' registered by {}", name, actor.login() );
            changes.audit( actor, "work.agent.register", AGENT, name, null );
            return new WorkService.AgentWithToken( agent, minted.token() );
        } catch ( final SQLException e ) {
            changes.revokeQuietly( minted.keyId(), actor );
            throw RegistryChanges.duplicateOrRethrow( e, "Agent " + name + " already exists." );
        } catch ( final RuntimeException e ) {
            changes.revokeQuietly( minted.keyId(), actor );
            throw e;
        }
    }

    /** A partial update: a null name, vendor, runtime or queue list leaves that field as it is. */
    public void updateAgent( final Actor actor, final long agentId, final AgentDraft patch ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Agent existing = requireActiveAgent( agentId );
        final AgentDraft draft = RegistryChanges.merged( patch, existing );
        final String name = agentName( draft.name() );
        final List< String > queues = servedQueues( draft.queues(), new LinkedHashSet<>( existing.queues() ) );
        final Agent sameName = store.agentByName( name ).orElse( null );
        if ( sameName != null && sameName.id() != agentId ) {
            throw RegistryChanges.exists( "Agent " + name + " already exists." );
        }
        try {
            store.updateAgent( agentId, name, RegistryChanges.orEmpty( draft.vendor() ),
                RegistryChanges.orEmpty( draft.runtime() ), queues );
        } catch ( final SQLException e ) {
            throw RegistryChanges.duplicateOrRethrow( e, "Agent " + name + " already exists." );
        }
        LOG.info( "Work agent '{}' updated by {} (name '{}' -> '{}')", existing.name(), actor.login(), existing.name(),
            name );
        changes.audit( actor, "work.agent.update", AGENT, name, existing.name().equals( name ) ? null
            : "renamed from " + existing.name() );
    }

    /**
     * Mint, rebind, then revoke the old key: the agent is never left on a revoked key nor a live key orphaned. With
     * {@code newOwner} (a display name, or from an admin a login, as on register) the new key is owned by that person;
     * without, by whoever owned the old key (never by the rotating admin), even when the old key was revoked out of
     * band: rotation is how such an agent is restored.
     */
    public WorkService.AgentWithToken rotateAgentKey( final Actor actor, final long agentId, final String newOwner )
            throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Agent existing = requireActiveAgent( agentId );
        final String label = "work-agent:" + existing.name();
        final String owner = newOwner == null || newOwner.isBlank() ? null : ownerLogin( actor, newOwner );
        final AgentKeys.Minted minted = changes.rotationKey( actor, existing, owner, label );
        try {
            store.rebindAgentKey( agentId, minted.keyId() );
        } catch ( final SQLException | RuntimeException e ) {
            changes.revokeQuietly( minted.keyId(), actor );
            throw e;
        }
        if ( existing.apiKeyId() != null ) {
            changes.revokeQuietly( existing.apiKeyId(), actor );
        }
        LOG.info( "Work agent '{}' key rotated by {}{}", existing.name(), actor.login(),
            owner == null ? "" : " to a new owner" );
        changes.audit( actor, "work.agent.rotate_key", AGENT, existing.name(),
            owner == null ? null : "new owner " + owner );
        return new WorkService.AgentWithToken( new Agent( existing.id(), existing.name(), existing.vendor(),
            existing.runtime(), minted.keyId(), existing.queues(), existing.createdAt(), null ), minted.token() );
    }

    public void retireAgent( final Actor actor, final long agentId ) throws SQLException {
        scopes.requireInstance( actor, Action.MANAGE_INSTANCE );
        final Agent existing = store.agent( agentId )
            .orElseThrow( () -> new WorkNotFoundException( "No such agent: " + agentId ) );
        if ( existing.active() ) {
            listener.released( store.claims().retireAgent( agentId, clock.instant(), actor ) );
        }
        if ( existing.apiKeyId() != null ) {
            changes.revokeQuietly( existing.apiKeyId(), actor );
        }
        LOG.info( "Work agent '{}' retired by {}", existing.name(), actor.login() );
        changes.audit( actor, "work.agent.retire", AGENT, existing.name(), null );
    }

    /**
     * The only place an actor's kind is derived: a key bound to an active agent is that agent; a key bound to a
     * retired agent, or one that was ever bound to an agent and since rotated away, is no actor (null, never its
     * owner, even if its revocation failed); a key never bound to an agent is its owner as a person, never admin.
     */
    public Actor actorForApiKey( final int apiKeyId, final String ownerLogin ) throws SQLException {
        final Agent bound = store.agentByApiKey( apiKeyId ).orElse( null );
        if ( bound == null ) {
            if ( store.wasEverAgentKey( apiKeyId ) ) {
                return null;
            }
            return Actor.human( ownerLogin, scopes.groupsOf( ownerLogin ), false );
        }
        return bound.active() ? Actor.agent( bound.id(), bound.name() ) : null;
    }

    /**
     * The name of the agent the key is bound to now, or was bound to before a rotation (retired agents included);
     * empty for any other key. Agent keys are managed only through the registry, so the generic key endpoints use
     * this to refuse rotating or revoking one. A system lookup: it takes no actor and the caller decides who may see
     * the name.
     */
    public java.util.Optional< String > agentNameForKey( final int apiKeyId ) throws SQLException {
        return store.agentEverBoundTo( apiKeyId ).map( Agent::name );
    }

    /** Whether the key is, or ever was, an agent's key ({@link #agentNameForKey} is present). */
    public boolean isAgentKey( final int apiKeyId ) throws SQLException {
        return agentNameForKey( apiKeyId ).isPresent();
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

    private String ownerLogin( final Actor actor, final String reference ) throws SQLException {
        return people.login( actor, reference )
            .orElseThrow( () -> new WorkValidationException( "owner", NO_SUCH_USER ) );
    }

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
}
