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
}
