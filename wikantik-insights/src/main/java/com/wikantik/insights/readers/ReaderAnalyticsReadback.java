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

import com.wikantik.insights.readers.UmamiViews.AcquisitionRow;
import com.wikantik.insights.readers.UmamiViews.EngagementRow;
import com.wikantik.insights.readers.UmamiViews.TrafficRow;
import com.wikantik.insights.readers.UmamiViews.VitalsRow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The nightly read-back: reads the suite's Umami views for the last {@code days} days, folds
 * path-keyed rows onto canonical ids and replaces the rollup window in one transaction. A failure
 * anywhere before the write leaves the previous rollups untouched.
 */
public final class ReaderAnalyticsReadback {

    private static final Logger LOG = LogManager.getLogger( ReaderAnalyticsReadback.class );

    static final String RUNS_METRIC = "wikantik_reader_readback_runs_total";
    static final String LAST_SUCCESS_METRIC = "wikantik_reader_readback_last_success_seconds";

    /** Outcome of one run; {@code error} is null when {@code ok}. */
    public record ReadbackResult( boolean ok, int engagementRows, int acquisitionRows, String error ) {
        static ReadbackResult success( final int engagement, final int acquisition ) {
            return new ReadbackResult( true, engagement, acquisition, null );
        }
        static ReadbackResult failure( final String error ) {
            return new ReadbackResult( false, 0, 0, error );
        }
    }

    private record DayId( LocalDate day, String id ) { }

    private record AcqKey( LocalDate day, String id, String channel, String source ) { }

    private final UmamiViews views;
    private final ReaderRollupStore store;
    private final Supplier< PathResolver > resolver;
    private final String siteHost;
    private final int days;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicLong lastSuccess = new AtomicLong();

    public ReaderAnalyticsReadback( final UmamiViews views, final ReaderRollupStore store,
                                    final Supplier< PathResolver > resolver, final String siteHost,
                                    final int days, final Clock clock, final MeterRegistry meters ) {
        this.views = views;
        this.store = store;
        this.resolver = resolver;
        this.siteHost = siteHost;
        this.days = days;
        this.clock = clock;
        this.meters = meters;
        Gauge.builder( LAST_SUCCESS_METRIC, lastSuccess, AtomicLong::doubleValue )
             .description( "Epoch seconds of the last successful reader read-back." )
             .register( meters );
    }

    public ReadbackResult runOnce() {
        final LocalDate to = LocalDate.now( clock.withZone( ZoneOffset.UTC ) );
        final LocalDate from = to.minusDays( days - 1L );
        try {
            final PathResolver paths = resolver.get();
            final List< TrafficRow > traffic = views.traffic( from, to );
            final List< EngagementRow > engagement = views.engagement( from, to );
            final List< VitalsRow > vitals = views.vitals( from, to );
            final List< AcquisitionRow > acquisition = views.acquisition( from, to );

            final List< EngagementDay > engagementDays = mergeEngagement( paths, traffic, engagement, vitals );
            final List< AcquisitionDay > acquisitionDays = mergeAcquisition( paths, acquisition );
            store.replace( siteHost, from, to, engagementDays, acquisitionDays );

            lastSuccess.set( clock.instant().getEpochSecond() );
            count( "ok" );
            LOG.info( "Reader read-back for {} [{}..{}]: {} engagement rows, {} acquisition rows",
                      siteHost, from, to, engagementDays.size(), acquisitionDays.size() );
            return ReadbackResult.success( engagementDays.size(), acquisitionDays.size() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Reader read-back for {} [{}..{}] failed; previous rollups kept: {}",
                      siteHost, from, to, e.getMessage(), e );
            count( "failed" );
            return ReadbackResult.failure( String.valueOf( e.getMessage() ) );
        }
    }

    private void count( final String outcome ) {
        Counter.builder( RUNS_METRIC ).tag( "outcome", outcome ).register( meters ).increment();
    }

    private static String idFor( final PathResolver paths, final String path ) {
        return path == null ? "" : paths.canonicalIdFor( path ).orElse( "" );
    }

