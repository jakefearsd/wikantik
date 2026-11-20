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


/**
 * {@code /api/work/changes/*}: what happens to a change once it is open. Opening and listing are item routes
 * ({@code /api/work/items/{key}/changes}, in {@link WorkItemResource}). A change the caller cannot see and one
 * that does not exist are the same 404.
 */
public class WorkChangeResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final SegmentRouteTable< Handler > r ) {
        route( r, "PATCH", "changes/{}", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.svc.changes().update( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "headSha" ),
                    WorkChangeJson.text( b, "reviewUrl" ) );
                reply( c );
            }
        } );
        route( r, "POST", "changes/{}/request-review", c -> {
            c.svc.changes().requestReview( c.actor, c.idParam( 0 ) );
            reply( c );
        } );
        route( r, "POST", "changes/{}/review", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.svc.changes().review( c.actor, c.idParam( 0 ), WorkChangeJson.approved( b ),
                    WorkChangeJson.text( b, "note" ) );
                reply( c );
            }
        } );
        route( r, "POST", "changes/{}/merge", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.svc.changes().merge( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "mergedSha" ) );
                reply( c );
            }
        } );
        route( r, "POST", "changes/{}/abandon", c -> {
            final JsonObject b = c.optionalBody();
            if ( b != null ) {
                c.svc.changes().abandon( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "note" ) );
                reply( c );
            }
        } );
    }

    /** The change as it now stands. */
    private void reply( final Call c ) throws Exception {
        c.tree( WorkChangeJson.change( c.svc.changes().view( c.actor, c.idParam( 0 ) ), v -> toTree( v, c.actor ) ) );
    }
}
