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
package com.wikantik.livevalues;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.livevalues.LiveValues;

import java.util.LinkedHashMap;
import java.util.Map;

/** Expands live-value tokens in the one frontmatter field that may carry them, {@code summary}. */
public final class LiveFrontmatter {

    private LiveFrontmatter() {
    }

    /**
     * @return a copy of {@code parsed} whose {@code summary} has its tokens expanded and everything else untouched;
     *         {@code parsed} itself (same instance) when {@code lv} is null or there is no string summary
     */
    public static ParsedPage expandSummary( final ParsedPage parsed, final LiveValues lv ) {
        if( lv == null || parsed == null || !( parsed.metadata().get( "summary" ) instanceof String summary ) ) {
            return parsed;
        }
        final String expanded = lv.expandText( summary );
        if( summary.equals( expanded ) ) {
            return parsed;
        }
        final Map< String, Object > copy = new LinkedHashMap<>( parsed.metadata() );
        copy.put( "summary", expanded );
        return new ParsedPage( copy, parsed.body() );
    }

    /**
     * Expands the summary and, code-aware, the body: for the reader-facing copies of a page (SSR head, no-JS
     * fallback, data island), none of which may show a raw token. Same instance when {@code lv} is null.
     */
    public static ParsedPage expandForReaders( final ParsedPage parsed, final LiveValues lv ) {
        if( lv == null || parsed == null ) {
            return parsed;
        }
        final ParsedPage withSummary = expandSummary( parsed, lv );
        final String body = withSummary.body();
        final String expandedBody = body == null ? null : lv.expandBody( body );
        if( expandedBody == null || expandedBody.equals( body ) ) {
            return withSummary;
        }
        return new ParsedPage( withSummary.metadata(), expandedBody );
    }

    /**
     * Expands a whole page text (frontmatter block plus body) for a reader surface that hands out the text as
     * written: tokens in the {@code summary} field and, code-aware, in the body; no other frontmatter field changes.
     * Same string when {@code lv} is null.
     */
    public static String expandRawText( final String text, final LiveValues lv ) {
        if( lv == null || text == null || text.isEmpty() ) {
            return text;
        }
        final int offset = FrontmatterParser.bodyOffset( text );
        return expandSummaryLines( text.substring( 0, offset ), lv ) + lv.expandBody( text.substring( offset ) );
    }

    /** The frontmatter block with the {@code summary:} field (and its indented continuation lines) expanded. */
    private static String expandSummaryLines( final String header, final LiveValues lv ) {
        if( header.isEmpty() ) {
            return header;
        }
        final StringBuilder out = new StringBuilder( header.length() );
        boolean inSummary = false;
        for( final String line : header.split( "(?<=\\n)" ) ) {
            if( line.startsWith( "summary:" ) ) {
                inSummary = true;
            } else if( !( inSummary && !line.isEmpty() && Character.isWhitespace( line.charAt( 0 ) ) ) ) {
                inSummary = false;
            }
            out.append( inSummary ? lv.expandText( line ) : line );
        }
        return out.toString();
    }
}
