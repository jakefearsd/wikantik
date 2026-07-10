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

import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.BundleSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleEvalSchedulerTest {

    /** Repo-root-relative; surefire CWD = the wikantik-main module dir (same as BundleEvalGateTest). */
    private static final Path CORPUS = Path.of( "..", "eval", "bundle-corpus", "queries.csv" );

    private static final BundleEvalThresholds FLOORS =
        new BundleEvalThresholds( 0.35, 0.30, 0.40, 0.45 );

    /** DAO that captures the last row instead of touching a DB. */
    private static final class CapturingDao extends BundleEvalRunDao {
        final AtomicReference< BundleEvalRun > last = new AtomicReference<>();
        CapturingDao() { super( null ); }
        @Override public void insert( final BundleEvalRun run ) { last.set( run ); }
    }

    @Test
    void runOnce_oracleRetriever_persistsNoRegression() {
        // Oracle: return each question's own gold sections -> perfect recall -> above all floors.
        final List< BundleEvalQuestion > corpus = BundleCorpusLoader.load( CORPUS );
        final BundleEvalRunner.BundleRetriever oracle = query -> corpus.stream()
            .filter( q -> q.query().equals( query ) ).findFirst()
            .map( q -> q.goldSections().stream()
                .map( g -> new BundleSection( g.canonicalId(), g.headingPath(), "x" ) ).toList() )
            .orElse( List.of() );
        final CapturingDao dao = new CapturingDao();

        new BundleEvalScheduler( oracle, CORPUS, FLOORS, dao, "test", 5, 0 ).runOnce();

        final BundleEvalRun row = dao.last.get();
        assertNotNull( row, "a row must be persisted" );
        assertFalse( row.regression(), "oracle recall is perfect -> no regression" );
    }

    @Test
    void runOnce_emptyRetriever_persistsRegression() {
        // Empty retriever -> zero recall -> below every floor -> regression.
        final CapturingDao dao = new CapturingDao();
        new BundleEvalScheduler( query -> List.of(), CORPUS, FLOORS, dao, "test", 5, 0 ).runOnce();
        assertTrue( dao.last.get().regression(), "zero recall must be flagged as a regression" );
    }

    @Test
    void start_disabledWhenIntervalNonPositive_noThrow() {
        // interval 0 -> start() is a no-op (never schedules); must not throw.
        new BundleEvalScheduler( q -> List.of(), CORPUS, FLOORS, new CapturingDao(), "test", 5, 0 ).start();
    }
}
