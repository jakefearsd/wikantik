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

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.mentions.Mention;
import com.wikantik.mentions.MentionScanner;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code POST /api/mentions/scan} — outgoing unlinked mentions in an editor draft: phrases naming another page
 * (name, title or alias) that the draft doesn't link yet. Only pages the caller can view are reported.
 */
public class MentionScanResource extends RestServletBase {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( MentionScanResource.class );

    /** 1 MiB of request characters. */
    static final int MAX_CHARS = 1 << 20;

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String raw = readBounded( request );
        if ( raw == null ) {
            sendError( response, 413, "Draft too large to scan (limit 1 MB)" );
            return;
        }
        final JsonObject body;
        try {
            body = JsonParser.parseString( raw ).getAsJsonObject();
        } catch ( final JsonParseException | IllegalStateException e ) {
            LOG.info( "Rejecting malformed mention-scan body: {}", e.getMessage() );
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Request body must be a JSON object" );
            return;
        }
        final String text = getJsonString( body, "text" );
        if ( text == null || text.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required" );
            return;
        }
        final Optional< PageTitleLookup > lookup = titleLookup();
        if ( lookup.isEmpty() ) {
            sendError( response, 503, "Title index is warming up" );
            return;
        }
        final String page = Optional.ofNullable( getJsonString( body, "page" ) ).orElse( "" );
        final List< Mention > all = MentionScanner.scan( text, page, lookup.get().entries() );
        final Set< String > viewable = filterViewable( request, all.stream().map( Mention::target ).distinct().toList() );
        final List< Map< String, Object > > out = all.stream()
                .filter( m -> viewable.contains( m.target() ) )
                .limit( MentionScanner.MAX_RESULTS )
                .map( MentionScanResource::toJson )
                .toList();
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "mentions", out );
        sendJson( response, payload );
    }

    /** Overridable for tests; production reads the structural index's title lookup. */
    protected Optional< PageTitleLookup > titleLookup() {
        try {
            final StructuralIndexService idx = getSubsystems().pageGraph().structuralIndexService();
            return idx == null ? Optional.empty() : idx.titleLookup();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Title lookup unavailable for mention scan: {}", e.getMessage(), e );
            return Optional.empty();
        }
    }

    /** The body as a string, or {@code null} when it exceeds {@link #MAX_CHARS}. */
    private static String readBounded( final HttpServletRequest request ) throws IOException {
        final BufferedReader reader = request.getReader();
        final StringBuilder sb = new StringBuilder();
        final char[] buf = new char[ 8192 ];
        int n;
        while ( ( n = reader.read( buf ) ) != -1 ) {
            sb.append( buf, 0, n );
            if ( sb.length() > MAX_CHARS ) {
                return null;
            }
        }
        return sb.toString();
    }

    private static Map< String, Object > toJson( final Mention m ) {
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "target", m.target() );
        o.put( "title", m.title() );
        o.put( "phrase", m.phrase() );
        o.put( "from", m.from() );
        o.put( "to", m.to() );
        o.put( "line", m.line() );
        o.put( "context", m.context() );
        o.put( "more", m.more() );
        return o;
    }
}
