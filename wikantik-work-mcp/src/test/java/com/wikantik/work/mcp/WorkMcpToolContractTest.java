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

import com.wikantik.work.Actor;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkStatus;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The base class's own contract: what a tool built without a spec does, a null argument map, the stale hint. */
class WorkMcpToolContractTest {

    /** A tool with no spec that records the arguments it ran with and then throws what it was told to. */
    private static final class Recording extends WorkMcpTool {
        Map< String, Object > seen;
        RuntimeException toThrow;

        @Override protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) {
            seen = args;
            if ( toThrow != null ) {
                throw toThrow;
            }
            return ok( Map.of( "done", true ) );
        }

        @Override public String name() {
            return "recording";
        }
    }

    @Test
    void aToolBuiltWithoutASpecSaysSoByWhatItFailedToOverride() {
        final Recording tool = new Recording();
        assertEquals( false, tool.writes(), "no spec: a read" );
        final IllegalStateException definition = assertThrows( IllegalStateException.class, tool::definition );
        assertTrue( definition.getMessage().contains( "definition()" ), definition.getMessage() );
        assertTrue( definition.getMessage().contains( Recording.class.getName() ), definition.getMessage() );
        final IllegalStateException allowed = assertThrows( IllegalStateException.class, tool::allowed );
        assertTrue( allowed.getMessage().contains( "allowed()" ), allowed.getMessage() );
        final IllegalStateException name = assertThrows( IllegalStateException.class,
            () -> new WorkMcpTool() {
                @Override protected McpSchema.CallToolResult run( final Actor a, final Map< String, Object > args ) {
                    return null;
                }
            }.name() );
        assertTrue( name.getMessage().contains( "name()" ), name.getMessage() );
    }

    @Test
    void aNullArgumentMapIsAnEmptyOneForTheToolAndForTheRefusal() {
        final Recording tool = new Recording();
        tool.call( Actor.human( "x", java.util.Set.of(), false ), null );
        assertEquals( Map.of(), tool.seen );
        assertEquals( WorkMcpTool.NEEDS_KEY, WorkMcpFixture.text( tool.call( null, null ) ), "no actor, no map: still the answer" );
    }

    @Test
    void aStaleRefusalOfAToolThatNamesNoItemHasNoVersionHint() {
        final Recording tool = new Recording();
        tool.toThrow = new WorkConflictException( "The item changed.", WorkConflictException.STALE );
        assertEquals( "stale: The item changed.",
            WorkMcpFixture.text( tool.call( Actor.human( "x", java.util.Set.of(), false ), Map.of( "key", "PLAT-1" ) ) ) );
    }

    @Test
    void aStaleRefusalWhoseVersionCannotBeReadStillAnswersWithoutTheHint() throws SQLException {
        final MockWork w = new MockWork();
        when( w.svc.item( any(), any() ) ).thenThrow( new SQLException( "down", "08006" ) );
        final WorkMcpTool tool = new WorkMcpTool( ToolSpec.writes( "t" ).requiredArg( "key", "string", "k", "P-1" ), w.svc ) {
            @Override protected McpSchema.CallToolResult run( final Actor a, final Map< String, Object > args ) {
                throw new WorkConflictException( "The item changed.", WorkConflictException.STALE );
            }
        };
        assertEquals( "stale: The item changed.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1" ) ) ) );
    }

    @Test
    void aStaleRefusalNamesTheVersionTheItemHasNowButOtherConflictsDoNot() throws SQLException {
        final MockWork w = new MockWork();
        when( w.svc.item( any(), any() ) ).thenReturn(
            MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 8, null, null ) ) );
        final boolean[] stale = { true };
        final WorkMcpTool tool = new WorkMcpTool( ToolSpec.writes( "t" ).requiredArg( "key", "string", "k", "P-1" ), w.svc ) {
            @Override protected McpSchema.CallToolResult run( final Actor a, final Map< String, Object > args ) {
                throw new WorkConflictException( "Nope.", stale[ 0 ] ? WorkConflictException.STALE : "not_claimable" );
            }
        };
        assertEquals( "stale: Nope. (currentVersion: 8)", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", " PLAT-1 " ) ) ) );
        stale[ 0 ] = false;
        assertEquals( "not_claimable: Nope.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1" ) ) ) );
    }

    @Test
    void aStaleRefusalNeverReadsTheVersionForAServiceThatIsNotThere() {
        final WorkService none = mock( WorkService.class );
        final WorkMcpTool tool = new WorkMcpTool( ToolSpec.writes( "t" ), none ) {
            @Override protected McpSchema.CallToolResult run( final Actor a, final Map< String, Object > args ) {
                throw new WorkConflictException( "The item changed.", WorkConflictException.STALE );
            }
        };
        assertEquals( "stale: The item changed.",
            WorkMcpFixture.text( tool.call( Actor.human( "x", java.util.Set.of(), false ), Map.of() ) ),
            "no key to read the version of" );
    }
}
