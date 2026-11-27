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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * A missing or blank item key never reaches the service: every tool that names an item answers "Not found." (the
 * answer for an unknown item) without asking anything, and a write tool logs the refusal with no item.
 */
class BlankItemKeyTest {

    private MockWork w;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
    }

    private List< WorkMcpTool > keyed() {
        return List.of( new GetItemTool( w.svc, w.directory ), new ClaimItemTool( w.svc, w.directory ),
            new ReleaseItemTool( w.svc ), new CommentOnItemTool( w.svc ), new UpdateItemTool( w.svc, w.directory ),
            new HandoffItemTool( w.svc, w.directory ), new OpenChangeTool( w.svc, w.directory ) );
    }

    private static Map< String, Object > withKey( final String key ) {
        final Map< String, Object > m = new HashMap<>();
        if ( key != null ) {
            m.put( "key", key );
        }
        m.put( "body", "b" );
        return m;
    }

    @ParameterizedTest
    @ValueSource( strings = { "", " ", "\t" } )
    void aBlankKeyIsNotFoundAndNothingIsAsked( final String blank ) {
        for ( final WorkMcpTool tool : keyed() ) {
            final Map< String, Object > args = withKey( blank );
            if ( tool.writes() ) {
                args.keySet().retainAll( java.util.Set.of( "key" ) );
                if ( tool instanceof CommentOnItemTool ) {
                    args.put( "body", "b" );
                }
            } else {
                args.remove( "body" );
            }
            if ( tool instanceof OpenChangeTool ) {
                args.put( "branch", "work/x" );
                args.put( "head_sha", "abc" );
            }
            if ( tool instanceof HandoffItemTool ) {
                args.put( "status", "review" );
                args.put( "queue", "reviewer" );
                args.put( "note", "n" );
            }
            if ( tool instanceof UpdateItemTool ) {
                args.put( "title", "t" );
            }
            assertEquals( WorkMcpTool.NOT_FOUND, WorkMcpFixture.text( tool.call( w.agent, args ) ), tool.name() );
        }
        verifyNoInteractions( w.claims, w.changes );
        assertTrue( org.mockito.Mockito.mockingDetails( w.svc ).getInvocations().stream()
            .noneMatch( i -> i.getMethod().getName().equals( "item" ) || i.getMethod().getName().equals( "updateItem" )
                || i.getMethod().getName().equals( "addComment" ) ),
            org.mockito.Mockito.mockingDetails( w.svc ).getInvocations().toString() );
    }

    @Test
    void anAbsentKeyIsNotFoundToo() {
        for ( final WorkMcpTool tool : keyed() ) {
            assertEquals( WorkMcpTool.NOT_FOUND, WorkMcpFixture.text( tool.call( w.agent, Map.of() ) ), tool.name() );
        }
    }

    @Test
    void aRefusedWriteForWantOfAKeyLogsNoItem() {
        final List< String > lines = WriteToolsAuditTest.audited( () -> new ClaimItemTool( w.svc, w.directory )
            .call( w.agent, Map.of( "key", " " ) ) );
        assertEquals( 1, lines.size(), lines.toString() );
        assertTrue( lines.get( 0 ).contains( "MCP_WORK_REFUSED tool=claim_item item=- agent=builder kind=not_found" ),
            lines.get( 0 ) );
        verifyNoMoreInteractions( w.claims );
    }
}
