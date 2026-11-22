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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wikantik.work.FindFilter;
import com.wikantik.work.SavedFilter;
import com.wikantik.work.SavedFilterView;

import java.util.Map;
import java.util.function.Function;

/** The JSON of a find page and of saved filters; people are shown by display name, never by login. */
final class WorkFindJson {

    private WorkFindJson() {
    }

    /** {@code {items, next}}: the summaries tree and the opaque cursor of the next page, or null. */
    static JsonObject page( final JsonElement items, final String next ) {
        final JsonObject out = new JsonObject();
        out.add( "items", items );
        out.add( "next", next == null ? JsonNull.INSTANCE : new JsonPrimitive( next ) );
        return out;
    }

    /**
     * {@code {id, name, project, shared, mine, mayDelete, ownerName, createdAt, filter}}. The stored owner and the
     * stored assignee and reporter are logins; {@code names} maps each to a display name (for everyone, admins too:
     * the filter is a URL anyone may open). {@code me} stays {@code me}. No login is written.
     */
    static JsonObject saved( final SavedFilterView view, final Function< String, String > names ) {
        final SavedFilter f = view.filter();
        final JsonObject out = new JsonObject();
        out.addProperty( "id", f.id() );
        out.addProperty( "name", f.name() );
        out.add( "project", f.projectKey() == null ? JsonNull.INSTANCE : new JsonPrimitive( f.projectKey() ) );
        out.addProperty( "shared", f.shared() );
        out.addProperty( "mine", view.mine() );
        out.addProperty( "mayDelete", view.mayDelete() );
        out.addProperty( "ownerName", names.apply( f.owner() ) );
        out.addProperty( "createdAt", f.createdAt() == null ? null : f.createdAt().toString() );
        out.add( "filter", shownFilter( f.filter(), names ) );
        return out;
    }

    private static JsonObject shownFilter( final Map< String, String > stored, final Function< String, String > names ) {
        final JsonObject out = new JsonObject();
        for ( final Map.Entry< String, String > e : stored.entrySet() ) {
            final boolean person = "assignee".equals( e.getKey() ) || "reporter".equals( e.getKey() );
            final String value = person && !FindFilter.ME.equals( e.getValue() ) ? names.apply( e.getValue() ) : e.getValue();
            out.addProperty( e.getKey(), value );
        }
        return out;
    }
}
