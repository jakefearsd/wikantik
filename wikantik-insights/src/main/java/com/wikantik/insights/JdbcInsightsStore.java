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

import com.wikantik.jdbc.Jdbc;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC-backed {@link InsightsStore}: a Facade that routes each call to the per-aggregate store that
 * owns its tables -- visibility snapshots, the change ledger, demand, imported opportunities and
 * the expected-CTR curve. All data access goes through the shared {@link Jdbc}.
 */
public class JdbcInsightsStore implements InsightsStore {

    private final JdbcVisibilityStore visibility;
    private final JdbcChangeLedger ledger;
    private final JdbcDemandStore demand;
    private final JdbcImportedOpportunities imported;
    private final JdbcCtrCurves ctrCurves;

    /**
     * Creates a new JdbcInsightsStore backed by the given DataSource.
     *
     * @param dataSource the data source to use for connections
     */
    public JdbcInsightsStore( final DataSource dataSource ) {
        final Jdbc jdbc = new Jdbc( dataSource );
        this.visibility = new JdbcVisibilityStore( jdbc );
        this.ledger = new JdbcChangeLedger( jdbc );
        this.demand = new JdbcDemandStore( jdbc );
        this.imported = new JdbcImportedOpportunities( jdbc );
        this.ctrCurves = new JdbcCtrCurves( jdbc );
    }

    @Override
    public int upsert( final List<VisibilityRow> rows ) {
        return visibility.upsert( rows );
    }

    @Override
    public Optional<LocalDate> latestSnapshotDate( final String siteHost ) {
        return visibility.latestSnapshotDate( siteHost );
    }

    @Override
    public List<EngineTotal> engineTotals( final String siteHost, final LocalDate snapshotDate ) {
        return visibility.engineTotals( siteHost, snapshotDate );
    }

    @Override
    public List<TrendPoint> trend( final String siteHost, final LocalDate since ) {
        return visibility.trend( siteHost, since );
    }

    @Override
    public List<VisibilityRow> latestRowsPerEngine( final String siteHost, final int withinDays ) {
        return visibility.latestRowsPerEngine( siteHost, withinDays );
    }

    @Override
    public int siteImpressions28d( final String siteHost ) {
        return visibility.siteImpressions28d( siteHost );
    }

    @Override
    public Optional<PageWindow> pageWindowNear( final String siteHost, final String pagePath,
                                                final LocalDate targetDate, final int toleranceDays ) {
        return visibility.pageWindowNear( siteHost, pagePath, targetDate, toleranceDays );
    }

    @Override
    public Optional<PageWindow> siteWindowNear( final String siteHost, final LocalDate targetDate,
                                                final int toleranceDays ) {
        return visibility.siteWindowNear( siteHost, targetDate, toleranceDays );
    }

    @Override
    public Optional<Long> recordChange( final ContentChange change ) {
        return ledger.recordChange( change );
    }

    @Override
    public List<PendingChange> unevaluatedChanges( final LocalDate cutoff ) {
        return ledger.unevaluatedChanges( cutoff );
    }

    @Override
    public boolean snooze( final OpportunitySnooze snooze ) {
        return ledger.snooze( snooze );
    }

    @Override
    public List<OpportunitySnooze> activeSnoozes( final LocalDate today ) {
        return ledger.activeSnoozes( today );
    }

    @Override
    public Map<String, LocalDate> lastChangeByTarget() {
        return ledger.lastChangeByTarget();
    }

    @Override
    public boolean recordEffect( final long changeId, final String verdict, final Double ctrDelta,
                                 final Double positionDelta, final Double clickDelta,
                                 final String method, final String detailJson ) {
        return ledger.recordEffect( changeId, verdict, ctrDelta, positionDelta, clickDelta, method, detailJson );
    }

    @Override
    public Map<String, Integer> verdictCountsByType() {
        return ledger.verdictCountsByType();
    }

    @Override
    public List<CalibrationSample> calibrationSamples() {
        return ledger.calibrationSamples();
    }

    @Override
    public Map<String, LocalDate> upsertSeen( final List<String[]> typeTargetPairs, final LocalDate today ) {
        return ledger.upsertSeen( typeTargetPairs, today );
    }

    @Override
    public List<DemandRow> demandRows( final int sinceDays ) {
        return demand.demandRows( sinceDays );
    }

    @Override
    public int upsertImported( final List<ImportedOpportunityRow> rows ) {
        return imported.upsertImported( rows );
    }

    @Override
    public List<Opportunity> latestImported( final String siteHost, final int maxAgeDays ) {
        return imported.latestImported( siteHost, maxAgeDays );
    }

    @Override
    public int upsertCtrCurve( final LocalDate asOf, final Map<Integer, Double> points ) {
        return ctrCurves.upsertCtrCurve( asOf, points );
    }

    @Override
    public Optional<CtrCurveSnapshot> latestCtrCurve( final int maxAgeDays ) {
        return ctrCurves.latestCtrCurve( maxAgeDays );
    }
}
