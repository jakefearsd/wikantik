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
package com.wikantik.auth.apikeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Holder is a tiny façade over JNDI but it sits on every request path of
 * both filters (MCP, tools) and the admin REST resource — silent breakage
 * here would mask all DB-key authentication. Tests cover the no-datasource
 * fail-soft path, the JNDI failure fail-soft path, and the testing hook
 * caching contract.
 */
class ApiKeyServiceHolderTest {

    @BeforeEach
    @AfterEach
    void resetHolder() {
        // Holder caches at static-field level; clear before AND after each test
        // so we never leak state into siblings or subsequent test classes.
        ApiKeyServiceHolder.setForTesting( null );
    }

    @Test
    void returnsNullWhenPropertiesIsNull() {
        assertNull( ApiKeyServiceHolder.get( null ),
                "Null properties must fail soft, not NPE" );
    }

    @Test
    void returnsNullWhenDatasourcePropertyMissing() {
        assertNull( ApiKeyServiceHolder.get( new Properties() ),
                "Missing wikantik.datasource means DB keys disabled — return null" );
    }

    @Test
    void returnsNullWhenDatasourcePropertyBlank() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.datasource", "   " );
        assertNull( ApiKeyServiceHolder.get( p ),
                "Blank datasource property must be treated like absent" );
    }

    @Test
    void returnsNullWhenJndiLookupFails() {
        // No JNDI context configured in the test JVM; lookup of the bound name
        // throws and the holder must swallow it (logged at warn) and return null
        // so callers fall back to legacy keys instead of crashing.
        final Properties p = new Properties();
        p.setProperty( "wikantik.datasource", "jdbc/NoSuchDataSourceInTest" );
        assertNull( ApiKeyServiceHolder.get( p ),
                "JNDI failure must fail soft so legacy-key path still works" );
    }

    @Test
    void setForTestingInstallsServiceAndIsReturnedRegardlessOfProperties() {
        final ApiKeyService injected = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( injected );

        // No datasource property at all — the cached instance must still come back.
        assertSame( injected, ApiKeyServiceHolder.get( new Properties() ),
                "setForTesting must short-circuit the JNDI path" );
        // Even if a datasource is set, cache wins.
        final Properties p = new Properties();
        p.setProperty( "wikantik.datasource", "jdbc/Whatever" );
        assertSame( injected, ApiKeyServiceHolder.get( p ),
                "Cached service must take precedence over a fresh JNDI lookup" );
    }

    @Test
    void setForTestingWithNullClearsCache() {
        ApiKeyServiceHolder.setForTesting( mock( ApiKeyService.class ) );
        ApiKeyServiceHolder.setForTesting( null );

        // Now a get with no datasource must return null (cache truly cleared).
        assertNull( ApiKeyServiceHolder.get( new Properties() ),
                "setForTesting(null) must drop the cached instance" );
    }

    @Test
    void cachedInstanceIsReusedAcrossCalls() {
        final ApiKeyService injected = mock( ApiKeyService.class );
        ApiKeyServiceHolder.setForTesting( injected );

        final ApiKeyService a = ApiKeyServiceHolder.get( null );
        final ApiKeyService b = ApiKeyServiceHolder.get( new Properties() );
        assertNotNull( a );
        assertSame( a, b, "Holder must return the same cached instance on every call" );
    }
}
