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
import com.wikantik.work.SprintDraft;
import com.wikantik.work.SprintReport;
import com.wikantik.work.WorkSprintsService;
import com.wikantik.work.WorkValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * {@code /api/work/sprints/*}: one sprint, read, edited, started and closed. A sprint of a project the caller cannot
 * see is the same 404 as one that does not exist; a non-numeric id is a 404 too. The project's own sprint routes
 * ({@code projects/{key}/sprints}, {@code projects/{key}/velocity}) live in {@link WorkProjectResource}.
 */
public class WorkSprintResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;
    static final Set< String > DRAFT_FIELDS = Set.of( "name", "goal", "startsOn", "endsOn" );

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "GET", "sprints/{}", c -> c.tree( WorkSprintJson.view( sprints( c ).get( c.actor, c.idParam( 0 ) ) ) ) );
        route( r, "GET", "sprints/{}/report", c -> {
            final SprintReport report = sprints( c ).report( c.actor, c.idParam( 0 ) );
            final JsonObject o = WorkSprintJson.report( report );
            o.add( "added", summariesTree( report.added(), c.actor ) );
            o.add( "removed", summariesTree( report.removed(), c.actor ) );
            c.tree( o );
        } );
        route( r, "PATCH", "sprints/{}", this::update );
        route( r, "POST", "sprints/{}/start", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                reject( b, Set.of( "version" ) );
                c.tree( WorkSprintJson.view( sprints( c ).start( c.actor, c.idParam( 0 ), version( b ) ) ) );
            }
        } );
        route( r, "POST", "sprints/{}/close", this::close );
    }

    private static WorkSprintsService sprints( final Call c ) {
        return c.svc.features().sprints();
    }

    private void update( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final long id = c.idParam( 0 );
        reject( b, Set.of( "version", "name", "goal", "startsOn", "endsOn" ) );
        final int version = version( b );
        final com.wikantik.work.Sprint current = sprints( c ).get( c.actor, id ).sprint();
        final SprintDraft draft = new SprintDraft(
            b.has( "name" ) ? optString( b, "name" ) : current.name(),
            b.has( "goal" ) ? optString( b, "goal" ) : current.goal(),
            b.has( "startsOn" ) ? date( b, "startsOn" ) : current.startsOn(),
            b.has( "endsOn" ) ? date( b, "endsOn" ) : current.endsOn() );
        c.tree( WorkSprintJson.view( sprints( c ).update( c.actor, id, version, draft ) ) );
    }

    private void close( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        reject( b, Set.of( "version", "moveOpenTo" ) );
        final int version = version( b );
        c.tree( WorkSprintJson.view( sprints( c ).close( c.actor, c.idParam( 0 ), version, moveOpenTo( b ) ) ) );
    }

    /** A sprint draft from a create body: every field optional here, the service refuses what is missing. */
    static SprintDraft draft( final JsonObject b ) {
        reject( b, DRAFT_FIELDS );
        return new SprintDraft( optString( b, "name" ), optString( b, "goal" ), date( b, "startsOn" ), date( b, "endsOn" ) );
    }

    /** Any field outside {@code allowed} is a 400 naming it. */
    static void reject( final JsonObject b, final Set< String > allowed ) {
        for ( final String field : b.keySet() ) {
            if ( !allowed.contains( field ) ) {
                throw new WorkValidationException( field, "Unknown field: " + field );
            }
        }
    }

    /** A whole number that fits an int; anything else (missing, text, 1.5, 1e99) is a 400 naming {@code version}. */
    static int version( final JsonObject b ) {
        final JsonElement v = b.get( "version" );
        if ( v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() ) {
            throw new WorkValidationException( "version", "version is required." );
        }
        try {
            return new BigDecimal( v.getAsString() ).intValueExact();
        } catch ( final ArithmeticException | NumberFormatException e ) {
            throw new WorkValidationException( "version", "version must be a whole number." );
        }
    }

    private static Long moveOpenTo( final JsonObject b ) {
        final JsonElement v = b.get( "moveOpenTo" );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        try {
            if ( v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ) {
                return new BigDecimal( v.getAsString() ).longValueExact();
            }
        } catch ( final ArithmeticException | NumberFormatException e ) {
            throw new WorkValidationException( "moveOpenTo", "moveOpenTo must be a sprint id or null." );
        }
        throw new WorkValidationException( "moveOpenTo", "moveOpenTo must be a sprint id or null." );
    }

    private static LocalDate date( final JsonObject b, final String field ) {
        final String v = optString( b, field );
        try {
            return v == null || v.isBlank() ? null : LocalDate.parse( v );
        } catch ( final DateTimeParseException e ) {
            throw new WorkValidationException( field, field + " must be a date such as 2026-12-31." );
        }
    }
}
