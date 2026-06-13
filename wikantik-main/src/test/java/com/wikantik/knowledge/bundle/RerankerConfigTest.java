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
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RerankerConfigTest {

    @Test
    void emptyOrNullProperties_yieldDefaults() {
        final RerankerConfig fromEmpty = RerankerConfig.fromProperties( new Properties() );
        assertEquals( "gemma4:e4b", fromEmpty.model() );
        assertEquals( "http://inference.jakefear.com:11434", fromEmpty.baseUrl() );
        assertEquals( 30_000L, fromEmpty.timeoutMs() );

        final RerankerConfig fromNull = RerankerConfig.fromProperties( null );
        assertEquals( "gemma4:e4b", fromNull.model() );
        assertEquals( 30_000L, fromNull.timeoutMs() );
    }

    @Test
    void presentKeys_override() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.model", "qwen3:4b" );
        p.setProperty( "wikantik.bundle.reranker.base_url", "http://gpu.local:11434" );
        p.setProperty( "wikantik.bundle.reranker.timeout_ms", "12000" );
        final RerankerConfig c = RerankerConfig.fromProperties( p );
        assertEquals( "qwen3:4b", c.model() );
        assertEquals( "http://gpu.local:11434", c.baseUrl() );
        assertEquals( 12_000L, c.timeoutMs() );
    }

    @Test
    void unparseableTimeout_fallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.timeout_ms", "soon" );
        assertEquals( 30_000L, RerankerConfig.fromProperties( p ).timeoutMs() );
    }
}
