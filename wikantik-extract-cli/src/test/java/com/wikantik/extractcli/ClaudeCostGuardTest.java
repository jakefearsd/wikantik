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
package com.wikantik.extractcli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared opt-in gate on paid Anthropic API calls.
 *
 * <p>{@code JudgeExperimentCli} used to carry a hand-copied second implementation of these
 * checks. The copies agreed, so nothing was broken — but a guard on real spend is the last
 * thing that should rely on two implementations staying in step, and nothing tested that they
 * did. These tests cover the shared implementation and, more usefully, the property that only
 * emerges from sharing it: each paid path has its OWN gate, so opting into one never opens
 * another.
 */
class ClaudeCostGuardTest {

    /** Every gate property the CLIs actually use; see BootstrapExtractionCli / JudgeExperimentCli. */
    private static final String JUDGE_GATE     = "wikantik.kg.judge.allow_claude";
    private static final String EXTRACTOR_GATE = "wikantik.kg.extractor.allow_claude";

    @AfterEach
    void clearGates() {
        System.clearProperty( JUDGE_GATE );
        System.clearProperty( EXTRACTOR_GATE );
    }

    /**
     * The default answer is no. Neither paid path may run without someone explicitly opting in,
     * whatever else is configured.
     */
    @Test
    void refusesByDefaultWhenTheGatePropertyIsAbsent() {
        for ( final String gate : new String[] { JUDGE_GATE, EXTRACTOR_GATE } ) {
            final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> ClaudeCostGuard.resolveKey( "PATH", gate, "--judge claude" ),
                "Gate '" + gate + "' must be shut by default." );

            assertTrue( ex.getMessage().contains( "gated cost guard" ), ex.getMessage() );
            assertTrue( ex.getMessage().contains( gate ),
                "The refusal must name the property to set: " + ex.getMessage() );
        }
    }

    /**
     * The point of gating each paid path separately: turning on the extractor must not also
     * turn on the judge. This is the property a single hand-copied guard could quietly lose.
     */
    @Test
    void eachPaidPathHasItsOwnGate() {
        System.setProperty( EXTRACTOR_GATE, "true" );

        // The extractor path is now open...
        assertEquals( System.getenv( "PATH" ),
            ClaudeCostGuard.resolveKey( "PATH", EXTRACTOR_GATE, "--extractor claude" ) );

        // ...and the judge path is still shut.
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> ClaudeCostGuard.resolveKey( "PATH", JUDGE_GATE, "--judge claude" ) );
        assertTrue( ex.getMessage().contains( JUDGE_GATE ),
            "Opting into the extractor must not open the judge path: " + ex.getMessage() );
    }

    /** A gate set to anything other than true stays shut — no accidental truthiness. */
    @Test
    void onlyTheLiteralTrueOpensTheGate() {
        for ( final String value : new String[] { "false", "1", "yes", "TRUE-ish", "" } ) {
            System.setProperty( JUDGE_GATE, value );
            assertThrows( IllegalStateException.class,
                () -> ClaudeCostGuard.resolveKey( "PATH", JUDGE_GATE, "--judge claude" ),
                "Gate value '" + value + "' must not be treated as opt-in." );
        }
    }

    /** Case-insensitive true is accepted, matching Boolean.parseBoolean. */
    @Test
    void mixedCaseTrueOpensTheGate() {
        System.setProperty( JUDGE_GATE, "TRUE" );
        assertEquals( System.getenv( "PATH" ),
            ClaudeCostGuard.resolveKey( "PATH", JUDGE_GATE, "--judge claude" ) );
    }

    @Test
    void refusesWhenNoEnvVarWasNamed() {
        System.setProperty( JUDGE_GATE, "true" );

        for ( final String missing : new String[] { null, "", "   " } ) {
            final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> ClaudeCostGuard.resolveKey( missing, JUDGE_GATE, "--judge claude" ) );
            assertTrue( ex.getMessage().contains( "--anthropic-key-env" ), ex.getMessage() );
        }
    }

    @Test
    void refusesWhenTheNamedEnvVarIsEmpty() {
        System.setProperty( JUDGE_GATE, "true" );

        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> ClaudeCostGuard.resolveKey( "WIKANTIK_DEFINITELY_UNSET_ENV_XYZ", JUDGE_GATE, "--judge claude" ) );

        assertTrue( ex.getMessage().contains( "unset or empty" ), ex.getMessage() );
        assertTrue( ex.getMessage().contains( "WIKANTIK_DEFINITELY_UNSET_ENV_XYZ" ),
            "The refusal must name the variable it looked at: " + ex.getMessage() );
    }

    /**
     * The key is read from the environment only — never from the gate property or a flag — so
     * it cannot leak into shell history or a process listing.
     */
    @Test
    void returnsTheEnvironmentValueVerbatim() {
        System.setProperty( JUDGE_GATE, "true" );
        assertEquals( System.getenv( "PATH" ),
            ClaudeCostGuard.resolveKey( "PATH", JUDGE_GATE, "--judge claude" ) );
    }
}
