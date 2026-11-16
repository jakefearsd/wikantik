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

import com.wikantik.work.WorkClaimsService.ClaimResult;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClaimItemToolTest {

    private MockWork w;
    private ClaimItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new ClaimItemTool( w.svc, w.directory );
    }

    @Test
    void happyPathReturnsTheClaimAndTheFullItem() throws SQLException {
        final Instant until = Instant.parse( "2030-01-01T00:30:00Z" );
        when( w.claims.claim( w.agent, "PLAT-1" ) ).thenReturn( new ClaimResult(
            MockWork.view( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 2, 7L, until ), "AgentHub" ), true ) );

        final var out = WorkMcpFixture.json( tool.call( w.agent, Map.of( "key", "PLAT-1" ) ) );

        assertEquals( true, out.getAsJsonObject( "claim" ).get( "renewed" ).getAsBoolean() );
        assertEquals( until.toString(), out.getAsJsonObject( "claim" ).get( "expiresAt" ).getAsString() );
        assertEquals( "PLAT-1", out.getAsJsonObject( "item" ).get( "key" ).getAsString() );
        assertEquals( "AgentHub", out.getAsJsonObject( "item" ).get( "wikiCluster" ).getAsString() );
    }

    @Test
    void nullActorRefusedAndServiceNeverCalled() {
        assertEquals( "This endpoint needs a work API key.", WorkMcpFixture.text( tool.call( null, Map.of( "key", "PLAT-1" ) ) ) );
        verifyNoInteractions( w.svc, w.claims );
    }

    @Test
    void hiddenAndUnknownGiveIdenticalError() throws SQLException {
        when( w.claims.claim( any(), eq( "SECRET-1" ) ) ).thenThrow( new WorkNotFoundException( "a" ) );
        when( w.claims.claim( any(), eq( "PLAT-9" ) ) ).thenThrow( new WorkNotFoundException( "b" ) );
        final var a = tool.call( w.agent, Map.of( "key", "SECRET-1" ) );
        assertEquals( "Not found.", WorkMcpFixture.text( a ) );
        assertEquals( a, tool.call( w.agent, Map.of( "key", "PLAT-9" ) ) );
        assertEquals( a, tool.call( w.agent, Map.of() ) );
    }

    @Test
    void anHeldItemIsNotClaimableAndNamesTheHolder() throws SQLException {
        when( w.claims.claim( any(), any() ) ).thenThrow( new WorkConflictException(
            "This item is claimed by reviewer-bot until 2030-01-01T00:30:00Z.", WorkConflictException.NOT_CLAIMABLE ) );
        assertEquals( "not_claimable: This item is claimed by reviewer-bot until 2030-01-01T00:30:00Z.",
            WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1" ) ) ) );
    }

    @Test
    void deniedIsMapped() throws SQLException {
        when( w.claims.claim( any(), any() ) ).thenThrow( new WorkDeniedException( "Agents in this project are viewers." ) );
        assertEquals( "denied: Agents in this project are viewers.",
            WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1" ) ) ) );
    }

    @Test
    void otherArgumentsAndWrongTypesAreRejectedByName() {
        assertEquals( "status: Unknown argument.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "status", "done" ) ) ) );
        assertEquals( "key: must be a string.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", 3 ) ) ) );
        verifyNoInteractions( w.svc, w.claims );
    }
}
