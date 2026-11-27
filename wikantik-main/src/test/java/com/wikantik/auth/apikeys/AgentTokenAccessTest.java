package com.wikantik.auth.apikeys;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Work agents' own tokens (wka_...): who may use them where, and what they never carry. */
class AgentTokenAccessTest {

    private static final AgentTokenAuth.AgentRef AGENT = new AgentTokenAuth.AgentRef( 7L, 42L );

    private static final class Filter extends AbstractApiAccessFilter {
        Filter( final ApiKeyService.Scope scope, final ApiKeyService svc ) {
            super( new Surface( "Test", "test.access", scope,
                    Outcome.Denied.of( HttpServletResponse.SC_SERVICE_UNAVAILABLE, "{\"error\":\"unconfigured\"}" ),
                    "{\"error\":\"scope\"}" ), null, false, s -> true, svc );
        }
    }

    private static HttpServletRequest bearer( final String token ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( token == null ? null : "Bearer " + token );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        return req;
    }

    // ---- the work surface

    @Test
    void aVerifiedAgentTokenOnTheWorkSurfaceIsAllowedAsThatAgentAndNothingElse() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Filter filter = new Filter( ApiKeyService.Scope.WORK, svc );
        filter.agentTokens( token -> token.equals( "wka_good" ) ? Optional.of( AGENT ) : Optional.empty() );

