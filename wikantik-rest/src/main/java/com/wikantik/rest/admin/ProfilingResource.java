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
package com.wikantik.rest.admin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.observability.JfrProfilingService;
import com.wikantik.observability.JfrProfilingService.RecordingInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Backing logic for {@code /admin/profiling/jfr/*}. Behind {@code AdminAuthFilter}
 * (AllPermission) — no extra auth check inside this class.
 *
 * <p>Maps {@link JfrProfilingService} exceptions to HTTP-status-carrying
 * {@link RestError}s so the servlet adapter emits the right code:</p>
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} → 400 (bad duration / bad recording id)
 *       — except for unknown-recording-id on {@code stop}, which is 404.</li>
 *   <li>{@link IllegalStateException} → 409 (concurrent recording)</li>
 * </ul>
 */
public final class ProfilingResource {

    private static final Gson GSON = new Gson();

    private final JfrProfilingService svc;

    public ProfilingResource( final JfrProfilingService svc ) {
        if ( svc == null ) throw new IllegalArgumentException( "svc must not be null" );
        this.svc = svc;
    }

    public String start( final int durationSeconds, final String label ) {
        try {
            return toJson( svc.start( durationSeconds, label ) );
        } catch ( final IllegalArgumentException e ) {
            throw new RestError( 400, e.getMessage() );
        } catch ( final IllegalStateException e ) {
            throw new RestError( 409, e.getMessage() );
        }
    }

    public String stop( final String recordingId ) {
        try {
            return toJson( svc.stop( recordingId ) );
        } catch ( final IllegalArgumentException e ) {
            // The service throws IllegalArgumentException both for null id and
            // for unknown id. The unknown-id case is the user-facing 404 path;
            // null id is a programming error but also maps to 404 because the
            // REST surface only sees one path through here.
            throw new RestError( 404, e.getMessage() );
        }
    }

    public String list() {
        final List< RecordingInfo > all = svc.list();
        final JsonArray arr = new JsonArray();
        for ( final RecordingInfo r : all ) arr.add( toJsonObject( r ) );
        return arr.toString();
    }

    /** Path to the underlying file for streaming — used by the servlet's download handler. */
    public Path filePathFor( final String recordingId ) {
        final RecordingInfo r = svc.get( recordingId );
        if ( r == null ) throw new RestError( 404, "Unknown recording_id: " + recordingId );
        return r.filePath();
    }

    /** Returns the {@link RecordingInfo} or throws 404 — used by tests. */
    public RecordingInfo getInfo( final String recordingId ) {
        final RecordingInfo r = svc.get( recordingId );
        if ( r == null ) throw new RestError( 404, "Unknown recording_id: " + recordingId );
        return r;
    }

    private String toJson( final RecordingInfo r ) {
        return GSON.toJson( toJsonObject( r ) );
    }

    private JsonObject toJsonObject( final RecordingInfo r ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "recording_id", r.recordingId() );
        o.addProperty( "start_time", r.startTime().toString() );
        o.addProperty( "duration_s", r.durationSeconds() );
        o.addProperty( "label", r.label() );
        o.addProperty( "file_path", r.filePath().toString() );
        o.addProperty( "size_bytes", r.sizeBytes() );
        o.addProperty( "status", r.status() );
        return o;
    }

    /** Carries an HTTP status to the servlet adapter. */
    public static final class RestError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        public RestError( final int status, final String message ) {
            super( message );
            this.status = status;
        }
        public int status() { return status; }
    }
}
