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
package com.wikantik.knowledge.bundle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.knowledge.extraction.OllamaChatRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Listwise reranker backed by an Ollama chat model (gemma4:e4b, think:false). The responder
 *  function is injectable for tests; production uses the real HTTP call. */
public final class LlmSectionReranker implements SectionReranker {

    private static final Logger LOG = LogManager.getLogger( LlmSectionReranker.class );
    private static final Gson GSON = new Gson();

    private final RerankerConfig config;
    private final Function< String, String > responder;  // prompt -> model text

    /** Production constructor: real HTTP responder. */
    public LlmSectionReranker( final RerankerConfig config ) {
        this( config, null );
    }

    /** Test/injected constructor. {@code responder == null} uses the real HTTP call. */
    public LlmSectionReranker( final RerankerConfig config, final Function< String, String > responder ) {
        this.config = config;
        this.responder = responder != null ? responder : this::callOllama;
    }

    @Override
    public List< CandidateSection > rerank( final String query, final List< CandidateSection > sections ) {
        if ( sections == null || sections.size() <= 1 ) return sections;
        try {
            final String text = responder.apply( buildPrompt( query, sections ) );
            final List< Integer > order = parseRanking( text, sections.size() );
            if ( order.isEmpty() ) return sections;
            final List< CandidateSection > out = new ArrayList<>( sections.size() );
            final boolean[] used = new boolean[ sections.size() ];
            for ( final int oneBased : order ) {
                final int i = oneBased - 1;
                if ( i >= 0 && i < sections.size() && !used[ i ] ) { out.add( sections.get( i ) ); used[ i ] = true; }
            }
            for ( int i = 0; i < sections.size(); i++ ) if ( !used[ i ] ) out.add( sections.get( i ) );
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Section rerank failed ({}); using dense order", e.getMessage() );
            return sections;
        }
    }

    private static String buildPrompt( final String query, final List< CandidateSection > s ) {
        final StringBuilder sb = new StringBuilder( "Query: " ).append( query )
            .append( "\n\nRank the passages from MOST to LEAST relevant to the query. "
                + "Respond ONLY as JSON: {\"ranking\":[passage numbers, best first]}.\n\nPassages:\n" );
        for ( int i = 0; i < s.size(); i++ ) {
            sb.append( i + 1 ).append( ". [" ).append( String.join( " > ", s.get( i ).headingPath() ) )
              .append( "] " ).append( truncate( s.get( i ).text(), 240 ) ).append( '\n' );
        }
        return sb.toString();
    }

    private static List< Integer > parseRanking( final String text, final int n ) {
        final List< Integer > out = new ArrayList<>();
        try {
            final JsonObject o = JsonParser.parseString( text ).getAsJsonObject();
            o.getAsJsonArray( "ranking" ).forEach( el -> {
                final int v = el.getAsInt();
                if ( v >= 1 && v <= n && !out.contains( v ) ) out.add( v );
            } );
        } catch ( final RuntimeException ignored ) {
            // malformed -> empty -> caller keeps dense order
        }
        return out;
    }

    private String callOllama( final String prompt ) {
        final var body = OllamaChatRequest.body( config.model(), "", prompt, null );
        final HttpRequest req = HttpRequest.newBuilder( URI.create( stripSlash( config.baseUrl() ) + "/api/chat" ) )
            .timeout( Duration.ofMillis( config.timeoutMs() ) )
            .header( "Content-Type", "application/json" )
            .POST( HttpRequest.BodyPublishers.ofString( GSON.toJson( body ) ) )
            .build();
        try {
            final HttpResponse< String > res = HttpClient.newHttpClient()
                .send( req, HttpResponse.BodyHandlers.ofString() );
            if ( res.statusCode() / 100 != 2 ) { LOG.warn( "Rerank HTTP {}", res.statusCode() ); return ""; }
            return JsonParser.parseString( res.body() ).getAsJsonObject()
                .getAsJsonObject( "message" ).get( "content" ).getAsString();
        } catch ( final java.io.IOException e ) {
            LOG.warn( "Rerank IO error: {}", e.getMessage() ); return "";
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt(); return "";
        }
    }

    private static String truncate( final String s, final int n ) { return s.length() <= n ? s : s.substring( 0, n ); }
    private static String stripSlash( final String s ) { return s.endsWith( "/" ) ? s.substring( 0, s.length() - 1 ) : s; }
}
