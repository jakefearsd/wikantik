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
import com.wikantik.work.DeploymentDraft;
import com.wikantik.work.DeploymentEvent;
import com.wikantik.work.Item;
import com.wikantik.work.WorkDeploymentsService;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import com.wikantik.work.WorkValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code /api/work/deployments/*}: a product version going to an environment, carrying items. A deployment is
 * seen through the items it carries: the caller is shown the ones it can see and nothing of the rest, and a
 * deployment it can see nothing of is the same 404 as one that does not exist.
 * {@code GET deployments/{id}/gate?version=} is what a deploy script asks before it goes ahead.
 */
public class WorkDeploymentResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;
    private static final Set< String > REQUEST_FIELDS = Set.of( "product", "environment", "version", "items" );
    private static final int LIST_DEFAULT = 20;

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "POST", "deployments", this::request );
        route( r, "GET", "deployments", this::list );
        route( r, "GET", "deployments/{}", c -> c.tree( view( c, c.svc.deployments().deployment( c.actor, c.idParam( 0 ) ) ) ) );
        route( r, "GET", "deployments/{}/history", this::history );
        route( r, "GET", "deployments/{}/gate", this::gate );
        route( r, "POST", "deployments/{}/approve", c -> {
            final JsonObject b = c.optionalBody();
            if ( b != null ) {
                c.tree( view( c, c.svc.deployments().approve( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "note" ) ) ) );
            }
        } );
        route( r, "POST", "deployments/{}/reject", c -> {
            final JsonObject b = c.optionalBody();
            if ( b != null ) {
                c.tree( view( c, c.svc.deployments().reject( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "note" ) ) ) );
            }
        } );
        route( r, "POST", "deployments/{}/start", c -> c.tree( view( c, c.svc.deployments().start( c.actor, c.idParam( 0 ) ) ) ) );
        route( r, "POST", "deployments/{}/verify", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.tree( view( c, c.svc.deployments().verify( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "evidence" ) ) ) );
            }
        } );
        route( r, "POST", "deployments/{}/fail", c -> {
            final JsonObject b = c.optionalBody();
            if ( b != null ) {
                c.tree( view( c, c.svc.deployments().fail( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "note" ) ) ) );
            }
        } );
        route( r, "POST", "deployments/{}/roll-back", c -> {
            final JsonObject b = c.optionalBody();
            if ( b != null ) {
                c.tree( view( c, c.svc.deployments().rollBack( c.actor, c.idParam( 0 ), WorkChangeJson.text( b, "note" ) ) ) );
            }
        } );
    }

    private void request( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        for ( final String field : b.keySet() ) {
            if ( !REQUEST_FIELDS.contains( field ) ) {
                throw new WorkValidationException( field, "Unknown field: " + field );
            }
        }
        final DeploymentView v = c.svc.deployments().request( c.actor, new DeploymentDraft(
            WorkChangeJson.text( b, "product" ), WorkChangeJson.text( b, "environment" ),
            WorkChangeJson.text( b, "version" ), itemKeys( b ) ) );
        WorkJson.write( c.resp, jakarta.servlet.http.HttpServletResponse.SC_CREATED, view( c, v ) );
    }

    private static List< String > itemKeys( final JsonObject b ) {
        final JsonElement raw = b.get( "items" );
        final List< String > keys = new ArrayList<>();
        if ( raw == null || raw.isJsonNull() ) {
            return keys;
        }
        if ( !raw.isJsonArray() ) {
            throw new WorkValidationException( "items", "items must be a list of item keys." );
        }
        for ( final JsonElement e : raw.getAsJsonArray() ) {
            if ( !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() ) {
                throw new WorkValidationException( "items", "items must be a list of item keys." );
            }
            keys.add( e.getAsString() );
        }
        return keys;
    }

    private void list( final Call c ) throws Exception {
        final String product = c.query( "product" );
        if ( product == null ) {
            throw new WorkValidationException( "product", "product is required." );
        }
        final int asked = parseIntParam( c.req, "limit", LIST_DEFAULT );
        final JsonArray list = new JsonArray();
        for ( final DeploymentView v : c.svc.deployments().forProduct( c.actor, product, asked <= 0 ? LIST_DEFAULT : asked ) ) {
            list.add( view( c, v ) );
        }
        final JsonObject out = new JsonObject();
        out.add( "deployments", list );
        c.tree( out );
    }

    private void history( final Call c ) throws Exception {
        final JsonArray rows = new JsonArray();
        for ( final DeploymentEvent e : c.svc.deployments().history( c.actor, c.idParam( 0 ) ) ) {
            rows.add( toTree( e, c.actor ) );
        }
        final JsonObject out = new JsonObject();
        out.add( "history", rows );
        c.tree( out );
    }

    /** The deploy script's question: may this version of this deployment go ahead? Never cached. */
    private void gate( final Call c ) throws Exception {
        final String version = c.query( "version" );
        if ( version == null ) {
            throw new WorkValidationException( "version", "version is required." );
        }
        // One read: the verdict and the state it is reported with can never disagree.
        final WorkDeploymentsService.GateAnswer answer = c.svc.deployments().gate( c.actor, c.idParam( 0 ), version );
        final JsonObject out = new JsonObject();
        out.addProperty( "approved", answer.approved() );
        out.addProperty( "state", answer.state().wire() );
        out.addProperty( "version", answer.version() );
        out.addProperty( "environment", answer.environment() );
        c.resp.setHeader( "Cache-Control", "no-store" );
        c.tree( out );
    }

    /**
     * A deployment for this caller: its record with people by name, the product key, the carried items the caller
     * can see ({@code key}, {@code title}, {@code status}) and what the caller could do next.
     */
    private JsonObject view( final Call c, final DeploymentView v ) {
        return WorkDeploymentJson.view( v, toTree( v.deployment(), c.actor ).getAsJsonObject() );
    }

    static JsonObject item( final Item i ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "key", i.key() );
        o.addProperty( "title", i.title() );
        o.addProperty( "status", i.status().wire() );
        return o;
    }
}
