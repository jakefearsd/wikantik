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
package com.wikantik.tools;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds LLM-friendly response fields (citation URL, summary, tags, snippet, body)
 * from raw page text and optional BM25 context fragments.
 *
 * <p>Snippet strategy: prefer the first Lucene context fragment (already centered on
 * matched terms); fall back to a leading slice of the body with whitespace collapsed
 * so the model always has something tangible to quote.</p>
 */
final class ResultShaper {

    private static final Logger LOG = LogManager.getLogger( ResultShaper.class );
    private static final int DEFAULT_SNIPPET_CHARS = 320;
    private static final int DEFAULT_BODY_TRUNCATION = 6000;

    private ResultShaper() {}

    /** Build the citation URL. Prefers the configured public base URL; falls back to the request host. */
    static String citationUrl( final String pageName, final HttpServletRequest request, final String publicBaseUrl ) {
        final String encoded = URLEncoder.encode( pageName, StandardCharsets.UTF_8 ).replace( "+", "%20" );
        if ( publicBaseUrl != null && !publicBaseUrl.isBlank() ) {
            final String trimmed = publicBaseUrl.endsWith( "/" )
                    ? publicBaseUrl.substring( 0, publicBaseUrl.length() - 1 )
                    : publicBaseUrl;
            return trimmed + "/wiki/" + encoded;
        }
        if ( request != null ) {
            final String scheme = request.getScheme();
            final String host = request.getServerName();
            final int port = request.getServerPort();
            final boolean defaultPort = ( "http".equals( scheme ) && port == 80 )
                    || ( "https".equals( scheme ) && port == 443 );
            final String authority = defaultPort ? host : host + ":" + port;
            return scheme + "://" + authority + "/wiki/" + encoded;
        }
        return "/wiki/" + encoded;
    }

    /** Parse frontmatter metadata from raw page text. Returns an empty map on any failure. */
    static Map< String, Object > frontmatter( final String rawText ) {
        if ( rawText == null || rawText.isEmpty() ) {
            return Map.of();
        }
        try {
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            final Map< String, Object > meta = parsed.metadata();
            return meta != null ? meta : Map.of();
        } catch ( final Exception e ) {
            LOG.warn( "Frontmatter metadata parse failed — returning empty map: {}", e.getMessage() );
            return Map.of();
        }
    }

    /** Strip YAML frontmatter so we only count/trim body characters. */
    static String bodyOnly( final String rawText ) {
        if ( rawText == null || rawText.isEmpty() ) {
            return "";
        }
        try {
            final ParsedPage parsed = FrontmatterParser.parse( rawText );
            return parsed.body() != null ? parsed.body() : rawText;
        } catch ( final Exception e ) {
            LOG.warn( "Frontmatter body parse failed — returning raw text: {}", e.getMessage() );
            return rawText;
        }
    }

    /**
     * Prefer the first Lucene context fragment. When none exists, return the first
     * {@value #DEFAULT_SNIPPET_CHARS} chars of body with whitespace collapsed.
     */
    static String snippet( final String[] contexts, final String rawBody ) {
        if ( contexts != null && contexts.length > 0 ) {
            final String first = contexts[ 0 ];
            if ( first != null && !first.isBlank() ) {
                return first.strip();
            }
        }
        if ( rawBody == null || rawBody.isEmpty() ) {
            return "";
        }
        final String collapsed = rawBody.replaceAll( "\\s+", " " ).strip();
        if ( collapsed.length() <= DEFAULT_SNIPPET_CHARS ) {
            return collapsed;
        }
        return collapsed.substring( 0, DEFAULT_SNIPPET_CHARS ) + "…";
    }

    /**
     * Truncate the body to at most {@code maxChars} characters. Returns the original
     * body untouched when under the limit; appends a single ellipsis otherwise.
     * Callers decide how to report truncation via {@link #wasTruncated}.
     */
    static String truncateBody( final String body, final int maxChars ) {
        if ( body == null ) {
            return "";
        }
        if ( maxChars <= 0 || body.length() <= maxChars ) {
            return body;
        }
        return body.substring( 0, maxChars ) + "…";
    }

    static boolean wasTruncated( final String body, final int maxChars ) {
        return body != null && maxChars > 0 && body.length() > maxChars;
    }

    static int defaultBodyTruncation() {
        return DEFAULT_BODY_TRUNCATION;
    }

    /** Copies {@code summary} and {@code tags} from frontmatter into the response map when present. */
    static void applyFrontmatter( final Map< String, Object > out, final Map< String, Object > frontmatter ) {
        if ( frontmatter == null || frontmatter.isEmpty() ) {
            return;
        }
        final Object summary = frontmatter.get( "summary" );
        if ( summary != null ) {
            out.put( "summary", summary.toString() );
        }
        final Object tags = frontmatter.get( "tags" );
        if ( tags != null ) {
            out.put( "tags", tags );
        }
    }

    /** Convenience helper producing a fresh ordered map. */
    static Map< String, Object > orderedMap() {
        return new LinkedHashMap<>();
    }
}
