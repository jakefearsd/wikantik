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
package com.wikantik.work;

import com.wikantik.work.WorkFlowStore.ChangeFact;
import com.wikantik.work.WorkFlowStore.DeploymentFact;
import com.wikantik.work.WorkFlowStore.RecoveryEnd;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles a DORA report: reads the facts the window needs from the store and hands them to {@link Dora}. It holds
 * no standing and no state; the service decides who may ask and for which project or product.
 */
final class DoraReports {

    private final WorkFlowStore flow;

    DoraReports( final WorkFlowStore flow ) {
        this.flow = flow;
    }

    /** The report for a project (its items' deployments and changes) or a product; exactly one id is given. */
    DoraReport build( final MetricWindow w, final Long projectId, final Long productId, final String productKey )
            throws SQLException {
        final List< DeploymentFact > deployments = flow.deployments( projectId, productId, w.start(), w.end() );
        final List< DeploymentFact > failing = deployments.stream().filter( f -> Dora.ran( f, w ) && Dora.failed( f ) )
            .toList();
        final List< RecoveryEnd > ends = endsOf( failing );
        final List< ChangeFact > changes = flow.shippedChanges( projectId, productId, w.start(), w.end() );
        final Dora.LeadTime lead = Dora.leadTime( changes );
        final Dora.Recovery recovery = Dora.recovery( deployments, ends, w );
        return new DoraReport( w.from(), w.to(), w.zone().getId(), productKey, Dora.frequency( deployments, w ),
            lead.durations(), lead.withoutCommitTime(), lead.clamped(),
            lead.withoutUsableCommitTime(), Dora.changeFailureRate( deployments, w ),
            recovery.durations(), recovery.unrecovered(), Dora.rework( deployments, w ),
            flow.merges( projectId, productId, w.start(), w.end() ), lead.excluded() );
    }

    private List< RecoveryEnd > endsOf( final List< DeploymentFact > failing ) throws SQLException {
        if ( failing.isEmpty() ) {
            return List.of();
        }
        final Set< Long > products = failing.stream().map( DeploymentFact::productId ).collect( Collectors.toSet() );
        final Instant since = failing.stream().map( Dora::start ).min( Instant::compareTo ).orElseThrow();
        return flow.recoveryEnds( products, since );
    }
}