        final var allowed = assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, filter.authorize( bearer( "wka_good" ) ) );

        assertEquals( "agent:42", allowed.clientId() );
        final HttpServletRequest eff = allowed.effectiveReq();
        assertInstanceOf( AgentTokenAuth.Request.class, eff );
        assertEquals( AGENT, ( (AgentTokenAuth.Request) eff ).ref() );
        assertEquals( AGENT, eff.getAttribute( AgentTokenAuth.ATTR_AGENT_REF ) );
        assertNull( eff.getUserPrincipal() );
        assertNull( eff.getRemoteUser() );
        assertFalse( eff.isUserInRole( "Admin" ) );
        assertFalse( eff.isUserInRole( "Authenticated" ) );
        assertEquals( ApiKeyPrincipalRequest.AUTH_TYPE, eff.getAuthType() );
        verifyNoInteractions( svc );
    }

    @Test
    void theAgentRequestPassesOtherAttributesThroughToTheWrappedRequest() {
        final HttpServletRequest inner = mock( HttpServletRequest.class );
        when( inner.getAttribute( "other" ) ).thenReturn( "value" );
        final AgentTokenAuth.Request wrapped = new AgentTokenAuth.Request( inner, AGENT );
        assertEquals( "value", wrapped.getAttribute( "other" ) );
        assertEquals( AGENT, wrapped.getAttribute( AgentTokenAuth.ATTR_AGENT_REF ) );
        assertEquals( "agent:42", AGENT.clientId() );
    }

    @Test
    void anUnknownOrRevokedAgentTokenIsDeniedNotTreatedAsAnApiKey() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Filter filter = new Filter( ApiKeyService.Scope.WORK, svc );
        filter.agentTokens( token -> Optional.empty() );
        final var denied = assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( bearer( "wka_nope" ) ) );
        assertEquals( HttpServletResponse.SC_FORBIDDEN, denied.status() );
        verify( svc, never() ).verify( any() );
    }

    @Test
    void anAgentTokenStoreOutageIsTheRetryable503() {
        final Filter filter = new Filter( ApiKeyService.Scope.WORK, mock( ApiKeyService.class ) );
        filter.agentTokens( token -> {
            throw new ApiKeyService.LookupFailedException( new java.sql.SQLException( "db blip" ) );
        } );
        final var denied = assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( bearer( "wka_x" ) ) );
        assertEquals( HttpServletResponse.SC_SERVICE_UNAVAILABLE, denied.status() );
    }

    // ---- everywhere else

    @Test
    void anAgentTokenOnANonWorkSurfaceIsNeverEvenVerified() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Filter filter = new Filter( ApiKeyService.Scope.MCP_ADMIN, svc );
        final AtomicInteger calls = new AtomicInteger();
        filter.agentTokens( token -> {
            calls.incrementAndGet();
            return Optional.of( AGENT );
        } );
        final var denied = assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( bearer( "wka_good" ) ) );
        assertEquals( HttpServletResponse.SC_FORBIDDEN, denied.status() );
        assertEquals( 0, calls.get(), "the verifier must not be consulted off the work surface" );
        verify( svc, never() ).verify( any() );
    }

    @Test
    void anAgentTokenWithoutAVerifierIsRefused() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Filter filter = new Filter( ApiKeyService.Scope.WORK, svc );
        final var denied = assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( bearer( "wka_good" ) ) );
        assertEquals( HttpServletResponse.SC_FORBIDDEN, denied.status() );
        verify( svc, never() ).verify( any() );
    }

    @Test
    void aNonAgentBearerGoesToTheApiKeyServiceAndNeverToTheAgentVerifier() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_key" ) ).thenReturn( Optional.empty() );
        final Filter filter = new Filter( ApiKeyService.Scope.WORK, svc );
        final AtomicInteger calls = new AtomicInteger();
        filter.agentTokens( token -> {
            calls.incrementAndGet();
            return Optional.of( AGENT );
        } );
        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( bearer( "wkk_key" ) ) );
        assertEquals( 0, calls.get() );
        verify( svc ).verify( "wkk_key" );
    }

    @Test
    void noBearerAtAllNeverReachesTheKeyStore() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Filter filter = new Filter( ApiKeyService.Scope.WORK, svc );
        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( bearer( null ) ) );
        verify( svc, never() ).verify( any() );
    }

    // ---- AgentTokenAuth helpers

    @Test
    void isAgentTokenIsAPrefixCheckAndNullSafe() {
        assertTrue( AgentTokenAuth.isAgentToken( "wka_abc" ) );
        assertTrue( AgentTokenAuth.isAgentToken( "wka_" ) );
        assertFalse( AgentTokenAuth.isAgentToken( "wkk_abc" ) );
        assertFalse( AgentTokenAuth.isAgentToken( "xwka_abc" ) );
        assertFalse( AgentTokenAuth.isAgentToken( "WKA_abc" ) );
        assertFalse( AgentTokenAuth.isAgentToken( "" ) );
        assertFalse( AgentTokenAuth.isAgentToken( null ) );
    }

    @Test
    void bearerExtractsOnlyABearerSchemeValue() {
        assertEquals( "tok", AgentTokenAuth.bearer( bearer( "tok" ) ) );
        assertNull( AgentTokenAuth.bearer( bearer( null ) ) );
        final HttpServletRequest basic = mock( HttpServletRequest.class );
        when( basic.getHeader( "Authorization" ) ).thenReturn( "Basic abc" );
        assertNull( AgentTokenAuth.bearer( basic ) );
        final HttpServletRequest lower = mock( HttpServletRequest.class );
        when( lower.getHeader( "Authorization" ) ).thenReturn( "bearer tok" );
        assertNull( AgentTokenAuth.bearer( lower ), "the scheme match is exact" );
    }

    @Test
    void isCredentialRequestIsTrueForAnApiKeyRecordOrAnAgentRefAndFalseOtherwise() {
        final HttpServletRequest none = mock( HttpServletRequest.class );
        assertFalse( AgentTokenAuth.isCredentialRequest( none ) );

        final HttpServletRequest key = mock( HttpServletRequest.class );
        when( key.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) ).thenReturn( new Object() );
        assertTrue( AgentTokenAuth.isCredentialRequest( key ) );

        final HttpServletRequest agent = mock( HttpServletRequest.class );
        when( agent.getAttribute( AgentTokenAuth.ATTR_AGENT_REF ) ).thenReturn( AGENT );
        assertTrue( AgentTokenAuth.isCredentialRequest( agent ) );

        assertTrue( AgentTokenAuth.isCredentialRequest( new AgentTokenAuth.Request( none, AGENT ) ) );
    }

    @Test
    void acceptNeedsAVerifierAndAWorkSurface() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        assertTrue( AgentTokenAuth.accept( null, true, req, "wka_x" ).isEmpty() );
        assertTrue( AgentTokenAuth.accept( t -> Optional.of( AGENT ), false, req, "wka_x" ).isEmpty() );
        final var accepted = AgentTokenAuth.accept( t -> Optional.of( AGENT ), true, req, "wka_x" );
        assertEquals( AGENT, accepted.orElseThrow().ref() );
        assertTrue( AgentTokenAuth.accept( t -> Optional.empty(), true, req, "wka_x" ).isEmpty() );
    }
}
