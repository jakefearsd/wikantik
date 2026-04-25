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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the generated {@code docs/wikantik-pages/Main.md}.
 * Runs the {@link GenerateMainPageCli} in {@code --check} mode against the
 * project root's pages directory and fails the build if Main.md has drifted
 * from what {@code Main.pins.yaml} would produce.
 *
 * <p>Skip with {@code WIKANTIK_SKIP_MAIN_REGRESSION=1} for IDE runs that
 * don't have the project root on the working-directory chain.</p>
 */
@DisabledIfEnvironmentVariable( named = "WIKANTIK_SKIP_MAIN_REGRESSION", matches = "1" )
class MainPageRegressionTest {

    @Test
    void on_disk_Main_md_matches_generated_output() throws Exception {
        final Path pagesDir = locatePagesDirectory();
        if ( pagesDir == null ) {
            // Project root not reachable from this invocation — skip gracefully
            // rather than fail. The CI build always sees the project root.
            return;
        }
        final var result = new GenerateMainPageCli().run( pagesDir, GenerateMainPageCli.Mode.CHECK );
        assertEquals( 0, result.exitCode(),
                "Main.md is out of sync with Main.pins.yaml. " + result.summary()
                  + " (Run the generator with --write to regenerate.)" );
        assertTrue( result.summary().toLowerCase().contains( "in sync" ),
                "Unexpected summary from check mode: " + result.summary() );
    }

    /**
     * Walk up from the surefire CWD looking for {@code docs/wikantik-pages/}.
     * Returns null when the directory cannot be located — the test then
     * skips, since the failure would be about the test harness rather than
     * the artifact under review.
     */
    private static Path locatePagesDirectory() {
        Path cursor = Path.of( "" ).toAbsolutePath();
        for ( int depth = 0; depth < 6 && cursor != null; depth++ ) {
            final Path candidate = cursor.resolve( "docs/wikantik-pages" );
            if ( Files.isDirectory( candidate ) && Files.exists( candidate.resolve( "Main.pins.yaml" ) ) ) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
