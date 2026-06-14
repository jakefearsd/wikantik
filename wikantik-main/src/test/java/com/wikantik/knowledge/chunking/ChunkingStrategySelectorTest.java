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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.derived.DerivedPage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Documents the behavior-neutral "flat-and-tuned v1" seam:
 * both hand-authored and derived pages receive the same default Config.
 */
class ChunkingStrategySelectorTest {

    private static final ContentChunker.Config DEFAULT_CONFIG =
        new ContentChunker.Config( 512, 150, 24, 40 );

    private final ChunkingStrategySelector selector = new ChunkingStrategySelector();

    @Test
    void handAuthoredPageReturnsDefaultConfig() {
        final ParsedPage page = new ParsedPage( Map.of( "type", "article" ), "Some body text." );
        final ContentChunker.Config result = selector.configFor( page, DEFAULT_CONFIG );
        assertEquals( DEFAULT_CONFIG, result,
            "hand-authored article page must use the default config unchanged (flat-and-tuned v1)" );
    }

    @Test
    void derivedPageReturnsDefaultConfig() {
        final ParsedPage page = new ParsedPage(
            Map.of( DerivedPage.DERIVED_FROM, "Research.pdf", "type", "reference" ),
            "Extracted body from PDF." );
        final ContentChunker.Config result = selector.configFor( page, DEFAULT_CONFIG );
        assertEquals( DEFAULT_CONFIG, result,
            "derived page must use the same default config in v1 (per-type tuning is harness-gated)" );
    }

    @Test
    void nullMetadataPageReturnsDefaultConfig() {
        // ParsedPage with empty metadata — selector must be null-safe
        final ParsedPage page = new ParsedPage( Map.of(), "" );
        final ContentChunker.Config result = selector.configFor( page, DEFAULT_CONFIG );
        assertNotNull( result );
        assertEquals( DEFAULT_CONFIG, result );
    }

    @Test
    void returnedConfigIsTheSameInstance() {
        // v1 returns the exact defaultConfig reference — documents the identity contract
        final ParsedPage page = new ParsedPage( Map.of( "type", "runbook" ), "Steps." );
        assertSame( DEFAULT_CONFIG, selector.configFor( page, DEFAULT_CONFIG ),
            "v1 selector must return the exact defaultConfig instance (no copying)" );
    }
}
