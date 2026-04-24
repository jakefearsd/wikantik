package com.wikantik.knowledge.mcp;

import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.RetrievedPage;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetPageToolTest {

    @Test
    void name_isGetPage() {
        assertEquals( "get_page", new GetPageTool( mock( ContextRetrievalService.class ) ).name() );
    }

    @Test
    void definition_requiresPageName() {
        final GetPageTool t = new GetPageTool( mock( ContextRetrievalService.class ) );
        assertTrue( t.definition().inputSchema().required().contains( "pageName" ) );
    }

    @Test
    void execute_returnsPageJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Alpha" ) ).thenReturn( new RetrievedPage(
            "Alpha", "https://wiki.example/Alpha", 0.0, "alpha summary",
            "search", List.of( "retrieval" ),
            List.of(), List.of(), "alice", new Date() ) );

        final McpSchema.CallToolResult result =
            new GetPageTool( svc ).execute( Map.of( "pageName", "Alpha" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"name\":\"Alpha\"" ) );
        assertTrue( text.contains( "\"summary\":\"alpha summary\"" ) );
        assertTrue( text.contains( "\"cluster\":\"search\"" ) );
        assertTrue( text.contains( "\"author\":\"alice\"" ) );
    }

    @Test
    void execute_returnsNotFoundWhenMissing() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Nope" ) ).thenReturn( null );

        final McpSchema.CallToolResult result =
            new GetPageTool( svc ).execute( Map.of( "pageName", "Nope" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"exists\":false" ) );
        assertTrue( text.contains( "\"pageName\":\"Nope\"" ) );
    }

    @Test
    void execute_returnsErrorOnBlankPageName() {
        final GetPageTool t = new GetPageTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "pageName", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }
}
