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

import com.wikantik.work.DoraQuery;
import com.wikantik.work.FlowQuery;
import com.wikantik.work.WorkType;

/**
 * The flow and DORA report routes, registered into {@link WorkProjectResource} (a project's reports) and
 * {@link WorkProductResource} (a product's, for wiki admins). Every report is computed on the request; nothing is
 * stored. Dates are {@code yyyy-MM-dd}, between the years 1970 and 9999; a bad parameter is a 400 naming it.
 */
final class WorkFlowRoutes {

    private WorkFlowRoutes() {
    }

    /** {@code projects/{key}/flow} and {@code projects/{key}/dora}. */
    static void registerProject( final SegmentRouteTable.Builder< WorkResourceBase.Handler > r ) {
        WorkResourceBase.route( r, "GET", "projects/{}/flow", c -> c.tree( WorkFlowJson.flow(
            c.svc.features().flow().project( c.actor, c.pathParam( 0 ), new FlowQuery(
                WorkFlowJson.date( "from", c.query( "from" ) ), WorkFlowJson.date( "to", c.query( "to" ) ),
                WorkFlowJson.sprint( c.query( "sprint" ) ), c.query( "label" ), type( c.query( "type" ) ),
                c.query( "assignee" ) ) ) ) ) );
        WorkResourceBase.route( r, "GET", "projects/{}/dora", c -> c.tree( WorkFlowJson.dora(
            c.svc.features().flow().dora( c.actor, c.pathParam( 0 ), new DoraQuery(
                WorkFlowJson.date( "from", c.query( "from" ) ), WorkFlowJson.date( "to", c.query( "to" ) ),
                c.query( "product" ) ) ) ) ) );
    }

    /** {@code products/{key}/dora}: wiki admins only (the service refuses everyone else). */
    static void registerProduct( final SegmentRouteTable.Builder< WorkResourceBase.Handler > r ) {
        WorkResourceBase.route( r, "GET", "products/{}/dora", c -> c.tree( WorkFlowJson.dora(
            c.svc.features().flow().doraForProduct( c.actor, c.pathParam( 0 ), WorkFlowJson.date( "from", c.query( "from" ) ),
                WorkFlowJson.date( "to", c.query( "to" ) ) ) ) ) );
    }

    private static WorkType type( final String raw ) {
        return raw == null ? null : WorkType.fromWire( raw );
    }
}
