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
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeployGate;
import com.wikantik.work.Product;
import com.wikantik.work.WorkNotFoundException;

import java.util.List;

/** {@code /api/work/products/*}: the product catalogue. */
public class WorkProductResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "products", c -> c.json( c.svc.listProducts( c.actor ) ) );
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
        route( r, "POST", "products/{}/archive", c -> {
            c.svc.archiveProduct( c.actor, c.pathParam( 0 ) );
            c.noContent();
        } );
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
        final String ceiling = b.has( "autonomyCeiling" ) ? optString( b, "autonomyCeiling" ) : null;
        final String gate = b.has( "deployGate" ) ? optString( b, "deployGate" ) : null;
        return new Product( base == null ? 0 : base.id(), key,
            b.has( "name" ) || base == null ? optString( b, "name" ) : base.name(),
            b.has( "repoUrl" ) || base == null ? optString( b, "repoUrl" ) : base.repoUrl(),
            b.has( "defaultBranch" ) || base == null ? optString( b, "defaultBranch" ) : base.defaultBranch(),
            b.has( "wikiCluster" ) || base == null ? optString( b, "wikiCluster" ) : base.wikiCluster(),
            ceiling == null ? ( base == null ? null : base.autonomyCeiling() ) : Autonomy.fromWire( ceiling ),
            gate == null ? ( base == null ? null : base.deployGate() ) : DeployGate.fromWire( gate ),
            base == null ? null : base.archivedAt() );
    }
}
