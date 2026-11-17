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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may do what to an agent, and who can see it. An agent is managed by the person who stands behind it (its
 * sponsor) or a wiki admin; it is registered by a lead of any project or an admin; it is seen by its sponsor and by
 * anyone who shares a project with it. An agent never manages agents. Split from {@link WorkRegistryService} to keep
 * both under the complexity limits.
 */
final class AgentSponsorship {

    static final String REGISTER_REFUSED = "Only a project lead or a wiki administrator may register an agent.";
    static final String TAKEOVER_REFUSED =
        "This agent has a sponsor. Ask them to release it, or ask a wiki administrator.";
    static final String TAKEOVER_NOT_LEAD =
        "Taking over an agent takes a lead of a project it is in, or a wiki administrator.";
    static final String MANAGE_REFUSED = "Only this agent's sponsor or a wiki administrator may do that.";
    static final String EDIT_REFUSED = "Editing an agent takes a project lead or a wiki administrator.";

    private final WorkStore store;
    private final WorkScopes scopes;

    AgentSponsorship( final WorkStore store, final WorkScopes scopes ) {
        this.store = store;
        this.scopes = scopes;
    }

    private WorkDeniedException denied( final String reason ) {
        scopes.denied( Action.MANAGE_AGENT );
        return new WorkDeniedException( reason );
    }

    private static boolean isPerson( final Actor actor ) {
        return actor.kind() == Actor.Kind.HUMAN;
    }

    private static boolean sponsors( final Actor actor, final Agent agent ) {
        return isPerson( actor ) && actor.login().equals( agent.sponsor() );
    }

    /** Whether the person is a lead of at least one project that is not archived. */
    boolean leadsAnyProject( final Actor person ) throws SQLException {
        for ( final Project p : scopes.standing().visibleProjects( person ) ) {
            if ( p.archivedAt() == null
                    && scopes.standing().roleIn( p.id(), person ).orElse( null ) == WorkRole.LEAD ) {
                return true;
            }
        }
        return false;
    }

    void requireMayRegister( final Actor actor ) throws SQLException {
        if ( !isPerson( actor ) || !actor.instanceAdmin() && !leadsAnyProject( actor ) ) {
            throw denied( REGISTER_REFUSED );
        }
    }

    /** The agents the actor may know of: all for an admin; else those it sponsors or shares a project with. */
    List< Agent > visibleAgents( final Actor actor ) throws SQLException {
        final List< Agent > all = store.agents();
        if ( actor.instanceAdmin() ) {
            return all;
        }
        final Set< Long > shared = sharedWith( actor );
        return all.stream().filter( a -> sponsors( actor, a ) || shared.contains( a.id() ) ).toList();
    }

    private Set< Long > sharedWith( final Actor actor ) throws SQLException {
        final Set< Long > projects = new HashSet<>();
        for ( final Project p : scopes.standing().visibleProjects( actor ) ) {
            projects.add( p.id() );
        }
        return projects.isEmpty() ? Set.of() : store.agentsWithMemberRowIn( projects );
    }

    /**
     * The agent, if the actor may know it exists; otherwise the same {@link WorkNotFoundException} as for an id
     * that names nothing. An agent to change must be active unless {@code retiredToo}.
     */
    Agent requireVisible( final Actor actor, final long agentId, final boolean retiredToo ) throws SQLException {
        final Agent agent = store.agent( agentId ).orElse( null );
        if ( agent != null && ( retiredToo || agent.active() ) && ( actor.instanceAdmin() || sponsors( actor, agent )
                || !isPerson( actor ) && actor.agentId() != null && actor.agentId() == agentId
                || sharedWith( actor ).contains( agentId ) ) ) {
            return agent;
        }
        throw new WorkNotFoundException( "No such agent: " + agentId );
    }

