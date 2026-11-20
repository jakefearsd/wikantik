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

/** Role policy: who may do what. Pure; knows nothing of storage. */
public final class WorkPolicy {

    private static final String AGENT_ACKNOWLEDGE = "A person acknowledges work closed without one.";
    private static final String HUMAN_CLAIM = "People are assigned work; assign it to yourself instead.";

    /** What an agent may not do to an item another agent holds the claim on. */
    private static final java.util.Set< Action > FENCED = java.util.EnumSet.of( Action.EDIT_ITEM, Action.MOVE,
        Action.LINK, Action.OPEN_CHANGE, Action.REVIEW_CHANGE, Action.MERGE_CHANGE );

    /** Leaving refining for one of these approves nothing. */
    private static final java.util.Set< WorkStatus > BEFORE_READY = java.util.EnumSet.of( WorkStatus.DRAFT,
        WorkStatus.REFINING, WorkStatus.CANCELLED );

    private static final java.util.Set< Action > LEAD_ACTIONS = java.util.EnumSet.of( Action.MANAGE_PROJECT,
        Action.SET_POLICY, Action.MANAGE_AGENT );

    /**
     * Besides draft, the statuses a move forward from is an approval: refining, and cancelled too, or an agent
     * could cancel refined work and bring it back as ready.
     */
    private static final java.util.Set< WorkStatus > REVIVABLE = java.util.EnumSet.of( WorkStatus.REFINING,
        WorkStatus.CANCELLED );

    private WorkPolicy() {
    }

    /**
     * Decides whether {@code actor} holding {@code roleOrNull} on the project may perform {@code action}.
     * Agents (admin or not) never SET_POLICY, MANAGE_PROJECT or MANAGE_INSTANCE. Otherwise wiki admins may do everything. {@code itemOrNull} is accepted so
     * later phases can add item-scoped rules without a signature change.
     */
    public static Decision may( final Actor actor, final WorkRole roleOrNull, final Action action, final Item itemOrNull ) {
        if ( action == Action.CLAIM && actor.kind() == Actor.Kind.HUMAN ) {
            return Decision.deny( HUMAN_CLAIM );
        }
        if ( action == Action.ACKNOWLEDGE && actor.kind() == Actor.Kind.AGENT ) {
            return Decision.deny( AGENT_ACKNOWLEDGE );
        }
        if ( actor.kind() == Actor.Kind.AGENT && isManagement( action ) ) {
            return Decision.deny( "An agent may not change policy, manage a project or manage the work instance." );
        }
        if ( actor.kind() == Actor.Kind.AGENT && claimedByOther( actor, action, itemOrNull ) ) {
            return Decision.deny( "This item is claimed by another agent." );
        }
        if ( actor.instanceAdmin() ) {
            return Decision.allow();
        }
        if ( action == Action.MANAGE_INSTANCE ) {
            return Decision.deny( "Only a wiki administrator may manage the work instance." );
        }
        if ( roleOrNull == null ) {
            return Decision.deny( "You are not a member of this project." );
        }
        final WorkRole needed = roleNeeded( action );
        if ( roleOrNull.atLeast( needed ) ) {
            return Decision.allow();
        }
        return Decision.deny( "This action needs the " + needed.wire() + " role; you hold " + roleOrNull.wire() + "." );
    }

    /**
     * Whether {@code actor} may set the status of {@code item} to {@code target} under {@code policy}, the item's
     * effective policy. People are not restricted. An agent moves an item out of {@code draft} only at the
     * autonomous level (the owner approved agent-created items on condition that a person refines and promotes
     * them); it approves refined work as ready only there too (and likewise brings cancelled work back); and it
     * closes work only above the supervised level.
     * A null {@code target} stands for some status the caller could not name, so it counts as a move forward.
     */
    public static Decision mayChangeStatus( final Actor actor, final Item item, final WorkStatus target,
                                            final EffectivePolicy policy ) {
        if ( actor.kind() != Actor.Kind.AGENT || item == null ) {
            return Decision.allow();
        }
        if ( item.status() == WorkStatus.DRAFT && target != WorkStatus.DRAFT
            && !WorkGates.mayApprove( actor, policy ).allowed() ) {
            return Decision.deny( target == WorkStatus.CANCELLED ? "A person cancels or moves a draft."
                : "A person moves a draft forward." );
        }
        if ( REVIVABLE.contains( item.status() ) && !BEFORE_READY.contains( target ) ) {
            final Decision approve = WorkGates.mayApprove( actor, policy );
            if ( !approve.allowed() ) {
                return approve;
            }
        }
        return target == WorkStatus.DONE ? WorkGates.mayClose( actor, policy ) : Decision.allow();
    }

    /**
     * The status a new item starts at. An agent's item is a draft, for a person to refine and move forward. A person's
     * task or story starts ready (creating it is that person's say-so); an epic has no ready status, so it starts as a
     * draft for everyone.
     */
    static WorkStatus startStatus( final Actor actor, final WorkLevel level ) {
        return actor.kind() == Actor.Kind.HUMAN && level != null && level != WorkLevel.EPIC
            ? WorkStatus.READY : WorkStatus.DRAFT;
    }

    /** Whether moving {@code item} to {@code target} takes it out of draft or refining: an approval as ready. */
    static boolean approves( final Item item, final WorkStatus target ) {
        return ( item.status() == WorkStatus.DRAFT && target != WorkStatus.DRAFT )
            || ( REVIVABLE.contains( item.status() ) && !BEFORE_READY.contains( target ) );
    }

    /** The status a patch names, or null when it names none or one that does not exist (which counts as a move). */
    static WorkStatus namedStatus( final Object raw ) {
        return WireEnum.tryParse( WorkStatus.class, raw );
    }

    private static boolean claimedByOther( final Actor actor, final Action action, final Item item ) {
        return FENCED.contains( action )
            && item != null && item.claimedByAgent() != null && !item.claimedByAgent().equals( actor.agentId() );
    }

    private static boolean isManagement( final Action action ) {
        return action == Action.SET_POLICY || action == Action.MANAGE_PROJECT || action == Action.MANAGE_INSTANCE
            || action == Action.MANAGE_AGENT;
    }

    private static WorkRole roleNeeded( final Action action ) {
        if ( action == Action.MANAGE_INSTANCE ) {
            throw new IllegalStateException( "handled above" );
        }
        if ( action == Action.VIEW ) {
            return WorkRole.VIEWER;
        }
        // Managing an agent is decided by sponsorship (AgentSponsorship), not by a role in one project; asked
        // here it takes a lead, the least that may ever register one.
        return LEAD_ACTIONS.contains( action ) ? WorkRole.LEAD : WorkRole.MEMBER;
    }
}
