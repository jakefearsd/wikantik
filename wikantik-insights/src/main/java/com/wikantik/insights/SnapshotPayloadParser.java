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
package com.wikantik.insights;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses one jakemon visibility snapshot (one JSON document per {@code (engine, site, date)})
 * into {@link VisibilityRow}s.
 *
 * <p>This is the first thing a public-adjacent write surface touches ({@code POST
 * /admin/insights/ingest} is admin-authed but LAN-facing, fed by an external shipper), so the
 * contract is: it never throws. A malformed payload, an unknown engine, or a site not on the
 * allowlist is counted as rejected rather than propagated as an exception -- a bad shipper run
 * must not be able to 500 the endpoint or take down the ingest pipeline.</p>
 */
public final class SnapshotPayloadParser {

    private static final Logger LOG = LogManager.getLogger( SnapshotPayloadParser.class );

    /** Wildcard allowlist entry meaning "accept every value" (used for the site allowlist's
     *  default of {@code *} -- jakemon polls eight sites and every one is retained; see
     *  ClusterDeclarationDesign-adjacent D10 in the content-intelligence spec). */
    private static final String WILDCARD = "*";

    /** One parse outcome: the rows successfully extracted, and a count of rejections.
     *  {@code rejected} counts whole-payload rejections (bad JSON shape, disallowed
     *  engine/site) as 1, and also counts any individual row dropped by the allowlist
     *  guard in {@link VisibilityRow#of}. */
    public record ParseResult( List< VisibilityRow > rows, int rejected ) {}

    private SnapshotPayloadParser() {}

