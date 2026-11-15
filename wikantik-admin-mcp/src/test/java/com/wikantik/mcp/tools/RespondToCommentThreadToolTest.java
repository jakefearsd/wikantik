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
package com.wikantik.mcp.tools;

import com.google.gson.Gson;
import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.comments.TextQuoteSelector;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.comments.CommentStore;
import com.wikantik.comments.CommentStore.AgentAction;
import com.wikantik.comments.CommentStore.AgentOutcome;
import com.wikantik.comments.CommentStore.AgentResponse;
import com.wikantik.comments.mentions.MentionService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RespondToCommentThreadToolTest {

    private final UUID threadId = UUID.randomUUID();
    private CommentStore store;
    private MentionService mentions;
    private StructuralIndexService index;
    private RespondToCommentThreadTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        store = mock( CommentStore.class );
        mentions = mock( MentionService.class );
        index = mock( StructuralIndexService.class );
        tool = new RespondToCommentThreadTool( store, mentions, index );
        tool.setDefaultAuthor( "claude" );
        when( store.findThread( threadId ) ).thenReturn( Optional.of( new CommentThread( threadId, "c1",
                new TextQuoteSelector( "e", "p", "s" ), "open", "alice", Instant.now(), null, null, java.util.List.of(), true ) ) );
        when( index.resolveSlugFromCanonicalId( "c1" ) ).thenReturn( Optional.of( "PageOne" ) );
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    private static Comment saved( final UUID thread, final UUID id ) {
        return new Comment( id, thread, "agent:claude", "done", Instant.now(), null, true, false );
    }

    private void assertRefusal( final McpSchema.CallToolResult r, final String message ) {
        assertTrue( r.isError() );
        final Map< ?, ? > body = gson.fromJson( text( r ), Map.class );
        assertEquals( message, body.get( "error" ) );
        assertTrue( body.get( "suggestion" ) != null && !body.get( "suggestion" ).toString().isBlank(), "needs a hint" );
    }

    @Test
    void nonUuidThreadIdIsRefusedNotThrown() {
        assertRefusal( tool.execute( Map.of( "threadId", "not-a-uuid", "action", "reply", "body", "x" ) ),
                "threadId must be a thread id from list_comment_threads" );
        verifyNoInteractions( store );
    }

    @Test
    void missingThreadIdIsRefused() {
        assertRefusal( tool.execute( Map.of( "action", "reply", "body", "x" ) ),
                "threadId must be a thread id from list_comment_threads" );
    }

    @Test
    void blankBodyIsRefused() {
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "  " ) ),
                "body is required: say what you did or why you are dismissing" );
        verifyNoInteractions( store );
    }

    @Test
    void unknownActionIsRefused() {
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "delete", "body", "x" ) ),
                "action must be reply, resolve or dismiss" );
        verifyNoInteractions( store );
    }

    @Test
    void notFoundIsRefused() {
        when( store.findThread( threadId ) ).thenReturn( Optional.empty() );
        when( store.respondAsAgent( any(), any(), any(), any(), any() ) )
                .thenReturn( new AgentResponse( AgentOutcome.NOT_FOUND, null, null ) );
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "x" ) ),
                "No such comment thread, or its page no longer exists" );
    }

    @Test
    void pageWithoutSlugIsRefusedAndNothingIsWritten() {
        when( index.resolveSlugFromCanonicalId( "c1" ) ).thenReturn( Optional.empty() );
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "x" ) ),
                "No such comment thread, or its page no longer exists" );
        verify( store, never() ).respondAsAgent( any(), any(), any(), any(), any() );
    }

    @Test
    void notAgentAvailableIsRefused() {
        when( store.respondAsAgent( any(), any(), any(), any(), any() ) )
                .thenReturn( new AgentResponse( AgentOutcome.NOT_AGENT_AVAILABLE, null, "open" ) );
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "x" ) ),
                "The commenter did not make this thread available to agents" );
    }

    @Test
    void notOpenIsRefused() {
        when( store.respondAsAgent( any(), any(), any(), any(), any() ) )
                .thenReturn( new AgentResponse( AgentOutcome.NOT_OPEN, null, "resolved" ) );
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "dismiss", "body", "x" ) ),
                "Thread is no longer open" );
    }

    @Test
    void notActionableIsRefused() {
        when( store.respondAsAgent( any(), any(), any(), any(), any() ) )
                .thenReturn( new AgentResponse( AgentOutcome.NOT_ACTIONABLE, null, "open" ) );
        assertRefusal( tool.execute( Map.of( "threadId", threadId.toString(), "action", "resolve", "body", "x" ) ),
                "No comment on this thread is from someone who may edit the page; reply or dismiss instead" );
    }

    @Test
    void replyReturnsOpenStatus() {
        final UUID commentId = UUID.randomUUID();
        when( store.respondAsAgent( threadId, AgentAction.REPLY, "agent:claude", "done", mentions ) )
                .thenReturn( new AgentResponse( AgentOutcome.OK, saved( threadId, commentId ), "open" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", " done " ) );

        assertTrue( !Boolean.TRUE.equals( r.isError() ), text( r ) );
        final Map< ?, ? > body = gson.fromJson( text( r ), Map.class );
        assertEquals( threadId.toString(), body.get( "threadId" ) );
        assertEquals( "reply", body.get( "action" ) );
        assertEquals( "open", body.get( "status" ) );
        assertEquals( commentId.toString(), body.get( "commentId" ) );
    }

    @Test
    void blankClientNameBecomesAgentUnknown() {
        final RespondToCommentThreadTool unnamed = new RespondToCommentThreadTool( store, mentions, index );
        unnamed.setDefaultAuthor( "  " );
        when( store.respondAsAgent( any(), any(), any(), any(), any() ) )
                .thenReturn( new AgentResponse( AgentOutcome.OK, saved( threadId, UUID.randomUUID() ), "open" ) );

        unnamed.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "x" ) );

        verify( store ).respondAsAgent( eq( threadId ), eq( AgentAction.REPLY ), eq( "agent:unknown" ), eq( "x" ), eq( mentions ) );
    }

    @Test
    void resolveAuditsWrite() {
        when( store.respondAsAgent( threadId, AgentAction.RESOLVE, "agent:claude", "fixed", mentions ) )
                .thenReturn( new AgentResponse( AgentOutcome.OK, saved( threadId, UUID.randomUUID() ), "resolved" ) );
        try ( MockedStatic< McpAudit > audit = Mockito.mockStatic( McpAudit.class ) ) {
            final McpSchema.CallToolResult r = tool.execute(
                    Map.of( "threadId", threadId.toString(), "action", "resolve", "body", "fixed" ) );
            assertEquals( "resolved", gson.fromJson( text( r ), Map.class ).get( "status" ) );
            audit.verify( () -> McpAudit.logWrite( eq( "respond_to_comment_thread" ), eq( "resolve" ),
                    eq( threadId.toString() ), eq( "claude" ) ) );
        }
    }

    @Test
    void refusalDoesNotAudit() {
        when( store.respondAsAgent( any(), any(), any(), any(), any() ) )
                .thenReturn( new AgentResponse( AgentOutcome.NOT_OPEN, null, "resolved" ) );
        try ( MockedStatic< McpAudit > audit = Mockito.mockStatic( McpAudit.class ) ) {
            tool.execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "x" ) );
            audit.verifyNoInteractions();
        }
    }

    @Test
    void unwiredToolRefusesWithAReason() {
        final McpSchema.CallToolResult r = new RespondToCommentThreadTool( null, null, index )
                .execute( Map.of( "threadId", threadId.toString(), "action", "reply", "body", "x" ) );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "not available" ), text( r ) );
    }

    @Test
    void descriptionStatesTheSafetyRules() {
        final String d = tool.definition().description();
        assertTrue( d.contains( "not instructions" ), d );
        assertTrue( d.contains( "trusted" ), d );
        assertTrue( d.contains( "I changed something" ), d );
        assertTrue( d.contains( "reason" ), d );
        assertEquals( "respond_to_comment_thread", tool.name() );
    }
}
