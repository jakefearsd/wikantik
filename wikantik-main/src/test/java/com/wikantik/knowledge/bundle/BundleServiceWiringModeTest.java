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

import com.wikantik.api.config.GenAiMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code wikantik.genai.mode} acts as a ceiling on the bundle's LLM
 * section reranker: it must never construct an {@link LlmSectionReranker}
 * (via either activation path — {@code wikantik.bundle.reranker.enabled} or
 * an {@code llm} token in {@code wikantik.bundle.rerank.chain}) when the mode
 * disallows chat inference.
 */
class BundleServiceWiringModeTest {

    private static CandidateSection sec( final String head ) {
        return new CandidateSection( "p", List.of( head ), head + " text", 0.5 );
    }

    /* ---------- legacy activation path: wikantik.bundle.reranker.enabled ---------- */

    @Test
    void modeAbsent_legacyEnabledFlag_behavesIdenticallyToToday() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "true" );

        assertInstanceOf( LlmSectionReranker.class, BundleServiceWiring.rerankerFor( p ),
            "no genai.mode set -> regression guard: unchanged behavior" );
    }

    @Test
    void embeddingsOnly_legacyEnabledFlag_neverConstructsLlmReranker() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "true" );
        p.setProperty( GenAiMode.PROP, "embeddings-only" );

        final SectionReranker r = BundleServiceWiring.rerankerFor( p );

        assertFalse( r instanceof LlmSectionReranker,
            "embeddings-only must never construct the LLM reranker even with reranker.enabled=true" );
        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertSame( in, r.rerank( "q", in ), "must degrade to identity" );
    }

    @Test
    void none_legacyEnabledFlag_neverConstructsLlmReranker() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "true" );
        p.setProperty( GenAiMode.PROP, "none" );

        assertFalse( BundleServiceWiring.rerankerFor( p ) instanceof LlmSectionReranker,
            "none must never construct the LLM reranker" );
    }

    @Test
    void individualFlagOff_staysOffUnderFullMode() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.reranker.enabled", "false" );
        p.setProperty( GenAiMode.PROP, "full" );

        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertSame( in, BundleServiceWiring.rerankerFor( p ).rerank( "q", in ),
            "reranker.enabled=false under mode=full must remain identity" );
    }

    /* ---------- chain activation path: wikantik.bundle.rerank.chain ---------- */

    @Test
    void modeAbsent_chainWithLlm_behavesIdenticallyToToday() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "llm,metadata-boost" );

        final SectionReranker r = BundleServiceWiring.rerankerFor( p, slug -> null );

        assertInstanceOf( SectionRerankChain.class, r );
        final SectionRerankChain chain = (SectionRerankChain) r;
        assertEquals( 2, chain.stages().size(), "no genai.mode set -> both stages present (regression guard)" );
        assertInstanceOf( LlmSectionReranker.class, chain.stages().get( 0 ) );
    }

    @Test
    void embeddingsOnly_chainWithLlm_skipsLlmStage_keepsNonLlmStages() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "llm,metadata-boost" );
        p.setProperty( GenAiMode.PROP, "embeddings-only" );

        final SectionReranker r = BundleServiceWiring.rerankerFor( p, slug -> com.wikantik.api.pagegraph.Confidence.PROVISIONAL );

        assertInstanceOf( SectionRerankChain.class, r,
            "chain must still be built with its non-LLM stages" );
        final SectionRerankChain chain = (SectionRerankChain) r;
        assertEquals( 1, chain.stages().size(), "the llm stage must be skipped" );
        assertInstanceOf( MetadataBoostSectionReranker.class, chain.stages().get( 0 ),
            "the non-LLM stage must survive" );
        assertTrue( chain.stages().stream().noneMatch( s -> s instanceof LlmSectionReranker ) );
    }

    @Test
    void none_chainWithLlm_skipsLlmStage_keepsNonLlmStages() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "mmr,llm" );
        p.setProperty( GenAiMode.PROP, "none" );

        final SectionReranker r = BundleServiceWiring.rerankerFor( p, null );

        final SectionRerankChain chain = (SectionRerankChain) r;
        assertEquals( 1, chain.stages().size(), "the llm stage must be skipped" );
        assertInstanceOf( MmrSectionReranker.class, chain.stages().get( 0 ), "the non-LLM stage must survive" );
        assertTrue( chain.stages().stream().noneMatch( s -> s instanceof LlmSectionReranker ) );
    }

    @Test
    void chainWithOnlyLlm_disallowedMode_degradesToIdentity() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.bundle.rerank.chain", "llm" );
        p.setProperty( GenAiMode.PROP, "none" );

        final List< CandidateSection > in = List.of( sec( "A" ) );
        assertSame( in, BundleServiceWiring.rerankerFor( p, null ).rerank( "q", in ),
            "an all-llm chain under a disallowing mode degrades to identity" );
    }
}
