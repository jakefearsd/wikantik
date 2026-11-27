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

import com.wikantik.work.Deployment;
import com.wikantik.work.DeploymentState;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What {@code update_deployment} sends to the service for each state, and what it refuses by name before moving. */
class UpdateDeploymentToolTest {

    private MockWork w;
    private UpdateDeploymentTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new UpdateDeploymentTool( w.svc, w.directory );
        final DeploymentView view = new DeploymentView( new Deployment( 4, 1, "prod", "1.2.3", DeploymentState.APPROVED,
            "builder", 7L, null, null, java.time.Instant.EPOCH, null, null, null, null, null, null, List.of() ), "web",
            List.of(), false, false );
        when( w.deployments.approve( any(), anyLong(), any() ) ).thenReturn( view );
        when( w.deployments.reject( any(), anyLong(), any() ) ).thenReturn( view );
        when( w.deployments.start( any(), anyLong() ) ).thenReturn( view );
        when( w.deployments.verify( any(), anyLong(), any() ) ).thenReturn( view );
        when( w.deployments.fail( any(), anyLong(), any() ) ).thenReturn( view );
        when( w.deployments.rollBack( any(), anyLong(), any() ) ).thenReturn( view );
    }

    private static Map< String, Object > args( final Object... kv ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    @Test
    void eachStateGoesToItsOwnMoveWithTheNoteOrEvidenceItTakes() throws SQLException {
        tool.call( w.agent, args( "deployment", 4, "state", "approved", "note", "n1" ) );
        verify( w.deployments ).approve( w.agent, 4L, "n1" );
        tool.call( w.agent, args( "deployment", 4, "state", "rejected", "note", "n2" ) );
        verify( w.deployments ).reject( w.agent, 4L, "n2" );
        tool.call( w.agent, args( "deployment", 4, "state", "failed", "note", "n3" ) );
        verify( w.deployments ).fail( w.agent, 4L, "n3" );
        tool.call( w.agent, args( "deployment", 4, "state", "rolled_back", "note", "n4" ) );
        verify( w.deployments ).rollBack( w.agent, 4L, "n4" );
        tool.call( w.agent, args( "deployment", 4, "state", "running" ) );
        verify( w.deployments ).start( w.agent, 4L );
        tool.call( w.agent, args( "deployment", 4, "state", "verified", "evidence", "smoke ok" ) );
        verify( w.deployments ).verify( w.agent, 4L, "smoke ok" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "running", "verified" } )
    void aNoteOnAMoveThatRecordsNoneIsRefusedByNameAndNothingMoves( final String state ) {
        final var r = tool.call( w.agent, args( "deployment", 4, "state", state, "note", "why" ) );
        assertEquals( "note: A note goes with approved, rejected, failed or rolled_back.", WorkMcpFixture.text( r ), state );
        verifyNoInteractions( w.deployments );
    }

    @ParameterizedTest
    @ValueSource( strings = { "approved", "rejected", "running", "failed", "rolled_back" } )
    void evidenceWithAnyStateButVerifiedIsRefusedByName( final String state ) {
        final var r = tool.call( w.agent, args( "deployment", 4, "state", state, "evidence", "proof" ) );
        assertEquals( "evidence: Evidence goes with verified.", WorkMcpFixture.text( r ), state );
        verifyNoInteractions( w.deployments );
    }

    @Test
    void anUnknownStateNamesTheStatesThatExist() {
        final var r = tool.call( w.agent, args( "deployment", 4, "state", "paused" ) );
        assertEquals( "state: Use approved, rejected, running, verified, failed or rolled_back.", WorkMcpFixture.text( r ) );
        verifyNoInteractions( w.deployments );
    }

    @Test
    void theStateAndTheDeploymentAreRequired() {
        assertEquals( "state: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "deployment", 4 ) ) ) );
        assertEquals( "state: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "deployment", 4, "state", " " ) ) ) );
        assertEquals( "deployment: is required.", WorkMcpFixture.text( tool.call( w.agent, args( "state", "running" ) ) ) );
        assertEquals( "deployment: must be a whole number.", WorkMcpFixture.text(
            tool.call( w.agent, args( "deployment", 4.5, "state", "running" ) ) ) );
    }

    @Test
    void aMoveIsAuditedWithItsUnderscoresTurnedToDashes() {
        final var lines = WriteToolsAuditTest.audited( () ->
            tool.call( w.agent, args( "deployment", 4, "state", "rolled_back", "note", "bad" ) ) );
        assertEquals( 1, lines.size(), lines.toString() );
        assertTrue( lines.get( 0 ).contains( "MCP_WRITE tool=update_deployment action=deployment-rolled-back "
            + "target=deployment:4 author=builder" ), lines.get( 0 ) );
    }
}
