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
package com.wikantik.work;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** SQL fixtures for tests that need real queue and agent rows (the registry store methods are a later task). */
final class WorkAgentFixtures {

    /** Tables a test that writes as an agent truncates, in one call. */
    static final String[] TABLES = { "work_agent_queue", "work_agent", "work_queue", "work_link", "work_mention",
        "work_comment", "work_event", "work_item_revision", "work_item", "work_member", "work_product",
        "work_project" };

    private WorkAgentFixtures() {
    }

    static long scalar( final DataSource ds, final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }

    static void exec( final DataSource ds, final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
        }
    }

    /** A fresh API key (random hash) of the given scope; {@code api_keys} is never truncated. */
    static long apiKey( final DataSource ds, final String scope ) throws SQLException {
        return scalar( ds, "INSERT INTO api_keys (key_hash, principal_login, scope) VALUES ('"
            + UUID.randomUUID().toString().replace( "-", "" ) + "', 'owner', '" + scope + "') RETURNING id" );
    }

    /** An agent row bound to the given key. */
    static long agent( final DataSource ds, final String name, final long keyId ) throws SQLException {
        return scalar( ds, "INSERT INTO work_agent (name, api_key_id) VALUES ('" + name + "'," + keyId
            + ") RETURNING id" );
    }

    /** An agent row on a fresh work-scope key. */
    static long agent( final DataSource ds, final String name ) throws SQLException {
        return agent( ds, name, apiKey( ds, "work" ) );
    }

    static void queue( final DataSource ds, final String key ) throws SQLException {
        exec( ds, "INSERT INTO work_queue (key, name) VALUES ('" + key + "', '" + key + "')" );
    }
}
