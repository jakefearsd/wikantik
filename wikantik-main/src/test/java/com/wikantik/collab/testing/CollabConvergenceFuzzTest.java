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
package com.wikantik.collab.testing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Section 13.2's convergence fuzz (F1 + F2 + F4 + F5) against the Phase 0 stub, once per {@link SimProfile}. Every seed
 * runs {@value #STEPS} steps with {@link CollabInvariants} after each and convergence at quiescence. On the first failure
 * the full trace and its shrunk form are written under {@code target/collab-failures/<profile>/} and the assertion names
 * seed, profile and both paths; the shrunk file belongs in {@code src/test/resources/collab/regressions/}.
 *
 * <p>A seed that ends with the stub poisoned is an unsupported scenario (Task 10 ruling): neither a pass nor a failure,
 * but more than 1% of them fails the run so a regression cannot hide there. {@code -Dcollab.fuzz.seeds} scales the run.</p>
 */
class CollabConvergenceFuzzTest {

    static final int STEPS = 200;
    private static final int DEFAULT_SEEDS = 2_000;

    @ParameterizedTest
    @EnumSource( SimProfile.class )
    void everyProfileConvergesAgainstTheStub( final SimProfile profile ) {
        final int seeds = Integer.getInteger( "collab.fuzz.seeds", DEFAULT_SEEDS );
        int unsupported = 0;
        for( long seed = 1; seed <= seeds; seed++ ) {
            final int clients = 2 + (int) ( seed % 3 );
            final SimTrace t = new SimScheduler( seed, profile, SimScheduler.STUB, clients ).run( STEPS );
            switch( t.outcome().status() ) {
                case PASS -> { }
                case UNSUPPORTED -> unsupported++;
                case FAIL -> fail( report( t ) );
            }
        }
        assertTrue( unsupported * 100L <= seeds, profile + ": " + unsupported + " of " + seeds
            + " seeds ended as unsupported scenarios (poisoned stub); more than 1% means something regressed" );
    }

    private static String report( final SimTrace failing ) {
        final String category = failing.outcome().category();
        final SimTrace shrunk = SimScheduler.replay( Shrinker.shrink( failing,
            c -> SimScheduler.replay( c, SimScheduler.STUB ).outcome().category().equals( category ) ), SimScheduler.STUB );
        final Path dir = Path.of( "target", "collab-failures", failing.profile().name() );
        final Path full = dir.resolve( failing.seed() + ".json" );
        final Path small = dir.resolve( failing.seed() + ".shrunk.json" );
        try {
            Files.createDirectories( dir );
            Files.writeString( full, failing.toJson(), StandardCharsets.UTF_8 );
            Files.writeString( small, shrunk.toJson(), StandardCharsets.UTF_8 );
        } catch( final IOException e ) {
            throw new UncheckedIOException( "could not write the failure traces for seed " + failing.seed(), e );
        }
        return "seed " + failing.seed() + " profile " + failing.profile() + " failed: " + failing.outcome().message()
            + "\n  full trace:   " + full.toAbsolutePath()
            + "\n  shrunk trace: " + small.toAbsolutePath() + " (" + shrunk.steps().size() + " steps: "
            + shrunk.outcome().message() + ")";
    }
}
