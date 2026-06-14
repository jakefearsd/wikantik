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

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class BundleServiceWiringTest {

    private static CandidateSection sec( final String head ) {
        return new CandidateSection( "p", List.of( head ), head + " text", 0.5 );
    }

    @Test
    void rerankerDisabledByDefault_isIdentity_noNetwork() {
        // No 'enabled' key -> default OFF: identity reranker that returns the input untouched
        // (the 2026-06-13 live measurement showed the reranker is ordering-only, +1.5s, no recall).
        final SectionReranker r = BundleServiceWiring.rerankerFor( new Properties() );
        final List< CandidateSection > in = List.of( sec( "A" ), sec( "B" ) );
        assertSame( in, r.rerank( "q", in ), "disabled reranker must return the input list unchanged" );
    }

    @Test
    void rerankerEnabled_isLlmReranker() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "true" );
        assertInstanceOf( LlmSectionReranker.class, BundleServiceWiring.rerankerFor( p ),
            "enabled=true must select the LLM reranker" );
    }

    @Test
    void rerankerExplicitlyFalse_isIdentity() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "false" );
        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertEquals( in, BundleServiceWiring.rerankerFor( p ).rerank( "q", in ) );
    }
}
