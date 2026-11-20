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
import com.wikantik.work.Autonomy;
import com.wikantik.work.Member;
import com.wikantik.work.Project;
import com.wikantik.work.WorkRegistryService;
import com.wikantik.work.WorkRole;

import java.util.List;

/** {@code /api/work/projects/*}: projects, their members and their board. */
public class WorkProjectResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "GET", "projects", c -> c.json( visibleProjects( c ) ) );
        route( r, "POST", "projects", c -> {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            final Project p = c.svc.createProject( c.actor, requiredString( b, "key" ), optString( b, "name" ),
                optString( b, "description" ) );
            c.created( projectWithRole( p, c.svc.roleIn( c.actor, p.key() ).wire() ) );
        } );
        route( r, "GET", "projects/{}", c -> c.json( projectJson( c, c.pathParam( 0 ) ) ) );
        route( r, "PATCH", "projects/{}", this::update );
        route( r, "POST", "projects/{}/archive", c -> {
            c.svc.archiveProject( c.actor, c.pathParam( 0 ) );
            c.noContent();
        } );
        route( r, "GET", "projects/{}/members", c -> c.tree( membersTree( c, c.svc.members( c.actor, c.pathParam( 0 ) ) ) ) );
        route( r, "PUT", "projects/{}/members", c -> {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            c.svc.putMember( c.actor, c.pathParam( 0 ), optString( b, "principal" ), optString( b, "principalKind" ),
                optRole( b ) );
            c.noContent();
        } );
        route( r, "DELETE", "projects/{}/members", this::removeMember );
        route( r, "GET", "projects/{}/change-counts", c -> c.tree(
            WorkChangeJson.counts( c.svc.changes().counts( c.actor, c.pathParam( 0 ) ) ) ) );
        route( r, "GET", "projects/{}/board", c -> c.tree( boardTree( c.svc.boardSummaries( c.actor, c.pathParam( 0 ), filter( c, false ) ), c.actor ) ) );
    }

    /**
     * The member rows, each user row with the {@code mentionId} the comment picker inserts (never a login), and each
     * agent row with whether it has any effect right now ({@code effective}) and, when it has none, the
     * {@code reason}: an agent's access is capped by its sponsor's standing in the project.
     */
    private JsonArray membersTree( final Call c, final List< Member > members ) {
        final JsonArray out = toTree( members, c.actor ).getAsJsonArray();
        final java.util.Map< Long, WorkRegistryService.MemberStanding > standings = agentStandings( c );
        for ( int i = 0; i < members.size(); i++ ) {
            final Member m = members.get( i );
            final JsonObject o = out.get( i ).getAsJsonObject();
            if ( "user".equals( m.principalKind() ) ) {
                mentionId( m.principal() ).ifPresent( id -> o.addProperty( "mentionId", id ) );
            } else if ( "agent".equals( m.principalKind() ) && m.principal().matches( "\\d{1,18}" ) ) {
                final WorkRegistryService.MemberStanding standing = standings.get( Long.parseLong( m.principal() ) );
                if ( standing != null ) {
                    final boolean effective = standing == WorkRegistryService.MemberStanding.EFFECTIVE;
                    o.addProperty( "effective", effective );
                    if ( !effective ) {
                        o.addProperty( "reason", standing.name().toLowerCase( java.util.Locale.ROOT ) );
                    }
                }
            }
        }
        return out;
    }

    /** How each agent member stands; empty (so nothing is claimed) when the lookup fails. */
    private java.util.Map< Long, WorkRegistryService.MemberStanding > agentStandings( final Call c ) {
        try {
            final java.util.Map< Long, WorkRegistryService.MemberStanding > m =
                c.svc.registry() == null ? null : c.svc.registry().agentStandings( c.actor, c.pathParam( 0 ) );
            return m == null ? java.util.Map.of() : m;
        } catch ( final java.sql.SQLException | RuntimeException e ) {
            org.apache.logging.log4j.LogManager.getLogger( WorkProjectResource.class ).warn(
                "Could not read how the agent members of a project stand; the list is shown without it: {}",
                e.getMessage() );
            return java.util.Map.of();
        }
    }

    private void update( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final String key = c.pathParam( 0 );
        final Project current = c.svc.project( c.actor, key );
        final String name = b.has( "name" ) ? optString( b, "name" ) : current.name();
        final String description = b.has( "description" ) ? optString( b, "description" ) : current.description();
        final String autonomy = optString( b, "defaultAutonomy" );
        c.svc.updateProject( c.actor, key, name, description, autonomy == null ? null : Autonomy.fromWire( autonomy ) );
        c.json( projectJson( c, key ) );
    }

    /** DELETE carries its target in the body, or in the query string for clients that cannot send one. */
    private void removeMember( final Call c ) throws Exception {
        String principal = c.query( "principal" );
        String kind = c.query( "principalKind" );
        if ( principal == null && c.req.getContentLength() != 0 ) {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            principal = optString( b, "principal" );
            kind = optString( b, "principalKind" );
        }
        c.svc.removeMember( c.actor, c.pathParam( 0 ), principal, kind );
        c.noContent();
    }

    private static WorkRole optRole( final JsonObject b ) {
        final String role = optString( b, "role" );
        return role == null ? null : WorkRole.fromWire( role );
    }
}
