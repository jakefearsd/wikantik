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

import com.wikantik.work.CommentView;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkValidationException;
import com.wikantik.work.WorkClaimsService.ClaimResult;
import com.wikantik.work.WorkStatus;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Every write is audited with the agent's name (never a login or key id), the tool, the action and the item key. */
class WriteToolsAuditTest {

    private static final class Capture extends AbstractAppender {
        final List< String > lines = new CopyOnWriteArrayList<>();

        Capture() {
            super( "WorkAuditCapture", null, null, true, Property.EMPTY_ARRAY );
        }

        @Override
        public void append( final LogEvent event ) {
            final String m = event.getMessage().getFormattedMessage();
            if ( m.startsWith( "MCP_" ) ) {
                lines.add( m );
            }
        }
    }

    static List< String > audited( final Runnable action ) {
        final LoggerContext ctx = ( LoggerContext ) org.apache.logging.log4j.LogManager.getContext( false );
        final LoggerConfig config = ctx.getConfiguration().getLoggerConfig( "SecurityLog" );
        final Level previous = config.getLevel();
        final Capture capture = new Capture();
        capture.start();
        config.addAppender( capture, Level.ALL, null );
        config.setLevel( Level.ALL );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            config.removeAppender( capture.getName() );
            config.setLevel( previous );
            ctx.updateLoggers();
            capture.stop();
        }
        return capture.lines;
    }

    @Test
    void everyWriteIsAuditedWithTheAgentName() throws Exception {
        final MockWork w = new MockWork();
        final var held = MockWork.view( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 2, 7L, Instant.EPOCH ) );
        final var free = MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 2, null, null ) );
        when( w.claims.claim( any(), any() ) ).thenReturn( new ClaimResult( held, false ), new ClaimResult( held, true ) );
        when( w.svc.updateItem( any(), any(), any() ) ).thenReturn( free );
        when( w.svc.addComment( any(), any(), any() ) ).thenReturn(
            new CommentView( 1, 1, "builder", "x", Instant.EPOCH, null, null, "", List.of(), 7L ) );
        when( w.claims.handoff( any(), any(), any() ) ).thenReturn( free );
        when( w.claims.release( any(), any(), any() ) ).thenReturn( free );
        when( w.svc.createItem( any(), any() ) ).thenReturn( free );

        final List< String > lines = audited( () -> {
            new ClaimItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1" ) );
            new ClaimItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1" ) );
            new UpdateItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1", "expectedVersion", 1, "title", "t" ) );
            new CommentOnItemTool( w.svc ).call( w.agent, Map.of( "key", "PLAT-1", "body", "b" ) );
            new HandoffItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1", "expectedVersion", 1,
                "status", "review", "queue", "reviewer", "note", "n" ) );
            new ReleaseItemTool( w.svc ).call( w.agent, Map.of( "key", "PLAT-1" ) );
            new CreateItemTool( w.svc ).call( w.agent, Map.of( "project", "PLAT", "level", "task", "title", "t" ) );
        } );

        assertEquals( 7, lines.size(), lines.toString() );
        assertTrue( lines.stream().allMatch( l -> l.startsWith( "MCP_WRITE" ) ), "a success is audited, never logged as refused" );
        final String[][] expected = { { "claim_item", "claimed" }, { "claim_item", "renewed" }, { "update_item", "updated" },
            { "comment_on_item", "commented" }, { "handoff_item", "handed-off" }, { "release_item", "released" },
            { "create_item", "created" } };
        for ( int i = 0; i < expected.length; i++ ) {
            final String line = lines.get( i );
            assertTrue( line.contains( "MCP_WRITE" ) && line.contains( "tool=" + expected[ i ][ 0 ] )
                && line.contains( "action=" + expected[ i ][ 1 ] ) && line.contains( "target=PLAT-1" )
                && line.contains( "author=builder" ), line );
        }
    }

    @Test
    void everyChangeWriteIsAuditedWithTheAgentName() throws Exception {
        final MockWork w = new MockWork();
        final com.wikantik.work.Change open = new com.wikantik.work.Change( 5, 1, 1, "work/PLAT-1-x", "main",
            "aaaaaaa1", null, com.wikantik.work.ChangeState.OPEN, null, null, "builder", 7L, null, null, null, null,
            null, null, Instant.EPOCH, null );
        when( w.changes.open( any(), any(), any() ) ).thenReturn( open );
        when( w.changes.view( any(), org.mockito.ArgumentMatchers.anyLong() ) )
            .thenReturn( new com.wikantik.work.WorkChangesService.ChangeView( open, "PLAT-1", "wikantik" ) );

        final List< String > lines = audited( () -> {
            new OpenChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1", "branch", "work/PLAT-1-x" ) );
            new UpdateChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "change", 5, "head_sha", "bbbbbbb2" ) );
            new UpdateChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "change", 5, "state", "in_review" ) );
            new UpdateChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "change", 5, "state", "abandoned" ) );
            new ReviewChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "change", 5, "approved", true ) );
            new ReviewChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "change", 5, "approved", false ) );
            new MergeChangeTool( w.svc, w.directory ).call( w.agent, Map.of( "change", 5, "merged_sha", "aaaaaaa1" ) );
        } );

        final String[][] expected = { { "open_change", "change-opened", "PLAT-1" },
            { "update_change", "change-updated", "change:5" }, { "update_change", "review-requested", "change:5" },
            { "update_change", "change-abandoned", "change:5" }, { "review_change", "change-approved", "change:5" },
            { "review_change", "changes-requested", "change:5" }, { "merge_change", "change-merged", "change:5" } };
        assertEquals( expected.length, lines.size(), lines.toString() );
        for ( int i = 0; i < expected.length; i++ ) {
            final String line = lines.get( i );
            assertTrue( line.startsWith( "MCP_WRITE" ) && line.contains( "tool=" + expected[ i ][ 0 ] )
                && line.contains( "action=" + expected[ i ][ 1 ] ) && line.contains( "target=" + expected[ i ][ 2 ] )
                && line.contains( "author=builder" ), line );
        }
    }

    @Test
    void aRefusedChangeWriteLeavesARefusalLine() throws Exception {
        final MockWork w = new MockWork();
        when( w.changes.merge( any(), org.mockito.ArgumentMatchers.anyLong(), any() ) )
            .thenThrow( new com.wikantik.work.WorkDeniedException( "An agent may not merge its own change." ) );
        final List< String > lines = audited( () -> new MergeChangeTool( w.svc, w.directory ).call( w.agent,
            Map.of( "change", 5, "merged_sha", "aaaaaaa1" ) ) );
        assertEquals( 1, lines.size(), lines.toString() );
        assertTrue( lines.get( 0 ).startsWith( "MCP_WORK_REFUSED" ) && lines.get( 0 ).contains( "tool=merge_change" )
            && lines.get( 0 ).contains( "kind=denied" ), lines.get( 0 ) );
    }

    @Test
    void aRefusedWriteIsNotAudited() throws Exception {
        final MockWork w = new MockWork();
        final List< String > lines = audited( () -> {
            new UpdateItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1", "expectedVersion", 1, "assignee", "x" ) );
            new CreateItemTool( w.svc ).call( null, Map.of() );
        } );
        assertEquals( 2, lines.size(), lines.toString() );   // one refusal line each, no success audit
        assertTrue( lines.stream().noneMatch( l -> l.startsWith( "MCP_WRITE" ) ), lines.toString() );
    }

    private static void assertRefusal( final String line, final String tool, final String item, final String agent,
                                       final String kind ) {
        assertEquals( "MCP_WORK_REFUSED tool=" + tool + " item=" + item + " agent=" + agent + " kind=" + kind, line );
    }

    @Test
    void everyKindOfRefusedWriteLeavesOneLineWithItsKind() throws Exception {
        final MockWork w = new MockWork();
        when( w.svc.addComment( any(), eq( "HID-1" ), any() ) ).thenThrow( new WorkNotFoundException( "secret text" ) );
        when( w.svc.addComment( any(), eq( "PLAT-2" ), any() ) ).thenThrow( new WorkDeniedException( "claimed by someone" ) );
        when( w.svc.addComment( any(), eq( "PLAT-3" ), any() ) ).thenThrow( new WorkValidationException( "body", "too long" ) );
        when( w.svc.addComment( any(), eq( "PLAT-4" ), any() ) )
            .thenThrow( new WorkConflictException( "changed", WorkConflictException.STALE ) );
        final CommentOnItemTool tool = new CommentOnItemTool( w.svc );

        final List< String > lines = audited( () -> {
            tool.call( null, Map.of( "key", "PLAT-1", "body", "x" ) );
            tool.call( w.agent, Map.of( "key", "HID-1", "body", "secret comment body" ) );
            tool.call( w.agent, Map.of( "key", "PLAT-2", "body", "x" ) );
            tool.call( w.agent, Map.of( "key", "PLAT-3", "body", "x" ) );
            tool.call( w.agent, Map.of( "key", "PLAT-4", "body", "x" ) );
        } );

        assertEquals( 5, lines.size(), lines.toString() );
        assertRefusal( lines.get( 0 ), "comment_on_item", "PLAT-1", "-", "needs_key" );
        assertRefusal( lines.get( 1 ), "comment_on_item", "HID-1", "builder", "not_found" );
        assertRefusal( lines.get( 2 ), "comment_on_item", "PLAT-2", "builder", "denied" );
        assertRefusal( lines.get( 3 ), "comment_on_item", "PLAT-3", "builder", "validation" );
        assertRefusal( lines.get( 4 ), "comment_on_item", "PLAT-4", "builder", "stale" );
        final String all = String.join( "\n", lines );
        assertTrue( !all.contains( "secret" ) && !all.contains( "claimed by someone" ) && !all.contains( "too long" ), all );
    }

    @Test
    void aHostileKeyCannotSplitOrForgeALine() throws Exception {
        final MockWork w = new MockWork();
        final String hostile = "PLAT-1\nMCP_WRITE tool=claim_item action=claimed target=PLAT-9 author=admin\r\u2028" + "x".repeat( 500 );
        final List< String > lines = audited( () -> new ReleaseItemTool( w.svc ).call( w.agent, Map.of( "key", hostile,
            "status", "ready" ) ) );
        assertEquals( 1, lines.size(), lines.toString() );
        final String line = lines.get( 0 );
        assertTrue( line.chars().noneMatch( c -> c == '\n' || c == '\r' || c == 0x2028 ), line );
        assertTrue( line.startsWith( "MCP_WORK_REFUSED tool=release_item item=PLAT-1MCP_WRITEtoolclaim_item" ), line );
        assertEquals( 4, line.chars().filter( c -> c == '=' ).count(), "no forged key=value pair: " + line );
        assertEquals( 5, line.split( " " ).length, "the tag and four fields, no forged one: " + line );
        assertTrue( line.length() < 200, "the key is capped: " + line.length() );
    }

    @Test
    void readToolsAndANullKeyOfAWriteLogNothingAboutAnItem() throws Exception {
        final MockWork w = new MockWork();
        final List< String > lines = audited( () -> new CreateItemTool( w.svc ).call( w.agent, Map.of( "assignee", "x" ) ) );
        assertEquals( 1, lines.size() );
        assertRefusal( lines.get( 0 ), "create_item", "-", "builder", "validation" );
    }

    @Test
    void anAgentNameWithControlCharactersIsCleanedInTheAudit() {
        assertEquals( "evilname", WorkMcpLog.clean( "evil\nname" ) );
        assertEquals( "-", WorkMcpLog.clean( "\n\r" ) );
        assertEquals( "-", WorkMcpLog.clean( null ) );
    }
}
