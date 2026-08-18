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
package com.wikantik.rest.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.rest.RestJson;
import com.wikantik.rest.RestServletBase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Small, dependency-free JSON/request helpers shared by the {@code com.wikantik.rest.knowledge}
 * admin handler classes.
 * <p>
 * {@code sendJson}, {@code sendError}, {@code sendNotFound}, {@code parseJsonBody},
 * {@code getJsonString}, {@code getJsonDouble}, {@code getJsonInt}, and {@code parseIntParam}
 * below are thin package-private delegations to {@link com.wikantik.rest.RestJson} — the
 * {@code public static} home for logic that {@link com.wikantik.rest.RestServletBase} also
 * delegates to for its own {@code protected} instance methods. Both call sites now share exactly
 * one implementation instead of each carrying a hand-maintained "verbatim copy" that could
 * silently drift apart. The handler classes in this package keep calling {@code AdminKnowledgeIo}
 * rather than {@code RestJson} directly, so this class's role narrows from "duplicate
 * implementation" to "visibility shim" for the six handler classes below, which are neither in
 * {@code com.wikantik.rest} nor subclasses of {@code RestServletBase}.
 * <p>
 * {@code parseUuid}, {@code actor}, and {@code resolveEdgeNames} are the exceptions: each is
 * genuinely local to the pre-extraction {@code AdminKnowledgeResource} (not inherited from
 * {@code RestServletBase}) and was shared by more than one handler group as they were extracted
 * across Tasks 1–3, so each moved here verbatim as the single canonical copy rather than being
 * duplicated per handler class.
 * <p>
 * <b>Visibility (as of the Task 3 decomposition, which extracted the final two handler
 * groups):</b> the class and every member here are package-private. Every caller — the six
 * {@code com.wikantik.rest.knowledge} handler classes — lives in this same package, and
 * {@code AdminKnowledgeResource} (a different package, {@code com.wikantik.rest}) no longer calls
 * any of them directly (it only constructs and dispatches to the handler classes). Confirmed via a
 * repo-wide grep for {@code AdminKnowledgeIo.} outside this package before narrowing.
 */
final class AdminKnowledgeIo {

    private static final Logger LOG = LogManager.getLogger( AdminKnowledgeIo.class );

    /** Shared Gson instance — same configuration as {@link RestServletBase#GSON}. */
    static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter( Date.class, RestServletBase.UTC_ISO_DATE_SERIALIZER )
            .create();

    /** Same shape as the {@code Map<String, Object>} type token used throughout AdminKnowledgeResource. */
    static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() {}.getType();

    private AdminKnowledgeIo() {
    }

    /** Verbatim copy of {@code AdminKnowledgeResource#parseUuid} — genuinely local helper, shared
     *  across handlers that stayed on the resource and handlers that moved out of it. */
    static UUID parseUuid( final String str, final HttpServletResponse response ) throws IOException {
        try {
            return UUID.fromString( str );
        } catch ( final IllegalArgumentException e ) {
            LOG.info( "Rejecting request with malformed UUID '{}': {}", str, e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID: " + str );
            return null;
        }
    }

    /** Delegates to {@link RestJson#sendJson}. */
    static void sendJson( final HttpServletResponse response, final Object object ) throws IOException {
        RestJson.sendJson( response, object );
    }

    /** Delegates to {@link RestJson#sendError}. */
    static void sendError( final HttpServletResponse response, final int status, final String message )
            throws IOException {
        RestJson.sendError( response, status, message );
    }

    /** Delegates to {@link RestJson#sendNotFound}. */
    static void sendNotFound( final HttpServletResponse response, final String message ) throws IOException {
        RestJson.sendNotFound( response, message );
    }

    /** Delegates to {@link RestJson#parseJsonBody}. */
    static JsonObject parseJsonBody( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        return RestJson.parseJsonBody( request, response );
    }

    /** Delegates to {@link RestJson#getJsonString}. */
    static String getJsonString( final JsonObject obj, final String key ) {
        return RestJson.getJsonString( obj, key );
    }

    /** Delegates to {@link RestJson#getJsonDouble}. */
    static double getJsonDouble( final JsonObject obj, final String key, final double def ) {
        return RestJson.getJsonDouble( obj, key, def );
    }

    /** Delegates to {@link RestJson#parseIntParam}. */
    static int parseIntParam( final HttpServletRequest request, final String paramName, final int defaultValue ) {
        return RestJson.parseIntParam( request, paramName, defaultValue );
    }

    /** Delegates to {@link RestJson#getJsonInt}. */
    static int getJsonInt( final JsonObject obj, final String key, final int def ) {
        return RestJson.getJsonInt( obj, key, def );
    }

    /** Verbatim copy of {@code AdminKnowledgeResource#actor} — genuinely local helper, shared by
     *  both the node and edge admin handler groups. */
    static String actor( final HttpServletRequest request ) {
        final String remoteUser = request.getRemoteUser();
        return remoteUser != null ? remoteUser : "admin";
    }

    /** Verbatim copy of {@code AdminKnowledgeResource#resolveEdgeNames} — genuinely local helper,
     *  shared by both the node and edge admin handler groups. */
    static Map< UUID, String > resolveEdgeNames( final KnowledgeGraphService service,
                                                          final List< KgEdge > edges ) {
        final Set< UUID > ids = new HashSet<>();
        for ( final KgEdge e : edges ) {
            ids.add( e.sourceId() );
            ids.add( e.targetId() );
        }
        return service.getNodeNames( ids );
    }
}
