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
            if ( m.startsWith( "MCP_WRITE" ) ) {
                lines.add( m );
            }
        }
    }

    private static List< String > audited( final Runnable action ) {
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
    void aRefusedWriteIsNotAudited() throws Exception {
        final MockWork w = new MockWork();
        final List< String > lines = audited( () -> {
            new UpdateItemTool( w.svc, w.directory ).call( w.agent, Map.of( "key", "PLAT-1", "expectedVersion", 1, "assignee", "x" ) );
            new CreateItemTool( w.svc ).call( null, Map.of() );
        } );
        assertEquals( List.of(), lines );
    }
}
