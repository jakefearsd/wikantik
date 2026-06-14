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
package com.wikantik.citation;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Test helper that creates an H2 DataSource (PostgreSQL compatibility mode) and
 * bootstraps the {@code citations} table for unit tests. Mirrors the approach used
 * by {@code DriftSnapshotRepositoryTest}.
 */
final class TestCitationDb {

    private TestCitationDb() {}

    /** Returns a fresh in-memory H2 DataSource in PostgreSQL compatibility mode. */
    static DataSource h2DataSource() {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:citations" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        return h2;
    }

    /** Creates the {@code citations} table in the given connection (H2-compatible DDL). */
    static void createSchema( final Connection c ) throws Exception {
        try ( Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE citations (
                    id                    BIGSERIAL   PRIMARY KEY,
                    source_canonical_id   TEXT        NOT NULL,
                    target_canonical_id   TEXT        NOT NULL,
                    target_heading_path   TEXT        NOT NULL DEFAULT '',
                    span_text             TEXT        NOT NULL DEFAULT '',
                    span_hash             TEXT        NOT NULL,
                    claim_text            TEXT,
                    ordinal               INT         NOT NULL DEFAULT 0,
                    pinned_target_version INT,
                    status                TEXT        NOT NULL DEFAULT 'current',
                    first_seen            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                    last_checked          TIMESTAMP WITH TIME ZONE,
                    last_status_change    TIMESTAMP WITH TIME ZONE,
                    CONSTRAINT uq_citation UNIQUE (source_canonical_id, target_canonical_id,
                                                   target_heading_path, span_hash, ordinal)
                )""" );
        }
    }
}
