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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three {@link IllegalStateException} gate branches of {@code resolveAnthropicKey}
 * (the shared cost-guard / key-env / populated-env gate behind both {@code --extractor claude}
 * and {@code --judge claude}) plus the populated-key happy path.
 */
class BootstrapExtractionCliClaudeGateTest {

    private static final String GATE = "wikantik.kg.extractor.allow_claude";
    private static final String LABEL = "--extractor claude";

    @Test
    void gateDisabled_throwsCostGuard() {
        System.clearProperty( GATE );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> BootstrapExtractionCli.resolveAnthropicKey( "ANY_VAR", GATE, LABEL ) );
        assertTrue( ex.getMessage().contains( "gated cost guard" ), ex.getMessage() );
        assertTrue( ex.getMessage().contains( LABEL ), ex.getMessage() );
    }

    @Test
    void gateEnabled_missingKeyEnv_throws() {
        System.setProperty( GATE, "true" );
        try {
            assertTrue( assertThrows( IllegalStateException.class,
                () -> BootstrapExtractionCli.resolveAnthropicKey( null, GATE, LABEL ) )
                .getMessage().contains( "--anthropic-key-env" ) );
            assertTrue( assertThrows( IllegalStateException.class,
                () -> BootstrapExtractionCli.resolveAnthropicKey( "  ", GATE, LABEL ) )
                .getMessage().contains( "--anthropic-key-env" ) );
        } finally {
            System.clearProperty( GATE );
        }
    }

    @Test
    void gateEnabled_unsetEnvVar_throws() {
        System.setProperty( GATE, "true" );
        try {
            final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> BootstrapExtractionCli.resolveAnthropicKey( "WIKANTIK_DEFINITELY_UNSET_ENV_XYZ", GATE, LABEL ) );
            assertTrue( ex.getMessage().contains( "unset or empty" ), ex.getMessage() );
        } finally {
            System.clearProperty( GATE );
        }
    }

    @Test
    void gateEnabled_populatedEnvVar_returnsKey() {
        // PATH is reliably set in the test environment; resolveAnthropicKey returns its value verbatim.
        Assumptions.assumeTrue( System.getenv( "PATH" ) != null && !System.getenv( "PATH" ).isBlank() );
        System.setProperty( GATE, "true" );
        try {
            assertEquals( System.getenv( "PATH" ),
                BootstrapExtractionCli.resolveAnthropicKey( "PATH", GATE, LABEL ) );
        } finally {
            System.clearProperty( GATE );
        }
    }
}
