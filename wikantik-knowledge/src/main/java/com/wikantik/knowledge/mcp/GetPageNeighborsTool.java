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
package com.wikantik.knowledge.mcp;

import com.google.gson.Gson;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.mcp.AbstractMcpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * MCP tool — the 1-hop Page Graph neighborhood of a page: the pages it wikilinks to, the pages that
 * wikilink to it, and its cluster siblings with each cluster's hub, as light descriptors so an agent
 * can choose what to read next without fetching page bodies. Every neighbor passes the guest view
 * gate, and a page the gate hides answers exactly like a missing one.
 */
public class GetPageNeighborsTool extends AbstractMcpTool {

    public static final String TOOL_NAME = "get_page_neighbors";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final StructuralIndexService index;
    private final Supplier< ReferenceManager > references;  // yields null until the async page scan has built it
    private final PageViewGate viewGate;

    public GetPageNeighborsTool( final StructuralIndexService index, final Supplier< ReferenceManager > references,
                                 final PageViewGate viewGate ) {
        this.index = index;
        this.references = references == null ? () -> null : references;
        this.viewGate = viewGate == null ? PageViewGate.ALLOW_ALL : viewGate;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "slug", Map.of(
                "type", "string",
                "description", "Name of the page whose neighbors to list.",
                "examples", List.of( "HybridRetrieval" )
        ) );
        props.put( "limit", Map.of(
                "type", "integer",
                "description", "Max neighbors per list (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + "); "
                        + "the *Total counts always cover every neighbor.",
                "examples", List.of( DEFAULT_LIMIT )
        ) );

        final Map< String, Object > neighbor = Map.of(
                "slug", "ChunkingStrategy", "title", "Chunking Strategy", "type", "article",
                "summary", "How pages are split into retrievable chunks." );
        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "page", "HybridRetrieval",
                "outbound", List.of( neighbor ), "outboundTotal", 1,
                "inbound", List.of(), "inboundTotal", 0,
                "clusters", List.of( Map.of(
                        "name", "wikantik-development",
                        "hub", Map.of( "slug", "WikantikDevelopmentHub", "title", "Wikantik Development",
                                "type", "hub", "summary", "Entry point for Wikantik's design and operations pages." ),
                        "siblings", List.of( neighbor ), "siblingsTotal", 1 ) )
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "The 1-hop Page Graph neighborhood of a page, without page bodies: pages it wikilinks "
                        + "to (outbound), pages that wikilink to it (inbound), and its cluster siblings (sub-cluster "
                        + "members included) with each cluster's hub. Each neighbor is {slug, title, type, summary}; lists are alphabetical and "
                        + "capped by 'limit', with *Total counts. Use it to choose which page to read next. Page "
                        + "Graph only — for Knowledge Graph entities use traverse." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of( "slug" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( READ_ONLY_ANNOTATIONS )
                .build();
    }

    @Override
    protected Gson gson() {
        return KnowledgeMcpUtils.GSON;
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        final String slug = McpToolUtils.pageSlug( arguments );
        if ( slug == null || slug.isBlank() ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "slug argument is required" );
        }
        final Optional< PageDescriptor > self = describe( slug );
        if ( self.isEmpty() ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON, "no page named " + slug );
        }
        final ReferenceManager refs = references.get();
        if ( refs == null ) {
            return McpToolUtils.errorResult( KnowledgeMcpUtils.GSON,
                    "the Page Graph link index is not ready yet (startup page scan in progress); retry shortly" );
        }
        final int limit = arguments.get( "limit" ) instanceof Number n
                ? Math.max( 1, Math.min( MAX_LIMIT, n.intValue() ) ) : DEFAULT_LIMIT;

        final Map< String, Object > out = new LinkedHashMap<>();
        final String page = self.get().slug();
        out.put( "page", page );
        putCapped( out, "outbound", linked( refs.findRefersTo( page ), page ), limit );
        putCapped( out, "inbound", linked( refs.findReferrers( page ), page ), limit );
        final List< Map< String, Object > > clusters = new ArrayList<>();
        for ( final String name : self.get().clusters() ) {
            index.getCluster( name ).ifPresent( c -> clusters.add( cluster( c, page, limit ) ) );
        }
        out.put( "clusters", clusters );
        return McpToolUtils.jsonResult( KnowledgeMcpUtils.GSON, out );
    }

    /** The page's descriptor, when it exists and the caller may view it. */
    private Optional< PageDescriptor > describe( final String slug ) {
        return index.resolveCanonicalIdFromSlug( slug )
                .flatMap( index::getByCanonicalId )
                .filter( d -> viewGate.canView( d.slug() ) );
    }

    /** Existing, viewable pages among the link endpoints, minus the page itself; links to uncreated pages drop out. */
    private List< PageDescriptor > linked( final Collection< String > names, final String self ) {
        return names.stream()
                .filter( n -> !n.equals( self ) )
                .distinct()
                .map( this::describe )
                .flatMap( Optional::stream )
                .sorted( Comparator.comparing( PageDescriptor::slug ) )
                .toList();
    }

    private Map< String, Object > cluster( final ClusterDetails c, final String self, final int limit ) {
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "name", c.name() );
        final PageDescriptor hub = c.hubPage();
        final String hubSlug = hub == null ? null : hub.slug();
        if ( hub != null && !hubSlug.equals( self ) && viewGate.canView( hubSlug ) ) {
            out.put( "hub", light( hub ) );
        }
        // articles lists every member, the hub and sub-cluster members included; the hub is reported above.
        putCapped( out, "siblings", c.articles().stream()
                .filter( d -> !d.slug().equals( self ) && !d.slug().equals( hubSlug ) && viewGate.canView( d.slug() ) )
                .sorted( Comparator.comparing( PageDescriptor::slug ) )
                .toList(), limit );
        return out;
    }

    private static void putCapped( final Map< String, Object > out, final String key,
                                   final List< PageDescriptor > pages, final int limit ) {
        out.put( key, pages.stream().limit( limit ).map( GetPageNeighborsTool::light ).toList() );
        out.put( key + "Total", pages.size() );
    }

    private static Map< String, Object > light( final PageDescriptor d ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "slug", d.slug() );
        m.put( "title", d.title() );
        m.put( "type", d.type() == null ? null : d.type().asFrontmatterValue() );
        m.put( "summary", d.summary() );
        return m;
    }
}