    /**
     * Strips scheme, host, and fragment from a {@code by_page} key so {@code page_path} is a
     * stable key regardless of whether the upstream engine reports a full URL or a bare path.
     * Also strips a trailing slash (except for the root path) so {@code /wiki/A} and
     * {@code /wiki/A/} converge on one row.
     */
    static String normalisePath( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return "";
        }
        String path = raw;
        try {
            final URI uri = URI.create( raw.trim() );
            if ( uri.getPath() != null && !uri.getPath().isBlank() ) {
                path = uri.getPath();
            }
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "unparseable page key '{}', using it verbatim: {}", raw, e.getMessage() );
        }
        final int hash = path.indexOf( '#' );
        if ( hash >= 0 ) {
            path = path.substring( 0, hash );
        }
        return path.length() > 1 && path.endsWith( "/" )
                ? path.substring( 0, path.length() - 1 ) : path;
    }

    /**
     * Parses a jakemon visibility snapshot into {@link VisibilityRow}s.
     *
     * @param json           the raw snapshot JSON document
     * @param allowedEngines allowed {@code engine} values, or a set containing {@code "*"} to
     *                       allow any engine
     * @param allowedSites   allowed {@code site} values, or a set containing {@code "*"} to
     *                       allow any site
     * @return the rows extracted and a count of rejections; never throws
     */
    public static ParseResult parse( final String json, final Set< String > allowedEngines,
                                     final Set< String > allowedSites ) {
        try {
            final JsonObject o = JsonParser.parseString( json ).getAsJsonObject();
            final String engine = o.get( "engine" ).getAsString();
            final String site   = o.get( "site" ).getAsString();
            if ( !isAllowed( allowedEngines, engine ) || !isAllowed( allowedSites, site ) ) {
                LOG.warn( "rejecting snapshot for engine={} site={} (not allowlisted)", engine, site );
                return new ParseResult( List.of(), 1 );
            }
            final LocalDate date = LocalDate.parse( o.get( "snapshot_date" ).getAsString() );
            final int window = o.has( "window_days" ) ? o.get( "window_days" ).getAsInt() : 28;

            final List< VisibilityRow > rows = new ArrayList<>();
            int rowsRejected = 0;
            rowsRejected += collect( rows, o.getAsJsonArray( "by_page" ),  date, window, engine, site, true );
            rowsRejected += collect( rows, o.getAsJsonArray( "by_query" ), date, window, engine, site, false );
            rowsRejected += collectQueryPage( rows, o.getAsJsonArray( "query_page" ),
                    date, window, engine, site );
            return new ParseResult( rows, rowsRejected );
        } catch ( final RuntimeException e ) {
            LOG.warn( "rejecting malformed visibility snapshot: {}", e.getMessage() );
            return new ParseResult( List.of(), 1 );
        }
    }

    private static boolean isAllowed( final Set< String > allowlist, final String value ) {
        return allowlist.contains( WILDCARD ) || allowlist.contains( value );
    }

    /**
     * Collects one {@code by_page} or {@code by_query} array into {@code out}. A missing or
     * absent array (Gson returns {@code null} for a key that was never present) yields no rows
     * -- Yandex, for example, ships an empty {@code by_page} array for some sites, and every
     * engine can simply omit a key rather than send {@code []}.
     *
     * @return the number of rows in {@code arr} that were dropped by the
     *         {@link VisibilityRow#of} allowlist guard (blank engine/site at the type boundary)
     */
    private static int collect( final List< VisibilityRow > out, final JsonArray arr,
                                final LocalDate date, final int window, final String engine,
                                final String site, final boolean isPage ) {
        if ( arr == null ) {
            return 0;
        }
        int rejected = 0;
        for ( final JsonElement el : arr ) {
            final JsonObject r = el.getAsJsonObject();
            final String key = r.get( "key" ).getAsString();
            final VisibilityRow row = VisibilityRow.of(
                    new VisibilityRow.SnapshotWindow( date, window ), engine, site,
                    isPage ? normalisePath( key ) : "",
                    isPage ? "" : key,
                    r.get( "impressions" ).getAsInt(),
                    r.get( "clicks" ).getAsInt(),
                    r.has( "position" ) ? r.get( "position" ).getAsDouble() : null );
            if ( row == null ) {
                LOG.warn( "dropping visibility row for engine={} site={} key='{}': blank engine/site "
                        + "at the VisibilityRow.of allowlist guard", engine, site, key );
                rejected++;
            } else {
                out.add( row );
            }
        }
        return rejected;
    }

    /**
     * Collects the {@code query_page} cross product -- rows carrying <em>both</em> a page and a
     * query.
     *
     * <p>This is the grain that {@code by_page} and {@code by_query} cannot express. Those two are
     * <strong>disjoint projections</strong>: a {@code by_page} row has no query and a
     * {@code by_query} row has no page, so nothing in the store attributes a query to a page.
     * Three things need that attribution and stay dormant without it -- the shared-query
     * restriction on {@code ENGINE_DIVERGENCE}, case (a) of {@code VOCABULARY_GAP}, and effect
     * measurement's {@code query_intersection} mode (design section 12.1, work item J1).</p>
     *
     * <p>These rows need no schema change: the fact table's primary key is already
     * {@code (snapshot_date, engine, site_host, page_path, query_text)}, so a row with both
     * populated slots in beside the two rollup projections rather than colliding with them.</p>
     *
     * <p>Unlike {@link #collect}, the fields are named {@code query} and {@code page} rather than a
     * single {@code key}, because a cross-product row has two identities and neither is "the" key.
     * A row missing either one identifies nothing joinable and is rejected rather than stored
     * against a blank.</p>
     */
    private static int collectQueryPage( final List< VisibilityRow > out, final JsonArray arr,
                                         final LocalDate date, final int window,
                                         final String engine, final String site ) {
        if ( arr == null ) {
            return 0;
        }
        int rejected = 0;
        for ( final JsonElement el : arr ) {
            final JsonObject r = el.getAsJsonObject();
            final String query = r.has( "query" ) ? r.get( "query" ).getAsString() : null;
            final String page  = r.has( "page" )  ? r.get( "page" ).getAsString()  : null;
            if ( query == null || query.isBlank() || page == null || page.isBlank() ) {
                LOG.warn( "dropping query_page row for engine={} site={}: needs both 'query' and "
                        + "'page' (got query='{}', page='{}')", engine, site, query, page );
                rejected++;
                continue;
            }
            final VisibilityRow row = VisibilityRow.of(
                    new VisibilityRow.SnapshotWindow( date, window ), engine, site,
                    normalisePath( page ), query,
                    r.has( "impressions" ) ? r.get( "impressions" ).getAsInt() : 0,
                    r.has( "clicks" ) ? r.get( "clicks" ).getAsInt() : 0,
                    r.has( "position" ) ? r.get( "position" ).getAsDouble() : null );
            if ( row == null ) {
                LOG.warn( "dropping query_page row for engine={} site={} query='{}': blank "
                        + "engine/site at the VisibilityRow.of allowlist guard", engine, site, query );
                rejected++;
            } else {
                out.add( row );
            }
        }
        return rejected;
    }
}
