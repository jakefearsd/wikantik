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
        final Actor agent = new Actor( "bot", Set.of(), false, Actor.Kind.AGENT, 7L );
        assertFalse( WorkPolicy.may( agent, WorkRole.MEMBER, Action.SET_POLICY, null ).allowed() );
        assertFalse( WorkPolicy.may( agent, WorkRole.MEMBER, Action.MANAGE_PROJECT, null ).allowed() );
    }

    @Test
    void setPolicyAlwaysRequiresHumanEvenForLeadAgent() {
        final Actor agent = new Actor( "bot", Set.of(), false, Actor.Kind.AGENT, 7L );
        final Decision d = WorkPolicy.may( agent, WorkRole.LEAD, Action.SET_POLICY, null );
        assertFalse( d.allowed() );
        assertFalse( d.reason().isBlank() );
        assertTrue( WorkPolicy.may( agent, WorkRole.LEAD, Action.MANAGE_PROJECT, null ).allowed() );
    }
}
