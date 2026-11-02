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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Where {@link AclShapeAuditCli} reads pages from: a directory of {@code *.md} files, or a live wiki's REST API. */
final class AclShapeAuditSources {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int PAGE_SIZE = 1_000;

    private AclShapeAuditSources() {
    }

    // ---- sources ----

    static AclShapeAuditCli.PageSource dirSource( final Path dir ) {
        return new AclShapeAuditCli.PageSource() {
            @Override
            public List< String > names() throws IOException {
                try ( Stream< Path > files = Files.list( dir ) ) {
                    return files.filter( p -> p.getFileName().toString().endsWith( ".md" ) )
                                .map( p -> p.getFileName().toString().replaceFirst( "\\.md$", "" ) ).sorted().toList();
                }
            }

            @Override
            public AclShapeAuditCli.Fetched fetch( final String name ) throws IOException {
                return new AclShapeAuditCli.Fetched( Files.readString( dir.resolve( name + ".md" ) ), List.of() );
            }
        };
    }

    /** A GET against the wiki with a fixed Authorization header, spaced at least {@code minIntervalMs} apart. */
    private static final class ThrottledGet {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 10 ) ).build();
        private final String root;
        private final String authorization;
        private final long minIntervalMs;
        private long last;

        ThrottledGet( final String baseUrl, final String authorization, final long minIntervalMs ) {
            this.root = baseUrl.endsWith( "/" ) ? baseUrl.substring( 0, baseUrl.length() - 1 ) : baseUrl;
            this.authorization = authorization;
            this.minIntervalMs = minIntervalMs;
        }

        String get( final String path ) throws IOException {
            try {
                final long wait = last + minIntervalMs - System.currentTimeMillis();
                if ( wait > 0 ) {
                    Thread.sleep( wait );
                }
                last = System.currentTimeMillis();
                final HttpRequest request = HttpRequest.newBuilder( URI.create( root + path ) )
                        .timeout( Duration.ofSeconds( 60 ) ).header( "Accept", "application/json" )
                        .header( "Authorization", authorization ).GET().build();
                final HttpResponse< String > response = client.send( request, HttpResponse.BodyHandlers.ofString() );
                if ( response.statusCode() != 200 ) {
                    throw new IOException( "HTTP " + response.statusCode() + " for " + path );
                }
                return response.body();
            } catch ( final InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IOException( "interrupted fetching " + path, e );
            }
        }
    }

    static AclShapeAuditCli.PageSource urlSource( final String baseUrl, final String authorization, final long minIntervalMs ) {
        final ThrottledGet http = new ThrottledGet( baseUrl, authorization, minIntervalMs );
        return new AclShapeAuditCli.PageSource() {
            @Override
            public List< String > names() throws IOException {
                return listNames( http );
            }

            @Override
            public AclShapeAuditCli.Fetched fetch( final String name ) throws IOException {
                final String encoded = URLEncoder.encode( name, StandardCharsets.UTF_8 ).replace( "+", "%20" );
                return parsePage( http.get( "/api/pages/" + encoded ) );
            }
        };
    }

    /** Every page name, paging {@code GET /api/pages} until the server's own total is reached. */
    private static List< String > listNames( final ThrottledGet http ) throws IOException {
        final List< String > names = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        try {
            while ( names.size() < total ) {
                final JsonObject o = JsonParser.parseString(
                        http.get( "/api/pages?limit=" + PAGE_SIZE + "&offset=" + names.size() ) ).getAsJsonObject();
                total = o.get( "total" ).getAsInt();
                final JsonArray pages = o.getAsJsonArray( "pages" );
                if ( pages.isEmpty() ) {
                    throw new IOException( "page list ended at " + names.size() + " of " + total );
                }
                for ( final JsonElement p : pages ) {
                    names.add( p.getAsJsonObject().get( "name" ).getAsString() );
                }
            }
        } catch ( final RuntimeException e ) {
            throw new IOException( "cannot list pages: " + e.getMessage(), e );
        }
        return names;
    }

    /** Rebuilds a page text from {@code GET /api/pages/{name}}'s JSON (see the class comment for what that loses). */
    static AclShapeAuditCli.Fetched parsePage( final String json ) {
        final JsonObject o = JsonParser.parseString( json ).getAsJsonObject();
        final String content = o.has( "content" ) && !o.get( "content" ).isJsonNull() ? o.get( "content" ).getAsString() : "";
        final StringBuilder text = new StringBuilder();
        if ( o.has( "metadata" ) && o.get( "metadata" ).isJsonObject() && !o.getAsJsonObject( "metadata" ).isEmpty() ) {
            final DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle( DumperOptions.FlowStyle.BLOCK );
            options.setWidth( Integer.MAX_VALUE );
            final Map< String, Object > fields = GSON.fromJson( o.get( "metadata" ), LinkedHashMap.class );
            text.append( "---\n" ).append( new Yaml( options ).dump( fields ) ).append( "---\n" );
        }
        text.append( content );
        final List< String > server = new ArrayList<>();
        if ( o.has( "accessRules" ) && o.get( "accessRules" ).isJsonObject() ) {
            for ( final JsonElement r : o.getAsJsonObject( "accessRules" ).getAsJsonArray( "reasons" ) ) {
                final JsonObject ro = r.getAsJsonObject();
                server.add( "server: " + ro.get( "shape" ).getAsString()
                        + ( ro.has( "line" ) ? " line " + ro.get( "line" ).getAsInt() : "" )
                        + ( ro.has( "field" ) ? " field " + ro.get( "field" ).getAsString() : "" )
                        + ": " + ro.get( "text" ).getAsString() );
            }
        }
        return new AclShapeAuditCli.Fetched( text.toString(), server );
    }
}
