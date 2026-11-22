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
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeployGate;
import com.wikantik.work.Product;
import com.wikantik.work.WorkNotFoundException;

import java.util.function.Function;

/** {@code /api/work/products/*}: the product catalogue. */
public class WorkProductResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "GET", "products", c -> c.tree( productsTree( c ) ) );
        route( r, "POST", "products", c -> {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            final String key = requiredString( b, "key" );
            c.svc.createProduct( c.actor, from( b, key, null ) );
            c.created( find( c, key ) );
        } );
        route( r, "PATCH", "products/{}", c -> {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            final String key = c.pathParam( 0 );
            c.svc.updateProduct( c.actor, from( b, key, find( c, key ) ) );
            c.json( find( c, key ) );
        } );
        route( r, "GET", "products/{}/history", c -> c.json( historyTree( c, c.pathParam( 0 ) ) ) );
        route( r, "POST", "products/{}/archive", c -> {
            c.svc.archiveProduct( c.actor, c.pathParam( 0 ) );
            c.noContent();
        } );
        WorkHookRoutes.register( r );
        WorkFlowRoutes.registerProduct( r );
    }

    /**
     * The catalogue as the caller may see it: every field for a wiki admin; key, name and {@code archived} only for
     * anyone else (the rest is instance configuration). Both carry {@code archived} as a boolean.
     */
    private JsonArray productsTree( final Call c ) throws Exception {
        final JsonArray out = new JsonArray();
        final java.util.Map< String, com.wikantik.work.HookStatus > hooks =
            c.actor.admin() ? WorkHookRoutes.hooksOf( c ) : java.util.Map.of();
        for ( final Product p : c.svc.listProducts( c.actor ) ) {
            final JsonObject o;
            if ( c.actor.admin() ) {
                o = toTree( p, c.actor ).getAsJsonObject();
                WorkHookRoutes.attach( o, hooks );
            } else {
                o = new JsonObject();
                o.addProperty( "key", p.key() );
                o.addProperty( "name", p.name() );
            }
            o.addProperty( "archived", p.archivedAt() != null );
            out.add( o );
        }
        return out;
    }

    /**
     * {@code { events: [ { at, actor, actorName, field, from, to } ] }}, newest first; the actor is shown the way
     * every other event's is ({@link WorkJson}): by display name, with the login only for a wiki admin.
     */
    private JsonObject historyTree( final Call c, final String key ) throws Exception {
        final JsonArray events = new JsonArray();
        for ( final JsonElement e : toTree( c.svc.productHistory( c.actor, key ), c.actor ).getAsJsonArray() ) {
            final JsonObject in = e.getAsJsonObject();
            final JsonObject out = new JsonObject();
            for ( final String k : new String[] { "at", "actor", "actorName", "actorKind", "field" } ) {
                if ( in.has( k ) ) {
                    out.add( k, in.get( k ) );
                }
            }
            out.add( "from", in.get( "fromValue" ) );
            out.add( "to", in.get( "toValue" ) );
            events.add( out );
        }
        final JsonObject body = new JsonObject();
        body.add( "events", events );
        return body;
    }

    private static Product find( final Call c, final String key ) throws Exception {
        for ( final Product p : c.svc.listProducts( c.actor ) ) {
            if ( p.key().equals( key ) ) {
                return p;
            }
        }
        throw new WorkNotFoundException( NOT_FOUND );
    }

    /** The product described by {@code b}; fields absent from the body keep the {@code base} value. */
    private static Product from( final JsonObject b, final String key, final Product base ) {
        return new Product( base == null ? 0 : base.id(), key,
            text( b, "name", base, Product::name ),
            text( b, "repoUrl", base, Product::repoUrl ),
            text( b, "defaultBranch", base, Product::defaultBranch ),
            text( b, "wikiCluster", base, Product::wikiCluster ),
            ceiling( b, base ), gate( b, base ),
            base == null ? null : base.archivedAt() );
    }

    private static String text( final JsonObject b, final String field, final Product base,
                                final Function< Product, String > current ) {
        return b.has( field ) || base == null ? optString( b, field ) : current.apply( base );
    }

    private static Autonomy ceiling( final JsonObject b, final Product base ) {
        final String wire = b.has( "autonomyCeiling" ) ? optString( b, "autonomyCeiling" ) : null;
        if ( wire != null ) {
            return Autonomy.fromWire( wire );
        }
        return base == null ? null : base.autonomyCeiling();
    }

    private static DeployGate gate( final JsonObject b, final Product base ) {
        final String wire = b.has( "deployGate" ) ? optString( b, "deployGate" ) : null;
        if ( wire != null ) {
            return DeployGate.fromWire( wire );
        }
        return base == null ? null : base.deployGate();
    }
}
