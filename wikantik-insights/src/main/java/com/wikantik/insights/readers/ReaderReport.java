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
package com.wikantik.insights.readers;

import com.wikantik.jdbc.Jdbc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.wikantik.jdbc.NullableColumns.getNullableDouble;

/**
 * Window queries over the reader rollups ({@code reader_engagement_daily}, {@code reader_acquisition_daily})
 * and the search-visibility snapshots: the read side of reader analytics.
 */
public final class ReaderReport {

    private static final Logger LOG = LogManager.getLogger( ReaderReport.class );

    /** Landings on days of the month before this are excluded from the returning share (see the read-back). */
    static final int RETURNING_FROM_DAY_OF_MONTH = 8;
    static final String ORGANIC_SEARCH = "organic-search";

    /** One engine x page row of the search-clicks versus organic-landings comparison. */
    public record CoverageRow( String engine, String canonicalId, int searchClicks, int organicLandings ) { }

    /**
     * @param site per engine, organic landings over search clicks across the whole site
     * @param pages the per-page rows, for pages the snapshot paths resolve to
     */
    public record Coverage( Map< String, Double > site, List< CoverageRow > pages ) { }

    private static final String ENGAGEMENT_SQL = """
        SELECT canonical_id, views, visitors, exits, active_median_s, exits_scroll75, code_copies,
               wikilink_clicks, outbound_clicks, diagram_interacts, toc_clicks, sign_in_clicks,
               lcp_p75_ms, inp_p75_ms, cls_p75
          FROM reader_engagement_daily
         WHERE site_host = ? AND canonical_id <> '' AND day BETWEEN ? AND ?
        """;

    private static final String ACQUISITION_SQL = """
        SELECT canonical_id, channel, day, landings, continued, returning_landings
          FROM reader_acquisition_daily
         WHERE site_host = ? AND canonical_id <> '' AND day BETWEEN ? AND ?
        """;

    /** The latest snapshot per engine inside the window; its rows are the engine's rolling totals. */
    private static final String CLICKS_SQL = """
        SELECT s.engine, s.page_path, s.clicks
          FROM search_visibility_snapshot s
          JOIN (SELECT engine, MAX(snapshot_date) AS d FROM search_visibility_snapshot
                 WHERE site_host = ? AND snapshot_date BETWEEN ? AND ? GROUP BY engine) latest
            ON latest.engine = s.engine AND latest.d = s.snapshot_date
         WHERE s.site_host = ? AND s.query_text = ''
        """;

    private static final String ORGANIC_SQL = """
        SELECT source, canonical_id, SUM(landings) AS landings
          FROM reader_acquisition_daily
         WHERE site_host = ? AND channel = 'organic-search' AND day BETWEEN ? AND ?
         GROUP BY source, canonical_id
        """;

    private final Jdbc jdbc;
    private final PathResolver resolver;
    private final PageDirectory directory;
    private final Clock clock;

