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
import com.wikantik.api.core.Engine;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * {@code /api/structure/*} — machine-queryable wiki structure for agents.
 * Mirrors the {@link StructuralIndexService} surface over REST.
 */
public class StructureResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( StructureResource.class );

    void setEngineForTesting( final Engine engine ) { setEngine( engine ); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        final StructuralIndexService svc = getSubsystems().pageGraph().structuralIndexService();
        if ( svc == null ) {
            writeError( resp, 503, "structural index unavailable" );
            return;
        }

        try {
            if ( "/clusters".equals( pathInfo ) || "/clusters/".equals( pathInfo ) ) {
                writeClusters( resp, svc, req );
            } else if ( pathInfo.startsWith( "/clusters/" ) ) {
                writeCluster( resp, svc, pathInfo.substring( "/clusters/".length() ), req );
            } else if ( "/tags".equals( pathInfo ) ) {
                final int min = parseIntOr( req.getParameter( "min_pages" ), 1 );
                writeTags( resp, svc, min, req );
            } else if ( "/pages".equals( pathInfo ) ) {
                writePages( resp, svc, req );
            } else if ( "/sitemap".equals( pathInfo ) ) {
                writeSitemap( resp, svc, req );
            } else {
                writeError( resp, 404, "unknown structure path: " + pathInfo );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "/api/structure{} failed: {}", pathInfo, e.getMessage(), e );
            writeError( resp, 500, e.getMessage() );
        }
    }

    /** The members of one cluster the caller may view; {@code hub} is null when it is hidden or absent. */
    private record VisibleCluster( PageDescriptor hub, List< PageDescriptor > articles ) {
        boolean empty() {
            return hub == null && articles.isEmpty();
        }

        /** Freshness of the newest viewable member, never of a hidden one. */
        Instant updatedAt() {
            return java.util.stream.Stream.concat( java.util.stream.Stream.ofNullable( hub ), articles.stream() )
                    .map( PageDescriptor::updated ).filter( java.util.Objects::nonNull )
                    .max( java.util.Comparator.naturalOrder() ).orElse( null );
        }
    }

    private VisibleCluster visibleMembers( final HttpServletRequest req, final ClusterDetails details ) {
        final PageDescriptor hub = details.hubPage() != null && !viewableOnly( req, List.of( details.hubPage() ) ).isEmpty()
                ? details.hubPage() : null;
        return new VisibleCluster( hub, viewableOnly( req, details.articles() ) );
    }

    private void writeClusters( final HttpServletResponse resp, final StructuralIndexService svc,
                                final HttpServletRequest req ) throws IOException {
        final JsonArray arr = new JsonArray();
        for ( final ClusterSummary c : svc.listClusters() ) {
            // Count and freshness are over the members the caller may view, and a cluster with none is not
            // listed: its size and last-edit time would say something about content that is hidden.
            final Optional< ClusterDetails > details = svc.getCluster( c.name() );
            if ( details.isEmpty() ) {
                continue;
            }
            final VisibleCluster visible = visibleMembers( req, details.get() );
            if ( visible.empty() ) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty( "name", c.name() );
            if ( visible.hub() != null ) {
                o.add( "hub_page", describe( visible.hub() ) );
            }
            o.addProperty( "article_count", visible.articles().size() );
            final Instant updated = visible.updatedAt();
            if ( updated != null ) o.addProperty( "updated_at", updated.toString() );
            arr.add( o );
        }
        final JsonObject data = new JsonObject();
        data.add( "clusters", arr );
        data.addProperty( "generated_at", Instant.now().toString() );
        writeEnvelope( resp, data );
    }

    private void writeCluster( final HttpServletResponse resp, final StructuralIndexService svc,
                                final String name, final HttpServletRequest req ) throws IOException {
        final Optional< ClusterDetails > d = svc.getCluster( name );
        final VisibleCluster visible = d.isEmpty() ? null : visibleMembers( req, d.get() );
        if ( visible == null || visible.empty() ) {
            // A cluster none of whose members the caller may view answers as one that does not exist.
            writeError( resp, 404, "cluster not found: " + name );
            return;
        }
        final ClusterDetails details = d.get();
        final JsonObject data = new JsonObject();
        data.addProperty( "name", details.name() );
        if ( visible.hub() != null ) {
            data.add( "hub_page", describe( visible.hub() ) );
        }
        final JsonArray articles = new JsonArray();
        visible.articles().forEach( p -> articles.add( describe( p ) ) );
        data.add( "articles", articles );
        final JsonObject tags = new JsonObject();
        // Counted over the pages the caller can view, never the cluster's full membership.
        final List< PageDescriptor > members = new java.util.ArrayList<>( visible.articles() );
        if ( visible.hub() != null ) {
            members.add( visible.hub() );
        }
        com.wikantik.api.pagegraph.TagAggregation.distribution( members ).forEach( tags::addProperty );
        data.add( "tag_distribution", tags );
        writeEnvelope( resp, data );
    }

    private void writeTags( final HttpServletResponse resp, final StructuralIndexService svc, final int min,
                            final HttpServletRequest req ) throws IOException {
        final JsonArray arr = new JsonArray();
        // Counts cover only pages the caller can view: frontmatter and inline tags of a restricted page must not leak.
        final java.util.Set< String > ok = filterViewable( req, svc.sitemap().pages().stream().map( PageDescriptor::slug ).toList() );
        for ( final TagSummary t : svc.listTags( min, d -> ok.contains( d.slug() ) ) ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "tag", t.tag() );
            o.addProperty( "count", t.count() );
            final JsonArray pages = new JsonArray();
            t.topPageIds().forEach( pages::add );
            o.add( "top_pages", pages );
            arr.add( o );
        }
        final JsonObject data = new JsonObject();
        data.add( "tags", arr );
        writeEnvelope( resp, data );
    }

    private void writePages( final HttpServletResponse resp, final StructuralIndexService svc,
                              final HttpServletRequest req ) throws IOException {
        final int limit = Math.max( 1, Math.min( 1000, parseIntOr( req.getParameter( "limit" ), 100 ) ) );
        final String cursor = req.getParameter( "cursor" );
        final StructuralFilter filter = new StructuralFilter(
                Optional.ofNullable( req.getParameter( "type" ) ).map( PageType::fromFrontmatter ),
                Optional.ofNullable( req.getParameter( "cluster" ) ),
                Arrays.asList( Optional.ofNullable( req.getParameter( "tag" ) )
                        .map( t -> t.split( "," ) ).orElse( new String[ 0 ] ) ),
                Optional.ofNullable( req.getParameter( "updated_since" ) ).map( Instant::parse ),
                1000,
                Optional.empty()
        );
        // Every match the caller may view, in canonical-id order; `total` counts all of them, `cursor` pages through them.
        final List< PageDescriptor > viewable = viewableOnly( req, allMatching( svc, filter ) );
        final List< PageDescriptor > rest = cursor == null || cursor.isBlank() ? viewable
                : viewable.stream().filter( p -> p.canonicalId().compareTo( cursor ) > 0 ).toList();
        final List< PageDescriptor > pages = rest.size() > limit ? rest.subList( 0, limit ) : rest;
        final JsonArray arr = new JsonArray();
        pages.forEach( p -> arr.add( describe( p ) ) );
        final JsonObject data = new JsonObject();
        data.add( "pages", arr );
        data.addProperty( "count", pages.size() );
        data.addProperty( "total", viewable.size() );
        if ( rest.size() > pages.size() ) {
            data.addProperty( "next_cursor", pages.get( pages.size() - 1 ).canonicalId() );
        }
        writeEnvelope( resp, data );
    }

    /** All pages matching {@code filter}, walking the service's cursor so the 1000-per-call clamp never truncates. */
    private static List< PageDescriptor > allMatching( final StructuralIndexService svc, final StructuralFilter filter ) {
        final List< PageDescriptor > out = new java.util.ArrayList<>();
        StructuralFilter next = filter;
        while ( true ) {
            final List< PageDescriptor > batch = svc.listPagesByFilter( next );
            out.addAll( batch );
            if ( batch.size() < filter.limit() ) {
                return out;
            }
            next = new StructuralFilter( filter.type(), filter.cluster(), filter.tags(), filter.updatedSince(),
                    filter.limit(), Optional.of( batch.get( batch.size() - 1 ).canonicalId() ) );
        }
    }

    private void writeSitemap( final HttpServletResponse resp, final StructuralIndexService svc,
                               final HttpServletRequest req ) throws IOException {
        final Sitemap s = svc.sitemap();
        final List< PageDescriptor > pages = viewableOnly( req, s.pages() );
        final JsonArray arr = new JsonArray();
        pages.forEach( p -> arr.add( describe( p ) ) );
        final JsonObject data = new JsonObject();
        data.add( "pages", arr );
        data.addProperty( "count", pages.size() );
        data.addProperty( "generated_at", s.generatedAt().toString() );
        writeEnvelope( resp, data );
    }

    /**
     * Drops descriptors the caller may not {@code view}. This endpoint is public and
     * unauthenticated, and a descriptor carries the page's title, cluster, tags and
     * one-line summary — so an ACL-restricted page must not appear in any listing.
     * Silent (UI-visibility) filter, matching WikiPageFormatFilter's posture.
     */
    private List< PageDescriptor > viewableOnly( final HttpServletRequest req, final List< PageDescriptor > pages ) {
        if ( pages == null || pages.isEmpty() ) {
            return pages == null ? List.of() : pages;
        }
        final java.util.Set< String > ok = filterViewable( req, pages.stream().map( PageDescriptor::slug ).toList() );
        return pages.stream().filter( p -> ok.contains( p.slug() ) ).toList();
    }

    private static JsonObject describe( final PageDescriptor p ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "id",      p.canonicalId() );
        o.addProperty( "slug",    p.slug() );
        o.addProperty( "title",   p.title() );
        o.addProperty( "type",    p.type().asFrontmatterValue() );
        if ( p.cluster() != null ) o.addProperty( "cluster", p.cluster() );
        if ( p.summary() != null ) o.addProperty( "summary", p.summary() );
        if ( p.updated() != null ) o.addProperty( "updated", p.updated().toString() );
        final JsonArray tags = new JsonArray();
        p.tags().forEach( tags::add );
        o.add( "tags", tags );
        return o;
    }

    private void writeEnvelope( final HttpServletResponse resp, final JsonObject data ) throws IOException {
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        final JsonObject envelope = new JsonObject();
        envelope.add( "data", data );
        resp.getWriter().write( GSON.toJson( envelope ) );
    }

    private void writeError( final HttpServletResponse resp, final int status, final String message )
            throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json; charset=UTF-8" );
        final JsonObject err = new JsonObject();
        err.addProperty( "error", message );
        resp.getWriter().write( GSON.toJson( err ) );
    }

    private static int parseIntOr( final String raw, final int fallback ) {
        if ( raw == null || raw.isBlank() ) return fallback;
        try { return Integer.parseInt( raw ); } catch ( final NumberFormatException e ) {
            // Client-supplied parameter; falling back to the default is the documented behaviour.
            LOG.debug( "'{}' is not an integer; using fallback {}: {}", raw, fallback, e.getMessage() );
            return fallback;
        }
    }
}
