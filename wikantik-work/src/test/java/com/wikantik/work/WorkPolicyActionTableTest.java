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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link WorkPolicy#may} for every action, actor kind, role and claim state against a committed golden
 * ({@code policy-action-table.golden}), recorded from the code before {@link Action} carried its own attributes.
 */
class WorkPolicyActionTableTest {

    private static final long SELF = 7L;
    private static final long OTHER = 99L;

    private static Item item( final Long claimedBy ) {
        return new Item( 1, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", WorkStatus.READY, 3,
            1.0, null, null, "alice", null, null, null, 1, null, null, null, null, claimedBy, null, null, null );
    }

    static List< String > table() {
        final Actor adminSponsor = Actor.human( "root", Set.of(), true );
        final Actor memberSponsor = Actor.human( "bob", Set.of(), false );
        final String[] actorNames = { "human", "admin", "agent-admin-sponsor", "agent-member-sponsor" };
        final Actor[] actors = { Actor.human( "alice", Set.of(), false ), adminSponsor,
            Actor.agent( SELF, "bot", adminSponsor ), Actor.agent( SELF, "bot", memberSponsor ) };
        final WorkRole[] roles = { null, WorkRole.VIEWER, WorkRole.MEMBER, WorkRole.LEAD };
        final String[] itemNames = { "none", "unclaimed", "other", "self" };
        final Item[] items = { null, item( null ), item( OTHER ), item( SELF ) };
        final List< String > lines = new ArrayList<>();
        for ( final Action action : Action.values() ) {
            for ( int a = 0; a < actors.length; a++ ) {
                for ( final WorkRole role : roles ) {
                    for ( int i = 0; i < items.length; i++ ) {
                        final Decision d = WorkPolicy.may( actors[ a ], role, action, items[ i ] );
                        lines.add( action + "|" + actorNames[ a ] + "|" + ( role == null ? "none" : role.wire() ) + "|"
                            + itemNames[ i ] + "|" + d.allowed() + "|" + d.reason() );
                    }
                }
            }
        }
        return lines;
    }

    @Test
    void decisionTableMatchesGolden() throws IOException {
        final List< String > golden;
        try ( InputStream in = WorkPolicyActionTableTest.class.getResourceAsStream( "policy-action-table.golden" ) ) {
            golden = List.of( new String( in.readAllBytes(), StandardCharsets.UTF_8 ).split( "\n" ) );
        }
        assertEquals( golden, table() );
    }
}
