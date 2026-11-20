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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.function.Function;

/**
 * GoF Template Method for the admin bulk-action endpoints ({@code POST .../bulk-action}). The skeleton is fixed
 * here: parse the body, require an {@code action} the resource supports, require a non-empty {@code ids} array,
 * run the optional {@link Prepare} step, loop the ids with a blank-id guard, write one summary log line and send
 * the {@link BulkActionResult} envelope. A resource supplies only what differs: its {@link Action}s (the per-id
 * work) and, when an action needs it, a {@code Prepare}.
 *
 * <p>The 400 texts are built from the keys of the {@code actions} map in iteration order, so register the actions
 * in the order the message should list them ({@code "supported: lock, unlock, delete, add-to-group"}).
 */
final class BulkActions {

    private static final Logger LOG = LogManager.getLogger( BulkActions.class );

    private static final String DEFAULT_BLANK_ID_MESSAGE = "id must be a non-blank string";

    private BulkActions() {
    }

    /** Applies one action to one non-blank id, recording success or failure on {@code result}. */
    @FunctionalInterface
    interface IdHandler {
        void apply( String id, BulkActionResult result ) throws Exception;
    }

    /**
     * An optional pre-step run once after validation and before the loop, for example resolving the add-to-group
     * target. It may send its own 400 and return {@code false}, which ends the request.
     */
    @FunctionalInterface
    interface Prepare {
        boolean run( String action, JsonObject body, HttpServletResponse resp ) throws IOException;
    }

    /**
     * One supported action.
     *
     * @param name           the wire name of the action
     * @param pastTense      the completion phrase after {@code "N of M "}, for example {@code "users locked"}
     * @param perId          the work for one id
     * @param blankIdMessage the per-id failure text for a blank or non-primitive id
     * @param logSuffix      text appended to the summary log line for a request (for example {@code " group=x"}),
     *                       or {@code null}
     */
    record Action( String name, String pastTense, IdHandler perId, String blankIdMessage,
                   Function< JsonObject, String > logSuffix ) {

        Action( final String name, final String pastTense, final IdHandler perId ) {
            this( name, pastTense, perId, DEFAULT_BLANK_ID_MESSAGE, null );
        }
    }

    /**
     * Runs the template for one request.
     *
     * @param resource the resource label of the summary log line, for example {@code users}
     * @param actions  the supported actions by wire name, in the order the 400 text lists them
     * @param prepare  the optional pre-step, or {@code null}
     */
    static void handle( final HttpServletRequest req, final HttpServletResponse resp, final String resource,
                        final Map< String, Action > actions, final Prepare prepare ) throws IOException {
        final JsonObject body = RestJson.parseJsonBody( req, resp );
        if ( body == null ) {
            return;
        }
        final String supported = String.join( ", ", actions.keySet() );
        final String name = RestJson.getJsonString( body, "action" );
        if ( name == null || name.isBlank() ) {
            RestJson.sendError( resp, HttpServletResponse.SC_BAD_REQUEST,
                    "action is required (supported: " + supported + ")" );
            return;
        }
        final Action action = actions.get( name );
        if ( action == null ) {
            RestJson.sendError( resp, HttpServletResponse.SC_BAD_REQUEST,
                    "Unsupported action '" + name + "' — supported: " + supported );
            return;
        }
        final JsonArray ids = requireIds( body, resp );
        if ( ids == null ) {
            return;
        }
        if ( prepare != null && !prepare.run( name, body, resp ) ) {
            return;
        }

        final Principal principal = req.getUserPrincipal();
        final String actor = principal != null ? principal.getName() : null;
        final BulkActionResult result = new BulkActionResult();
        for ( final JsonElement idEl : ids ) {
            applyOne( action, idEl, actor, result );
        }

        final String suffix = action.logSuffix() == null ? "" : action.logSuffix().apply( body );
        LOG.info( "bulk action={} resource={} actor={} attempted={} succeeded={} failed={}{}",
                name, resource, actor, ids.size(), result.succeededCount(), result.failedCount(), suffix );
        RestJson.sendJson( resp, result.toResponseBody( ids.size(), action.pastTense() ) );
    }

    private static JsonArray requireIds( final JsonObject body, final HttpServletResponse resp ) throws IOException {
        final JsonElement idsEl = body.get( "ids" );
        if ( idsEl == null || !idsEl.isJsonArray() ) {
            RestJson.sendError( resp, HttpServletResponse.SC_BAD_REQUEST,
                    "ids is required and must be a JSON array" );
            return null;
        }
        final JsonArray ids = idsEl.getAsJsonArray();
        if ( ids.isEmpty() ) {
            RestJson.sendError( resp, HttpServletResponse.SC_BAD_REQUEST, "ids must not be empty" );
            return null;
        }
        return ids;
    }

    private static void applyOne( final Action action, final JsonElement idEl, final String actor,
                                  final BulkActionResult result ) {
        final String id = idEl.isJsonPrimitive() ? idEl.getAsString() : null;
        if ( id == null || id.isBlank() ) {
            result.fail( idEl.toString(), action.blankIdMessage() );
            return;
        }
        try {
            action.perId().apply( id, result );
        } catch ( final Exception e ) {
            // The handlers record their own failures; this is the net for one that escapes. The cause stays in
            // the log, never in the response.
            LOG.warn( "bulk action={} id={} actor={} failed unexpectedly: {}", action.name(), id, actor,
                    e.getMessage(), e );
            result.fail( id, "Action failed; see the server log." );
        }
    }
}
