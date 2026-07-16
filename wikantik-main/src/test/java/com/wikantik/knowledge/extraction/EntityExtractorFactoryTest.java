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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.EntityExtractor;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link EntityExtractorFactory#create(EntityExtractorConfig, Function)} — the
 * injectable env-lookup seam (Task 1.5 review follow-up) that makes the
 * {@code ANTHROPIC_API_KEY} gate deterministically testable. The public
 * {@link EntityExtractorFactory#create(EntityExtractorConfig)} binds
 * {@code System::getenv} to this same overload.
 */
class EntityExtractorFactoryTest {

    private static final Function< String, String > FAKE_KEY_ENV =
        name -> "ANTHROPIC_API_KEY".equals( name ) ? "sk-ant-test-not-a-real-key" : null;

    private static final Function< String, String > EMPTY_ENV = name -> null;

    private static EntityExtractorConfig config( final String backend ) {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", backend );
        return EntityExtractorConfig.fromProperties( p );
    }

    @Test
    void claudeBackendWithInjectedKeyReturnsClaudeExtractor() {
        // Building the Anthropic SDK client is local-only — no network until a call
        // is made — so a fake key deterministically exercises the claude branch.
        final Optional< EntityExtractor > e =
            EntityExtractorFactory.create( config( "claude" ), FAKE_KEY_ENV );
        assertTrue( e.isPresent() );
        assertInstanceOf( ClaudeEntityExtractor.class, e.get() );
    }

    @Test
    void claudeBackendWithoutKeyReturnsEmpty() {
        assertTrue( EntityExtractorFactory.create( config( "claude" ), EMPTY_ENV ).isEmpty() );
    }

    @Test
    void claudeBackendWithBlankKeyReturnsEmpty() {
        assertTrue( EntityExtractorFactory.create( config( "claude" ), name -> "  " ).isEmpty() );
    }

    @Test
    void ollamaBackendIgnoresEnvAndReturnsOllamaExtractor() {
        final Optional< EntityExtractor > e =
            EntityExtractorFactory.create( config( "ollama" ), EMPTY_ENV );
        assertTrue( e.isPresent() );
        assertInstanceOf( OllamaEntityExtractor.class, e.get() );
    }

    @Test
    void disabledBackendReturnsEmpty() {
        assertTrue( EntityExtractorFactory.create( config( "disabled" ), FAKE_KEY_ENV ).isEmpty() );
    }

    @Test
    void unknownBackendReturnsEmpty() {
        assertTrue( EntityExtractorFactory.create( config( "gpt-nope" ), FAKE_KEY_ENV ).isEmpty() );
    }
}
