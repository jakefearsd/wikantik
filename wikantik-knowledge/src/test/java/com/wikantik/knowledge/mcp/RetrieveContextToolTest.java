package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextQuery;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RelatedPage;
import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RetrieveContextToolTest {

    @Test
    void name_isRetrieveContext() {
        final RetrieveContextTool t = new RetrieveContextTool( mock( ContextRetrievalService.class ) );
        assertEquals( "retrieve_context", t.name() );
    }

    @Test
    void definition_requiresQuery() {
        final RetrieveContextTool t = new RetrieveContextTool( mock( ContextRetrievalService.class ) );
        final McpSchema.Tool def = t.definition();
        assertEquals( "retrieve_context", def.name() );
        assertTrue( def.inputSchema().required().contains( "query" ) );
    }

    @Test
    void execute_returnsShapedJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) ).thenReturn(
            new RetrievalResult( "search query", List.of( new RetrievedPage(
                "Alpha", "https://wiki.example/Alpha", 0.87, "alpha summary",
                "search", List.of( "retrieval" ),
                List.of( new RetrievedChunk(
                    List.of( "Alpha", "Intro" ), "alpha body", 0.9, List.of() ) ),
                List.of( new RelatedPage( "Beta", "similarity 0.75" ) ),
                "alice", new Date() ) ), 3 ) );

        final RetrieveContextTool t = new RetrieveContextTool( svc );
        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "search query" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"query\":\"search query\"" ) );
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"summary\":\"alpha summary\"" ) );
        assertTrue( text.contains( "\"cluster\":\"search\"" ) );
        assertTrue( text.contains( "\"contributingChunks\"" ) );
        assertTrue( text.contains( "\"headingPath\"" ) );
        assertTrue( text.contains( "\"alpha body\"" ) );
        assertTrue( text.contains( "\"relatedPages\"" ) );
        assertTrue( text.contains( "\"Beta\"" ) );
        assertTrue( text.contains( "\"totalMatched\":3" ) );
    }

    @Test
    void execute_passesFiltersToService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) )
            .thenReturn( new RetrievalResult( "q", List.of(), 0 ) );

        final RetrieveContextTool t = new RetrieveContextTool( svc );
        t.execute( Map.of(
            "query", "q",
            "maxPages", 3,
            "chunksPerPage", 2,
            "filters", Map.of( "cluster", "search", "tags", List.of( "retrieval" ) ) ) );

        final var captor = org.mockito.ArgumentCaptor.forClass( ContextQuery.class );
        verify( svc ).retrieve( captor.capture() );
        final ContextQuery q = captor.getValue();
        assertEquals( "q", q.query() );
        assertEquals( 3, q.maxPages() );
        assertEquals( 2, q.chunksPerPage() );
        assertEquals( "search", q.filter().cluster() );
        assertEquals( List.of( "retrieval" ), q.filter().tags() );
    }

    @Test
    void execute_returnsErrorOnBlankQuery() {
        final RetrieveContextTool t = new RetrieveContextTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_returnsErrorOnRuntimeExceptionFromService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.retrieve( any( ContextQuery.class ) ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final RetrieveContextTool t = new RetrieveContextTool( svc );
        final McpSchema.CallToolResult result = t.execute( Map.of( "query", "anything" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ),
            "runtime error should surface in the error payload" );
    }

    @Test
    void execute_rejectsInvalidIsoInstantInFilter() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        final RetrieveContextTool t = new RetrieveContextTool( svc );
        final McpSchema.CallToolResult result = t.execute( Map.of(
            "query", "q",
            "filters", Map.of( "modifiedAfter", "not-a-date" ) ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.toLowerCase().contains( "invalid iso-8601 instant" ),
            "bad ISO-8601 should surface as error text" );
    }
}
