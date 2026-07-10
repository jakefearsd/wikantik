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
package com.wikantik.knowledge.eval;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.knowledge.bundle.BundleHarnessAdapter;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/** Single derivation point for the {@link BundleEvalScheduler}, mirroring {@code BundleServiceWiring}.
 *  Returns {@code null} when no bundle service is available; otherwise builds a scheduler that is
 *  started by the caller. Interval 0 (default) yields a scheduler whose {@code start()} is a no-op. */
public final class BundleEvalWiring {

    private static final Logger LOG = LogManager.getLogger( BundleEvalWiring.class );

    private BundleEvalWiring() {}

    public static BundleEvalScheduler build( final BundleAssemblyService bundleService, final DataSource dataSource,
                                             final Properties props, final Function< String, Optional< String > > slugToCanonicalId ) {
        if ( bundleService == null ) {
            LOG.debug( "bundle service not wired — bundle-eval scheduler unavailable" );
            return null;
        }
        final long interval = (long) TextUtil.getDoubleProperty( props, "wikantik.bundle.eval.interval.hours", 0 );
        final int precisionK = (int) TextUtil.getDoubleProperty( props, "wikantik.bundle.eval.precision_k", 12 );
        final String corpus = props == null ? "eval/bundle-corpus/queries.csv"
            : props.getProperty( "wikantik.bundle.eval.corpus", "eval/bundle-corpus/queries.csv" );
        final BundleEvalThresholds thresholds = loadThresholds( corpus );
        final String configId = props == null ? "unknown"
            : props.getProperty( "wikantik.applicationName", "wikantik" );
        return new BundleEvalScheduler(
            new BundleHarnessAdapter( bundleService ), Path.of( corpus ),
            thresholds, new BundleEvalRunDao( dataSource )::insert, configId, precisionK, interval );
        // NB: the 4th arg is a Consumer< BundleEvalRun > (the Task-4 write seam); the DAO stays final
        // and we pass its ::insert method reference, not the DAO itself.
    }

    /** Loads thresholds from the sibling thresholds.properties next to the corpus; floors default to 0.0. */
    private static BundleEvalThresholds loadThresholds( final String corpusCsv ) {
        final Path p = Path.of( corpusCsv ).resolveSibling( "thresholds.properties" );
        final Properties t = new Properties();
        try ( var in = java.nio.file.Files.newInputStream( p ) ) {
            t.load( in );
        } catch ( final java.io.IOException e ) {
            LOG.warn( "bundle-eval thresholds not loaded from {} ({}); using 0.0 floors", p, e.getMessage() );
        }
        return BundleEvalThresholds.fromProperties( t );
    }
}
