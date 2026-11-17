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
package com.wikantik.work.runtime;

import com.wikantik.TestEngine;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkWiringTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private static Properties props( final String enabled ) {
        final Properties p = new Properties();
        if ( enabled != null ) {
            p.setProperty( WorkWiring.PROP_ENABLED, enabled );
        }
        return p;
    }

    @Test
    void disabledFlagBuildsNothing() {
        assertNull( WorkWiring.build( Mockito.mock( DataSource.class ), props( "false" ), engine ) );
    }

    @Test
    void absentFlagMeansDisabled() {
        assertNull( WorkWiring.build( Mockito.mock( DataSource.class ), props( null ), engine ) );
    }

    @Test
    void enabledWithoutADatasourceBuildsNothing() {
        assertNull( WorkWiring.build( null, props( "true" ), engine ) );
    }

    @Test
    void enabledWithADatasourceBuildsTheService() {
        final WorkService service = WorkWiring.build( Mockito.mock( DataSource.class ), props( "true" ), engine );
        assertNotNull( service );
    }

    private static Properties expiry( final String minutes ) {
        final Properties p = new Properties();
        if ( minutes != null ) {
            p.setProperty( WorkWiring.PROP_CLAIM_EXPIRY, minutes );
        }
        return p;
    }

    @Test
    void claimExpiryReadFromProperty() {
        assertEquals( java.time.Duration.ofMinutes( 30 ), WorkWiring.claimExpiry( expiry( "30" ) ) );
        assertEquals( java.time.Duration.ofMinutes( 1 ), WorkWiring.claimExpiry( expiry( "1" ) ) );
        assertEquals( java.time.Duration.ofMinutes( 10080 ), WorkWiring.claimExpiry( expiry( "10080" ) ) );
        assertEquals( "wikantik.work.claim.expiryMinutes", WorkWiring.PROP_CLAIM_EXPIRY );
    }

    @Test
    void outOfRangeExpiryFallsBackTo240() {
        for ( final String bad : new String[] { "0", "-5", "10081", "99999", "soon", "" } ) {
            assertEquals( java.time.Duration.ofMinutes( 240 ), WorkWiring.claimExpiry( expiry( bad ) ), bad );
        }
        assertEquals( java.time.Duration.ofMinutes( 240 ), WorkWiring.claimExpiry( expiry( null ) ) );
    }

    @Test
    void theServiceIsBuiltWithOrWithoutAMetricsRegistry() {
        final io.micrometer.core.instrument.MeterRegistry before = com.wikantik.api.observability.MeterRegistryHolder.get();
        try {
            com.wikantik.api.observability.MeterRegistryHolder.clear();
            assertNotNull( WorkWiring.build( Mockito.mock( DataSource.class ), props( "true" ), engine ),
                "no registry: work management still starts, unmeasured" );
            com.wikantik.api.observability.MeterRegistryHolder.set(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry() );
            assertNotNull( WorkWiring.build( Mockito.mock( DataSource.class ), props( " true " ), engine ),
                "a padded flag still reads as on" );
        } finally {
            if ( before == null ) {
                com.wikantik.api.observability.MeterRegistryHolder.clear();
            } else {
                com.wikantik.api.observability.MeterRegistryHolder.set( before );
            }
        }
    }
}
