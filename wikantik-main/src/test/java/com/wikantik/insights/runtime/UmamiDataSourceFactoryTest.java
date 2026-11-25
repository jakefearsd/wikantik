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
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UmamiDataSourceFactoryTest {

    @Test
    void setsReadOnlyAndTheTimeouts() throws Exception {
        final Properties p = new Properties();
        p.setProperty( AnalyticsSettings.PROP_READBACK_URL, "jdbc:postgresql://umami.lan:5432/umami" );
        p.setProperty( AnalyticsSettings.PROP_READBACK_USER, "ro_user" );
        p.setProperty( AnalyticsSettings.PROP_READBACK_PASSWORD, "s3cret" );
        final PGSimpleDataSource ds = assertInstanceOf( PGSimpleDataSource.class,
            UmamiDataSourceFactory.create( AnalyticsSettings.from( p ) ) );
        assertTrue( ds.isReadOnly() );
        assertEquals( 5, ds.getConnectTimeout() );
        assertEquals( 120, ds.getSocketTimeout() );
        assertEquals( 5, ds.getLoginTimeout() );
        assertEquals( "-c statement_timeout=60000 -c TimeZone=UTC", ds.getOptions() );
        assertEquals( "ro_user", ds.getUser() );
        assertEquals( "s3cret", ds.getPassword() );
        assertEquals( "umami.lan", ds.getServerNames()[0] );
        assertEquals( "umami", ds.getDatabaseName() );
    }
}
