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
import com.wikantik.api.structure.ClusterDetails;
import com.wikantik.api.structure.ClusterSummary;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.Sitemap;
import com.wikantik.api.structure.StructuralFilter;
import com.wikantik.api.structure.StructuralIndexService;
import com.wikantik.api.structure.TagSummary;
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

    private Engine engineOverride;

    void setEngineForTesting( final Engine engine ) {
        this.engineOverride = engine;
    }

    private Engine engine() {
        return engineOverride != null ? engineOverride : getEngine();
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        final StructuralIndexService svc = engine().getManager( StructuralIndexService.class );
        if ( svc == null ) {
            writeError( resp, 503, "structural index unavailable" );
            return;
        }

        try {
            if ( pathInfo.equals( "/clusters" ) || pathInfo.equals( "/clusters/" ) ) {
                writeClusters( resp, svc );
            } else if ( pathInfo.startsWith( "/clusters/" ) ) {
                writeCluster( resp, svc, pathInfo.substring( "/clusters/".length() ) );
            } else if ( pathInfo.equals( "/tags" ) ) {
                final int min = parseIntOr( req.getParameter( "min_pages" ), 1 );
                writeTags( resp, svc, min );
            } else if ( pathInfo.equals( "/pages" ) ) {
                writePages( resp, svc, req );
            } else if ( pathInfo.equals( "/sitemap" ) ) {
                writeSitemap( resp, svc );
            } else {
                writeError( resp, 404, "unknown structure path: " + pathInfo );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "/api/structure{} failed: {}", pathInfo, e.getMessage(), e );
            writeError( resp, 500, e.getMessage() );
        }
    }

    private void writeClusters( final HttpServletResponse resp, final StructuralIndexService svc )
            throws IOException {
        final JsonArray arr = new JsonArray();
        for ( final ClusterSummary c : svc.listClusters() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "name", c.name() );
            if ( c.hubPage() != null ) o.add( "hub_page", describe( c.hubPage() ) );
            o.addProperty( "article_count", c.articleCount() );
            if ( c.updatedAt() != null ) o.addProperty( "updated_at", c.updatedAt().toString() );
            arr.add( o );
        }
        final JsonObject data = new JsonObject();
        data.add( "clusters", arr );
        data.addProperty( "generated_at", Instant.now().toString() );
        writeEnvelope( resp, data );
    }

    private void writeCluster( final HttpServletResponse resp, final StructuralIndexService svc,
                                final String name ) throws IOException {
        final Optional< ClusterDetails > d = svc.getCluster( name );
        if ( d.isEmpty() ) {
            writeError( resp, 404, "cluster not found: " + name );
            return;
        }
        final ClusterDetails details = d.get();
        final JsonObject data = new JsonObject();
        data.addProperty( "name", details.name() );
        if ( details.hubPage() != null ) data.add( "hub_page", describe( details.hubPage() ) );
        final JsonArray articles = new JsonArray();
        details.articles().forEach( p -> articles.add( describe( p ) ) );
        data.add( "articles", articles );
        final JsonObject tags = new JsonObject();
        details.tagDistribution().forEach( tags::addProperty );
        data.add( "tag_distribution", tags );
        writeEnvelope( resp, data );
    }

    private void writeTags( final HttpServletResponse resp, final StructuralIndexService svc, final int min )
            throws IOException {
        final JsonArray arr = new JsonArray();
        for ( final TagSummary t : svc.listTags( min ) ) {
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
        final StructuralFilter filter = new StructuralFilter(
                Optional.ofNullable( req.getParameter( "type" ) ).map( PageType::fromFrontmatter ),
                Optional.ofNullable( req.getParameter( "cluster" ) ),
                Arrays.asList( Optional.ofNullable( req.getParameter( "tag" ) )
                        .map( t -> t.split( "," ) ).orElse( new String[ 0 ] ) ),
                Optional.ofNullable( req.getParameter( "updated_since" ) ).map( Instant::parse ),
                parseIntOr( req.getParameter( "limit" ), 100 ),
                Optional.ofNullable( req.getParameter( "cursor" ) )
        );
        final List< PageDescriptor > pages = svc.listPagesByFilter( filter );
        final JsonArray arr = new JsonArray();
        pages.forEach( p -> arr.add( describe( p ) ) );
        final JsonObject data = new JsonObject();
        data.add( "pages", arr );
        data.addProperty( "count", pages.size() );
        writeEnvelope( resp, data );
    }

    private void writeSitemap( final HttpServletResponse resp, final StructuralIndexService svc )
            throws IOException {
        final Sitemap s = svc.sitemap();
        final JsonArray arr = new JsonArray();
        s.pages().forEach( p -> arr.add( describe( p ) ) );
        final JsonObject data = new JsonObject();
        data.add( "pages", arr );
        data.addProperty( "count", s.count() );
        data.addProperty( "generated_at", s.generatedAt().toString() );
        writeEnvelope( resp, data );
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
        try { return Integer.parseInt( raw ); } catch ( final NumberFormatException e ) { return fallback; }
    }
}
