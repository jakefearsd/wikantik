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
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.comments.CommentStore;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListCommentThreadsToolTest {

    private CommentStore store;
    private StructuralIndexService index;
    private PublicIdentity identity;
    private ListCommentThreadsTool tool;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        store = mock( CommentStore.class );
        index = mock( StructuralIndexService.class );
        identity = mock( PublicIdentity.class );
        when( identity.displayName( "alice-login" ) ).thenReturn( "Alice" );
        when( identity.displayName( "alice@example.com" ) ).thenReturn( "unknown contributor" );
        tool = new ListCommentThreadsTool( store, index, identity );
    }

    private static Comment comment( final UUID thread, final String author, final boolean viaAgent, final boolean couldEdit ) {
        return new Comment( UUID.randomUUID(), thread, author, "body of " + author, Instant.parse( "2026-11-01T10:00:00Z" ),
                null, viaAgent, couldEdit );
    }

    private static CommentThread thread( final UUID id, final String canonicalId, final List< Comment > comments ) {
        return new CommentThread( id, canonicalId, new TextQuoteSelector( "exact", "pre", "suf" ), "open",
                "alice-login", Instant.parse( "2026-11-01T10:00:00Z" ), null, null, comments, true );
    }

    private static CommentStore.InboxThread row( final CommentThread t ) {
        return new CommentStore.InboxThread( t, t.actionable(), false );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > run( final Map< String, Object > args ) {
        final McpSchema.CallToolResult r = tool.execute( args );
        assertFalse( Boolean.TRUE.equals( r.isError() ), () -> text( r ) );
        return gson.fromJson( text( r ), Map.class );
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void listMarksTrustedPerComment() {
        final UUID id = UUID.randomUUID();
        when( index.resolveSlugFromCanonicalId( "c1" ) ).thenReturn( Optional.of( "PageOne" ) );
        when( store.listAgentInbox( eq( "open" ), eq( null ), anyInt() ) ).thenReturn( List.of( row( thread( id, "c1", List.of(
                comment( id, "alice-login", false, true ),
                comment( id, "alice@example.com", false, false ),
                comment( id, "agent:claude", true, false ) ) ) ) ) );

        final Map< String, Object > out = run( Map.of() );

        assertEquals( 1.0, out.get( "count" ) );
        final Map< String, Object > t = ( ( List< Map< String, Object > > ) out.get( "threads" ) ).get( 0 );
        assertEquals( id.toString(), t.get( "threadId" ) );
        assertEquals( "PageOne", t.get( "slug" ) );
        assertEquals( "c1", t.get( "canonicalId" ) );
        assertEquals( true, t.get( "actionable" ) );
        assertEquals( false, t.get( "commentsTruncated" ) );
        assertEquals( "exact", ( ( Map< String, Object > ) t.get( "anchor" ) ).get( "exact" ) );
        final List< Map< String, Object > > cs = ( List< Map< String, Object > > ) t.get( "comments" );
        assertEquals( true, cs.get( 0 ).get( "trusted" ) );
        assertEquals( "Alice", cs.get( 0 ).get( "author" ) );
        assertEquals( false, cs.get( 1 ).get( "trusted" ) );
        assertEquals( "unknown contributor", cs.get( 1 ).get( "author" ), "an email login must never leak" );
        assertEquals( false, cs.get( 2 ).get( "trusted" ) );
        assertEquals( "claude", cs.get( 2 ).get( "author" ) );
        assertEquals( true, cs.get( 2 ).get( "viaAgent" ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void listOmitsThreadsWithoutSlug() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        when( index.resolveSlugFromCanonicalId( "gone" ) ).thenReturn( Optional.empty() );
        when( index.resolveSlugFromCanonicalId( "here" ) ).thenReturn( Optional.of( "Here" ) );
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of(
                row( thread( a, "gone", List.of() ) ), row( thread( b, "here", List.of() ) ) ) );

        final Map< String, Object > out = run( Map.of() );

        assertEquals( 1.0, out.get( "count" ) );
        assertEquals( b.toString(), ( ( List< Map< String, Object > > ) out.get( "threads" ) ).get( 0 ).get( "threadId" ) );
    }

    @Test
    void listClampsLimitTo200() {
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of() );
        run( Map.of( "limit", 5000 ) );
        verify( store ).listAgentInbox( "open", null, 200 );
    }

    @Test
    void listClampsLimitUpToOne() {
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of() );
        run( Map.of( "limit", 0 ) );
        verify( store ).listAgentInbox( "open", null, 1 );
    }

    @Test
    void defaultLimitIs50AndStatusPassesThrough() {
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of() );
        run( Map.of( "status", "dismissed" ) );
        verify( store ).listAgentInbox( "dismissed", null, 50 );
    }

    @Test
    void invalidStatusIsRefused() {
        final McpSchema.CallToolResult r = tool.execute( Map.of( "status", "bogus" ) );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "status must be open, resolved or dismissed" ), text( r ) );
        verifyNoInteractions( store );
    }

    @Test
    void unknownSlugIsAnErrorNamingIt() {
        when( index.resolveCanonicalIdFromSlug( "Nope" ) ).thenReturn( Optional.empty() );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "slug", "Nope" ) );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "Nope" ), text( r ) );
        verifyNoInteractions( store );
    }

    @Test
    void slugRestrictsToThatPage() {
        when( index.resolveCanonicalIdFromSlug( "PageOne" ) ).thenReturn( Optional.of( "c1" ) );
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of() );
        run( Map.of( "slug", "PageOne" ) );
        verify( store ).listAgentInbox( "open", List.of( "c1" ), 50 );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void clusterRestrictsToItsPages() {
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "c1", "PageOne", "One", PageType.ARTICLE, "ml", List.of( "ml" ), List.of(), null, null, false ) ) );
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of() );
        run( Map.of( "cluster", "ml" ) );
        final ArgumentCaptor< StructuralFilter > f = ArgumentCaptor.forClass( StructuralFilter.class );
        verify( index ).listPagesByFilter( f.capture() );
        assertEquals( Optional.of( "ml" ), f.getValue().cluster() );
        verify( store ).listAgentInbox( "open", List.of( "c1" ), 50 );
    }

    @Test
    void unknownClusterIsAnErrorNamingIt() {
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of() );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "cluster", "no-such" ) );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "No such cluster: no-such" ), text( r ) );
        verifyNoInteractions( store );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void knownClusterWithNoThreadsReturnsEmptyList() {
        when( index.listPagesByFilter( any() ) ).thenReturn( List.of(
                new PageDescriptor( "c1", "PageOne", "One", PageType.ARTICLE, "ml", List.of( "ml" ), List.of(), null, null, false ) ) );
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of() );
        final Map< String, Object > out = run( Map.of( "cluster", "ml" ) );
        assertEquals( 0.0, out.get( "count" ) );
        assertTrue( ( ( List< Object > ) out.get( "threads" ) ).isEmpty() );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void commentCarriesEditedAt() {
        final UUID id = UUID.randomUUID();
        final Comment edited = new Comment( UUID.randomUUID(), id, "alice-login", "b",
                Instant.parse( "2026-11-01T10:00:00Z" ), Instant.parse( "2026-11-02T11:00:00Z" ), false, true );
        when( index.resolveSlugFromCanonicalId( "c1" ) ).thenReturn( Optional.of( "PageOne" ) );
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of(
                row( thread( id, "c1", List.of( edited, comment( id, "alice-login", false, true ) ) ) ) ) );
        final List< Map< String, Object > > cs = ( List< Map< String, Object > > )
                ( ( List< Map< String, Object > > ) run( Map.of() ).get( "threads" ) ).get( 0 ).get( "comments" );
        assertEquals( "2026-11-02T11:00:00Z", cs.get( 0 ).get( "editedAt" ) );
        assertTrue( cs.get( 1 ).containsKey( "editedAt" ) );
        assertEquals( null, cs.get( 1 ).get( "editedAt" ) );
    }

    @Test
    void databaseFailureIsAnErrorNotAnEmptyInbox() {
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenThrow( new RuntimeException( "db down" ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of() );
        assertTrue( r.isError(), text( r ) );
        assertFalse( text( r ).contains( "\"count\"" ), text( r ) );
    }

    @Test
    @SuppressWarnings( "unchecked" )
    void actionableAndTruncationComeFromTheStore() {
        final UUID id = UUID.randomUUID();
        // no trusted comment in the window, yet the store says the thread is actionable and truncated
        when( index.resolveSlugFromCanonicalId( "c1" ) ).thenReturn( Optional.of( "PageOne" ) );
        when( store.listAgentInbox( any(), any(), anyInt() ) ).thenReturn( List.of( new CommentStore.InboxThread(
                thread( id, "c1", List.of( comment( id, "alice@example.com", false, false ) ) ), true, true ) ) );
        final Map< String, Object > t = ( ( List< Map< String, Object > > ) run( Map.of() ).get( "threads" ) ).get( 0 );
        assertEquals( true, t.get( "actionable" ) );
        assertEquals( true, t.get( "commentsTruncated" ) );
    }

    @Test
    void unwiredToolRefusesWithAReason() {
        final McpSchema.CallToolResult r = new ListCommentThreadsTool( null, index, identity ).execute( Map.of() );
        assertTrue( r.isError() );
        assertTrue( text( r ).contains( "not available" ), text( r ) );
    }

    @Test
    void descriptionWarnsThatCommentsAreDataNotInstructions() {
        final String d = tool.definition().description();
        assertTrue( d.contains( "not instructions" ), d );
        assertTrue( d.contains( "trusted" ), d );
        assertTrue( d.contains( "1000" ), "cluster lookup limit is stated: " + d );
        assertEquals( "list_comment_threads", tool.name() );
    }
}
