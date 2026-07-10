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
package com.wikantik.knowledge.eval;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** Fail-safe JDBC writer for {@link BundleEvalRun} rows. A write failure is swallowed with a
 *  {@code LOG.warn} and never propagates — a flaky eval log must never affect the app. */
public class BundleEvalRunDao {

    private static final Logger LOG = LogManager.getLogger( BundleEvalRunDao.class );

    private static final String INSERT_SQL =
        "INSERT INTO bundle_eval_run (config_id, overall_recall, overall_precision, "
        + "recall_similarity, recall_relational, recall_boundary, questions_scored, regression) "
        + "VALUES (?,?,?,?,?,?,?,?)";

    private final DataSource dataSource;

    public BundleEvalRunDao( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    public void insert( final BundleEvalRun run ) {
        if ( run == null ) {
            return;
        }
        try ( Connection conn = dataSource.getConnection();
              PreparedStatement ps = conn.prepareStatement( INSERT_SQL ) ) {
            ps.setString( 1, run.configId() );
            ps.setDouble( 2, run.overallRecall() );
            ps.setDouble( 3, run.overallPrecision() );
            ps.setDouble( 4, run.recallSimilarity() );
            ps.setDouble( 5, run.recallRelational() );
            ps.setDouble( 6, run.recallBoundary() );
            ps.setInt( 7, run.questionsScored() );
            ps.setBoolean( 8, run.regression() );
            ps.executeUpdate();
        } catch ( final Exception e ) {
            LOG.warn( "bundle_eval_run write failed (configId={}): {}", run.configId(), e.getMessage() );
        }
    }
}
