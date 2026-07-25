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
    void schema_advertisesSlugNotPageName() {
        final var props = ToolSchemas.properties( new GetPageTool( mock( ContextRetrievalService.class ) )
                .definition().inputSchema() );
        assertTrue( props.containsKey( "slug" ) );
        assertFalse( props.containsKey( "pageName" ) );
    }

    @Test
    void execute_rejectsRetiredNameAlias() {
        // `slug` is the only accepted key. The declared schema marks it required and
        // mcp-sdk 2.0.0 rejects an alias-only call before execute() is even reached, so
        // resolving the alias here would just make the two layers disagree.
        final var result = new GetPageTool( mock( ContextRetrievalService.class ) )
                .execute( Map.of( "name", "Alpha" ) );
        assertTrue( result.isError(), "the retired 'name' alias must not resolve" );
    }


    @Test
    void execute_returnsPageJson() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Alpha" ) ).thenReturn( new RetrievedPage(
            "Alpha", "https://wiki.example/Alpha", 0.0, "alpha summary",
            "search", List.of( "retrieval" ),
            List.of(), List.of(), "alice", new Date(), false ) );

        final McpSchema.CallToolResult result =
            new GetPageTool( svc ).execute( Map.of( "slug", "Alpha" ) );

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
            new GetPageTool( svc ).execute( Map.of( "slug", "Nope" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "\"exists\":false" ) );
        assertTrue( text.contains( "\"pageName\":\"Nope\"" ) );
    }

    @Test
    void execute_returnsErrorOnBlankPageName() {
        final GetPageTool t = new GetPageTool( mock( ContextRetrievalService.class ) );
        final McpSchema.CallToolResult result = t.execute( Map.of( "slug", "" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "error" ) );
    }

    @Test
    void execute_returnsErrorOnRuntimeExceptionFromService() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( anyString() ) )
            .thenThrow( new RuntimeException( "DB offline" ) );
        final McpSchema.CallToolResult result = new GetPageTool( svc ).execute( Map.of( "slug", "Alpha" ) );
        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertTrue( text.contains( "DB offline" ) );
    }

    @Test
    void restrictedPageFilteredForGuest() {
        final ContextRetrievalService svc = mock( ContextRetrievalService.class );
        when( svc.getPage( "Secret" ) ).thenReturn( new RetrievedPage(
            "Secret", "https://wiki.example/Secret", 0.0, "TOP SECRET SUMMARY",
            "classified", List.of(), List.of(), List.of(), "alice", new Date(), false ) );

        final PageViewGate gate = slug -> !"Secret".equals( slug );
        final McpSchema.CallToolResult result = new GetPageTool( svc, gate ).execute( Map.of( "slug", "Secret" ) );

        final String text = ( (McpSchema.TextContent) result.content().get( 0 ) ).text();
        assertFalse( text.contains( "TOP SECRET SUMMARY" ),
                "restricted page content must not leak through get_page" );
        assertTrue( text.contains( "\"exists\":false" ),
                "restricted page should appear as not found" );
    }
}
