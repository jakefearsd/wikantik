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
package com.wikantik.http.analytics;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Beacon checks and the upstream request for {@code /analytics/api/send}, kept apart from the servlet's flow. */
final class AnalyticsBeacons {

    private static final Logger LOG = LogManager.getLogger( AnalyticsBeacons.class );
    private static final int MAX_CACHE_TOKEN_CHARS = 4096;

    private AnalyticsBeacons() { }

    /** True when the body is a JSON object whose {@code payload.website} is exactly {@code websiteId}. */
    static boolean carriesWebsite( final byte[] body, final String websiteId ) {
        try {
            final JsonElement root = JsonParser.parseString( new String( body, StandardCharsets.UTF_8 ) );
            if( !root.isJsonObject() || !root.getAsJsonObject().has( "payload" ) ) {
                return false;
            }
            final JsonElement payload = root.getAsJsonObject().get( "payload" );
            if( !payload.isJsonObject() || !payload.getAsJsonObject().has( "website" ) ) {
                return false;
            }
            final JsonElement w = payload.getAsJsonObject().get( "website" );
            return w.isJsonPrimitive() && websiteId.equals( w.getAsString() );
        } catch( final JsonParseException | IllegalStateException | UnsupportedOperationException e ) {
            LOG.debug( "Rejecting malformed analytics beacon body: {}", e.toString() );
            return false;
        }
    }

    /**
     * The upstream POST: the reader's address (never an incoming X-Forwarded-For), user agent and the tracker's
     * x-umami-cache token are relayed. Without that token Umami re-derives the visit id from an hourly salt and
     * splits visits at hour boundaries.
     */
    static HttpRequest sendRequest( final URI uri, final Duration timeout, final HttpServletRequest req,
                                    final byte[] body ) {
        final String contentType = req.getContentType();
        final HttpRequest.Builder builder = HttpRequest.newBuilder( uri )
                .timeout( timeout )
                .header( "Content-Type", contentType != null ? contentType : "application/json" )
                .header( "X-Forwarded-For", req.getRemoteAddr() )
                .POST( HttpRequest.BodyPublishers.ofByteArray( body ) );
        final String userAgent = req.getHeader( "User-Agent" );
        if( userAgent != null ) {
            builder.header( "User-Agent", userAgent );
        }
        final String umamiCache = req.getHeader( "x-umami-cache" );
        if( umamiCache != null && !umamiCache.isEmpty() && umamiCache.length() <= MAX_CACHE_TOKEN_CHARS ) {
            builder.header( "x-umami-cache", umamiCache );
        }
        return builder.build();
    }
}