    private static int toInt( final long v ) {
        return ( int ) Math.min( v, Integer.MAX_VALUE );
    }

    /** Mutable accumulator for one (day, canonical id). */
    private static final class Acc {
        long views;
        long visitors;
        long exits;
        Double median;
        Double p90;
        long scroll75;
        long copies;
        long wikilinks;
        long outbound;
        long diagrams;
        long toc;
        long signIn;
        long percentileExits = -1;
        long vitalsSamples = -1;
        Double lcp;
        Double inp;
        Double cls;
    }

    private List< EngagementDay > mergeEngagement( final PathResolver paths,
            final List< TrafficRow > traffic, final List< EngagementRow > engagement,
            final List< VitalsRow > vitals ) {
        final Map< DayId, Acc > merged = new TreeMap<>(
            java.util.Comparator.comparing( DayId::day ).thenComparing( DayId::id ) );
        for ( final TrafficRow t : traffic ) {
            final Acc a = merged.computeIfAbsent( new DayId( t.day(), idFor( paths, t.path() ) ), k -> new Acc() );
            a.views += t.views();
            a.visitors += t.sessions();
        }
        for ( final EngagementRow e : engagement ) {
            final String id = e.canonicalId() == null ? "" : e.canonicalId();
            final Acc a = merged.computeIfAbsent( new DayId( e.day(), id ), k -> new Acc() );
            a.exits += e.exits();
            a.scroll75 += e.exitsScroll75();
            a.copies += e.codeCopies();
            a.wikilinks += e.wikilinkClicks();
            a.outbound += e.outboundClicks();
            a.diagrams += e.diagramInteracts();
            a.toc += e.tocClicks();
            a.signIn += e.signInClicks();
            // Percentiles cannot be summed: keep those of the row with the most exits.
            if ( e.activeMedianS() != null && e.exits() > a.percentileExits ) {
                a.percentileExits = e.exits();
                a.median = e.activeMedianS();
                a.p90 = e.activeP90S();
            }
        }
        for ( final VitalsRow v : vitals ) {
            final Acc a = merged.computeIfAbsent( new DayId( v.day(), idFor( paths, v.path() ) ), k -> new Acc() );
            if ( v.samples() > a.vitalsSamples ) {
                a.vitalsSamples = v.samples();
                a.lcp = v.lcpP75();
                a.inp = v.inpP75();
                a.cls = v.clsP75();
            }
        }
        final List< EngagementDay > out = new ArrayList<>( merged.size() );
        merged.forEach( ( k, a ) -> out.add( new EngagementDay( k.day(), k.id(), toInt( a.views ),
            toInt( a.visitors ), toInt( a.exits ), a.median, a.p90, toInt( a.scroll75 ),
            toInt( a.copies ), toInt( a.wikilinks ), toInt( a.outbound ), toInt( a.diagrams ),
            toInt( a.toc ), toInt( a.signIn ), a.lcp, a.inp, a.cls ) ) );
        return out;
    }

    private List< AcquisitionDay > mergeAcquisition( final PathResolver paths,
                                                     final List< AcquisitionRow > rows ) {
        final Map< AcqKey, long[] > merged = new HashMap<>();
        for ( final AcquisitionRow r : rows ) {
            final AcqKey key = new AcqKey( r.day(), idFor( paths, r.path() ),
                r.channel() == null ? "" : r.channel(), r.source() == null ? "" : r.source() );
            final long[] sums = merged.computeIfAbsent( key, k -> new long[ 3 ] );
            sums[ 0 ] += r.landings();
            sums[ 1 ] += r.continued();
            sums[ 2 ] += r.returningLandings();
        }
        final List< AcquisitionDay > out = new ArrayList<>( merged.size() );
        merged.forEach( ( k, s ) -> out.add( new AcquisitionDay( k.day(), k.id(), k.channel(),
            k.source(), toInt( s[ 0 ] ), toInt( s[ 1 ] ), toInt( s[ 2 ] ) ) ) );
        return out;
    }
}
