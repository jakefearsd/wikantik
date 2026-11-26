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
package com.wikantik.agentreports.runtime;

import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.JdbcAgentReportStore;
import com.wikantik.jdbc.Jdbc;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Properties;

/**
 * Builds the {@link AgentReportService} at startup: datasource, {@link Jdbc}, store, service. Returns {@code null}
 * without a datasource, so the {@code file_agent_report} tool answers "report store unavailable".
 */
public final class AgentReportsWiring {

    /** Attempts one key may make per hour; zero or less disables the limit. */
    public static final String KEY_MAX_PER_HOUR = "wikantik.agentReports.maxPerHourPerKey";

    private static final int DEFAULT_MAX_PER_HOUR = 60;

    private AgentReportsWiring() {}

    public static AgentReportService build( final DataSource ds, final Properties props ) {
        if ( ds == null ) {
            return null;
        }
        return new AgentReportService( new JdbcAgentReportStore( new Jdbc( ds ) ),
                maxPerHour( props ), Clock.systemUTC() );
    }

    static int maxPerHour( final Properties props ) {
        return com.wikantik.util.TextUtil.getIntegerProperty( props, KEY_MAX_PER_HOUR, DEFAULT_MAX_PER_HOUR );
    }
}
