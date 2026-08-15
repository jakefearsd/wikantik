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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.pagegraph.spine.ClusterRenameService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 *  Unit tests for {@link AdminClusterResource} — ClusterDeclarationDesign Phase 4.
 *
 *  <p>Runs against a real {@link ClusterRenameService} over mocked collaborators, injected
 *  through a subclass stub, so no engine boot is needed and the HTTP envelope is checked
 *  against the service's real behaviour.</p>
 */
class AdminClusterResourceTest {

    private PageManager pageManager;
    private StructuralIndexService structural;
    private PageSaveHelper saveHelper;
    private AdminClusterResource servlet;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter body;

    private final class Stub extends AdminClusterResource {
        @Override
        protected ClusterRenameService buildRenameService() {
            return new ClusterRenameService( pageManager, structural, saveHelper );
        }

        @Override
        protected String resolveAdminAuthor( final HttpServletRequest request ) {
            return "root";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        pageManager = mock( PageManager.class );
        structural = mock( StructuralIndexService.class );
        saveHelper = mock( PageSaveHelper.class );
        when( structural.getCluster( anyString() ) ).thenReturn( Optional.empty() );
        servlet = new Stub();
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        when( req.getPathInfo() ).thenReturn( "/rename" );
    }

    private static PageDescriptor page( final String slug, final PageType type, final String cluster ) {
        return new PageDescriptor( "01HAA0000000000000000000" + slug.length(), slug, slug, type,
                cluster, List.of(), null, Instant.now(), Optional.empty(), false );
    }

    private void corpus( final PageDescriptor... pages ) {
        when( structural.listPagesByFilter( any( StructuralFilter.class ) ) ).thenReturn( List.of( pages ) );
        for ( final PageDescriptor p : pages ) {
            final Page mockPage = mock( Page.class );
            when( pageManager.getPage( p.slug() ) ).thenReturn( mockPage );
            when( pageManager.getPureText( mockPage ) ).thenReturn(
                    "---\ncanonical_id: " + p.canonicalId() + "\ntype: " + p.type().asFrontmatterValue()
                            + "\ncluster: " + p.cluster() + "\n---\nBody of " + p.slug() + "." );
        }
    }

    private JsonObject post( final String from, final String to, final String confirm ) throws Exception {
        when( req.getParameter( "from" ) ).thenReturn( from );
        when( req.getParameter( "to" ) ).thenReturn( to );
        when( req.getParameter( "confirm" ) ).thenReturn( confirm );
        servlet.doPost( req, resp );
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    /** Without confirm the endpoint previews the blast radius rather than applying it. */
    @Test
    void rename_without_confirm_returns_the_plan_and_writes_nothing() throws Exception {
        corpus( page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );

        final JsonObject json = post( "machine-learning", "ml", null );

        assertFalse( json.get( "applied" ).getAsBoolean() );
        assertEquals( 2, json.get( "pageCount" ).getAsInt() );
        assertEquals( "ml/mlops",
                json.getAsJsonArray( "changes" ).get( 1 ).getAsJsonObject().get( "toCluster" ).getAsString() );
        verifyNoInteractions( saveHelper );
    }

    @Test
    void rename_with_confirm_applies_and_reports_the_renamed_pages() throws Exception {
        corpus( page( "MLHub", PageType.HUB, "machine-learning" ),
                page( "InferenceServing", PageType.ARTICLE, "machine-learning/mlops" ) );

        final JsonObject json = post( "machine-learning", "ml", "true" );

        assertTrue( json.get( "applied" ).getAsBoolean() );
        assertTrue( json.get( "complete" ).getAsBoolean() );
        assertEquals( 2, json.getAsJsonArray( "renamed" ).size() );
    }

    /** A second hub already declaring the target is a conflict, not a merge — 409, no writes. */
    @Test
    void a_conflicting_rename_is_refused_with_409() throws Exception {
        corpus( page( "AmericanCoinageHub", PageType.HUB, "american-coinage" ) );
        final PageDescriptor incumbent = page( "CoinCollectingHub", PageType.HUB, "numismatics" );
        when( structural.getCluster( "numismatics" ) ).thenReturn( Optional.of(
                new ClusterDetails( "numismatics", incumbent, List.of( incumbent ), Map.of(), Instant.now() ) ) );

        final JsonObject json = post( "american-coinage", "numismatics", "true" );

        assertEquals( 409, json.get( "status" ).getAsInt() );
        assertTrue( json.get( "message" ).getAsString().contains( "CoinCollectingHub" ) );
        verifyNoInteractions( saveHelper );
    }

    @Test
    void a_missing_target_is_rejected() throws Exception {
        final JsonObject json = post( "machine-learning", null, "true" );

        assertEquals( 400, json.get( "status" ).getAsInt() );
        verifyNoInteractions( saveHelper );
    }
}
