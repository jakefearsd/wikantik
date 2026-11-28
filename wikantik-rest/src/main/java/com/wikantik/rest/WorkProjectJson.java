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
import com.wikantik.work.Actor;
import com.wikantik.work.Product;
import com.wikantik.work.Project;
import com.wikantik.work.WorkService;

import java.util.HashMap;
import java.util.Map;

/**
 * A project's JSON for the work API: the viewer's {@code role}, read-only {@code archived}, and the default product
 * shown by its key ({@code defaultProductKey}, null when none), never by its id.
 */
final class WorkProjectJson {

    private WorkProjectJson() {
    }

    /** One project with the viewer's role and the default product by key. */
    static JsonObject withRole( final Project project, final String role, final Map< Long, String > productKeys ) {
        final JsonObject o = WorkJson.GSON.toJsonTree( project ).getAsJsonObject();
        o.remove( "defaultProductId" );
        o.addProperty( "defaultProductKey",
            project.defaultProductId() == null ? null : productKeys.get( project.defaultProductId() ) );
        o.addProperty( "role", role );
        o.addProperty( "archived", project.archivedAt() != null );
        return o;
    }

    /** Product id to key, read once per response. */
    static Map< Long, String > productKeys( final WorkService svc, final Actor actor ) throws Exception {
        final Map< Long, String > keys = new HashMap<>();
        for ( final Product p : svc.listProducts( actor ) ) {
            keys.put( p.id(), p.key() );
        }
        return keys;
    }

    /** Every project the viewer can see, each with the viewer's role (resolved in one pass by the service). */
    static JsonArray visible( final WorkService svc, final Actor actor ) throws Exception {
        final JsonArray out = new JsonArray();
        final Map< Long, String > keys = productKeys( svc, actor );
        for ( final WorkService.ProjectRole pr : svc.listProjectsWithRoles( actor ) ) {
            out.add( withRole( pr.project(), pr.role().wire(), keys ) );
        }
        return out;
    }

    /** One project with the viewer's role (404 when hidden or unknown). */
    static JsonObject one( final WorkService svc, final Actor actor, final String key ) throws Exception {
        final Project p = svc.project( actor, key );
        return withRole( p, svc.roleIn( actor, key ).wire(), productKeys( svc, actor ) );
    }

    /** A just-created project: the viewer's role in it. */
    static JsonObject created( final WorkService svc, final Actor actor, final Project p ) throws Exception {
        return withRole( p, svc.roleIn( actor, p.key() ).wire(), productKeys( svc, actor ) );
    }
}