    public ReaderReport( final Jdbc jdbc, final PathResolver resolver, final PageDirectory directory,
                         final Clock clock ) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.directory = directory;
        this.clock = clock;
    }

    /** The newest refresh of any rollup row for the site, or null when nothing has been read back. */
    public Instant refreshedAt( final String siteHost ) {
        try {
            return jdbc.queryOne( "SELECT MAX(refreshed_at) AS r FROM reader_engagement_daily WHERE site_host = ?",
                    ps -> ps.setString( 1, siteHost ),
                    rs -> rs.getTimestamp( "r" ) == null ? null : rs.getTimestamp( "r" ).toInstant() )
                    .orElse( null );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Could not read reader refresh time: " + e.getMessage(), e );
        }
    }

    public List< PageReaderStats > pages( final String siteHost, final int days, final int limit ) {
        return pages( siteHost, days, limit, null );
    }

    /** The pages with the most views in the window, most-viewed first; {@code cluster} null means all pages. */
    public List< PageReaderStats > pages( final String siteHost, final int days, final int limit,
                                          final String cluster ) {
        final LocalDate to = LocalDate.now( clock.withZone( ZoneOffset.UTC ) );
        final LocalDate from = to.minusDays( Math.max( 1, days ) - 1L );
        final Map< String, Acc > byPage = new LinkedHashMap<>();
        try {
            jdbc.query( ENGAGEMENT_SQL, ps -> bindWindow( ps, siteHost, from, to ), rs -> {
                final Acc a = byPage.computeIfAbsent( rs.getString( "canonical_id" ).strip(), k -> new Acc() );
                a.engagement( rs );
                return null;
            } );
            jdbc.query( ACQUISITION_SQL, ps -> bindWindow( ps, siteHost, from, to ), rs -> {
                final Acc a = byPage.get( rs.getString( "canonical_id" ).strip() );
                if ( a != null ) {
                    a.acquisition( rs.getString( "channel" ), rs.getDate( "day" ).toLocalDate(),
                            rs.getInt( "landings" ), rs.getInt( "continued" ), rs.getInt( "returning_landings" ) );
                }
                return null;
            } );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Could not read reader engagement: " + e.getMessage(), e );
        }
        final List< PageReaderStats > out = new ArrayList<>();
        final List< Map.Entry< String, Acc > > ranked = new ArrayList<>( byPage.entrySet() );
        ranked.sort( Comparator.< Map.Entry< String, Acc > >comparingInt( e -> e.getValue().views ).reversed()
                .thenComparing( Map.Entry::getKey ) );
        for ( final Map.Entry< String, Acc > e : ranked ) {
            if ( out.size() >= limit ) {
                break;
            }
            final Optional< PageDirectory.Entry > page = directory.lookup( e.getKey() );
            if ( cluster != null && ( page.isEmpty() || !cluster.equals( page.get().cluster() ) ) ) {
                continue;
            }
            out.add( e.getValue().toStats( e.getKey(), page.map( PageDirectory.Entry::slug ).orElse( null ) ) );
        }
        return out;
    }

    /**
     * Whether organic search landings account for the clicks the search engines report. Search clicks come
     * from each engine's latest snapshot in the window (rolling totals; they are not summed across
     * snapshots), organic landings from the rollup over the window, joined on engine key and page.
     */
    public Coverage coverage( final String siteHost, final int days ) {
        final LocalDate to = LocalDate.now( clock.withZone( ZoneOffset.UTC ) );
        final LocalDate from = to.minusDays( Math.max( 1, days ) - 1L );
        final Map< String, Integer > engineClicks = new TreeMap<>();
        final Map< String, Integer > pageClicks = new LinkedHashMap<>(); // engine \u0001 id
        final Map< String, Integer > engineLandings = new HashMap<>();
        final Map< String, Integer > pageLandings = new HashMap<>();
        try {
            jdbc.query( CLICKS_SQL, ps -> {
                ps.setString( 1, siteHost );
                ps.setDate( 2, Date.valueOf( from ) );
                ps.setDate( 3, Date.valueOf( to ) );
                ps.setString( 4, siteHost );
            }, rs -> {
                final String engine = rs.getString( "engine" );
                final int clicks = rs.getInt( "clicks" );
                engineClicks.merge( engine, clicks, Integer::sum );
                resolver.canonicalIdFor( pathOf( rs.getString( "page_path" ) ) )
                        .ifPresent( id -> pageClicks.merge( engine + '\u0001' + id, clicks, Integer::sum ) );
                return null;
            } );
            jdbc.query( ORGANIC_SQL, ps -> bindWindow( ps, siteHost, from, to ), rs -> {
                final String engine = rs.getString( "source" );
                final int landings = rs.getInt( "landings" );
                engineLandings.merge( engine, landings, Integer::sum );
                pageLandings.merge( engine + '\u0001' + rs.getString( "canonical_id" ).strip(), landings,
                        Integer::sum );
                return null;
            } );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "Could not read search coverage: " + e.getMessage(), e );
        }
        final Map< String, Double > site = new LinkedHashMap<>();
        engineClicks.forEach( ( engine, clicks ) -> {
            if ( clicks > 0 ) {
                site.put( engine, engineLandings.getOrDefault( engine, 0 ) / (double) clicks );
            }
        } );
        final List< CoverageRow > rows = new ArrayList<>();
        final Map< String, Boolean > keys = new LinkedHashMap<>();
        pageClicks.keySet().forEach( k -> keys.put( k, true ) );
        pageLandings.keySet().forEach( k -> keys.put( k, true ) );
        for ( final String key : keys.keySet() ) {
            final int split = key.indexOf( '\u0001' );
            if ( !engineClicks.containsKey( key.substring( 0, split ) ) ) {
                continue; // an engine with no snapshot: landings alone make no coverage comparison
            }
            rows.add( new CoverageRow( key.substring( 0, split ), key.substring( split + 1 ),
                    pageClicks.getOrDefault( key, 0 ), pageLandings.getOrDefault( key, 0 ) ) );
        }
        rows.sort( Comparator.comparing( CoverageRow::engine )
                .thenComparing( Comparator.comparingInt( CoverageRow::searchClicks ).reversed() )
                .thenComparing( CoverageRow::canonicalId ) );
        return new Coverage( site, rows );
    }

    /** A snapshot's {@code page_path} may be a bare path or a full URL; the resolver wants the path. */
    private static String pathOf( final String pagePath ) {
        if ( pagePath != null && pagePath.startsWith( "http" ) ) {
            try {
                final String path = URI.create( pagePath ).getPath();
                return path == null || path.isEmpty() ? "/" : path;
            } catch ( final IllegalArgumentException e ) {
                LOG.warn( "Unparseable search snapshot page_path '{}': {}", pagePath, e.toString() );
            }
        }
        return pagePath;
    }

    private static void bindWindow( final java.sql.PreparedStatement ps, final String siteHost,
                                    final LocalDate from, final LocalDate to ) throws SQLException {
        ps.setString( 1, siteHost );
        ps.setDate( 2, Date.valueOf( from ) );
        ps.setDate( 3, Date.valueOf( to ) );
    }

    /** Running sums for one page. */
    private static final class Acc {
        int views;
        int visitors;
        int exits;
        int scroll75;
        long actions;
        // daily (median, exits) pairs for the weighted median
        final List< double[] > medians = new ArrayList<>();
        final Vital lcp = new Vital();
        final Vital inp = new Vital();
        final Vital cls = new Vital();
        final Map< String, Integer > landingsByChannel = new TreeMap<>();
        int landings;
        int continued;
        int lateLandings;
        int lateReturning;

        /** Folds one {@code reader_engagement_daily} row into the page's totals. */
        void engagement( final ResultSet rs ) throws SQLException {
            final int v = rs.getInt( "views" );
            final int ex = rs.getInt( "exits" );
            final Double median = getNullableDouble( rs, "active_median_s" );
            views += v;
            visitors += rs.getInt( "visitors" );
            exits += ex;
            scroll75 += rs.getInt( "exits_scroll75" );
            actions += rs.getInt( "code_copies" ) + rs.getInt( "wikilink_clicks" ) + rs.getInt( "outbound_clicks" )
                    + rs.getInt( "diagram_interacts" ) + rs.getInt( "toc_clicks" ) + rs.getInt( "sign_in_clicks" );
            if ( median != null && ex > 0 ) {
                medians.add( new double[] { median, ex } );
            }
            lcp.add( getNullableDouble( rs, "lcp_p75_ms" ), v );
            inp.add( getNullableDouble( rs, "inp_p75_ms" ), v );
            cls.add( getNullableDouble( rs, "cls_p75" ), v );
        }

        void acquisition( final String channel, final LocalDate day, final int l, final int cont, final int ret ) {
            landingsByChannel.merge( channel, l, Integer::sum );
            landings += l;
            continued += cont;
            if ( day.getDayOfMonth() >= RETURNING_FROM_DAY_OF_MONTH ) {
                lateLandings += l;
                lateReturning += ret;
            }
        }

        PageReaderStats toStats( final String id, final String slug ) {
            return new PageReaderStats( id, slug, views, visitors, weightedMedian( medians ),
                    exits == 0 ? null : scroll75 / (double) exits,
                    views == 0 ? null : actions / (double) views,
                    landingsByChannel, landings == 0 ? null : continued / (double) landings,
                    lateLandings == 0 ? null : lateReturning / (double) lateLandings,
                    lcp.mean(), inp.mean(), cls.mean() );
        }
    }

    /** The smallest value whose cumulative weight reaches half the total weight. */
    static Double weightedMedian( final List< double[] > valueWeight ) {
        if ( valueWeight.isEmpty() ) {
            return null;
        }
        final List< double[] > sorted = new ArrayList<>( valueWeight );
        sorted.sort( Comparator.comparingDouble( p -> p[ 0 ] ) );
        double total = 0;
        for ( final double[] p : sorted ) {
            total += p[ 1 ];
        }
        double cumulative = 0;
        for ( final double[] p : sorted ) {
            cumulative += p[ 1 ];
            if ( cumulative >= total / 2.0 ) {
                return p[ 0 ];
            }
        }
        return sorted.get( sorted.size() - 1 )[ 0 ];
    }

    /** Views-weighted mean of the days that had a sample. */
    private static final class Vital {
        double sum;
        double weight;

        void add( final Double value, final int views ) {
            if ( value != null && views > 0 ) {
                sum += value * views;
                weight += views;
            }
        }

        Double mean() {
            return weight == 0 ? null : sum / weight;
        }
    }
}
