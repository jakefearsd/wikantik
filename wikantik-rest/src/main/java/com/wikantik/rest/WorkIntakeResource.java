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
import com.wikantik.work.Actor;
import com.wikantik.work.IntakeDraft;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkType;
import com.wikantik.work.WorkValidationException;

import java.util.Set;

/**
 * {@code /api/work/intake/*}: what a reporter (a person who may file into a project without belonging to it) can do:
 * list where they may file, file an item, and follow the items they filed. These are the only routes that answer a
 * reporter with data; every other work route answers them as it answers a stranger, because the service refuses it.
 *
 * <p>Nothing here decides who may see what: each route calls {@code features().intake()}, and an item that is not the
 * caller's, a member-only item, an unknown key and a malformed key are all the one {@code 404}. The JSON is built by
 * {@link WorkIntakeJson}, field by field from the service's reduced records.
 */
public class WorkIntakeResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    /** The fields a reporter may send when filing. */
    private static final Set< String > FILE_FIELDS = Set.of( "projectKey", "project", "level", "type", "title", "body" );

    /** Fields a member sets: named in the 400 so the form can say why, rather than being dropped. */
    private static final Set< String > MEMBER_FIELDS = Set.of( "assignee", "priority", "parentKey", "productKey", "labels",
        "sprint", "estimate", "queue", "dueOn", "kind", "status" );

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "GET", "intake/projects", c -> c.tree(
            WorkIntakeJson.reportable( c.svc.features().intake().reportableProjects( c.actor ) ) ) );
        route( r, "POST", "intake/items", this::file );
        route( r, "GET", "intake/items", c -> c.tree( WorkIntakeJson.listed( c.svc.features().intake().reported( c.actor ) ) ) );
        route( r, "GET", "intake/items/{}", c -> c.tree( WorkIntakeJson.view(
            c.svc.features().intake().item( c.actor, c.pathParam( 0 ) ), viewerName( c.actor ) ) ) );
        route( r, "POST", "intake/items/{}/comments", this::comment );
    }

    private void file( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        refuseOtherFields( b );
        final String project = b.has( "projectKey" ) ? optString( b, "projectKey" ) : optString( b, "project" );
        final String level = optString( b, "level" );
        final String type = optString( b, "type" );
        final var view = c.svc.features().intake().submit( c.actor, new IntakeDraft( project,
            level == null ? null : WorkLevel.fromWire( level ), type == null ? null : WorkType.fromWire( type ),
            optString( b, "title" ), optString( b, "body" ) ) );
        WorkJson.write( c.resp, jakarta.servlet.http.HttpServletResponse.SC_CREATED,
            WorkIntakeJson.view( view, viewerName( c.actor ) ) );
    }

    private void comment( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        for ( final String field : b.keySet() ) {
            if ( !"body".equals( field ) ) {
                throw new WorkValidationException( field, "Unknown field: " + field );
            }
        }
        final var comment = c.svc.features().intake().comment( c.actor, c.pathParam( 0 ), optString( b, "body" ) );
        WorkJson.write( c.resp, jakarta.servlet.http.HttpServletResponse.SC_CREATED,
            WorkIntakeJson.comment( comment, viewerName( c.actor ) ) );
    }

    /** A member-only field is named as such; any other field the form does not have is unknown. */
    private static void refuseOtherFields( final JsonObject b ) {
        for ( final String field : b.keySet() ) {
            if ( MEMBER_FIELDS.contains( field ) ) {
                throw new WorkValidationException( field, "A project member sets this after triage." );
            }
            if ( !FILE_FIELDS.contains( field ) ) {
                throw new WorkValidationException( field, "Unknown field: " + field );
            }
        }
    }

    /** The caller's display name (only a person can be a reporter); null for anyone else. */
    private String viewerName( final Actor actor ) {
        return actor.kind() == Actor.Kind.HUMAN ? displayName( actor.login() ) : null;
    }
}
