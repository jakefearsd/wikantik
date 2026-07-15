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
import com.wikantik.api.core.Engine;
import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.kgpolicy.KgInclusionPolicy;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
import com.wikantik.api.kgpolicy.PolicyExplanation;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.kgpolicy.ReconciliationJobRunner;
import com.wikantik.kgpolicy.ReconciliationStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminKgPolicyResourceTest {

    private WikiEngine engine;
    private KgInclusionPolicy policy;
    private StructuralIndexService struct;
    private ReconciliationJobRunner runner;
    private AdminKgPolicyResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter buf;

    @BeforeEach
    void setup() throws Exception {
        engine  = mock( WikiEngine.class );
        policy  = mock( KgInclusionPolicy.class );
        struct  = mock( StructuralIndexService.class );
        runner  = mock( ReconciliationJobRunner.class );
        when( engine.getManager( KgInclusionPolicy.class ) ).thenReturn( policy );
        when( engine.getManager( StructuralIndexService.class ) ).thenReturn( struct );
        when( engine.getManager( ReconciliationJobRunner.class ) ).thenReturn( runner );
        resource = new AdminKgPolicyResource();
        resource.setEngineForTesting( engine );
        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        buf  = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( buf ) );
    }

    private JsonObject body() {
        return JsonParser.parseString( buf.toString() ).getAsJsonObject();
    }

    // --- helpers ---

    private static ClusterSummary summary( final String name, final int count ) {
        return new ClusterSummary( name, null, count, Instant.EPOCH );
    }

    private static PageDescriptor page( final String id ) {
        return new PageDescriptor( id, id, id, PageType.ARTICLE,
                null, List.of(), null, Instant.EPOCH, Optional.empty(), false );
    }

    // -------------------------------------------------------------------------

    @Test
    void list_clusters_joins_policy_with_structural_index() throws Exception {
        // Three clusters in the structural index
        when( struct.listClusters() ).thenReturn( List.of(
                summary( "alpha", 10 ),
                summary( "beta",  5  ),
                summary( "gamma", 3  ) ) );

        // Two have policy rows; gamma does not
        final Instant now = Instant.now();
        when( policy.listClusterPolicies() ).thenReturn( List.of(
                new ClusterPolicy( "alpha", ClusterAction.INCLUDE, "good content", "admin", now, now ),
                new ClusterPolicy( "beta",  ClusterAction.EXCLUDE, "noise",        "admin", now, null ) ) );

        when( req.getPathInfo() ).thenReturn( "/clusters" );
        resource.doGet( req, resp );

        final JsonArray clusters = body().getAsJsonArray( "clusters" );
        assertEquals( 3, clusters.size() );

        // Find each row by name
        JsonObject alphaRow = null, betaRow = null, gammaRow = null;
        for ( int i = 0; i < clusters.size(); i++ ) {
            final JsonObject r = clusters.get( i ).getAsJsonObject();
            switch ( r.get( "cluster" ).getAsString() ) {
                case "alpha" -> alphaRow = r;
                case "beta"  -> betaRow  = r;
                case "gamma" -> gammaRow = r;
            }
        }
        assertNotNull( alphaRow );
        assertNotNull( betaRow );
        assertNotNull( gammaRow );

        assertEquals( "include", alphaRow.get( "action" ).getAsString() );
        assertEquals( 10,        alphaRow.get( "page_count" ).getAsInt() );

        assertEquals( "exclude", betaRow.get( "action" ).getAsString() );
        assertEquals( 5,         betaRow.get( "page_count" ).getAsInt() );

        // gamma has no policy row — action must be JSON null
        assertTrue( gammaRow.get( "action" ).isJsonNull(),
                "gamma action should be null when no policy row exists" );
        assertEquals( 3, gammaRow.get( "page_count" ).getAsInt() );
    }

    @Test
    void cluster_detail_returns_audit_history() throws Exception {
        final Instant setAt = Instant.parse( "2026-01-01T00:00:00Z" );
        when( policy.getClusterPolicy( "alpha" ) ).thenReturn( Optional.of(
                new ClusterPolicy( "alpha", ClusterAction.INCLUDE, "good", "admin", setAt, null ) ) );

        final Instant t1 = Instant.parse( "2026-01-01T00:00:00Z" );
        final Instant t2 = Instant.parse( "2026-01-02T00:00:00Z" );
        when( policy.listAudit( Optional.of( "alpha" ), 50 ) ).thenReturn( List.of(
                new PolicyAuditEntry( 1L, "alpha", null,      "include", "initial",  "admin", t1 ),
                new PolicyAuditEntry( 2L, "alpha", "include", "exclude", "rethought", "alice", t2 ) ) );

        when( req.getPathInfo() ).thenReturn( "/clusters/alpha" );
        resource.doGet( req, resp );

        final JsonObject b = body();
        assertEquals( "alpha",   b.get( "cluster" ).getAsString() );
        assertEquals( "include", b.get( "action" ).getAsString() );

        final JsonArray audit = b.getAsJsonArray( "audit" );
        assertEquals( 2, audit.size() );

        final JsonObject first = audit.get( 0 ).getAsJsonObject();
        assertEquals( 1,         first.get( "id" ).getAsLong() );
        assertEquals( "alpha",   first.get( "cluster" ).getAsString() );
        assertTrue( first.get( "old_action" ).isJsonNull() );
        assertEquals( "include", first.get( "new_action" ).getAsString() );

        final JsonObject second = audit.get( 1 ).getAsJsonObject();
        assertEquals( "include", second.get( "old_action" ).getAsString() );
        assertEquals( "exclude", second.get( "new_action" ).getAsString() );
        assertEquals( "alice",   second.get( "actor" ).getAsString() );
    }

    @Test
    void explain_returns_404_for_unknown_page() throws Exception {
        when( policy.explain( "no-such-page" ) )
                .thenThrow( new IllegalArgumentException( "page not found: no-such-page" ) );

        when( req.getPathInfo() ).thenReturn( "/explain/no-such-page" );
        resource.doGet( req, resp );

        verify( resp ).setStatus( 404 );
        final JsonObject b = body();
        assertTrue( b.has( "error" ) );
        assertTrue( b.get( "error" ).getAsString().contains( "not found" ) );
    }

    @Test
    void pending_lists_unset_clusters_and_stale_reviews() throws Exception {
        final Instant freshReview = Instant.now().minus( Duration.ofDays( 10 ) );
        final Instant staleReview = Instant.now().minus( Duration.ofDays( 100 ) );

        when( struct.listClusters() ).thenReturn( List.of(
                summary( "unset-cluster",  7 ),
                summary( "fresh-cluster",  4 ),
                summary( "stale-cluster",  2 ) ) );

        when( policy.listClusterPolicies() ).thenReturn( List.of(
                // unset-cluster has no row
                new ClusterPolicy( "fresh-cluster", ClusterAction.INCLUDE, "ok", "admin",
                        freshReview, freshReview ),
                new ClusterPolicy( "stale-cluster", ClusterAction.EXCLUDE, "old", "admin",
                        staleReview, staleReview ) ) );

        when( req.getPathInfo() ).thenReturn( "/pending" );
        resource.doGet( req, resp );

        final JsonObject b = body();

        final JsonArray unset = b.getAsJsonArray( "unset_clusters" );
        assertEquals( 1, unset.size() );
        assertEquals( "unset-cluster", unset.get( 0 ).getAsJsonObject().get( "cluster" ).getAsString() );

        final JsonArray stale = b.getAsJsonArray( "stale_reviews" );
        assertEquals( 1, stale.size() );
        assertEquals( "stale-cluster", stale.get( 0 ).getAsJsonObject().get( "cluster" ).getAsString() );
        assertEquals( "exclude", stale.get( 0 ).getAsJsonObject().get( "action" ).getAsString() );

        // fresh-cluster should not appear in either list
        final JsonArray recent = b.getAsJsonArray( "recent_count_changes" );
        assertEquals( 0, recent.size() );
    }

    @Test
    void audit_endpoint_supports_cluster_filter_and_limit() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/audit" );
        when( req.getParameter( "cluster" ) ).thenReturn( "alpha" );
        when( req.getParameter( "limit" ) ).thenReturn( "25" );

        final Instant t = Instant.parse( "2026-03-01T12:00:00Z" );
        when( policy.listAudit( Optional.of( "alpha" ), 25 ) ).thenReturn( List.of(
                new PolicyAuditEntry( 5L, "alpha", "exclude", "include", "approved", "admin", t ) ) );

        resource.doGet( req, resp );

        // Verify the correct parameters were forwarded
        verify( policy ).listAudit( Optional.of( "alpha" ), 25 );

        final JsonObject b = body();
        final JsonArray audit = b.getAsJsonArray( "audit" );
        assertEquals( 1, audit.size() );
        assertEquals( 5L,       audit.get( 0 ).getAsJsonObject().get( "id" ).getAsLong() );
        assertEquals( "alpha",  audit.get( 0 ).getAsJsonObject().get( "cluster" ).getAsString() );
        assertEquals( "admin",  audit.get( 0 ).getAsJsonObject().get( "actor" ).getAsString() );
    }

    @Test
    void reconciliation_endpoint_returns_active_jobs() throws Exception {
        final Instant startedAt  = Instant.parse( "2026-04-01T08:00:00Z" );
        final Instant finishedAt = Instant.parse( "2026-04-01T08:01:00Z" );

        when( runner.allStatuses() ).thenReturn( Map.of(
                "alpha", new ReconciliationStatus(
                        "alpha", ReconciliationStatus.State.DONE,
                        50, 50, 0, startedAt, finishedAt, null ),
                "beta",  new ReconciliationStatus(
                        "beta", ReconciliationStatus.State.RUNNING,
                        20, 8, 0, startedAt, null, null ) ) );

        when( req.getPathInfo() ).thenReturn( "/reconciliation" );
        resource.doGet( req, resp );

        final JsonArray rows = body().getAsJsonArray( "reconciliation" );
        assertEquals( 2, rows.size() );

        // Find alpha and beta rows
        JsonObject alphaRow = null, betaRow = null;
        for ( int i = 0; i < rows.size(); i++ ) {
            final JsonObject r = rows.get( i ).getAsJsonObject();
            if ( "alpha".equals( r.get( "cluster" ).getAsString() ) ) alphaRow = r;
            if ( "beta".equals(  r.get( "cluster" ).getAsString() ) ) betaRow  = r;
        }
        assertNotNull( alphaRow );
        assertNotNull( betaRow );

        assertEquals( "DONE",    alphaRow.get( "state" ).getAsString() );
        assertEquals( 50,        alphaRow.get( "total_pages" ).getAsInt() );
        assertEquals( 50,        alphaRow.get( "processed" ).getAsInt() );
        assertTrue( alphaRow.get( "error_message" ).isJsonNull() );

        assertEquals( "RUNNING", betaRow.get( "state" ).getAsString() );
        assertEquals( 20,        betaRow.get( "total_pages" ).getAsInt() );
        assertEquals( 8,         betaRow.get( "processed" ).getAsInt() );
    }

    @Test
    void estimate_returns_400_without_required_params() throws Exception {
        // Neither cluster nor action provided
        when( req.getPathInfo() ).thenReturn( "/estimate" );
        when( req.getParameter( "cluster" ) ).thenReturn( null );
        when( req.getParameter( "action" )  ).thenReturn( null );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
        final JsonObject b = body();
        assertTrue( b.has( "error" ) );
        assertTrue( b.get( "error" ).getAsString().contains( "required" ) );
    }

    // -------------------------------------------------------------------------
    // Write endpoint tests (Task 17)
    // -------------------------------------------------------------------------

    @Test
    void put_cluster_sets_policy_with_actor_from_request() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/clusters/java" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( req.getReader() ).thenReturn(
                new BufferedReader( new StringReader( "{\"action\":\"include\",\"reason\":\"x\"}" ) ) );

        resource.doPut( req, resp );

        verify( policy ).setClusterPolicy( "java", ClusterAction.INCLUDE, "x", "admin" );
        final JsonObject b = body();
        assertEquals( "java",    b.get( "cluster" ).getAsString() );
        assertEquals( "include", b.get( "action" ).getAsString() );
        assertEquals( "x",       b.get( "reason" ).getAsString() );
        assertEquals( "admin",   b.get( "actor" ).getAsString() );
    }

    @Test
    void put_cluster_returns_400_on_invalid_action() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/clusters/java" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( req.getReader() ).thenReturn(
                new BufferedReader( new StringReader( "{\"action\":\"maybe\"}" ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body().has( "error" ) );
    }

    @Test
    void put_cluster_uses_unknown_actor_when_principal_missing() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/clusters/java" );
        when( req.getRemoteUser() ).thenReturn( null );
        when( req.getReader() ).thenReturn(
                new BufferedReader( new StringReader( "{\"action\":\"exclude\"}" ) ) );

        resource.doPut( req, resp );

        verify( policy ).setClusterPolicy( eq( "java" ), eq( ClusterAction.EXCLUDE ), any(), eq( "unknown" ) );
        assertEquals( "unknown", body().get( "actor" ).getAsString() );
    }

    @Test
    void delete_cluster_clears_policy() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/clusters/java" );
        when( req.getRemoteUser() ).thenReturn( "admin" );

        resource.doDelete( req, resp );

        verify( policy ).clearClusterPolicy( "java", "admin" );
        final JsonObject b = body();
        assertEquals( "java",  b.get( "cluster" ).getAsString() );
        assertTrue( b.get( "cleared" ).getAsBoolean() );
        assertEquals( "admin", b.get( "actor" ).getAsString() );
    }

    @Test
    void post_review_marks_reviewed() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/clusters/java/review" );
        when( req.getRemoteUser() ).thenReturn( "admin" );

        resource.doPost( req, resp );

        verify( policy ).markReviewed( "java", "admin" );
        final JsonObject b = body();
        assertEquals( "java",  b.get( "cluster" ).getAsString() );
        assertTrue( b.get( "reviewed" ).getAsBoolean() );
        assertEquals( "admin", b.get( "actor" ).getAsString() );
    }

    @Test
    void post_bootstrap_applies_include_and_exclude_lists() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bootstrap" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( req.getReader() ).thenReturn(
                new BufferedReader( new StringReader(
                        "{\"include\":[\"a\",\"b\"],\"exclude\":[\"c\"],\"reason\":\"boot\"}" ) ) );

        resource.doPost( req, resp );

        verify( policy ).bootstrap( List.of( "a", "b" ), List.of( "c" ), "boot", "admin" );
        final JsonObject b = body();
        assertTrue( b.get( "applied" ).getAsBoolean() );
        assertEquals( 2, b.get( "included" ).getAsInt() );
        assertEquals( 1, b.get( "excluded" ).getAsInt() );
    }

    @Test
    void post_bootstrap_returns_409_when_table_non_empty() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/bootstrap" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        when( req.getReader() ).thenReturn(
                new BufferedReader( new StringReader(
                        "{\"include\":[\"a\"],\"exclude\":[],\"reason\":\"boot\"}" ) ) );
        doThrow( new IllegalStateException( "bootstrap table is non-empty" ) )
                .when( policy ).bootstrap( any(), any(), any(), any() );

        resource.doPost( req, resp );

        verify( resp ).setStatus( 409 );
        assertTrue( body().has( "error" ) );
    }

    @Test
    void put_cluster_returns_400_on_missing_body() throws Exception {
        when( req.getPathInfo() ).thenReturn( "/clusters/java" );
        when( req.getRemoteUser() ).thenReturn( "admin" );
        // Provide a non-JSON array as body instead of an object
        when( req.getReader() ).thenReturn(
                new BufferedReader( new StringReader( "[1,2,3]" ) ) );

        resource.doPut( req, resp );

        verify( resp ).setStatus( 400 );
        assertTrue( body().has( "error" ) );
    }
}
