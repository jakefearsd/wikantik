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
package com.wikantik.audit;

import com.wikantik.jdbc.testing.PostgresTestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression test for the audit writer against a <b>least-privilege</b> app role — a role with
 * {@code USAGE} but not {@code CREATE} on schema {@code public}, exactly as the V036 migration's
 * grant model intends (and the PostgreSQL 15+ default). Privileged test/CI roles hid the bug where
 * {@code append()} ran a table-creation {@code … PARTITION OF} DDL statement on every batch: it fails with
 * "permission denied for schema public" even when the partition already exists, rolling back the
 * whole batch and silently losing the audit trail.
 *
 * <p>Schema comes from the real {@code bin/db/migrations} (V036/V037) via {@link PostgresTestDb} —
 * not a hand-written copy — so {@code audit_log} is already partitioned exactly as prod is, and
 * {@code JdbcAuditRepository#ensurePartition}'s DDL-skip path is exercised against the genuine
 * pre-created current-month partition (migrations pre-create the current + next two months).
 */
@Testcontainers( disabledWithoutDocker = true )
class JdbcAuditRepositoryTest {

    private static final String APP_ROLE = "audit_app_lowpriv";
    private static final String APP_PW = "lowpriv_pw";

    private static DataSource superuserDs;   // migrated-schema owner (container default)
    private static DataSource restrictedDs;  // least-privilege app role

    @BeforeAll
    static void setUp() throws Exception {
        superuserDs = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "audit_log" );

        try ( Connection c = superuserDs.getConnection(); Statement st = c.createStatement() ) {
            // Least-privilege role: USAGE but NOT CREATE on public; INSERT/SELECT on the parent only.
            st.execute( "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='" + APP_ROLE + "') "
                    + "THEN CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PW + "'; END IF; END $$" );
            st.execute( "REVOKE CREATE ON SCHEMA public FROM PUBLIC" );
            st.execute( "REVOKE CREATE ON SCHEMA public FROM " + APP_ROLE );
            st.execute( "GRANT USAGE ON SCHEMA public TO " + APP_ROLE );
            st.execute( "GRANT SELECT, INSERT ON audit_log TO " + APP_ROLE );
            st.execute( "GRANT USAGE, SELECT ON SEQUENCE audit_log_seq TO " + APP_ROLE );
        }

        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( PostgresTestDb.getJdbcUrl() );
        ds.setUser( APP_ROLE );
        ds.setPassword( APP_PW );
        restrictedDs = ds;
    }

    @Test
    void append_succeeds_for_least_privilege_role_when_partition_exists() throws Exception {
        final JdbcAuditRepository repo = new JdbcAuditRepository( restrictedDs );
        final AuditEntry entry = AuditEntry.builder()
                .eventTime( Instant.now() )
                .category( AuditCategory.ADMIN )
                .eventType( "smoke.test" )
                .actorType( "TEST" )
                .actorPrincipal( "tester" )
                .outcome( AuditOutcome.SUCCESS )
                // Compact JSON in a TEXT column: must round-trip byte-for-byte for the hash chain. A
                // stray ?::jsonb cast would reformat this to {"src": "smoke", "n": 1} and fail below.
                .detail( "{\"src\":\"smoke\",\"n\":1}" )
                .build();

        // Before the fix this throws IllegalStateException("audit append failed") because
        // ensurePartition() runs a table-creation … PARTITION OF DDL, which a no-CREATE role can't execute.
        assertDoesNotThrow( () -> repo.append( List.of( entry ) ),
                "append() must not run DDL when the partition already exists — a least-privilege "
                        + "app role (no CREATE on schema public) must be able to write the audit trail" );

        try ( Connection c = superuserDs.getConnection();
              Statement st = c.createStatement();
              ResultSet rs = st.executeQuery(
                      "SELECT detail FROM audit_log WHERE event_type = 'smoke.test'" ) ) {
            assertEquals( true, rs.next(), "the audit entry must have been persisted" );
            assertEquals( "{\"src\":\"smoke\",\"n\":1}", rs.getString( 1 ),
                    "detail must round-trip byte-for-byte (TEXT, no JSONB reformatting)" );
        }
    }
}
