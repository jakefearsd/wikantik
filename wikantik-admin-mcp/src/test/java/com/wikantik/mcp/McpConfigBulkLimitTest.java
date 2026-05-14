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
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

public class McpConfigBulkLimitTest {

    @Test
    void defaultBulkLimitIs50() {
        assertEquals( 50, new McpConfig( new Properties() ).kgCurationBulkLimit() );
    }

    @Test
    void zeroOrNegativeFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.kg_curation.bulk_limit", "0" );
        assertEquals( 50, new McpConfig( p ).kgCurationBulkLimit() );
    }

    @Test
    void positiveValueIsHonoured() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.kg_curation.bulk_limit", "12" );
        assertEquals( 12, new McpConfig( p ).kgCurationBulkLimit() );
    }

    @Test
    void defaultRateLimiterMaxClientsIs10000() {
        assertEquals( 10000, new McpConfig( new Properties() ).rateLimiterMaxClients() );
    }

    @Test
    void rateLimiterMaxClientsZeroOrNegativeFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.rate_limit.max_clients", "0" );
        assertEquals( 10000, new McpConfig( p ).rateLimiterMaxClients() );
    }

    @Test
    void rateLimiterMaxClientsHonoursPositiveValue() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.rate_limit.max_clients", "500" );
        assertEquals( 500, new McpConfig( p ).rateLimiterMaxClients() );
    }

    // Covers McpConfig.java:215-228 — non-positive integer values (negative
    // and non-numeric) for wikantik.mcp.rate_limit.max_clients must fall back
    // to the bundled default rather than configuring a non-functional limiter.
    @Test
    void rateLimiterMaxClientsNonPositiveFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.rate_limit.max_clients", "-7" );
        assertEquals( 10000, new McpConfig( p ).rateLimiterMaxClients() );

        p.setProperty( "wikantik.mcp.rate_limit.max_clients", "not-a-number" );
        assertEquals( 10000, new McpConfig( p ).rateLimiterMaxClients() );
    }
}
