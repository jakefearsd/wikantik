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
package com.wikantik.work.mcp;

import com.wikantik.work.WorkClaimsService.Handoff;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HandoffItemToolTest {

    private MockWork w;
    private HandoffItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new HandoffItemTool( w.svc, w.directory );
    }

    private static Map< String, Object > args( final Object... kv ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "key", "PLAT-1" );
        m.put( "expectedVersion", 3 );
        m.put( "status", "review" );
        m.put( "note", "Done; please review the retry logic." );
        for ( int i = 0; i < kv.length; i += 2 ) {
            if ( kv[ i + 1 ] == null ) {
                m.remove( ( String ) kv[ i ] );
            } else {
                m.put( ( String ) kv[ i ], kv[ i + 1 ] );
            }
        }
        return m;
    }

    @Test
    void toAQueue() throws SQLException {
        when( w.claims.handoff( eq( w.agent ), eq( "PLAT-1" ), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.REVIEW, 4, null, null ) ) );
        final var out = WorkMcpFixture.json( tool.call( w.agent, args( "queue", "reviewer" ) ) );
        assertEquals( "PLAT-1", out.get( "key" ).getAsString() );
        verify( w.claims ).handoff( w.agent, "PLAT-1", new Handoff( 3, WorkStatus.REVIEW, null, "reviewer",
            "Done; please review the retry logic." ) );
    }

    @Test
    void toAPersonByDisplayName() throws SQLException {
        when( w.claims.handoff( any(), any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.REVIEW, 4, null, null ) ) );
        tool.call( w.agent, args( "assignee", "Lena Lead" ) );
        verify( w.claims ).handoff( w.agent, "PLAT-1", new Handoff( 3, WorkStatus.REVIEW, "Lena Lead", null,
            "Done; please review the retry logic." ) );
    }

    @Test
    void nullActorRefusedAndServiceNeverCalled() {
        assertEquals( "This endpoint needs a work API key.", WorkMcpFixture.text( tool.call( null, args( "queue", "q" ) ) ) );
        verifyNoInteractions( w.svc, w.claims );
    }

    @Test
    void hiddenAndUnknownGiveIdenticalError() throws SQLException {
        when( w.claims.handoff( any(), eq( "SECRET-1" ), any() ) ).thenThrow( new WorkNotFoundException( "a" ) );
        when( w.claims.handoff( any(), eq( "PLAT-9" ), any() ) ).thenThrow( new WorkNotFoundException( "b" ) );
        final var a = tool.call( w.agent, args( "key", "SECRET-1", "queue", "q" ) );
        assertEquals( "Not found.", WorkMcpFixture.text( a ) );
        assertEquals( a, tool.call( w.agent, args( "key", "PLAT-9", "queue", "q" ) ) );
    }

    @Test
    void exactlyOneTargetIsRequired() {
        assertEquals( "assignee: Name a person (assignee) or a queue, not both and not neither.",
            WorkMcpFixture.text( tool.call( w.agent, args( "assignee", "Lena Lead", "queue", "q" ) ) ) );
        assertEquals( "assignee: Name a person (assignee) or a queue, not both and not neither.",
            WorkMcpFixture.text( tool.call( w.agent, args() ) ) );
        verifyNoInteractions( w.claims );
    }

    @Test
    void aNoteStatusAndVersionAreRequired() {
        assertEquals( "note: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q", "note", null ) ) ) );
        final Map< String, Object > blank = args( "queue", "q" );
        blank.put( "note", "   " );
        assertEquals( "note: is required.", WorkMcpFixture.text( tool.call( w.agent, blank ) ) );
        assertEquals( "status: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q", "status", null ) ) ) );
        assertEquals( "expectedVersion: is required.",
            WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q", "expectedVersion", null ) ) ) );
        assertEquals( "status: Unknown status: sideways",
            WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q", "status", "sideways" ) ) ) );
        verifyNoInteractions( w.claims );
    }

    @Test
    void wrongTypesAndUnknownArgumentsAreRejectedByName() {
        assertEquals( "queue: must be a string.", WorkMcpFixture.text( tool.call( w.agent, args( "queue", 5 ) ) ) );
        assertEquals( "assignee: must be a string.", WorkMcpFixture.text( tool.call( w.agent, args( "assignee", 5 ) ) ) );
        assertEquals( "priority: Unknown argument.", WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q", "priority", 1 ) ) ) );
        verifyNoInteractions( w.claims );
    }

    @Test
    void staleCarriesTheCurrentVersion() throws SQLException {
        when( w.claims.handoff( any(), any(), any() ) ).thenThrow( new WorkConflictException( "The item changed." ) );
        when( w.svc.item( any(), eq( "PLAT-1" ) ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 8, 7L, null ) ) );
        assertEquals( "stale: The item changed. (currentVersion: 8)",
            WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q" ) ) ) );
    }

    @Test
    void deniedAndValidationAreMapped() throws SQLException {
        when( w.claims.handoff( any(), any(), any() ) )
            .thenThrow( new WorkDeniedException( "This item is claimed by another agent." ) )
            .thenThrow( new WorkValidationException( "queue", "The item is already assigned there." ) );
        assertEquals( "denied: This item is claimed by another agent.",
            WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q" ) ) ) );
        assertEquals( "queue: The item is already assigned there.",
            WorkMcpFixture.text( tool.call( w.agent, args( "queue", "q" ) ) ) );
    }
}