    /** Rotating, retiring and releasing only reduce risk, so the sponsor may always do them. */
    void requireSponsorOrAdmin( final Actor actor, final Agent agent ) {
        if ( !isPerson( actor ) || !actor.instanceAdmin() && !sponsors( actor, agent ) ) {
            throw denied( MANAGE_REFUSED );
        }
    }

    /** Changing what an agent is or serves takes its sponsor while they lead somewhere, or an admin. */
    void requireMayEdit( final Actor actor, final Agent agent ) throws SQLException {
        requireSponsorOrAdmin( actor, agent );
        if ( !actor.instanceAdmin() && !leadsAnyProject( actor ) ) {
            throw denied( EDIT_REFUSED );
        }
    }

    /**
     * An admin takes over at any time. Anyone else must lead a project the agent is in, and only when nobody
     * stands behind the agent: it has no sponsor, or its sponsor's account is gone. A directory failure is
     * {@link WorkUnavailableException}: "gone" is never guessed.
     */
    void requireMayTakeOver( final Actor actor, final Agent agent ) throws SQLException {
        final String refusal = takeOverRefusal( actor, agent );
        if ( refusal != null ) {
            throw denied( refusal );
        }
    }

    /**
     * Whether a list should offer the caller this agent to take over: it is active, it is not already theirs, and
     * {@link #requireMayTakeOver} would let them.
     */
    boolean mayTakeOver( final Actor actor, final Agent agent ) throws SQLException {
        return agent.active() && !sponsors( actor, agent ) && takeOverRefusal( actor, agent ) == null;
    }

    /** Why the actor may not take the agent over, or null when they may. */
    private String takeOverRefusal( final Actor actor, final Agent agent ) throws SQLException {
        if ( !isPerson( actor ) ) {
            return MANAGE_REFUSED;
        }
        if ( actor.instanceAdmin() ) {
            return null;
        }
        if ( agent.sponsor() != null && scopes.person( agent.sponsor() ).isPresent() ) {
            return TAKEOVER_REFUSED;
        }
        for ( final long projectId : store.projectsWithAgentMember( agent.id() ) ) {
            if ( scopes.standing().roleIn( projectId, actor ).orElse( null ) == WorkRole.LEAD ) {
                return null;
            }
        }
        return TAKEOVER_NOT_LEAD;
    }

    /** How the agent's member row in one project stands: its sponsor's standing there caps it. */
    WorkRegistryService.MemberStanding standingIn( final long projectId, final Agent agent ) throws SQLException {
        if ( agent.sponsor() == null ) {
            return WorkRegistryService.MemberStanding.NO_SPONSOR;
        }
        final WorkDirectory.Person p = scopes.person( agent.sponsor() ).orElse( null );
        if ( p == null ) {
            return WorkRegistryService.MemberStanding.SPONSOR_GONE;
        }
        if ( p.admin() ) {
            return WorkRegistryService.MemberStanding.EFFECTIVE;
        }
        final WorkRole role = scopes.standing().roleIn( projectId, Actor.human( p.login(), p.groups(), false ) )
            .orElse( null );
        if ( role == null ) {
            return WorkRegistryService.MemberStanding.SPONSOR_NOT_MEMBER;
        }
        return role == WorkRole.VIEWER ? WorkRegistryService.MemberStanding.SPONSOR_VIEWER
            : WorkRegistryService.MemberStanding.EFFECTIVE;
    }

    /** How the agent stands right now; computed, never stored. */
    WorkRegistryService.AgentStatus status( final Agent agent ) {
        if ( !agent.active() ) {
            return WorkRegistryService.AgentStatus.RETIRED;
        }
        if ( agent.sponsor() == null ) {
            return WorkRegistryService.AgentStatus.NO_SPONSOR;
        }
        return scopes.person( agent.sponsor() ).isPresent() ? WorkRegistryService.AgentStatus.ACTIVE
            : WorkRegistryService.AgentStatus.SPONSOR_GONE;
    }
}
