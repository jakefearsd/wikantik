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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wikantik.work.SavedFilterDraft;
import com.wikantik.work.SavedFilterView;
import com.wikantik.work.WorkFindService;
import com.wikantik.work.WorkValidationException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code /api/work/filters}: the caller's saved filters and the shared filters of projects they can see. The view
 * shows people by display name only (the stored owner and filter people are logins, mapped here), and a filter the
 * caller may not know exists is the same 404 as an unknown id.
 */
public class WorkFilterResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;
    private static final Set< String > SAVE_FIELDS = Set.of( "name", "project", "shared", "filter" );
    private static final Set< String > RENAME_FIELDS = Set.of( "name" );

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "GET", "filters", this::list );
        route( r, "POST", "filters", this::save );
        route( r, "PATCH", "filters/{}", this::rename );
        route( r, "DELETE", "filters/{}", c -> {
            find( c ).delete( c.actor, c.idParam( 0 ) );
            c.noContent();
        } );
    }

    private static WorkFindService find( final Call c ) {
        return c.svc.features().find();
    }

    private Function< String, String > names() {
        final Map< String, String > cache = new HashMap<>();
        return login -> cache.computeIfAbsent( login, this::displayName );
    }

    private void list( final Call c ) throws Exception {
        final Function< String, String > names = names();
        final JsonArray filters = new JsonArray();
        for ( final SavedFilterView v : find( c ).saved( c.actor ) ) {
            filters.add( WorkFindJson.saved( v, names ) );
        }
        final JsonObject out = new JsonObject();
        out.add( "filters", filters );
        c.tree( out );
    }

    private void save( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        refuseUnknown( b, SAVE_FIELDS );
        final SavedFilterDraft draft = new SavedFilterDraft( optString( b, "name" ), optString( b, "project" ),
            shared( b ), filter( b ) );
        WorkJson.write( c.resp, jakarta.servlet.http.HttpServletResponse.SC_CREATED,
            WorkFindJson.saved( find( c ).save( c.actor, draft ), names() ) );
    }

    private void rename( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        refuseUnknown( b, RENAME_FIELDS );
        c.tree( WorkFindJson.saved( find( c ).rename( c.actor, c.idParam( 0 ), optString( b, "name" ) ), names() ) );
    }

    private static void refuseUnknown( final JsonObject b, final Set< String > allowed ) {
        for ( final String field : b.keySet() ) {
            if ( !allowed.contains( field ) ) {
                throw new WorkValidationException( field, "Unknown field." );
            }
        }
    }

    private static boolean shared( final JsonObject b ) {
        final JsonElement v = b.get( "shared" );
        if ( v == null || v.isJsonNull() ) {
            return false;
        }
        if ( !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean() ) {
            throw new WorkValidationException( "shared", "shared must be true or false." );
        }
        return v.getAsBoolean();
    }

    /** The {@code filter} object: parameter name to text; a value that is not text is a 400 on {@code filter}. */
    private static Map< String, String > filter( final JsonObject b ) {
        final JsonElement v = b.get( "filter" );
        if ( v == null || v.isJsonNull() ) {
            return Map.of();
        }
        if ( !v.isJsonObject() ) {
            throw new WorkValidationException( "filter", "filter must be an object." );
        }
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final Map.Entry< String, JsonElement > e : v.getAsJsonObject().entrySet() ) {
            final JsonElement value = e.getValue();
            if ( !value.isJsonPrimitive() || !( ( JsonPrimitive ) value ).isString() ) {
                throw new WorkValidationException( "filter", "filter values are text." );
            }
            out.put( e.getKey(), value.getAsString() );
        }
        return out;
    }
}
