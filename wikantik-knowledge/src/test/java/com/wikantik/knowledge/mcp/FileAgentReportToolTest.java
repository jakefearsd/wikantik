package com.wikantik.knowledge.mcp;

import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.FileResult;
import com.wikantik.agentreports.Filer;
import com.wikantik.agentreports.ReportStoreException;
import com.wikantik.auth.apikeys.ApiKeyService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileAgentReportToolTest {

    private final AgentReportService service = mock( AgentReportService.class );
    private final FileAgentReportTool tool = new FileAgentReportTool( () -> service );

    private static com.wikantik.http.ratelimit.SlidingWindowRateLimiter limiter() {
        final var l = mock( com.wikantik.http.ratelimit.SlidingWindowRateLimiter.class );
        when( l.tryAcquire( org.mockito.ArgumentMatchers.anyString() ) ).thenReturn( true );
        return l;
    }

    private static ApiKeyService.Record key( final String login ) {
        return new ApiKeyService.Record( 7, "hash", login, "label", ApiKeyService.Scope.MCP_READ,
                Instant.EPOCH, "x", null, null, null, false );
    }

    private static McpSyncServerExchange exchange( final String client, final ApiKeyService.Record key ) {
        final McpSyncServerExchange ex = mock( McpSyncServerExchange.class );
        when( ex.getClientInfo() ).thenReturn( new McpSchema.Implementation( client, "1" ) );
        when( ex.sessionId() ).thenReturn( "sess-1" );
        when( ex.transportContext() ).thenReturn( key == null ? McpTransportContext.EMPTY
                : McpTransportContext.create( Map.of( FileAgentReportTool.KEY_RECORD, key ) ) );
        return ex;
    }

    private static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    private static Map< String, Object > args() {
        final Map< String, Object > m = new HashMap<>();
        m.put( "kind_of_work", "build" );
        m.put( "task", "t" );
        m.put( "outcome", "completed" );
        return m;
    }

    @Test
    void validCallReturnsTheNewId() {
        when( service.file( any(), any() ) ).thenReturn( new FileResult.Filed( 42 ) );
        final McpSchema.CallToolResult r = tool.execute( args(), exchange( "claude-code", key( "jake" ) ) );
        assertFalse( Boolean.TRUE.equals( r.isError() ), text( r ) );
        assertTrue( text( r ).contains( "42" ), text( r ) );
    }

    @Test
    void refusedIsAnErrorListingEveryReason() {
        when( service.file( any(), any() ) )
                .thenReturn( new FileResult.Refused( List.of( "task: required", "outcome: required" ) ) );
        final McpSchema.CallToolResult r = tool.execute( args(), exchange( "c", key( "jake" ) ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ) );
        assertTrue( text( r ).contains( "task: required" ) && text( r ).contains( "outcome: required" ), text( r ) );
    }

    @Test
    void rateLimitedNamesTheRetrySeconds() {
        when( service.file( any(), any() ) ).thenReturn( new FileResult.RateLimited( 1234 ) );
        final McpSchema.CallToolResult r = tool.execute( args(), exchange( "c", key( "jake" ) ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ) );
        assertTrue( text( r ).contains( "1234" ), text( r ) );
    }

    @Test
    void identityIsServerFilledNotTakenFromArguments() {
        when( service.file( any(), any() ) ).thenReturn( new FileResult.Filed( 1 ) );
        final Map< String, Object > a = args();
        a.put( "client", "spoof" );
        a.put( "filed_by", "mallory" );
        a.put( "session_hash", "spoof" );
        tool.execute( a, exchange( "claude-code", key( "jake" ) ) );
        final ArgumentCaptor< Filer > filer = ArgumentCaptor.forClass( Filer.class );
        verify( service ).file( any(), filer.capture() );
        assertEquals( "claude-code", filer.getValue().client() );
        assertEquals( "jake", filer.getValue().filedBy() );
        assertFalse( "spoof".equals( filer.getValue().sessionHash() ) );
    }

    @Test
    void noKeyOwnerIsRefusedAndNothingIsFiled() {
        final McpSchema.CallToolResult r = tool.execute( args(), exchange( "c", null ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ) );
        org.mockito.Mockito.verifyNoInteractions( service );
    }

    @Test
    void storeFailureIsAnErrorNotAThrow() {
        when( service.file( any(), any() ) ).thenThrow( new ReportStoreException( "boom", new RuntimeException() ) );
        final McpSchema.CallToolResult r = tool.execute( args(), exchange( "c", key( "jake" ) ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ) );
        assertTrue( text( r ).contains( "temporarily unavailable" ), text( r ) );
    }

    @Test
    void unwiredServiceFailsClosed() {
        final McpSchema.CallToolResult r = new FileAgentReportTool( () -> null )
                .execute( args(), exchange( "c", key( "jake" ) ) );
        assertTrue( Boolean.TRUE.equals( r.isError() ) );
        assertTrue( text( r ).contains( "report store unavailable" ), text( r ) );
    }

    @Test
    void descriptionStatesLimitsAndThatItIsTheOnlyWriteToTheReportStore() {
        final String d = tool.definition().description();
        for ( final String s : new String[] { "only write", "report store", "never creates or changes wiki pages",
                "300", "1000", "500", "2000", "100", "50", "every attempt the tool receives", "per key owner" } ) {
            assertTrue( d.contains( s ), "description missing '" + s + "': " + d );
        }
        assertFalse( Boolean.TRUE.equals( tool.definition().annotations().readOnlyHint() ) );
    }

    @Test
    void instructionsStateTheLimitsAndTheHourlyCount() {
        final String i = KnowledgeMcpInitializer.serverInstructions();
        assertTrue( i.contains( "file_agent_report" ) && i.contains( "only write" ) && i.contains( "hourly limit" ) && i.contains( "per key owner" ), i );
    }

    @Test
    void extractorCarriesTheKeyRecordAndNothingElse() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getAttribute( FileAgentReportTool.KEY_RECORD ) ).thenReturn( key( "jake" ) );
        assertTrue( FileAgentReportTool.extract( req ).get( FileAgentReportTool.KEY_RECORD ) instanceof ApiKeyService.Record );
        assertEquals( McpTransportContext.EMPTY, FileAgentReportTool.extract( mock( HttpServletRequest.class ) ) );
    }

    @Test
    void sessionlessExecuteIsRefused() {
        assertTrue( Boolean.TRUE.equals( tool.execute( args() ).isError() ) );
    }

    @Test
    void aKeyVerifiedByTheRealAccessFilterReachesTheServiceAsTheFilingOwner() {
        final ApiKeyService keys = mock( ApiKeyService.class );
        when( keys.verify( "wkk_read" ) ).thenReturn( java.util.Optional.of( key( "alice" ) ) );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_read" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        // a real request stores attributes; the wrapper the filter returns writes the key record through to it
        final Map< String, Object > attrs = new HashMap<>();
        org.mockito.Mockito.doAnswer( inv -> attrs.put( inv.getArgument( 0 ), inv.getArgument( 1 ) ) )
                .when( req ).setAttribute( any(), any() );
        when( req.getAttribute( any() ) ).thenAnswer( inv -> attrs.get( ( String ) inv.getArgument( 0 ) ) );
        final com.wikantik.auth.apikeys.AbstractApiAccessFilter.Outcome out =
                new com.wikantik.mcp.McpAccessFilter( "", false, limiter(), keys, ApiKeyService.Scope.MCP_READ )
                        .authorize( req );
        final HttpServletRequest forwarded = ( ( com.wikantik.auth.apikeys.AbstractApiAccessFilter.Outcome.Allowed ) out )
                .effectiveReq();

        final McpSyncServerExchange ex = mock( McpSyncServerExchange.class );
        final McpTransportContext carried = FileAgentReportTool.extract( forwarded );
        when( ex.transportContext() ).thenReturn( carried );
        when( service.file( any(), any() ) ).thenReturn( new FileResult.Filed( 9 ) );
        tool.execute( args(), ex );
        final ArgumentCaptor< Filer > filer = ArgumentCaptor.forClass( Filer.class );
        verify( service ).file( any(), filer.capture() );
        assertEquals( "alice", filer.getValue().filedBy() );
    }

    @Test
    void theSameReadKeyIsRefusedByTheAdminEndpointFilter() {
        final ApiKeyService keys = mock( ApiKeyService.class );
        when( keys.verify( "wkk_read" ) ).thenReturn( java.util.Optional.of( key( "alice" ) ) );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_read" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final var out = new com.wikantik.mcp.McpAccessFilter( "", false, limiter(), keys,
                ApiKeyService.Scope.MCP_ADMIN ).authorize( req );
        assertTrue( out instanceof com.wikantik.auth.apikeys.AbstractApiAccessFilter.Outcome.Denied, out.toString() );
    }
}
