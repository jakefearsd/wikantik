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
package com.wikantik.extractcli.configref;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Committed {@code docs/ConfigurationReference.md} and the wiki page must match the generator.
 * Regenerate with {@code bin/config-reference.sh --write}.
 *
 * <p>The wiki page carries a generation {@code date:} line in its frontmatter;
 * {@link GenerateConfigReferenceCli#run} ignores that line on both sides of the comparison so a
 * regenerate on a later day is not "stale" by itself.</p>
 */
@DisabledIfEnvironmentVariable( named = "WIKANTIK_SKIP_MAIN_REGRESSION", matches = "1" )
class ConfigReferenceRegressionTest {

    @Test
    void committed_reference_docs_match_generated_output() throws Exception {
        final Path root = locateRepoRoot();
        if ( root == null ) {
            return;   // IDE run without the project root on the cwd chain; CI always has it
        }
        final GenerateConfigReferenceCli.Result r = new GenerateConfigReferenceCli().run( root, GenerateConfigReferenceCli.Mode.CHECK );
        assertEquals( 0, r.exitCode(), "Configuration reference is stale: " + r.summary() + " (run bin/config-reference.sh --write)" );
    }

    private static Path locateRepoRoot() {
        Path cursor = Path.of( "" ).toAbsolutePath();
        while ( cursor != null ) {
            if ( Files.isDirectory( cursor.resolve( "bin/db/migrations" ) ) ) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
