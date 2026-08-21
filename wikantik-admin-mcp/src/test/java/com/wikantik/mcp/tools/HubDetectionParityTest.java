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
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.test.StubPageManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins "is this page a hub?" to one answer.
 *
 * <p>{@link PageType#fromFrontmatter} is the canonical resolver — it trims and lowercases,
 * so {@code Hub}, {@code HUB} and {@code " hub "} are all hubs. The page renderer agrees
 * (via {@code PageSeoModel}, which is case-insensitive). Several other places re-derived
 * the same fact with a bare {@code "hub".equals(...)}, which does not.
 *
 * <p>That split is reachable: a non-canonical enum value is only an advisory WARNING at save
 * time (see {@code SchemaValidationPageFilter}'s {@code nonCanonical.severity}, default
 * {@code warning}), so {@code type: Hub} saves happily. The live page then renders as
 * {@code CollectionPage} with {@code hasPart}, while {@code preview_structured_data} — whose
 * entire job is to show what the live page will render — previewed {@code Article} plus a
 * fabricated {@code isPartOf}. A curator checking a hub's SEO before publishing was shown
 * structured data the site never emits.
 */
class HubDetectionParityTest {

    private StubPageManager pm;
    private PreviewStructuredDataTool preview;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        pm = new StubPageManager();
        preview = new PreviewStructuredDataTool( pm, "TestWiki", "http://localhost:8080" );
    }

    @SuppressWarnings( "unchecked" )
    private Map< String, Object > previewOf( final String slug ) {
        final Map< String, Object > args = new HashMap<>();
        args.put( "slug", slug );
        final McpSchema.CallToolResult r = preview.execute( args );
        return gson.fromJson( ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text(), Map.class );
    }

    /** The authority every site must match. */
    @ParameterizedTest
    @ValueSource( strings = { "hub", "Hub", "HUB", "hUb" } )
    void canonicalResolverTreatsEveryCasingAsAHub( final String written ) {
        assertEquals( PageType.HUB, PageType.fromFrontmatter( written ),
            "Baseline: the canonical resolver lowercases, so '" + written + "' is a hub." );
    }

    /**
     * The defect. {@code preview_structured_data} must classify the page the same way the
     * live render will, whatever casing the author used.
     */
    @ParameterizedTest
    @ValueSource( strings = { "hub", "Hub", "HUB" } )
    @SuppressWarnings( "unchecked" )
    void previewClassifiesAHubTheSameWayTheRendererWill( final String written ) {
        final String slug = "HubCase" + written;
        pm.savePage( slug, "---\ntype: " + written + "\ncluster: ai\nrelated:\n- Member\n"
                + "summary: A hub page collecting the cluster's members for readers\n---\nBody." );

        final Map< String, Object > data = previewOf( slug );
        final Map< String, Object > jsonLd = ( Map< String, Object > ) data.get( "jsonLd" );

        assertEquals( "CollectionPage", jsonLd.get( "@type" ),
            "type: '" + written + "' is a hub, so the preview must show CollectionPage — "
          + "the live page will. Got: " + jsonLd.get( "@type" ) );
        assertTrue( jsonLd.containsKey( "hasPart" ),
            "A hub's JSON-LD carries hasPart, not isPartOf: " + jsonLd.keySet() );
    }

    /**
     * The same fact drives {@code HubRelatedCheck}, which only audits pages it considers hubs.
     * A case-sensitive check silently skips the audit for {@code type: Hub}.
     */
    @ParameterizedTest
    @ValueSource( strings = { "hub", "Hub", "HUB" } )
    void hubRelatedCheckAuditsEveryCasingOfHub( final String written ) {
        final Map< String, Object > metadata = new HashMap<>();
        metadata.put( "type", written );          // a hub...
        // ...with no `related`, which is what HubRelatedCheck exists to flag.

        final var results = new PageChecks.HubRelatedCheck( false )
            .check( new PageCheckContext( "P", metadata, "", null, null ) );

        assertEquals( 1, results.size(),
            "A hub written as '" + written + "' with no related pages must still be flagged; "
          + "otherwise the audit silently skips hubs whose type is not lowercase." );
        assertEquals( "hub_empty_related", results.get( 0 ).issue() );
    }

    /** Non-hub types must stay non-hubs — the fix must not widen what counts as a hub. */
    @ParameterizedTest
    @ValueSource( strings = { "article", "Article", "runbook", "hubbub", "sub-hub" } )
    @SuppressWarnings( "unchecked" )
    void nonHubTypesAreNotTreatedAsHubs( final String written ) {
        final String slug = "NonHub" + written.replace( "-", "" );
        pm.savePage( slug, "---\ntype: " + written + "\ncluster: ai\n"
                + "summary: An ordinary page that is not a hub of any kind whatsoever\n---\nBody." );

        final Map< String, Object > jsonLd =
            ( Map< String, Object > ) previewOf( slug ).get( "jsonLd" );

        assertTrue( !"CollectionPage".equals( jsonLd.get( "@type" ) ),
            "'" + written + "' is not a hub and must not preview as CollectionPage." );
    }
}
