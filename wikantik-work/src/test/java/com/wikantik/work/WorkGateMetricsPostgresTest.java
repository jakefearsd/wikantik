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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every gate an agent meets is reported once, allowed or refused; a person's passage is not a gate decision. */
@RequiresPostgres
class WorkGateMetricsPostgresTest {

    private final List< String > gates = new ArrayList<>();
    private GateFixture f;
    private Item item;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture( new WorkListener() {
            @Override public void gate( final String gate, final boolean allowed ) {
                gates.add( gate + ( allowed ? ":allowed" : ":refused" ) );
            }
        } );
        item = f.task();
    }

    @Test
    void approveAndCloseAreReportedForAnAgent() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> f.status( f.author, item, "ready" ) );
        f.level( item, Autonomy.AUTONOMOUS );
        f.status( f.author, item, "ready" );
        f.svc.claims().claim( f.author, item.key() );
        f.status( f.author, item, "done" );
        assertEquals( List.of( "approve:refused", "approve:allowed", "close:allowed" ), gates );
    }

    @Test
    void aRefusedCloseIsReported() throws SQLException {
        f.level( item, Autonomy.SUPERVISED );
        f.claimedBy( f.author, item );
        gates.clear();
        assertThrows( WorkDeniedException.class, () -> f.status( f.author, item, "done" ) );
        assertEquals( List.of( "close:refused" ), gates );
    }

    @Test
    void reviewMergeAndDirectAreReportedForAnAgent() throws SQLException {
        f.status( f.lena, item, "ready" );
        final Change c = f.svc.changes().open( f.author, item.key(),
            new ChangeDraft( "work/PLAT-1-x", null, "aaaaaaa1", null, null ) );
        f.svc.changes().requestReview( f.author, c.id() );
        assertThrows( WorkDeniedException.class, () -> f.svc.changes().review( f.author, c.id(), true, null ) );
        assertThrows( WorkConflictException.class, () -> f.svc.changes().merge( f.reviewer, c.id(), "aaaaaaa1" ) );
        f.svc.changes().review( f.reviewer, c.id(), true, null );
        assertThrows( WorkDeniedException.class, () -> f.svc.changes().merge( f.author, c.id(), "aaaaaaa1" ) );
        f.svc.changes().merge( f.reviewer, c.id(), "aaaaaaa1" );
        assertThrows( WorkDeniedException.class, () -> f.svc.changes().open( f.author, item.key(),
            new ChangeDraft( "main", null, null, null, "bbbbbbb2" ) ) );
        assertEquals( List.of( "review:refused", "merge:refused", "review:allowed", "merge:refused", "merge:allowed",
            "direct:refused" ),
            gates );
    }

    @Test
    void aPersonPassingIsNotReported() throws SQLException {
        f.status( f.mia, item, "ready" );
        final Change c = f.svc.changes().open( f.mia, item.key(), new ChangeDraft( "main", null, null, null, "aaaaaaa1" ) );
        assertEquals( MergeKind.DIRECT, c.mergeKind() );
        f.status( f.mia, item, "done" );
        assertEquals( List.of(), gates );
    }
}
