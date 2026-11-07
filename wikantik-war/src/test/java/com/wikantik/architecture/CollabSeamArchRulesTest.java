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
package com.wikantik.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import com.wikantik.collab.seamfixture.SeamViolations;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the {@link CollabSeamArchTest} rules detect what their descriptions claim, against {@link SeamViolations}. */
class CollabSeamArchRulesTest {

    private static final JavaClasses FIXTURE = new ClassFileImporter().importClasses( SeamViolations.class );

    private static List< String > violations( final com.tngtech.archunit.lang.ArchRule rule ) {
        final EvaluationResult r = rule.evaluate( FIXTURE );
        return r.getFailureReport().getDetails();
    }

    private static void detects( final List< String > details, final String method ) {
        assertTrue( details.stream().anyMatch( d -> d.contains( "SeamViolations." + method + "()" ) ),
            method + " must be flagged: " + details );
    }

    @Test
    void wallClockRuleFlagsEveryZeroArgNowAndNewDate() {
        final List< String > d = violations( CollabSeamArchTest.no_wall_clock_or_sleep );
        detects( d, "zonedNow" );
        detects( d, "offsetNow" );
        detects( d, "localDateNow" );
        detects( d, "newDate" );
        assertFalse( d.stream().anyMatch( x -> x.contains( "SeamViolations.injectedClock(" ) ),
            "the injected-clock overloads are sanctioned: " + d );
    }

    @Test
    void threadRuleFlagsThreadAndExecutorCreation() {
        final List< String > d = violations( CollabSeamArchTest.no_thread_or_executor_creation );
        detects( d, "newThread" );
        detects( d, "virtualThread" );
        detects( d, "executor" );
        assertFalse( d.stream().anyMatch( x -> x.contains( "SeamViolations.injectedClock(" ) ), d.toString() );
    }

    @Test
    void ruleDescriptionsNameEachForbiddenCallOnce() {
        final String clock = CollabSeamArchTest.no_wall_clock_or_sleep.getDescription();
        assertTrue( clock.indexOf( "LocalDateTime.now" ) == clock.lastIndexOf( "LocalDateTime.now" ), clock );
        for( final String named : List.of( "ZonedDateTime.now", "OffsetDateTime.now", "LocalDate.now", "new Date()" ) ) {
            assertTrue( clock.contains( named ), clock );
        }
    }
}
