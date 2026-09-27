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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument handling for the replay CLI — no database needed, so it runs without Docker. */
class MaterializeApprovedProposalsCliArgsTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final MaterializeApprovedProposalsCli cli = new MaterializeApprovedProposalsCli(
        new PrintStream( new ByteArrayOutputStream(), true, StandardCharsets.UTF_8 ),
        new PrintStream( errBytes, true, StandardCharsets.UTF_8 ) );

    @Test
    void run_rejects_unknown_argument_with_exit_2_before_touching_a_database() {
        assertEquals( 2, cli.run( new String[]{ "--bogus" } ) );
        assertTrue( errBytes.toString( StandardCharsets.UTF_8 ).contains( "unknown argument: --bogus" ) );
    }

    @Test
    void runWithDataSource_rejects_bad_arguments_with_exit_2() {
        assertEquals( 2, cli.runWithDataSource( null, new String[]{ "--limit", "not-a-number" } ) );
        assertTrue( errBytes.toString( StandardCharsets.UTF_8 ).startsWith( "error: " ) );
    }

    @Test
    void parse_reads_limit_and_dry_run() {
        final MaterializeApprovedProposalsCli.Args a =
            MaterializeApprovedProposalsCli.Args.parse( new String[]{ "--limit", "25", "--dry-run" } );
        assertEquals( 25, a.limit );
        assertTrue( a.dryRun );
    }

    @Test
    void parse_defaults_to_unlimited_real_run() {
        final MaterializeApprovedProposalsCli.Args a = MaterializeApprovedProposalsCli.Args.parse( new String[]{} );
        assertEquals( 0, a.limit );
        assertFalse( a.dryRun );
    }

    @Test
    void parse_rejects_unknown_argument() {
        assertThrows( IllegalArgumentException.class,
            () -> MaterializeApprovedProposalsCli.Args.parse( new String[]{ "--nope" } ) );
    }

    @Test
    void parse_mixes_a_common_flag_with_its_own_limit_flag() {
        // Exercises the "consumed a common flag, resume scanning" branch of this class's
        // own parse() loop, distinct from CommonCliArgs itself.
        final MaterializeApprovedProposalsCli.Args a = MaterializeApprovedProposalsCli.Args.parse(
            new String[]{ "--jdbc-url", "jdbc:postgresql://db/x", "--limit", "7" } );
        assertEquals( "jdbc:postgresql://db/x", a.jdbcUrl );
        assertEquals( 7, a.limit );
    }
}
