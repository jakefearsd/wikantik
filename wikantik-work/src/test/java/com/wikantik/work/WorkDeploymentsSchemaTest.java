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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins V082: deployments, the items they carry and their append-only history (work management phase 4). */
@RequiresPostgres
class WorkDeploymentsSchemaTest {

    private DataSource ds;
    private long item;
    private long product;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final long p = scalar( "INSERT INTO work_project (key, name) VALUES ('WIKI', 'Wiki') RETURNING id" );
        product = scalar( "INSERT INTO work_product (key, name) VALUES ('wikantik', 'Wikantik') RETURNING id" );
        item = scalar( "INSERT INTO work_item (project_id, seq, level, kind, title, rank, reporter, product_id) VALUES ("
            + p + ",1,'task','software','t',1.0,'alice'," + product + ") RETURNING id" );
    }

    private void exec( final String sql ) throws SQLException {
        WorkAgentFixtures.exec( ds, sql );
    }

    private long scalar( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private long deployment() throws SQLException {
        return scalar( "INSERT INTO work_deployment (product_id, version, requested_by) VALUES (" + product
            + ", '2.5.26', 'alice') RETURNING id" );
    }

    private static String state( final SQLException e ) {
        return e.getSQLState();
    }

    private static String v082() throws Exception {
        return Files.readString( Path.of( "..", "bin", "db", "migrations", "V082__work_deployments.sql" ) );
    }

    @Test
    void aDeploymentStartsRequestedInProduction() throws SQLException {
        final long d = deployment();
        assertEquals( 1, scalar( "SELECT count(*) FROM work_deployment WHERE id = " + d
            + " AND state = 'requested' AND environment = 'production' AND evidence IS NULL" ) );
    }

    @Test
    void aVerifiedDeploymentNeedsEvidence() throws SQLException {
        final long d = deployment();
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_deployment SET state = 'verified' WHERE id = " + d ) ) ) );
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_deployment SET state = 'verified', evidence = '  ' WHERE id = " + d ) ) ) );
        exec( "UPDATE work_deployment SET state = 'verified', evidence = 'health 200; smoke ok' WHERE id = " + d );
    }

    @Test
    void anUnknownStateIsRefused() throws SQLException {
        final long d = deployment();
        assertEquals( "23514", state( assertThrows( SQLException.class,
            () -> exec( "UPDATE work_deployment SET state = 'done' WHERE id = " + d ) ) ) );
    }

    @Test
    void anItemIsCarriedOnceByADeployment() throws SQLException {
        final long d = deployment();
        exec( "INSERT INTO work_deployment_item (deployment_id, item_id) VALUES (" + d + "," + item + ")" );
        assertEquals( "23505", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_deployment_item (deployment_id, item_id) VALUES (" + d + "," + item + ")" ) ) ) );
        exec( "INSERT INTO work_deployment_item (deployment_id, item_id) VALUES (" + deployment() + "," + item + ")" );
    }

    @Test
    void historyNamesItsActorKindAsTheEventLogDoes() throws SQLException {
        final long d = deployment();
        exec( "INSERT INTO work_deployment_event (deployment_id, actor, actor_kind, to_state) VALUES (" + d
            + ", 'alice', 'user', 'requested')" );
        assertEquals( "23514", state( assertThrows( SQLException.class, () -> exec(
            "INSERT INTO work_deployment_event (deployment_id, actor, actor_kind, to_state) VALUES (" + d
                + ", 'alice', 'human', 'requested')" ) ) ) );
    }

    /** The test container's role is a superuser, so the append-only grants are pinned on the migration text. */
    @Test
    void theAppRoleCannotRewriteHistoryOrRemoveACarriedItem() throws Exception {
        final List< String > grants = Arrays.stream( v082().replaceAll( "(?m)^--.*$", "" ).split( ";" ) )
            .filter( st -> st.contains( "GRANT" ) ).map( st -> st.replaceAll( "\\s+", " " ).trim() ).toList();
        assertEquals( List.of( "GRANT SELECT, INSERT, UPDATE ON work_deployment TO :app_user",
            "GRANT SELECT, INSERT ON work_deployment_item, work_deployment_event TO :app_user" ), grants );
    }

    @Test
    void v082IsANoOpWhenReapplied() throws Exception {
        final String sql = v082().replace( ":app_user", "CURRENT_USER" );
        exec( sql );
        exec( sql );
        deployment();
    }
}
