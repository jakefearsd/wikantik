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

import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkValidationException;
import com.wikantik.work.WorkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseItemToolTest {

    private MockWork w;
    private ReleaseItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new ReleaseItemTool( w.svc );
    }

    @Test
    void happyPathReturnsKeyAndStatusAndPassesTheNote() throws SQLException {
        when( w.claims.release( w.agent, "PLAT-1", "Cannot finish" ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 5, null, null ) ) );
        final var out = WorkMcpFixture.json( tool.call( w.agent, Map.of( "key", "PLAT-1", "note", "Cannot finish" ) ) );
        assertEquals( "PLAT-1", out.get( "key" ).getAsString() );
        assertEquals( "ready", out.get( "status" ).getAsString() );
    }

    @Test
    void theNoteIsOptional() throws SQLException {
        when( w.claims.release( any(), any(), any() ) )
            .thenReturn( MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 5, null, null ) ) );
        tool.call( w.agent, Map.of( "key", "PLAT-1" ) );
        verify( w.claims ).release( w.agent, "PLAT-1", null );
    }

    @Test
    void nullActorRefusedAndServiceNeverCalled() {
        assertEquals( "This endpoint needs a work API key.", WorkMcpFixture.text( tool.call( null, Map.of( "key", "PLAT-1" ) ) ) );
        verifyNoInteractions( w.svc, w.claims );
    }

    @Test
    void hiddenAndUnknownGiveIdenticalError() throws SQLException {
        when( w.claims.release( any(), eq( "SECRET-1" ), any() ) ).thenThrow( new WorkNotFoundException( "a" ) );
        when( w.claims.release( any(), eq( "PLAT-9" ), any() ) ).thenThrow( new WorkNotFoundException( "b" ) );
        final var a = tool.call( w.agent, Map.of( "key", "SECRET-1" ) );
        assertEquals( "Not found.", WorkMcpFixture.text( a ) );
        assertEquals( a, tool.call( w.agent, Map.of( "key", "PLAT-9" ) ) );
    }

    @Test
    void notClaimedAndDeniedAndArchivedAreMapped() throws SQLException {
        when( w.claims.release( any(), any(), any() ) )
            .thenThrow( new WorkConflictException( "This item is not claimed.", WorkConflictException.NOT_CLAIMED ) )
            .thenThrow( new WorkDeniedException( "This item is claimed by another agent." ) )
            .thenThrow( new WorkConflictException( "Archived.", WorkConflictException.ARCHIVED ) );
        final Map< String, Object > a = Map.of( "key", "PLAT-1" );
        assertEquals( "not_claimed: This item is not claimed.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
        assertEquals( "denied: This item is claimed by another agent.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
        assertEquals( "archived: Archived.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
    }

    @Test
    void wrongTypesAndUnknownArgumentsAreRejectedByName() {
        assertEquals( "note: must be a string.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "note", 4 ) ) ) );
        assertEquals( "status: Unknown argument.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "status", "ready" ) ) ) );
        verifyNoInteractions( w.svc, w.claims );
    }

    @Test
    void aReservedReleaseNoteIsRefusedByNameAndTheRefusalIsLogged() throws SQLException {
        when( w.claims.release( any(), any(), eq( "moved" ) ) )
            .thenThrow( new WorkValidationException( "note", "Choose a different note." ) );
        final java.util.concurrent.atomic.AtomicReference< String > text = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.List< String > lines = WriteToolsAuditTest.audited( () -> text.set(
            WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "note", "moved" ) ) ) ) );
        assertEquals( "note: Choose a different note.", text.get() );
        assertEquals( 1, lines.size(), lines.toString() );
        assertTrue( lines.get( 0 ).startsWith( "MCP_WORK_REFUSED tool=release_item item=PLAT-1" )
            && lines.get( 0 ).endsWith( "kind=validation" ), lines.get( 0 ) );
    }
}
