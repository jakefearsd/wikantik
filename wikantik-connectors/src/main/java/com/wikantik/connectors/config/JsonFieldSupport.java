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
package com.wikantik.connectors.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Shape-proof JSON field read/write helpers shared by the per-type connector config codecs
 * ({@link WebCrawlerCodec}, {@link SitemapCodec}, {@link FeedCodec}, {@link DriveCodec},
 * {@link GithubCodec}, {@link ConfluenceCodec}). Field-shape validation helpers that build up a
 * field-keyed error map live in the sibling {@link ConfigValidationSupport} instead — kept
 * separate so neither class grows into a god class.
 *
 * <p>All read helpers are shape-proof: a value of the wrong JSON shape (object where a string is
 * expected, multi-element array where a scalar is expected, …) degrades to null / the default
 * instead of throwing, so validate() turns it into a field-keyed error — validate() must never
 * throw on any JsonObject input.
 */
final class JsonFieldSupport {

    private static final Logger LOG = LogManager.getLogger( JsonFieldSupport.class );

    /** Default crawl user agent, shared by the URL-fetching connector types (webcrawler/sitemap/feed). */
    static final String DEFAULT_USER_AGENT = "WikantikCrawler/1.0 (+https://wiki.wikantik.com)";

    private JsonFieldSupport() {}

    // ---- test-connection cap clamping --------------------------------------------------------

    /** Dry-run-safe cap clamp shared by every type's {@code max_pages}/{@code max_files}/{@code max_items}. */
    static int clampMax( final int value, final boolean forTest ) {
        return forTest ? Math.min( value, 3 ) : value;
    }

    // ---- typed config -> JSON helper ---------------------------------------------------------

    static void putStrList( final JsonObject json, final String key, final List< String > values ) {
        final JsonArray arr = new JsonArray();
        for ( final String v : values ) arr.add( v );
        json.add( key, arr );
    }

    // ---- JSON field read helpers --------------------------------------------------------------

    static String str( final JsonObject config, final String key ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return null;
        try {
            final String value = config.get( key ).getAsString().trim();
            return value.isBlank() ? null : value;
        } catch ( final UnsupportedOperationException | IllegalStateException | NumberFormatException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as a string ({}) — treating as absent", key, e.getMessage() );
            return null;
        }
    }

    static String strOr( final JsonObject config, final String key, final String def ) {
        final String value = str( config, key );
        return value == null ? def : value;
    }

    static int intVal( final JsonObject config, final String key, final int def ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return def;
        try {
            return config.get( key ).getAsInt();
        } catch ( final NumberFormatException | UnsupportedOperationException | IllegalStateException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as an int ({}) — using default {}", key, e.getMessage(), def );
            return def;
        }
    }

    static long longVal( final JsonObject config, final String key, final long def ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return def;
        try {
            return config.get( key ).getAsLong();
        } catch ( final NumberFormatException | UnsupportedOperationException | IllegalStateException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as a long ({}) — using default {}", key, e.getMessage(), def );
            return def;
        }
    }

    static boolean boolVal( final JsonObject config, final String key, final boolean def ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return def;
        try {
            return config.get( key ).getAsBoolean();
        } catch ( final UnsupportedOperationException | IllegalStateException | ClassCastException e ) {
            LOG.warn( "connector config field '{}': not readable as a boolean ({}) — using default {}", key, e.getMessage(), def );
            return def;
        }
    }

    static List< String > strList( final JsonObject config, final String key ) {
        if ( config == null || !config.has( key ) || config.get( key ).isJsonNull() ) return List.of();
        final JsonElement el = config.get( key );
        if ( !el.isJsonArray() ) return List.of();
        final List< String > out = new ArrayList<>();
        for ( final JsonElement item : el.getAsJsonArray() ) {
            if ( item.isJsonNull() ) continue;
            try {
                final String value = item.getAsString().trim();
                if ( !value.isEmpty() ) out.add( value );
            } catch ( final UnsupportedOperationException | IllegalStateException | NumberFormatException | ClassCastException e ) {
                LOG.warn( "connector config field '{}': list entry not readable as a string ({}) — skipping entry", key, e.getMessage() );
            }
        }
        return List.copyOf( out );
    }
}
