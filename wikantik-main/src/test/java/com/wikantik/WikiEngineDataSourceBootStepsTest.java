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
package com.wikantik;

import com.wikantik.runtime.boot.BootStep;
import com.wikantik.runtime.boot.FailurePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the datasource boot order and each step's failure policy to what the pre-BootSequence
 * {@code initDataSourceSubsystems} did: connectors, collaboration and work management had their own catch and
 * continued; anything else fell through to the method's single catch and abandoned the rest.
 */
class WikiEngineDataSourceBootStepsTest {

    @Test
    void stepsKeepTheirLoadBearingOrderAndCharacterisedFailurePolicies() {
        final TestEngine engine = TestEngine.build();
        try {
            final List< String > declared = engine.dataSourceBootSteps( new WikiEngine.DataSourceBootProducts() )
                .stream().map( s -> s.name() + ":" + s.policy() ).toList();

            final String abort = ":" + FailurePolicy.ABORT_REST;
            final String cont = ":" + FailurePolicy.CONTINUE;
            assertEquals( List.of(
                "persistence" + abort,
                "audit-dropped-gauge" + abort,
                "knowledge-core" + abort,
                "structural-spine" + abort,
                "content-services" + abort,
                "hybrid-retrieval" + abort,
                "ontology" + abort,
                "connectors" + cont,
                "collaboration" + cont,
                "drift" + abort,
                "citations" + abort,
                "content-dates" + abort,
                "retrieval-quality-runner" + abort,
                "query-log" + abort,
                "insights" + abort,
                "work-management" + cont,
                "work-claim-sweeper" + abort,
                "briefing-log" + abort,
                "save-filters" + abort ), declared );
        } finally {
            engine.stop();
        }
    }

    @Test
    void aFailedWorkManagementWiringLeavesNoWorkServiceAndBootGoesOn() {
        final TestEngine engine = TestEngine.build();
        try {
            final BootStep work = engine.dataSourceBootSteps( new WikiEngine.DataSourceBootProducts() ).stream()
                .filter( s -> s.name().equals( "work-management" ) ).findFirst().orElseThrow();
            engine.setWorkService( org.mockito.Mockito.mock( com.wikantik.work.WorkService.class ) );

            work.reportContinuedFailure( new IllegalStateException( "work schema missing" ) );

            assertNull( engine.workService() );
        } finally {
            engine.stop();
        }
    }
}
