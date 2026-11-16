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

import com.wikantik.work.Actor;
import com.wikantik.work.AssignmentLogQuery;
import com.wikantik.work.WorkValidationException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code /api/work/activity}: a project's event stream, newest first; {@code item} narrows it to one item and
 * {@code types} (comma-separated) to those event types. {@code /api/work/log}: the assignment log over every project
 * the caller can see (an agent: the projects it was admitted to), narrowed by {@code project}, {@code types},
 * {@code actorKind} ({@code human|agent}) and {@code agent} (an agent id). Both page on the compound cursor
 * {@code before} + {@code beforeId}.
 */
public class WorkActivityResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "activity", c -> {
            final String project = c.query( "project" );
            if ( project == null ) {
                throw new WorkValidationException( "project", "project is required." );
            }
            final Instant before = before( c );
            c.json( c.svc.activity( c.actor, project, before, beforeId( c, before ), c.query( "item" ), types( c ),
                clampLimit( c ) ) );
        } );
        route( r, "GET", "log", c -> {
            final Instant before = before( c );
            final String kind = c.query( "actorKind" );
            if ( kind != null && !"human".equals( kind ) && !"agent".equals( kind ) ) {
                throw new WorkValidationException( "actorKind", "actorKind must be human or agent." );
            }
            Long agent = null;
            if ( c.query( "agent" ) != null ) {
                try {
                    agent = Long.valueOf( c.query( "agent" ) );
                } catch ( final NumberFormatException e ) {
                    throw new WorkValidationException( "agent", "agent must be an agent id." );
                }
            }
            c.json( java.util.Map.of( "events", c.svc.assignmentLog( c.actor, new AssignmentLogQuery( c.query( "project" ), before,
                beforeId( c, before ), types( c ), kind == null ? null
                    : "agent".equals( kind ) ? Actor.Kind.AGENT : Actor.Kind.HUMAN, agent, clampLimit( c ) ) ) ) );
        } );
    }

    private static Instant before( final Call c ) {
        if ( c.query( "before" ) == null ) {
            return null;
        }
        try {
            return Instant.parse( c.query( "before" ) );
        } catch ( final DateTimeParseException e ) {
            throw new WorkValidationException( "before", "before must be an ISO-8601 instant." );
        }
    }

    private static Long beforeId( final Call c, final Instant before ) {
        if ( c.query( "beforeId" ) == null ) {
            return null;
        }
        final Long id;
        try {
            id = Long.valueOf( c.query( "beforeId" ) );
        } catch ( final NumberFormatException e ) {
            throw new WorkValidationException( "beforeId", "beforeId must be a number." );
        }
        if ( before == null ) {
            throw new WorkValidationException( "beforeId", "beforeId needs before." );
        }
        return id;
    }

    /** The comma-separated {@code types}, or null when absent; whether each is a known type is the service's call. */
    private static Set< String > types( final Call c ) {
        final String raw = c.query( "types" );
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        return new LinkedHashSet<>( Arrays.stream( raw.split( "," ) ).map( String::trim )
            .filter( t -> !t.isEmpty() ).toList() );
    }
}
