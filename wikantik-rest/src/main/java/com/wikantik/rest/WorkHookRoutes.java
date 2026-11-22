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

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.work.HookCreated;
import com.wikantik.work.HookStatus;

import java.sql.SQLException;
import java.util.Map;

/**
 * A product's code-host hook routes, registered into {@link WorkProductResource} (they live under
 * {@code /api/work/products/{key}}): turn the hook on (again to rotate the secret) and off. Only a wiki admin may; the
 * service refuses everyone else. The secret is in the response that issues it and in no other response.
 */
final class WorkHookRoutes {

    private static final String HOOKS_PATH = "/api/work/hooks/";

    private WorkHookRoutes() {
    }

    static void register( final SegmentRouteTable.Builder< WorkResourceBase.Handler > r ) {
        WorkResourceBase.route( r, "POST", "products/{}/hook", c -> {
            final String key = c.pathParam( 0 );
            final HookCreated created = c.svc.features().codeHost().enableHook( c.actor, key );
            final JsonObject body = new JsonObject();
            body.addProperty( "secret", created.secret() );
            body.addProperty( "githubUrl", urlOf( key, "github" ) );
            body.addProperty( "genericUrl", urlOf( key, "generic" ) );
            // the one response that carries the secret: no browser or proxy may keep it
            c.resp.setHeader( "Cache-Control", "no-store" );
            c.tree( body );
        } );
        WorkResourceBase.route( r, "DELETE", "products/{}/hook", c -> {
            c.svc.features().codeHost().disableHook( c.actor, c.pathParam( 0 ) );
            c.noContent();
        } );
    }

    /** The path a code host posts to; it carries the product key and the format and never the secret. */
    static String urlOf( final String productKey, final String format ) {
        return HOOKS_PATH + productKey + "/" + format;
    }

    /** {@code hook: {enabled, enabledAt, lastReceivedAt}} for an admin's product row; a product with no hook is off. */
    static void attach( final JsonObject product, final Map< String, HookStatus > hooks ) {
        final HookStatus s = hooks.get( product.get( "key" ).getAsString() );
        final JsonObject hook = new JsonObject();
        hook.addProperty( "enabled", s != null && s.enabled() );
        hook.add( "enabledAt", s == null || s.enabledAt() == null ? JsonNull.INSTANCE
            : new com.google.gson.JsonPrimitive( s.enabledAt().toString() ) );
        hook.add( "lastReceivedAt", s == null || s.lastReceivedAt() == null ? JsonNull.INSTANCE
            : new com.google.gson.JsonPrimitive( s.lastReceivedAt().toString() ) );
        product.add( "hook", hook );
    }

    /** The hooks an admin may read, keyed by product key. */
    static Map< String, HookStatus > hooksOf( final WorkResourceBase.Call c ) throws SQLException {
        return c.svc.features().codeHost().hooks( c.actor );
    }
}
