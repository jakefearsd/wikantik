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
package com.wikantik.auth.user;

import com.wikantik.jdbc.testing.MigrationApplier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * When {@code dedupe_display_names.sh} hits a constraint error, the output must not carry the failing row. With
 * psql's default verbosity a constraint violation prints {@code DETAIL: Failing row contains (...)}, and that row is
 * an account: uid, email address, login name. The script runs psql with {@code VERBOSITY=terse}; this runs the real
 * script with the real {@code psql} against the test container and forces a constraint failure from inside the
 * script's own transaction, so nothing it injects survives.
 *
 * <p>The fault is injected through the script's {@code PSQL_COMMAND} hook: a wrapper that forwards to {@code psql}
 * and slips two statements in right after {@code BEGIN} (two accounts that will be renamed, and a CHECK constraint
 * the rename violates). The script's own psql session then fails, and the server rolls the whole transaction back.
 * Skipped, visibly, where no {@code psql} client is installed.</p>
 */
@RequiresPostgres
class DedupeDisplayNamesPsqlErrorTest {

    private static final Logger LOG = LogManager.getLogger( DedupeDisplayNamesPsqlErrorTest.class );

    private static final Pattern JDBC_URL = Pattern.compile( "jdbc:postgresql://([^:/]+):(\\d+)/([^?]+).*" );

    private static boolean psqlAvailable() {
        try {
            final Process p = new ProcessBuilder( "psql", "--version" ).redirectErrorStream( true ).start();
            p.getInputStream().readAllBytes();
            return p.waitFor( 30, TimeUnit.SECONDS ) && p.exitValue() == 0;
        } catch ( final IOException e ) {
            LOG.warn( "psql is not runnable, so the psql-error test is skipped: {}", e.getMessage() );
            return false;
        } catch ( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void aConstraintFailureInsideTheScriptPrintsNoAccountData( @TempDir final Path tmp )
            throws IOException, InterruptedException, SQLException {
        assumeTrue( psqlAvailable(), "no psql client on PATH: cannot run the script against the container" );
        final Matcher url = JDBC_URL.matcher( PostgresTestDb.getJdbcUrl() );
        assertTrue( url.matches(), "unexpected JDBC URL " + PostgresTestDb.getJdbcUrl() );

        final Path wrapper = tmp.resolve( "psql-with-fault" );
        Files.writeString( wrapper, """
            #!/usr/bin/env bash
            args=()
            for a in "$@"; do
              args+=( "$a" )
              if [[ "$a" == "BEGIN" ]]; then
                args+=( -c "INSERT INTO users (uid, email, full_name, login_name, wiki_name, created) VALUES
                              ('leak-uid-1', 'leak-email-1@example.test', 'Leak Probe', 'leak-login-1@example.test', 'LeakProbeA', '2020-01-01'),
                              ('leak-uid-2', 'leak-email-2@example.test', 'Leak Probe', 'leak-login-2@example.test', 'LeakProbeB', '2020-01-02')" )
                args+=( -c "ALTER TABLE users ADD CONSTRAINT dd_probe_chk CHECK ( login_name NOT LIKE 'leak-login-%' OR full_name !~ ' 2$' )" )
              fi
            done
            exec psql "${args[@]}"
            """, StandardCharsets.UTF_8 );
        assertTrue( wrapper.toFile().setExecutable( true ), "cannot make the wrapper executable" );

        final Path script = MigrationApplier.findRepoRoot().resolve( "bin/db/one-shots/dedupe_display_names.sh" );
        final ProcessBuilder pb = new ProcessBuilder( "bash", script.toString(), "--apply" ).redirectErrorStream( true );
        final Map< String, String > env = pb.environment();
        env.put( "PSQL_COMMAND", wrapper.toString() );
        env.put( "PGHOST", url.group( 1 ) );
        env.put( "PGPORT", url.group( 2 ) );
        env.put( "PGDATABASE", url.group( 3 ) );
        env.put( "PGUSER", PostgresTestDb.getUsername() );
        env.put( "PGPASSWORD", PostgresTestDb.getPassword() );
        final Process process = pb.start();
        final String output = new String( process.getInputStream().readAllBytes(), StandardCharsets.UTF_8 );
        assertTrue( process.waitFor( 120, TimeUnit.SECONDS ), "the script did not finish" );

        assertNotEquals( 0, process.exitValue(), "the forced constraint failure must fail the script: " + output );
        assertTrue( output.contains( "dd_probe_chk" ), "the failure must be the forced one, else this proves nothing: " + output );
        for ( final String leak : new String[] { "leak-uid", "leak-email", "leak-login", "example.test", "Failing row" } ) {
            assertFalse( output.contains( leak ), "the error output must not carry account data (" + leak + "): " + output );
        }

        try ( Connection conn = PostgresTestDb.createDataSource().getConnection(); Statement s = conn.createStatement() ) {
            try ( ResultSet rs = s.executeQuery( "SELECT count(*) FROM users WHERE login_name LIKE 'leak-login-%'" ) ) {
                rs.next();
                assertEquals( 0, rs.getInt( 1 ), "the failed run rolled back everything it injected" );
            }
            try ( ResultSet rs = s.executeQuery( "SELECT count(*) FROM pg_constraint WHERE conname = 'dd_probe_chk'" ) ) {
                rs.next();
                assertEquals( 0, rs.getInt( 1 ), "and the constraint it added" );
            }
        }
    }
}
