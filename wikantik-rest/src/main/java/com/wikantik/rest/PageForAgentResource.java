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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.api.agent.ForAgentProjection;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.agent.HeadingOutline;
import com.wikantik.api.agent.KeyFact;
import com.wikantik.api.agent.McpToolHint;
import com.wikantik.api.agent.RecentChange;
import com.wikantik.api.core.Engine;
import com.wikantik.api.structure.RelationEdge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * {@code GET /api/pages/for-agent/{canonical_id}} — token-budgeted projection
 * of a wiki page for agent consumption. Wraps {@link ForAgentProjectionService}.
 *
 * <p>URL deviation from the design doc: the spec named this
 * {@code /api/pages/{id}/for-agent} but that collides with
 * {@code PageResource}'s {@code /api/pages/*} mapping. Same shape, different
 * prefix order — see Structural Spine Phase 1's {@code /api/pages/by-id/*}.</p>
 */
public class PageForAgentResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( PageForAgentResource.class );

    /**
     * Override the parent's GSON to enable {@code serializeNulls()} — the
     * for-agent payload promises {@code "runbook": null} as a stable contract
     * (Phase 3 will populate it), and the default GSON would silently drop it.
     */
    private static final Gson AGENT_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat( "yyyy-MM-dd'T'HH:mm:ss'Z'" )
            .serializeNulls()
            .create();

    private Engine engineOverride;
    void setEngineForTesting( final Engine engine ) { this.engineOverride = engine; }
    private Engine engine() { return engineOverride != null ? engineOverride : getEngine(); }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp ) throws IOException {
        final String pathInfo = Optional.ofNullable( req.getPathInfo() ).orElse( "" );
        if ( pathInfo.length() < 2 ) {
            resp.setStatus( 400 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"canonical_id required in path\"}" );
            return;
        }
        final String canonicalId = pathInfo.substring( 1 );

        final ForAgentProjectionService svc = engine().getManager( ForAgentProjectionService.class );
        if ( svc == null ) {
            resp.setStatus( 503 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"for-agent projection service unavailable\"}" );
            return;
        }

        final Optional< ForAgentProjection > maybe = svc.project( canonicalId );
        if ( maybe.isEmpty() ) {
            resp.setStatus( 404 );
            resp.setContentType( "application/json; charset=UTF-8" );
            resp.getWriter().write( "{\"error\":\"no page for canonical_id " + canonicalId + "\"}" );
            return;
        }

        final ForAgentProjection p = maybe.get();
        final JsonObject envelope = new JsonObject();
        envelope.add( "data", toJson( p ) );
        final String body = AGENT_GSON.toJson( envelope );
        resp.setContentType( "application/json; charset=UTF-8" );
        resp.setStatus( 200 );
        resp.getWriter().write( body );
    }

    private static JsonObject toJson( final ForAgentProjection p ) {
        final JsonObject d = new JsonObject();
        d.addProperty( "id",         p.canonicalId() );
        d.addProperty( "slug",       p.slug() );
        d.addProperty( "title",      p.title() );
        d.addProperty( "type",       p.type() );
        if ( p.cluster() != null ) d.addProperty( "cluster", p.cluster() );
        d.addProperty( "audience",   p.audience() == null ? null : p.audience().wireName() );
        d.addProperty( "confidence", p.confidence() == null ? null : p.confidence().wireName() );
        if ( p.verifiedAt() != null ) d.addProperty( "verified_at", p.verifiedAt().toString() );
        if ( p.verifiedBy() != null ) d.addProperty( "verified_by", p.verifiedBy() );
        if ( p.updated()    != null ) d.addProperty( "updated",     p.updated().toString() );
        if ( p.summary()    != null ) d.addProperty( "summary",     p.summary() );

        final JsonArray facts = new JsonArray();
        for ( final KeyFact kf : p.keyFacts() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "text", kf.text() );
            if ( kf.sourceHint() != null ) o.addProperty( "source", kf.sourceHint() );
            facts.add( o );
        }
        d.add( "key_facts", facts );

        final JsonArray outline = new JsonArray();
        for ( final HeadingOutline h : p.headingsOutline() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "level", h.level() );
            o.addProperty( "text",  h.text() );
            outline.add( o );
        }
        d.add( "headings_outline", outline );

        final JsonObject relations = new JsonObject();
        relations.add( "outgoing", relationsToJson( p.outgoingRelations() ) );
        relations.add( "incoming", relationsToJson( p.incomingRelations() ) );
        d.add( "typed_relations", relations );

        final JsonArray changes = new JsonArray();
        for ( final RecentChange c : p.recentChanges() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "version", c.version() );
            if ( c.at()      != null ) o.addProperty( "at",      c.at().toString() );
            if ( c.author()  != null ) o.addProperty( "author",  c.author() );
            if ( c.summary() != null ) o.addProperty( "summary", c.summary() );
            changes.add( o );
        }
        d.add( "recent_changes", changes );

        final JsonArray hints = new JsonArray();
        for ( final McpToolHint h : p.mcpToolHints() ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "tool", h.tool() );
            o.addProperty( "when", h.when() );
            hints.add( o );
        }
        d.add( "mcp_tool_hints", hints );

        d.add( "runbook", p.runbook() == null ? JsonNull.INSTANCE
                : AGENT_GSON.toJsonTree( p.runbook() ) );
        d.addProperty( "full_body_url",    p.fullBodyUrl() );
        d.addProperty( "raw_markdown_url", p.rawMarkdownUrl() );
        d.addProperty( "degraded",         p.degraded() );

        final JsonArray missing = new JsonArray();
        for ( final String f : p.missingFields() ) missing.add( f );
        d.add( "missing_fields", missing );
        return d;
    }

    private static JsonArray relationsToJson( final List< RelationEdge > rels ) {
        final JsonArray a = new JsonArray();
        for ( final RelationEdge r : rels ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "type",         r.type().wireName() );
            o.addProperty( "source_id",    r.sourceId() );
            if ( r.sourceSlug()  != null ) o.addProperty( "source_slug",  r.sourceSlug() );
            o.addProperty( "target_id",    r.targetId() );
            if ( r.targetSlug()  != null ) o.addProperty( "target_slug",  r.targetSlug() );
            if ( r.targetTitle() != null ) o.addProperty( "target_title", r.targetTitle() );
            a.add( o );
        }
        return a;
    }
}
