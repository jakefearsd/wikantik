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
package com.wikantik.extractcli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *  Reads a live wiki's corpus from {@code GET /api/structure/sitemap} into a
 *  {@link CorpusSnapshot}.
 *
 *  <p>The structural sitemap returns every indexed page — canonical id, slug, type and
 *  cluster — in a single unpaginated response, so the snapshot is complete by construction
 *  rather than assembled from pages that might each fail independently. That is deliberate:
 *  the transport this replaces ({@code bin/remote.sh pages-pull}) failed per-file and
 *  returned the survivors, which made a partial corpus indistinguishable from a small one.</p>
 *
 *  <p>Every failure mode marks the snapshot incomplete rather than yielding a smaller corpus:
 *  a transport error, unparseable JSON, or a response holding fewer pages than the server's
 *  own {@code count} claims.</p>
 */
public final class RemoteCorpusSource {

    /** Path fetched to build the snapshot. */
    public static final String SITEMAP_PATH = "/api/structure/sitemap";

    /** The HTTP seam, so the parsing and completeness rules can be tested without a server. */
    @FunctionalInterface
    public interface Fetcher {
        /** @return the response body for the given wiki-relative path */
        String get( String path ) throws IOException;
    }

    private final Fetcher fetcher;

    public RemoteCorpusSource( final Fetcher fetcher ) {
        this.fetcher = fetcher;
    }

    /** Builds a fetcher that talks to a live wiki over HTTP. */
    public static Fetcher httpFetcher( final String baseUrl ) {
        final HttpClient client = HttpClient.newBuilder()
                                            .connectTimeout( Duration.ofSeconds( 10 ) )
                                            .build();
        final String root = baseUrl.endsWith( "/" )
                ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl;
        return path -> {
            try {
                final HttpRequest request = HttpRequest.newBuilder( URI.create( root + path ) )
                                                       .timeout( Duration.ofSeconds( 60 ) )
                                                       .header( "Accept", "application/json" )
                                                       .GET().build();
                final HttpResponse< String > response =
                        client.send( request, HttpResponse.BodyHandlers.ofString() );
                if ( response.statusCode() != 200 ) {
                    throw new IOException( "HTTP " + response.statusCode() + " for " + path );
                }
                return response.body();
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IOException( "interrupted fetching " + path, e );
            }
        };
    }

    /** Loads the remote corpus. Never throws — failures are recorded on the snapshot. */
    public CorpusSnapshot load() {
        final Map< String, PageFacts > pages = new LinkedHashMap<>();
        final List< String > errors = new ArrayList<>();
        try {
            final JsonObject data = JsonParser.parseString( fetcher.get( SITEMAP_PATH ) )
                                              .getAsJsonObject()
                                              .getAsJsonObject( "data" );
            final JsonArray arr = data.getAsJsonArray( "pages" );
            for ( final JsonElement el : arr ) {
                final JsonObject o = el.getAsJsonObject();
                final String slug = str( o, "slug" );
                if ( slug == null ) {
                    continue;
                }
                pages.put( slug, new PageFacts( slug, str( o, "id" ), str( o, "cluster" ),
                                                 normaliseType( str( o, "type" ) ) ) );
            }
            final int claimed = data.has( "count" ) ? data.get( "count" ).getAsInt() : arr.size();
            if ( arr.size() < claimed ) {
                errors.add( "truncated response: server reported " + claimed
                                    + " pages but only " + arr.size() + " arrived" );
            }
        } catch ( final IOException | RuntimeException e ) {
            errors.add( "failed to read " + SITEMAP_PATH + ": " + e.getMessage() );
        }
        return new CorpusSnapshot( "prod", pages, errors );
    }

    /**
     *  The API always emits a {@code type}, serialising an absent one as
     *  {@code PageType.UNKNOWN} → {@code "unknown"}. The repository just omits the field.
     *  Both mean untyped, so folding {@code "unknown"} to {@code null} keeps the report free
     *  of a false positive on every system page.
     */
    private static String normaliseType( final String type ) {
        return "unknown".equals( type ) ? null : type;
    }

    private static String str( final JsonObject o, final String field ) {
        if ( !o.has( field ) || o.get( field ).isJsonNull() ) {
            return null;
        }
        final String s = o.get( field ).getAsString().trim();
        return s.isEmpty() ? null : s;
    }
}
