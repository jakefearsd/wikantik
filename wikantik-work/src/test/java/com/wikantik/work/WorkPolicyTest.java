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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkPolicyTest {

    private static WorkRole role( final String r ) {
        return "none".equals( r ) ? null : WorkRole.fromWire( r );
    }

    @ParameterizedTest( name = "{0} {1} -> {2}" )
    @CsvSource( {
        "none,VIEW,false", "none,CREATE_ITEM,false", "none,EDIT_ITEM,false", "none,MOVE,false", "none,COMMENT,false",
        "none,LINK,false", "none,MANAGE_PROJECT,false", "none,SET_POLICY,false", "none,MANAGE_INSTANCE,false",
        "viewer,VIEW,true", "viewer,CREATE_ITEM,false", "viewer,EDIT_ITEM,false", "viewer,MOVE,false",
        "viewer,COMMENT,false", "viewer,LINK,false", "viewer,MANAGE_PROJECT,false", "viewer,SET_POLICY,false",
        "viewer,MANAGE_INSTANCE,false",
        "member,VIEW,true", "member,CREATE_ITEM,true", "member,EDIT_ITEM,true", "member,MOVE,true",
        "member,COMMENT,true", "member,LINK,true", "member,MANAGE_PROJECT,false", "member,SET_POLICY,false",
        "member,MANAGE_INSTANCE,false",
        "lead,VIEW,true", "lead,CREATE_ITEM,true", "lead,EDIT_ITEM,true", "lead,MOVE,true", "lead,COMMENT,true",
        "lead,LINK,true", "lead,MANAGE_PROJECT,true", "lead,SET_POLICY,true", "lead,MANAGE_INSTANCE,false"
    } )
    void rolePolicyTable( final String role, final Action action, final boolean allowed ) {
        final Actor actor = Actor.human( "alice", Set.of(), false );
        assertEquals( allowed, WorkPolicy.may( actor, role( role ), action, null ).allowed() );
    }

    @ParameterizedTest
    @CsvSource( { "none", "viewer", "member", "lead" } )
    void adminMayDoEverythingWithAnyRole( final String role ) {
        final Actor admin = Actor.human( "root", Set.of(), true );
        for ( final Action action : Action.values() ) {
            if ( action == Action.CLAIM ) {
                continue; // claiming is for agents only, even for an admin (see claimDeniedToHumansSaysAssignInstead)
            }
            assertTrue( WorkPolicy.may( admin, role( role ), action, null ).allowed(), action.name() );
        }
    }

    @Test
    void deniedDecisionCarriesReason() {
        final Decision d = WorkPolicy.may( Actor.human( "alice", Set.of(), false ), WorkRole.VIEWER, Action.EDIT_ITEM, null );
        assertFalse( d.allowed() );
        assertFalse( d.reason() == null || d.reason().isBlank() );
    }

    @Test
    void agentCannotSetPolicyOrManage() {
        final Actor agent = new Actor( "bot", Set.of(), false, Actor.Kind.AGENT, 7L, null );
        assertFalse( WorkPolicy.may( agent, WorkRole.MEMBER, Action.SET_POLICY, null ).allowed() );
        assertFalse( WorkPolicy.may( agent, WorkRole.MEMBER, Action.MANAGE_PROJECT, null ).allowed() );
    }

    @Test
    void setPolicyAlwaysRequiresHumanEvenForLeadAgent() {
        final Actor agent = new Actor( "bot", Set.of(), false, Actor.Kind.AGENT, 7L, null );
        final Decision d = WorkPolicy.may( agent, WorkRole.LEAD, Action.SET_POLICY, null );
        assertFalse( d.allowed() );
        assertFalse( d.reason().isBlank() );
        assertFalse( WorkPolicy.may( agent, WorkRole.LEAD, Action.MANAGE_PROJECT, null ).allowed() );
    }

    @ParameterizedTest
    @CsvSource( { "SET_POLICY", "MANAGE_PROJECT", "MANAGE_INSTANCE" } )
    void adminAgentIsDeniedAuthorityActions( final Action action ) {
        final Actor adminAgent = new Actor( "bot", Set.of(), true, Actor.Kind.AGENT, 7L, null );
        for ( final WorkRole role : new WorkRole[] { null, WorkRole.MEMBER, WorkRole.LEAD } ) {
            final Decision d = WorkPolicy.may( adminAgent, role, action, null );
            assertFalse( d.allowed(), action + " with role " + role );
            assertFalse( d.reason().isBlank() );
        }
    }

    private static Item claimedBy( final Long agent ) {
        return new Item( 1, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", WorkStatus.READY, 3,
            1.0, null, null, "alice", null, null, null, 1, null, null, null, null, agent, null, null, null );
    }

    @ParameterizedTest
    @CsvSource( {
        "viewer,CLAIM,false", "member,CLAIM,true",
        "viewer,EDIT_ITEM,false", "member,EDIT_ITEM,true", "member,MOVE,true", "member,LINK,true",
        "viewer,COMMENT,false", "member,COMMENT,true"
    } )
    void agentRowsOnUnclaimedItem( final String role, final Action action, final boolean allowed ) {
        assertEquals( allowed, WorkPolicy.may( Actor.agent( 7, "bot" ), role( role ), action, claimedBy( null ) ).allowed() );
    }

    @ParameterizedTest
    @CsvSource( { "EDIT_ITEM", "MOVE", "LINK" } )
    void agentCannotWriteItemClaimedByAnotherAgent( final Action action ) {
        final Decision d = WorkPolicy.may( Actor.agent( 7, "bot" ), WorkRole.MEMBER, action, claimedBy( 9L ) );
        assertFalse( d.allowed() );
        assertEquals( "This item is claimed by another agent.", d.reason() );
        assertTrue( WorkPolicy.may( Actor.agent( 7, "bot" ), WorkRole.MEMBER, action, claimedBy( 7L ) ).allowed() );
    }

    @Test
    void commentIsAllowedOnItemClaimedByAnotherAgent() {
        assertTrue( WorkPolicy.may( Actor.agent( 7, "bot" ), WorkRole.MEMBER, Action.COMMENT, claimedBy( 9L ) ).allowed() );
    }

    @ParameterizedTest
    @CsvSource( { "EDIT_ITEM", "MOVE", "LINK", "COMMENT" } )
    void humansAreNotBlockedByAnAgentClaim( final Action action ) {
        assertTrue( WorkPolicy.may( Actor.human( "alice", Set.of(), false ), WorkRole.MEMBER, action, claimedBy( 9L ) ).allowed() );
        assertTrue( WorkPolicy.may( Actor.human( "root", Set.of(), true ), null, action, claimedBy( 9L ) ).allowed() );
    }

    @Test
    void claimDeniedToHumansSaysAssignInstead() {
        final Decision d = WorkPolicy.may( Actor.human( "alice", Set.of(), false ), WorkRole.MEMBER, Action.CLAIM, null );
        assertFalse( d.allowed() );
        assertTrue( d.reason().contains( "assign" ) );
        assertFalse( WorkPolicy.may( Actor.human( "root", Set.of(), true ), null, Action.CLAIM, null ).allowed() );
    }

    @Test
    void claimedByOtherDenialNamesNoLogin() {
        final Decision d = WorkPolicy.may( Actor.agent( 7, "bot@x" ), WorkRole.MEMBER, Action.EDIT_ITEM, claimedBy( 9L ) );
        assertFalse( d.reason().contains( "bot@x" ) );
        assertFalse( d.reason().contains( "alice" ) );
    }

    @Test
    void agentWithAdminFlagGetsNothingBeyondItsRole() {
        final Actor flagged = new Actor( "bot", Set.of(), true, Actor.Kind.AGENT, 7L, null );
        final Actor plain = Actor.agent( 7L, "bot" );
        assertFalse( flagged.admin(), "only a human can be an admin: the flag is dropped at construction" );
        assertFalse( flagged.instanceAdmin() );
        for ( final Action action : Action.values() ) {
            assertFalse( WorkPolicy.may( flagged, null, action, null ).allowed(), "no role: " + action );
            assertEquals( WorkPolicy.may( plain, WorkRole.MEMBER, action, null ).allowed(),
                WorkPolicy.may( flagged, WorkRole.MEMBER, action, null ).allowed(), "member: " + action );
        }
        assertTrue( WorkPolicy.may( flagged, WorkRole.MEMBER, Action.CLAIM, null ).allowed() );
        assertTrue( WorkPolicy.may( flagged, WorkRole.MEMBER, Action.COMMENT, null ).allowed() );
    }

    private static Item inStatus( final WorkStatus status ) {
        return new Item( 1, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", status, 3,
            1.0, null, null, "alice", null, null, null, 1, null, null, null, null, null, null, null, null );
    }

    @Test
    void anAgentCannotMoveADraftOutOfDraft() {
        final Actor agent = Actor.agent( 7, "bot" );
        final Decision d = WorkPolicy.mayChangeStatus( agent, inStatus( WorkStatus.DRAFT ), WorkStatus.READY );
        assertFalse( d.allowed() );
        assertEquals( "A person moves a draft forward.", d.reason() );
        assertFalse( WorkPolicy.mayChangeStatus( agent, inStatus( WorkStatus.DRAFT ), null ).allowed(), "unknown target counts as a move" );
        assertTrue( WorkPolicy.mayChangeStatus( agent, inStatus( WorkStatus.DRAFT ), WorkStatus.DRAFT ).allowed() );
        assertTrue( WorkPolicy.mayChangeStatus( agent, inStatus( WorkStatus.READY ), WorkStatus.REVIEW ).allowed() );
    }

    @Test
    void peopleMayMoveADraftForward() {
        assertTrue( WorkPolicy.mayChangeStatus( Actor.human( "alice", Set.of(), false ), inStatus( WorkStatus.DRAFT ),
            WorkStatus.READY ).allowed() );
        assertTrue( WorkPolicy.mayChangeStatus( Actor.human( "root", Set.of(), true ), inStatus( WorkStatus.DRAFT ),
            WorkStatus.READY ).allowed() );
    }
}
