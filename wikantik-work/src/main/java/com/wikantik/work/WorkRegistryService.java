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
    private final GuardedListener listener;
    private final Clock clock;
    private final RegistryChanges changes;
    private final AgentSponsorship sponsorship;

    WorkRegistryService( final WorkStore store, final WorkScopes scopes, final WorkPeople people,
                  final Clock clock, final GuardedListener listener, final WorkAudit audit ) {
        this.store = store;
        this.changes = new RegistryChanges( audit );
        this.sponsorship = new AgentSponsorship( store, scopes );
        this.listener = listener;
        this.scopes = scopes;
        this.people = people;
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

    /** How an agent stands right now: computed from its row and the directory, never stored. */
    public enum AgentStatus { ACTIVE, RETIRED, NO_SPONSOR, SPONSOR_GONE }

    /** An agent as the registry shows it: with its sponsor's public name (null when it has none) and status. */
    public record AgentView( Agent agent, String sponsorName, AgentStatus status ) {
    }

    /** Whether an agent's member row in a project has any effect right now, and if not, why. Computed, never stored. */
    public enum MemberStanding { EFFECTIVE, NO_SPONSOR, SPONSOR_GONE, SPONSOR_NOT_MEMBER, SPONSOR_VIEWER }

    /**
     * For each agent with a member row in the project, how that row stands: an agent's access there is capped by
     * its sponsor's, so a row can be on record and have no effect. Visible to whoever may see the project's
     * members. An agent whose sponsor could not be looked up is left out: nothing is claimed about it.
     */
    public java.util.Map< Long, MemberStanding > agentStandings( final Actor actor, final String projectKey )
            throws SQLException {
        final WorkScopes.Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        final java.util.Map< Long, MemberStanding > out = new java.util.LinkedHashMap<>();
        for ( final Member m : store.members( s.project().id() ) ) {
            if ( !"agent".equals( m.principalKind() ) || !m.principal().matches( "\\d{1,18}" ) ) {
                continue;
            }
            final long agentId = Long.parseLong( m.principal() );
            final Agent agent = store.agent( agentId ).orElse( null );
            if ( agent == null || !agent.active() ) {
                continue;
            }
            try {
                out.put( agentId, sponsorship.standingIn( s.project().id(), agent ) );
            } catch ( final WorkUnavailableException e ) {
                LOG.warn( "A sponsor could not be looked up for the member list; that agent's standing is left out: {}",
                    e.getMessage() );
            }
        }
        return out;
    }

    /**
     * The agents the caller may know of: every agent for a wiki admin; for anyone else the agents they sponsor and
     * the agents that are members of a project they can see. Never a key id.
     */
    public List< Agent > listAgents( final Actor actor ) throws SQLException {
        return sponsorship.visibleAgents( actor );
    }

    /** {@link #listAgents} with each agent's sponsor name and status. */
    public List< AgentView > agentViews( final Actor actor ) throws SQLException {
        final List< AgentView > out = new ArrayList<>();
        for ( final Agent a : listAgents( actor ) ) {
            out.add( view( a ) );
        }
        return out;
    }

    /**
     * The agent a change has just produced, as the response to that change shows it. Unlike {@link #agentViews} it
     * never depends on the user directory: the change has already committed, and a reply that failed now would lose
     * a one-time token (a rotation's old token is already dead) or make a retry fail on what the first attempt did.
     * The status is taken from the row alone (retired, no sponsor, else active); the sponsor's name is left out when
     * it cannot be read. It takes the agent the caller was just given, so it needs no actor.
     */
    public AgentView describe( final Agent agent ) {
        String sponsorName = null;
        if ( agent.sponsor() != null ) {
            try {
                sponsorName = scopes.displayName( agent.sponsor() );
            } catch ( final RuntimeException e ) {
                LOG.warn( "A sponsor's name could not be read for the reply to a registry change; it is left out: {}",
                    e.getMessage() );
            }
        }
        final AgentStatus status = !agent.active() ? AgentStatus.RETIRED
            : agent.sponsor() == null ? AgentStatus.NO_SPONSOR : AgentStatus.ACTIVE;
        return new AgentView( agent, sponsorName, status );
    }

    private AgentView view( final Agent a ) {
        return new AgentView( a, a.sponsor() == null ? null : scopes.displayName( a.sponsor() ),
            sponsorship.status( a ) );
    }

    /**
     * The calling agent's own record, whether or not it was admitted to any project: it needs to know its own name,
     * vendor, runtime and queues. Empty for a person, for an agent actor without an id, and for a retired agent.
     */
    public java.util.Optional< Agent > self( final Actor actor ) throws SQLException {
        if ( actor.kind() != Actor.Kind.AGENT || actor.agentId() == null ) {
            return java.util.Optional.empty();
        }
        return store.agent( actor.agentId() ).filter( Agent::active );
    }

    /**
     * Registers an agent for the caller, who becomes its sponsor: a lead of any unarchived project or a wiki admin.
     * The agent gets its own credential; the token is returned once and only its hash is kept. No API key is
     * involved. (The agent row and the credential are two transactions: if the second fails the agent exists with
     * no credential, which a rotation supplies.)
     */
    public WorkService.AgentWithToken registerAgent( final Actor actor, final AgentDraft draft ) throws SQLException {
        sponsorship.requireMayRegister( actor );
        final String name = agentName( draft.name() );
        final List< String > queues = servedQueues( draft.queues(), Set.of() );
        if ( store.agentByName( name ).isPresent() ) {
            throw RegistryChanges.exists( "Agent " + name + " already exists." );
        }
        final Agent agent;
        try {
            agent = store.registerAgent( name, RegistryChanges.orEmpty( draft.vendor() ),
                RegistryChanges.orEmpty( draft.runtime() ), queues, actor.login() );
        } catch ( final SQLException e ) {
            throw RegistryChanges.duplicateOrRethrow( e, "Agent " + name + " already exists." );
        }
        final String token = store.credentials().issue( agent.id(), actor.login() ).token();
        LOG.info( "Work agent '{}' registered by {}", name, actor.login() );
        changes.audit( actor, "work.agent.register", AGENT, name, null );
        return new WorkService.AgentWithToken( agent, token );
    }

    /**
     * A partial update: a null name, vendor, runtime or queue list leaves that field as it is. For the agent's
     * sponsor while they lead a project, or a wiki admin.
     */
    public void updateAgent( final Actor actor, final long agentId, final AgentDraft patch ) throws SQLException {
        final Agent existing = sponsorship.requireVisible( actor, agentId, false );
        sponsorship.requireMayEdit( actor, existing );
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
     * Issues a new credential and revokes the old one in one transaction: the old token is nobody from the next
     * request. For the agent's sponsor or a wiki admin.
     */
    public WorkService.AgentWithToken rotateCredential( final Actor actor, final long agentId ) throws SQLException {
        final Agent existing = sponsorship.requireVisible( actor, agentId, false );
        sponsorship.requireSponsorOrAdmin( actor, existing );
        final String token = store.credentials().issue( agentId, actor.login() ).token();
        LOG.info( "Work agent '{}' credential rotated by {}", existing.name(), actor.login() );
        changes.audit( actor, "work.agent.rotate_key", AGENT, existing.name(), null );
        return new WorkService.AgentWithToken( existing, token );
    }

    /** Retires the agent, releases its claims and revokes its credential; repeatable. Sponsor or wiki admin. */
    public void retireAgent( final Actor actor, final long agentId ) throws SQLException {
        final Agent existing = sponsorship.requireVisible( actor, agentId, true );
        sponsorship.requireSponsorOrAdmin( actor, existing );
        if ( existing.active() ) {
            listener.released( store.claims().retireAgent( agentId, clock.instant(), actor ) );
        }
        store.credentials().revokeAll( agentId );
        LOG.info( "Work agent '{}' retired by {}", existing.name(), actor.login() );
        changes.audit( actor, "work.agent.retire", AGENT, existing.name(), null );
    }

    /**
     * The caller becomes the agent's sponsor. Doing it oneself is the acceptance: nobody can make another person a
     * sponsor. A wiki admin may at any time; a lead of a project the agent is in may only while nobody stands
     * behind it ({@link AgentSponsorship#requireMayTakeOver}).
     */
    public Agent takeOver( final Actor actor, final long agentId ) throws SQLException {
        final Agent existing = sponsorship.requireVisible( actor, agentId, false );
        sponsorship.requireMayTakeOver( actor, existing );
        // One transaction: the new sponsor, the history row, and the end of every claim outside their reach.
        listener.released( store.claims().setSponsorAndRelease( agentId, existing.sponsor(), actor, actor.login(), actor ) );
        LOG.info( "Work agent '{}' taken over by {}", existing.name(), actor.login() );
        changes.audit( actor, "work.agent.takeover", AGENT, existing.name(), null );
        return store.agent( agentId ).orElse( existing );
    }

    /** The sponsor (or a wiki admin) gives the agent up: it is no actor until someone takes it over. */
    public void releaseSponsorship( final Actor actor, final long agentId ) throws SQLException {
        final Agent existing = sponsorship.requireVisible( actor, agentId, false );
        sponsorship.requireSponsorOrAdmin( actor, existing );
        // With nobody behind it the agent may hold nothing: every claim ends in the same transaction.
        listener.released( store.claims().setSponsorAndRelease( agentId, existing.sponsor(), null, actor.login(), actor ) );
        LOG.info( "Work agent '{}' released by its sponsor ({})", existing.name(), actor.login() );
        changes.audit( actor, "work.agent.sponsor_released", AGENT, existing.name(), null );
    }

    /**
     * The actor for a personal API key with the work scope: its owner as a person, with their groups as they are
     * now and never as a wiki admin. No API key is, was or can become an agent: agents have their own credential
     * ({@link #agentForToken}).
     */
    public Actor actorForApiKey( final int apiKeyId, final String ownerLogin ) {
        return Actor.human( ownerLogin, scopes.groupsOf( ownerLogin ), false );
    }

    /** A verified agent token: the credential it is and the agent it names. */
    public record AgentLogin( long credentialId, long agentId ) {
    }

    /**
     * The agent a bearer token names: empty unless it is shaped like an agent token and matches a live credential.
     * Anything else (a personal API key among them) never reaches the credential table. Nothing is cached, so a
     * rotation or retirement holds from the next request; the use is recorded at most about once a minute. A system
     * lookup: it takes no actor, and a store failure is an exception, never "unknown token".
     */
    public java.util.Optional< AgentLogin > agentForToken( final String token ) throws SQLException {
        if ( !AgentTokens.looksLikeAgentToken( token ) ) {
            return java.util.Optional.empty();
        }
        final WorkCredentialStore credentials = store.credentials();
        final java.util.Optional< WorkCredentialStore.Live > live = credentials.findLive( AgentTokens.hash( token ) );
        if ( live.isEmpty() ) {
            return java.util.Optional.empty();
        }
        try {
            credentials.touch( live.get().credentialId() );
        } catch ( final SQLException e ) {
            // Bookkeeping only: the token was verified, and refusing it would turn a failed timestamp into an outage.
            LOG.warn( "Could not record the last use of work agent credential {}: {}", live.get().credentialId(),
                e.getMessage() );
        }
        return java.util.Optional.of( new AgentLogin( live.get().credentialId(), live.get().agentId() ) );
    }

    /**
     * The actor for a verified agent, resolved for this request: null when the agent is unknown, retired, has no
     * sponsor, or its sponsor's account is gone ({@link #agentActor}). A system lookup, like
     * {@link #actorForApiKey}.
     */
    public Actor actorForAgent( final long agentId ) throws SQLException {
        final Agent agent = store.agent( agentId ).orElse( null );
        return agent == null ? null : agentActor( agent );
    }

    /**
     * The agent as an actor for this request, with its sponsor resolved from the directory as they are now; null
     * (no actor at all) when the agent is retired, has no sponsor, or its sponsor's account no longer exists.
     * Nothing about the sponsor is stored, so the agent works again the moment the account does. A directory
     * failure is {@link WorkUnavailableException}, never "no actor".
     */
    Actor agentActor( final Agent agent ) {
        if ( !agent.active() || agent.sponsor() == null ) {
            return null;
        }
        return scopes.person( agent.sponsor() )
            .map( p -> Actor.agent( agent.id(), agent.name(), Actor.human( p.login(), p.groups(), p.admin() ) ) )
            .orElse( null );
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
        return actor.instanceAdmin() || !scopes.standing().visibleProjects( actor ).isEmpty();
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
