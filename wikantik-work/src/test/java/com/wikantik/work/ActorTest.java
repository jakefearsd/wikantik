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

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorTest {

    @Test
    void agentHasNoGroupsAndIsNeverAdmin() {
        final Actor a = Actor.agent( 7, "codex@docker2" );
        assertTrue( a.groups().isEmpty() );
        assertFalse( a.admin() );
        assertEquals( Actor.Kind.AGENT, a.kind() );
        assertEquals( 7L, a.agentId() );
        assertEquals( "codex@docker2", a.login() );
    }

    @Test
    void anAgentCarriesItsSponsor() {
        final Actor ana = Actor.human( "ana", Set.of( "Leads" ), true );
        final Actor a = Actor.agent( 7, "codex", ana );
        assertEquals( ana, a.sponsor() );
        assertFalse( a.admin(), "the sponsor's admin flag never becomes the agent's" );
        assertTrue( a.groups().isEmpty(), "nor do the sponsor's groups" );
    }

    @Test
    void anAgentBuiltWithoutASponsorHasNone() {
        assertNull( Actor.agent( 7, "codex" ).sponsor() );
    }

    @Test
    void aPersonNeverHasASponsor() {
        assertNull( Actor.human( "ana", Set.of(), false ).sponsor() );
        assertThrows( IllegalArgumentException.class, () -> new Actor( "ana", Set.of(), false, Actor.Kind.HUMAN, null,
            Actor.human( "bob", Set.of(), false ) ) );
    }

    @Test
    void aSponsorMustBeAPerson() {
        assertThrows( IllegalArgumentException.class, () -> Actor.agent( 7, "codex", Actor.agent( 8, "other" ) ) );
    }
}
