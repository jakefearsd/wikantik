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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Who may manage a sprint and who may put an item in one. */
class WorkPolicySprintTest {

    private static final Actor ADMIN = Actor.human( "root", Set.of(), true );

    private static Actor actor( final String who ) {
        return switch ( who ) {
            case "admin" -> ADMIN;
            case "agent-member", "agent-lead-sponsor" -> Actor.agent( 7L, "bot", Actor.human( "lead", Set.of(), false ) );
            default -> Actor.human( "alice", Set.of(), false );
        };
    }

    private static WorkRole role( final String who ) {
        return switch ( who ) {
            case "viewer" -> WorkRole.VIEWER;
            case "member", "agent-member" -> WorkRole.MEMBER;
            case "lead", "agent-lead-sponsor" -> WorkRole.LEAD;
            default -> null;
        };
    }

    @ParameterizedTest
    @CsvSource( {
        "viewer,MANAGE_SPRINT,false", "viewer,PLAN_SPRINT,false",
        "member,MANAGE_SPRINT,false", "member,PLAN_SPRINT,true",
        "lead,MANAGE_SPRINT,true", "lead,PLAN_SPRINT,true",
        "admin,MANAGE_SPRINT,true", "admin,PLAN_SPRINT,true",
        "agent-member,MANAGE_SPRINT,false", "agent-member,PLAN_SPRINT,false",
        "agent-lead-sponsor,MANAGE_SPRINT,false", "agent-lead-sponsor,PLAN_SPRINT,false"
    } )
    void sprintPolicyTable( final String who, final Action action, final boolean allowed ) {
        assertEquals( allowed, WorkPolicy.may( actor( who ), role( who ), action, null ).allowed() );
    }

    @Test
    void anAgentIsToldAPersonPlansASprint() {
        for ( final Action action : new Action[] { Action.MANAGE_SPRINT, Action.PLAN_SPRINT } ) {
            for ( final WorkRole role : new WorkRole[] { null, WorkRole.MEMBER, WorkRole.LEAD } ) {
                final Decision d = WorkPolicy.may( actor( "agent-member" ), role, action, null );
                assertFalse( d.allowed() );
                assertEquals( "A person plans a sprint.", d.reason() );
            }
        }
    }
}
