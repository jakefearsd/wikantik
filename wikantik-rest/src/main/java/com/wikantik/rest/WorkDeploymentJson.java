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
import com.wikantik.work.Item;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;

import java.util.List;

/** The wire form of a deployment as one caller sees it. Evidence is plain text and is passed through as text. */
final class WorkDeploymentJson {

    private WorkDeploymentJson() {
    }

    /** {@code tree} is the deployment record already run through the name enrichment for this caller. */
    static JsonObject view( final DeploymentView v, final JsonObject tree ) {
        tree.remove( "productId" );
        tree.remove( "itemIds" );
        tree.addProperty( "product", v.productKey() );
        final JsonArray items = new JsonArray();
        for ( final Item i : v.visibleItems() ) {
            items.add( WorkDeploymentResource.item( i ) );
        }
        tree.add( "items", items );
        tree.addProperty( "mayApprove", v.mayApprove() );
        tree.addProperty( "mayRun", v.mayRun() );
        return tree;
    }

    /** The short form for lists beside something else: what shipped an item, what waits for an approval. */
    static JsonArray brief( final List< DeploymentView > views ) {
        final JsonArray out = new JsonArray();
        for ( final DeploymentView v : views ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "id", v.deployment().id() );
            o.addProperty( "product", v.productKey() );
            o.addProperty( "version", v.deployment().version() );
            o.addProperty( "environment", v.deployment().environment() );
            o.addProperty( "state", v.deployment().state().wire() );
            o.addProperty( "itemCount", v.visibleItems().size() );
            o.addProperty( "requestedAt", String.valueOf( v.deployment().requestedAt() ) );
            o.addProperty( "verifiedAt", v.deployment().verifiedAt() == null ? null
                : v.deployment().verifiedAt().toString() );
            out.add( o );
        }
        return out;
    }
}
