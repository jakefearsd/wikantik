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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.ItemPatch;
import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /api/work/items/*}, {@code /api/work/comments/*}, {@code /api/work/links/*} and
 * {@code /api/work/claimable}: items and everything hanging off them, including claim, release and hand-off.
 */
public class WorkItemResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;
    private static final Set< String > DRAFT_FIELDS = Set.of( "project", "projectKey", "level", "parentKey",
        "productKey", "kind", "title", "body", "priority", "dueOn", "assignee", "queue" );
    private static final int CLAIMABLE_DEFAULT = 20;
    private static final int CLAIMABLE_MAX = 50;

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "items", c -> {
            final String project = c.query( "project" );
            if ( project == null ) {
                throw new WorkValidationException( "project", "project is required." );
            }
            c.tree( summariesTree( c.svc.itemSummaries( c.actor, project, filter( c, true ) ), c.actor ) );
        } );
        route( r, "POST", "items", this::create );
        route( r, "GET", "items/{}", c -> c.json( c.svc.item( c.actor, c.pathParam( 0 ) ) ) );
        route( r, "PATCH", "items/{}", this::patch );
        route( r, "GET", "items/{}/revisions", c -> c.json( c.svc.revisions( c.actor, c.pathParam( 0 ) ) ) );
        route( r, "GET", "items/{}/events", c -> c.json( c.svc.events( c.actor, c.pathParam( 0 ) ) ) );
        route( r, "POST", "items/{}/comments", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.created( c.svc.addComment( c.actor, c.pathParam( 0 ), optString( b, "body" ) ) );
            }
        } );
        route( r, "PATCH", "comments/{}", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.json( c.svc.editComment( c.actor, c.idParam( 0 ), optString( b, "body" ) ) );
            }
        } );
        route( r, "DELETE", "comments/{}", c -> {
            c.svc.removeComment( c.actor, c.idParam( 0 ) );
            c.noContent();
        } );
        route( r, "GET", "items/{}/changes", c -> c.tree( WorkChangeJson.changes(
            c.svc.changes().views( c.actor, c.pathParam( 0 ) ), v -> toTree( v, c.actor ) ) ) );
        route( r, "POST", "items/{}/changes", this::openChange );
        route( r, "GET", "items/{}/policy", c -> c.tree( WorkChangeJson.policy(
            c.svc.policyOf( c.actor, c.pathParam( 0 ) ), v -> toTree( v, c.actor ) ) ) );
        route( r, "POST", "items/{}/acknowledge", c -> c.json( c.svc.acknowledge( c.actor, c.pathParam( 0 ) ) ) );
        route( r, "GET", "claimable", this::claimable );
        route( r, "POST", "items/{}/claim", this::claim );
        route( r, "POST", "items/{}/release", this::release );
        route( r, "POST", "items/{}/handoff", this::handoff );
        route( r, "POST", "items/{}/links", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.created( c.svc.addLink( c.actor, c.pathParam( 0 ), optString( b, "kind" ), optString( b, "target" ) ) );
            }
        } );
        route( r, "DELETE", "links/{}", c -> {
            c.svc.removeLink( c.actor, c.idParam( 0 ) );
            c.noContent();
        } );
    }

    private void create( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        for ( final String field : b.keySet() ) {
            if ( !DRAFT_FIELDS.contains( field ) ) {
                throw new WorkValidationException( field, "Unknown field: " + field );
            }
        }
        final String project = b.has( "projectKey" ) ? optString( b, "projectKey" ) : optString( b, "project" );
        final String level = optString( b, "level" );
        final String kind = optString( b, "kind" );
        c.created( c.svc.createItem( c.actor, new ItemDraft( project, level == null ? null : WorkLevel.fromWire( level ),
            optString( b, "parentKey" ), optString( b, "productKey" ), kind == null ? null : WorkKind.fromWire( kind ),
            optString( b, "title" ), optString( b, "body" ), priority( b ), dueOn( b ), optString( b, "assignee" ),
            optString( b, "queue" ) ) ) );
    }

    private void openChange( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final long id = c.svc.changes().open( c.actor, c.pathParam( 0 ), WorkChangeJson.draft( b ) ).id();
        WorkJson.write( c.resp, jakarta.servlet.http.HttpServletResponse.SC_CREATED,
            WorkChangeJson.change( c.svc.changes().view( c.actor, id ), v -> toTree( v, c.actor ) ) );
    }

    /** Unclaimed ready or review items the caller could claim (or, for a person, the queues they can see). */
    private void claimable( final Call c ) throws Exception {
        final int asked = parseIntParam( c.req, "limit", CLAIMABLE_DEFAULT );
        final int limit = asked <= 0 ? CLAIMABLE_DEFAULT : Math.min( asked, CLAIMABLE_MAX );
        c.tree( summariesTree( c.svc.claims().claimable( c.actor, c.query( "project" ), c.query( "queue" ), limit ),
            c.actor ) );
    }

    private void claim( final Call c ) throws Exception {
        final WorkClaimsService.ClaimResult result = c.svc.claims().claim( c.actor, c.pathParam( 0 ) );
        final JsonObject out = toTree( result.view(), c.actor ).getAsJsonObject();
        out.addProperty( "renewed", result.renewed() );
        c.tree( out );
    }

    /** The body is optional: a bare POST releases with no note. */
    private void release( final Call c ) throws Exception {
        final JsonObject b = c.optionalBody();
        if ( b != null ) {
            c.json( c.svc.claims().release( c.actor, c.pathParam( 0 ), optString( b, "note" ) ) );
        }
    }

    private void handoff( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final String status = optString( b, "status" );
        c.json( c.svc.claims().handoff( c.actor, c.pathParam( 0 ), new WorkClaimsService.Handoff( expectedVersion( b ),
            status == null ? null : WorkStatus.fromWire( status ), optString( b, "assignee" ), optString( b, "queue" ),
            optString( b, "note" ) ) ) );
    }

    private static Integer priority( final JsonObject b ) {
        final JsonElement v = b.get( "priority" );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        try {
            return v.getAsInt();
        } catch ( final RuntimeException e ) {
            throw new WorkValidationException( "priority", "priority must be a whole number." );
        }
    }

    private static LocalDate dueOn( final JsonObject b ) {
        final String v = optString( b, "dueOn" );
        try {
            return v == null || v.isBlank() ? null : LocalDate.parse( v );
        } catch ( final DateTimeParseException e ) {
            throw new WorkValidationException( "dueOn", "dueOn must be a date such as 2026-12-31." );
        }
    }

    /** A whole number that fits an int; anything else (missing, text, 1.5, 1e99) is a 400 on the field. */
    private static int expectedVersion( final JsonObject b ) {
        final JsonElement v = b.get( "expectedVersion" );
        if ( v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() ) {
            throw new WorkValidationException( "expectedVersion", "expectedVersion is required." );
        }
        try {
            return new java.math.BigDecimal( v.getAsString() ).intValueExact();
        } catch ( final ArithmeticException | NumberFormatException e ) {
            throw new WorkValidationException( "expectedVersion", "expectedVersion must be a whole number." );
        }
    }

    private void patch( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final int expectedVersion = expectedVersion( b );
        final Map< String, Object > changes = new LinkedHashMap<>();
        final JsonElement raw = b.get( "changes" );
        if ( raw != null && !raw.isJsonNull() ) {
            if ( !raw.isJsonObject() ) {
                throw new WorkValidationException( "changes", "changes must be an object." );
            }
            for ( final Map.Entry< String, JsonElement > e : raw.getAsJsonObject().entrySet() ) {
                changes.put( e.getKey(), plainValue( e.getKey(), e.getValue() ) );
            }
        }
        c.json( c.svc.updateItem( c.actor, c.pathParam( 0 ),
            new ItemPatch( expectedVersion, changes, optString( b, "note" ) ) ) );
    }
}
