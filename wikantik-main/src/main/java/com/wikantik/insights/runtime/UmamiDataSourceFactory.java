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
package com.wikantik.insights.runtime;

import com.wikantik.analytics.AnalyticsSettings;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

/**
 * Builds the read-only {@link DataSource} onto the Umami suite's database (a different server from the wiki's
 * own). The settings' read-back URL is a full JDBC URL; connections are read-only and bounded so a slow or
 * absent Umami cannot hold a read-back thread indefinitely.
 */
public final class UmamiDataSourceFactory {

    private UmamiDataSourceFactory() {}

    public static DataSource create( final AnalyticsSettings settings ) {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( settings.readbackUrl() );
        ds.setUser( settings.readbackUser() );
        ds.setPassword( settings.readbackPassword() );
        ds.setReadOnly( true );
        ds.setConnectTimeout( 5 );
        ds.setSocketTimeout( 120 );
        ds.setLoginTimeout( 5 );
        ds.setOptions( "-c statement_timeout=60000 -c TimeZone=UTC" );
        return ds;
    }
}
