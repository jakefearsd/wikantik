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
package com.wikantik.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzTest {

    private final String savedMode = System.getProperty( Fuzz.MODE );
    private final String savedSeed = System.getProperty( Fuzz.SEED );

    @AfterEach
    void restore() {
        restore( Fuzz.MODE, savedMode );
        restore( Fuzz.SEED, savedSeed );
    }

    private static void restore( final String key, final String value ) {
        if ( value == null ) {
            System.clearProperty( key );
        } else {
            System.setProperty( key, value );
        }
    }

    @Test
    void theGateRunsATwentiethOfTheCasesOnTheWrittenSeeds() {
        System.clearProperty( Fuzz.MODE );
        System.clearProperty( Fuzz.SEED );
        assertFalse( Fuzz.deep() );
        assertEquals( 300, Fuzz.cases( 6_000 ) );
        assertEquals( 1, Fuzz.cases( 7 ), "never zero cases" );
        assertEquals( 0xB0D1E5L, Fuzz.seed( 0xB0D1E5L ) );
        assertEquals( 0xD2AF7L, Fuzz.seed( 0xD2AF7L ) );
    }

    @Test
    void aSeedGivenWithoutDeepModeIsIgnoredSoTheGateStaysReproducible() {
        System.clearProperty( Fuzz.MODE );
        System.setProperty( Fuzz.SEED, "0x1234" );
        assertEquals( 0xB0D1E5L, Fuzz.seed( 0xB0D1E5L ) );
    }

    @Test
    void deepModeRunsEveryCaseOnSeedsDerivedFromTheRunSeed() {
        System.setProperty( Fuzz.MODE, "deep" );
        System.setProperty( Fuzz.SEED, "0x1234" );
        assertTrue( Fuzz.deep() );
        assertEquals( 6_000, Fuzz.cases( 6_000 ) );
        assertEquals( 0xB0D1E5L ^ 0x1234L, Fuzz.seed( 0xB0D1E5L ) );
        assertNotEquals( Fuzz.seed( 0xB0D1E5L ), Fuzz.seed( 0xD2AF7L ), "each written seed keeps its own stream" );
        assertTrue( Fuzz.reproduce().contains( "-Dtests.fuzz.seed=0x1234" ), Fuzz.reproduce() );
    }

    @Test
    void deepModeWithoutASeedPicksOneAndKeepsItForTheWholeRun() {
        System.setProperty( Fuzz.MODE, "deep" );
        System.clearProperty( Fuzz.SEED );
        final long first = Fuzz.seed( 0L );
        assertEquals( first, Fuzz.seed( 0L ) );
        assertTrue( Fuzz.reproduce().contains( "-Dtests.fuzz.seed=0x" + Long.toHexString( first ) ), Fuzz.reproduce() );
    }

    @Test
    void theGateSaysHowToRunTheFullCount() {
        System.clearProperty( Fuzz.MODE );
        assertTrue( Fuzz.reproduce().contains( "-Dtests.fuzz=deep" ), Fuzz.reproduce() );
    }
}
