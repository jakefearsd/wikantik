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
package com.wikantik.search.embedding.experiment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Direct JDBC connectivity for the sandbox harness. The wiki runs under
 * Tomcat and gets its {@code DataSource} via JNDI, but the experiment
 * runners start from {@code main()} outside any container — they take
 * connection parameters from system properties.
 */
public final class ExperimentDb {

    public static final String PROP_URL      = "wikantik.experiment.db.url";
    public static final String PROP_USER     = "wikantik.experiment.db.user";
    public static final String PROP_PASSWORD = "wikantik.experiment.db.password";

    public static final String DEFAULT_URL  = "jdbc:postgresql://localhost:5432/jspwiki";
    public static final String DEFAULT_USER = "jspwiki";

    private ExperimentDb() {}

    public static Connection open() throws SQLException {
        final String url  = sysOrDefault( PROP_URL,  DEFAULT_URL );
        final String user = sysOrDefault( PROP_USER, DEFAULT_USER );
        final String pw   = System.getProperty( PROP_PASSWORD );
        if( pw == null || pw.isBlank() ) {
            throw new IllegalStateException( "set -D" + PROP_PASSWORD + "=<pw> (database password for "
                + user + "@" + url + ")" );
        }
        return DriverManager.getConnection( url, user, pw );
    }

    private static String sysOrDefault( final String key, final String fallback ) {
        final String v = System.getProperty( key );
        return v == null || v.isBlank() ? fallback : v;
    }
}
