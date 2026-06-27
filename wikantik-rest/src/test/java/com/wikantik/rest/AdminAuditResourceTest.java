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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditQuery;
import com.wikantik.audit.AuditService;
import com.wikantik.audit.PersistedAuditEntry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AdminAuditResource}. AuditService is mocked — no database.
 *
 * <p>Note on status assertions: {@code sendJson} in {@link RestServletBase} does NOT
 * call {@code setStatus(200)} — the servlet container defaults the response to 200.
 * Success-path tests therefore do NOT verify {@code setStatus(200)}; they instead verify
 * the response body (JSON content-type set, body parseable, specific fields present).
 * Error-path tests DO verify {@code setStatus(4xx/5xx)} since {@code sendError} explicitly
 * calls {@code response.setStatus(status)}.</p>
 */
class AdminAuditResourceTest {

    private AuditService auditService;
    private AdminAuditResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    /**
     * Builds a minimal AuditEntry with required fields.
     */
    private static AuditEntry entry( final AuditCategory cat, final AuditOutcome outcome,
                                     final String actor, final String targetId ) {
        return AuditEntry.builder()
                .eventTime( Instant.parse( "2026-06-01T10:00:00Z" ) )
                .category( cat )
                .eventType( "page.save" )
                .actorType( "user" )
                .actorPrincipal( actor )
                .targetId( targetId )
                .outcome( outcome )
                .build();
    }

    private static PersistedAuditEntry persisted( final long seq, final AuditEntry e ) {
        return new PersistedAuditEntry( seq,
                Instant.parse( "2026-06-01T10:00:01Z" ),
                "prev-hash-" + seq,
                "row-hash-" + seq,
                e );
    }

    /** Returns null for any parameter not explicitly stubbed. */
    private void stubAllParamsNull() {
        when( req.getParameter( any() ) ).thenReturn( null );
    }

    @BeforeEach
    void setUp() throws Exception {
        auditService = mock( AuditService.class );
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getAuditService() ).thenReturn( auditService );

