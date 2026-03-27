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
package com.wikantik.observability.health;

import com.wikantik.api.core.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class EngineHealthCheckTest {

    @Mock private Engine engine;

    @Test
    void reportsUpWhenEngineIsConfigured() {
        when( engine.isConfigured() ).thenReturn( true );
        final EngineHealthCheck check = new EngineHealthCheck( engine );
        final HealthResult result = check.check();
        assertEquals( HealthStatus.UP, result.status() );
    }

    @Test
    void reportsDownWhenEngineIsNotConfigured() {
        when( engine.isConfigured() ).thenReturn( false );
        final EngineHealthCheck check = new EngineHealthCheck( engine );
        final HealthResult result = check.check();
        assertEquals( HealthStatus.DOWN, result.status() );
        assertTrue( result.detail().containsKey( "error" ) );
    }

    @Test
    void reportsDownWhenEngineIsNull() {
        final EngineHealthCheck check = new EngineHealthCheck( null );
        final HealthResult result = check.check();
        assertEquals( HealthStatus.DOWN, result.status() );
    }

    @Test
    void nameIsEngine() {
        final EngineHealthCheck check = new EngineHealthCheck( engine );
        assertEquals( "engine", check.name() );
    }

}
