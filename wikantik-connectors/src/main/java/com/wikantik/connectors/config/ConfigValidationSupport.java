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

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * Field-keyed validation helpers shared by the per-type connector config codecs
 * ({@link WebCrawlerCodec}, {@link SitemapCodec}, {@link FeedCodec}, {@link DriveCodec},
 * {@link GithubCodec}, {@link ConfluenceCodec}). Each helper appends to a caller-supplied
 * {@code errors} map rather than throwing — the JSON-field-shape values themselves are read via
 * the sibling {@link JsonFieldSupport}, which is already shape-proof, so {@code validate()} must
 * never throw on any JsonObject input.
 */
final class ConfigValidationSupport {

    private ConfigValidationSupport() {}

    static void requireHttpUrlList( final Map< String, String > errors, final JsonObject config, final String key ) {
        final List< String > urls = JsonFieldSupport.strList( config, key );
        if ( urls.isEmpty() ) {
            errors.put( key, "at least one http(s) URL is required" );
            return;
        }
        for ( final String url : urls ) {
            if ( !isHttpUrl( url ) ) {
                errors.put( key, "all entries must be valid http(s) URLs" );
                return;
            }
        }
    }

    static void requireHttpUrl( final Map< String, String > errors, final JsonObject config, final String key ) {
        final String value = JsonFieldSupport.str( config, key );
        if ( value == null ) {
            errors.put( key, key + " is required" );
        } else if ( !isHttpUrl( value ) ) {
            errors.put( key, "must be a valid http(s) URL" );
        }
    }

    static void requireNonBlank( final Map< String, String > errors, final JsonObject config, final String key ) {
        if ( JsonFieldSupport.str( config, key ) == null ) {
            errors.put( key, key + " is required" );
        }
    }

    static void requireMinInt( final Map< String, String > errors, final JsonObject config, final String key, final int def, final int min ) {
        if ( JsonFieldSupport.intVal( config, key, def ) < min ) {
            errors.put( key, "must be >= " + min );
        }
    }

    static void requireMinLong( final Map< String, String > errors, final JsonObject config, final String key, final long def, final long min ) {
        if ( JsonFieldSupport.longVal( config, key, def ) < min ) {
            errors.put( key, "must be >= " + min );
        }
    }

    private static boolean isHttpUrl( final String value ) {
        try {
            final URI uri = new URI( value.trim() );
            final String scheme = uri.getScheme();
            return ( "http".equalsIgnoreCase( scheme ) || "https".equalsIgnoreCase( scheme ) ) && uri.getHost() != null;
        } catch ( final URISyntaxException e ) {
            return false;
        }
    }
}