        resource = new AdminAuditResource();
        resource.setEngine( engine );   // protected accessor on RestServletBase — same package

        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );
    }

    // -----------------------------------------------------------------------
    // Helper: parse JSON body
    // -----------------------------------------------------------------------

    private JsonObject json() {
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    private JsonArray jsonArray() {
        return JsonParser.parseString( body.toString() ).getAsJsonArray();
    }

    /** Asserts a 200-equivalent success: sendJson was invoked (content-type set, body non-empty). */
    private void assertSuccessJson() {
        verify( resp ).setContentType( "application/json" );
        assertFalse( body.toString().isBlank(), "Response body should not be empty on success" );
        // setStatus was NOT called (sendJson doesn't call it — the container defaults to 200)
        verify( resp, never() ).setStatus( anyInt() );
    }

    // -----------------------------------------------------------------------
    // Service unavailable
    // -----------------------------------------------------------------------

    @Test
    void auditServiceUnavailableReturns503() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getAuditService() ).thenReturn( null );
        resource.setEngine( engine );

        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body.toString().contains( "audit log unavailable" ),
                "Body should describe why service is unavailable" );
    }

    @Test
    void nonWikiEngineReturns503() throws Exception {
        // A plain Engine mock (not WikiEngine) fails the instanceof check → auditService() returns null
        final com.wikantik.api.core.Engine plainEngine = mock( com.wikantik.api.core.Engine.class );
        resource.setEngine( plainEngine );

        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    // -----------------------------------------------------------------------
    // doList — happy path: no filters, returns JSON array
    // -----------------------------------------------------------------------

    @Test
    void listNoFiltersReturnsJsonArrayWith200() throws Exception {
        final AuditEntry e = entry( AuditCategory.AUTHN, AuditOutcome.SUCCESS, "alice", null );
        when( auditService.query( any() ) ).thenReturn( List.of( persisted( 1L, e ) ) );

        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();

        resource.doGet( req, resp );

        // sendJson sets content-type but not setStatus — implicit 200
        verify( resp ).setContentType( "application/json" );
        final JsonArray arr = jsonArray();
        assertEquals( 1, arr.size() );
        final JsonObject row = arr.get( 0 ).getAsJsonObject();
        assertEquals( 1L, row.get( "seq" ).getAsLong() );
        assertEquals( "AUTHN", row.get( "category" ).getAsString() );
        assertEquals( "page.save", row.get( "eventType" ).getAsString() );
        assertEquals( "alice", row.get( "actorPrincipal" ).getAsString() );
        assertEquals( "SUCCESS", row.get( "outcome" ).getAsString() );
        assertEquals( "row-hash-1", row.get( "rowHash" ).getAsString() );
        assertEquals( "prev-hash-1", row.get( "prevHash" ).getAsString() );
    }

    @Test
    void listEmptyResultReturnsEmptyJsonArray() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        assertEquals( 0, jsonArray().size() );
    }

    // -----------------------------------------------------------------------
    // doList — category filter: valid enum, unknown string
    // -----------------------------------------------------------------------

    @Test
    void listWithValidCategoryPropagatesEnumToQuery() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "category" ) ).thenReturn( "CONTENT" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( AuditCategory.CONTENT, captor.getValue().category() );
    }

    @Test
    void listWithUnknownCategoryReturns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "category" ) ).thenReturn( "BOGUS_CAT" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body.toString().contains( "BOGUS_CAT" ), "Error should echo the bad category name" );
        verify( auditService, never() ).query( any() );
    }

    // -----------------------------------------------------------------------
    // doList — outcome filter: valid (case-insensitive), unknown string
    // -----------------------------------------------------------------------

    @Test
    void listWithValidOutcomePropagatesEnumToQuery() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "outcome" ) ).thenReturn( "denied" );  // lowercase → toUpperCase

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( AuditOutcome.DENIED, captor.getValue().outcome() );
    }

    @Test
    void listWithUnknownOutcomeReturns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "outcome" ) ).thenReturn( "MAYBE" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body.toString().contains( "MAYBE" ) );
        verify( auditService, never() ).query( any() );
    }

    // -----------------------------------------------------------------------
    // doList — from / to timestamp filters: valid ISO + invalid format
    // -----------------------------------------------------------------------

    @Test
    void listWithValidFromAndToPropagatesToQuery() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "from" ) ).thenReturn( "2026-06-01T00:00:00Z" );
        when( req.getParameter( "to" ) ).thenReturn( "2026-06-30T23:59:59Z" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( Instant.parse( "2026-06-01T00:00:00Z" ), captor.getValue().from() );
        assertEquals( Instant.parse( "2026-06-30T23:59:59Z" ), captor.getValue().to() );
    }

    @Test
    void listWithInvalidFromReturns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "from" ) ).thenReturn( "not-a-timestamp" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body.toString().contains( "not-a-timestamp" ) );
        verify( auditService, never() ).query( any() );
    }

    @Test
    void listWithInvalidToReturns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "to" ) ).thenReturn( "bad-date" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body.toString().contains( "bad-date" ) );
        verify( auditService, never() ).query( any() );
    }

    // -----------------------------------------------------------------------
    // doList — limit parameter: non-numeric (uses default), over-max (clamped), zero (clamped)
    // -----------------------------------------------------------------------

    @Test
    void listWithNonNumericLimitUsesDefaultAndSucceeds() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "limit" ) ).thenReturn( "not-a-number" );

        resource.doGet( req, resp );

        // Non-numeric limit logs a warning but falls back to DEFAULT_LIMIT=100 — NOT a 400
        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( 100, captor.getValue().limit(), "Should use DEFAULT_LIMIT=100 for non-numeric input" );
    }

    @Test
    void listWithLimitExceedingMaxIsClampedTo1000() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "limit" ) ).thenReturn( "99999" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( 1000, captor.getValue().limit(), "Limit should be clamped to MAX_LIMIT=1000" );
    }

    @Test
    void listWithLimitZeroIsClampedToOne() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "limit" ) ).thenReturn( "0" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( 1, captor.getValue().limit(), "Limit should be raised to minimum 1" );
    }

    // -----------------------------------------------------------------------
    // doList — beforeSeq parameter: valid + invalid
    // -----------------------------------------------------------------------

    @Test
    void listWithValidBeforeSeqPropagatesValue() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "beforeSeq" ) ).thenReturn( "42" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( 42L, captor.getValue().beforeSeq() );
    }

    @Test
    void listWithInvalidBeforeSeqReturns400() throws Exception {
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "beforeSeq" ) ).thenReturn( "not-a-long" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body.toString().contains( "not-a-long" ) );
        verify( auditService, never() ).query( any() );
    }

    // -----------------------------------------------------------------------
    // doList — actor / target / eventType filters passed through to AuditQuery
    // -----------------------------------------------------------------------

    @Test
    void listWithActorAndTargetAndEventTypePropagatedToQuery() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "actor" ) ).thenReturn( "bob" );
        when( req.getParameter( "target" ) ).thenReturn( "MainPage" );
        when( req.getParameter( "eventType" ) ).thenReturn( "page.delete" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( "bob", captor.getValue().actorId() );
        assertEquals( "MainPage", captor.getValue().targetId() );
        assertEquals( "page.delete", captor.getValue().eventType() );
    }

    // -----------------------------------------------------------------------
    // doList — JSON serialization of null fields in PersistedAuditEntry
    // -----------------------------------------------------------------------

    @Test
    void listJsonSerializesNullableFieldsAsJsonNull() throws Exception {
        // Entry with null actorPrincipal, targetId — no sourceIp, no detail
        final AuditEntry e = AuditEntry.builder()
                .eventTime( Instant.parse( "2026-06-10T08:00:00Z" ) )
                .category( AuditCategory.ADMIN )
                .eventType( "admin.action" )
                .actorType( "service" )
                .outcome( AuditOutcome.SUCCESS )
                .build();  // actorPrincipal, targetId, sourceIp all null
        final PersistedAuditEntry row = new PersistedAuditEntry( 7L,
                null,  // createdAt null
                null,  // prevHash null (first row)
                "hash-7",
                e );
        when( auditService.query( any() ) ).thenReturn( List.of( row ) );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final JsonObject r = jsonArray().get( 0 ).getAsJsonObject();
        assertEquals( 7L, r.get( "seq" ).getAsLong() );
        assertEquals( "hash-7", r.get( "rowHash" ).getAsString() );
        // toJson uses addProperty(key, (String)null) which sets JsonNull for null Strings,
        // EXCEPT that Gson may omit the key entirely when a GsonBuilder without serializeNulls()
        // is used. Verify either: the key is absent, OR it is present as JsonNull.
        // The production concern we're pinning: the seq and rowHash ARE present; null fields
        // are either absent or null — never a wrong non-null value.
        final com.google.gson.JsonElement createdAtEl = r.get( "createdAt" );
        assertTrue( createdAtEl == null || createdAtEl.isJsonNull(),
                "null createdAt should serialize as absent or null, got: " + createdAtEl );
        final com.google.gson.JsonElement prevHashEl = r.get( "prevHash" );
        assertTrue( prevHashEl == null || prevHashEl.isJsonNull(),
                "null prevHash should serialize as absent or null, got: " + prevHashEl );
        final com.google.gson.JsonElement actorPrincipalEl = r.get( "actorPrincipal" );
        assertTrue( actorPrincipalEl == null || actorPrincipalEl.isJsonNull(),
                "null actorPrincipal should serialize as absent or null" );
    }

    // -----------------------------------------------------------------------
    // GET /admin/audit/verify — chain intact + chain broken
    // -----------------------------------------------------------------------

    @Test
    void verifyChainIntactReturnsOkTrueInBody() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/verify" );
        when( auditService.verifyChain( 1L, Long.MAX_VALUE ) ).thenReturn( Optional.empty() );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final JsonObject out = json();
        assertTrue( out.get( "ok" ).getAsBoolean() );
        assertFalse( out.has( "firstBrokenSeq" ), "Intact chain should not include firstBrokenSeq" );
    }

    @Test
    void verifyChainBrokenReturnsOkFalseWithFirstBrokenSeq() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/verify" );
        when( auditService.verifyChain( 1L, Long.MAX_VALUE ) ).thenReturn( Optional.of( 17L ) );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final JsonObject out = json();
        assertFalse( out.get( "ok" ).getAsBoolean() );
        assertEquals( 17L, out.get( "firstBrokenSeq" ).getAsLong() );
    }

    // -----------------------------------------------------------------------
    // GET /admin/audit/export — CSV content, headers, empty case, null fields
    // -----------------------------------------------------------------------

    @Test
    void exportProducesCsvWithHeaderAndDataRows() throws Exception {
        final AuditEntry e = AuditEntry.builder()
                .eventTime( Instant.parse( "2026-06-15T14:30:00Z" ) )
                .category( AuditCategory.CONTENT )
                .eventType( "page.save" )
                .actorType( "user" )
                .actorPrincipal( "carol" )
                .targetId( "HomePage" )
                .outcome( AuditOutcome.SUCCESS )
                .sourceIp( "10.0.0.1" )
                .build();
        final PersistedAuditEntry row = new PersistedAuditEntry( 3L,
                Instant.parse( "2026-06-15T14:30:01Z" ), "prev", "rowhash", e );
        when( auditService.query( AuditQuery.all() ) ).thenReturn( List.of( row ) );
        when( req.getPathInfo() ).thenReturn( "/export" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        verify( resp ).setContentType( "text/csv; charset=UTF-8" );
        verify( resp ).setHeader( "Content-Disposition", "attachment; filename=audit-log.csv" );

        final String csv = body.toString();
        assertTrue( csv.startsWith( "seq,created_at,event_time,category,event_type,actor,outcome,target,source_ip\n" ),
                "CSV must start with the canonical header" );
        assertTrue( csv.contains( "3," ), "CSV data row must include seq=3" );
        assertTrue( csv.contains( "page.save" ), "CSV data row must include event_type" );
        assertTrue( csv.contains( "carol" ), "CSV data row must include actor" );
        assertTrue( csv.contains( "HomePage" ), "CSV data row must include target" );
        assertTrue( csv.contains( "10.0.0.1" ), "CSV data row must include source_ip" );
    }

    @Test
    void exportWithNoEntriesProducesHeaderOnlyAndCallsQueryAll() throws Exception {
        when( auditService.query( AuditQuery.all() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( "/export" );

        resource.doGet( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_OK );
        final String csv = body.toString();
        // Exactly one line (the header) — no trailing data rows
        final String[] lines = csv.split( "\n" );
        assertEquals( 1, lines.length, "Empty export should have header line only" );
        assertEquals( "seq,created_at,event_time,category,event_type,actor,outcome,target,source_ip",
                lines[0] );
    }

    @Test
    void exportNullNullableFieldsRenderedAsEmptyString() throws Exception {
        // targetId and sourceIp are null — nz() should emit ""
        final AuditEntry e = AuditEntry.builder()
                .eventTime( Instant.parse( "2026-06-20T09:00:00Z" ) )
                .category( AuditCategory.AUTHN )
                .eventType( "login" )
                .actorType( "user" )
                .actorPrincipal( "dave" )
                .outcome( AuditOutcome.FAILURE )
                .build();   // targetId=null, sourceIp=null
        when( auditService.query( AuditQuery.all() ) ).thenReturn(
                List.of( new PersistedAuditEntry( 9L,
                        Instant.parse( "2026-06-20T09:00:01Z" ), null, "h9", e ) ) );
        when( req.getPathInfo() ).thenReturn( "/export" );

        resource.doGet( req, resp );

        final String csv = body.toString();
        // The data row should end with ",," — two trailing empty fields (target, source_ip)
        assertTrue( csv.contains( "dave,FAILURE,," ),
                "Null targetId and sourceIp must appear as empty strings in CSV" );
    }

    // -----------------------------------------------------------------------
    // Route dispatch: unknown pathInfo falls through to doList (not /verify or /export)
    // -----------------------------------------------------------------------

    @Test
    void unknownPathInfoFallsThroughToDoList() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        // "/unknown" is not "/verify" or "/export" — falls through to doList
        when( req.getPathInfo() ).thenReturn( "/unknown" );
        stubAllParamsNull();

        resource.doGet( req, resp );

        // doList is called; query is invoked; JSON response is written
        verify( resp ).setContentType( "application/json" );
        verify( auditService ).query( any() );
    }

    // -----------------------------------------------------------------------
    // doList — blank-string params skip parsing and pass null to the query
    // -----------------------------------------------------------------------

    @Test
    void listWithBlankCategorySkipsParseAndPassesNullCategory() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "category" ) ).thenReturn( "   " );  // blank, not null

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertNull( captor.getValue().category(), "Blank category should result in null AuditCategory in query" );
    }

    @Test
    void listWithBlankOutcomeSkipsParseAndPassesNullOutcome() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "outcome" ) ).thenReturn( "  " );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertNull( captor.getValue().outcome(), "Blank outcome should result in null AuditOutcome in query" );
    }

    @Test
    void listWithBlankFromSkipsParseAndPassesNullFrom() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "from" ) ).thenReturn( "" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertNull( captor.getValue().from(), "Blank from should result in null Instant in query" );
    }

    @Test
    void listWithBlankToSkipsParseAndPassesNullTo() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "to" ) ).thenReturn( "" );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertNull( captor.getValue().to(), "Blank to should result in null Instant in query" );
    }

    @Test
    void listWithBlankBeforeSeqSkipsParseAndUsesLongMax() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "beforeSeq" ) ).thenReturn( "  " );

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( Long.MAX_VALUE, captor.getValue().beforeSeq(),
                "Blank beforeSeq should default to Long.MAX_VALUE" );
    }

    @Test
    void listWithBlankLimitUsesDefault100() throws Exception {
        when( auditService.query( any() ) ).thenReturn( List.of() );
        when( req.getPathInfo() ).thenReturn( null );
        stubAllParamsNull();
        when( req.getParameter( "limit" ) ).thenReturn( "" );  // blank

        resource.doGet( req, resp );

        verify( resp ).setContentType( "application/json" );
        final ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass( AuditQuery.class );
        verify( auditService ).query( captor.capture() );
        assertEquals( 100, captor.getValue().limit(), "Blank limit should default to 100" );
    }
}
