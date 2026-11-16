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

    private static final String HUMAN_CLAIM = "People are assigned work; assign it to yourself instead.";

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
        if ( actor.kind() == Actor.Kind.AGENT && isManagement( action ) ) {
            return Decision.deny( "An agent may not change policy, manage a project or manage the work instance." );
        }
        if ( actor.kind() == Actor.Kind.AGENT && claimedByOther( actor, action, itemOrNull ) ) {
            return Decision.deny( "This item is claimed by another agent." );
        }
        if ( actor.admin() ) {
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

    private static boolean claimedByOther( final Actor actor, final Action action, final Item item ) {
        return ( action == Action.EDIT_ITEM || action == Action.MOVE || action == Action.LINK )
            && item != null && item.claimedByAgent() != null && !item.claimedByAgent().equals( actor.agentId() );
    }

    private static boolean isManagement( final Action action ) {
        return action == Action.SET_POLICY || action == Action.MANAGE_PROJECT || action == Action.MANAGE_INSTANCE;
    }

    private static WorkRole roleNeeded( final Action action ) {
        return switch ( action ) {
            case VIEW -> WorkRole.VIEWER;
            case CREATE_ITEM, EDIT_ITEM, MOVE, COMMENT, LINK, CLAIM -> WorkRole.MEMBER;
            case MANAGE_PROJECT, SET_POLICY -> WorkRole.LEAD;
            case MANAGE_INSTANCE -> throw new IllegalStateException( "handled above" );
        };
    }
}
