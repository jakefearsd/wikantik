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
package com.wikantik.knowledge.subsystem;

import com.wikantik.WikiEngine;
import com.wikantik.WikiSubsystemsTestFactory;
import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.chunking.ChunkProjector;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.extraction.AsyncEntityExtractionListener;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.extraction.ChunkEntityMentionRepository;
import com.wikantik.knowledge.extraction.ClaudePageExtractor;
import com.wikantik.knowledge.extraction.EntityExtractorConfig;
import com.wikantik.knowledge.extraction.EvidenceGroundingVerifier;
import com.wikantik.knowledge.extraction.OllamaPageExtractor;
import com.wikantik.knowledge.extraction.PageExtractionResponseParser;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Task 1.5: {@link KnowledgeWiringHelper#wireEntityExtraction} previously wired an
 * admin-batch {@link com.wikantik.api.knowledge.PageExtractor} only for
 * {@code backend=ollama}; {@code backend=claude} left
 * {@code /admin/knowledge-graph/extract-mentions} permanently 503 even with a working
 * chunk-level Claude extractor. These tests pin the fixed dispatch.
 *
 * <p>{@link KnowledgeWiringHelper#buildPageExtractor} is the extracted, deterministic
 * seam under test — it is package-private specifically so these assertions don't have
 * to route through {@link com.wikantik.knowledge.extraction.EntityExtractorFactory}'s
 * {@code ANTHROPIC_API_KEY} network gate, which this repository has no established
 * pattern for stubbing (the only prior Claude-backend env-var test,
 * {@code ClaudeProposalJudgeTest}, only ever exercises the deterministic
 * key-missing path). {@link ClaudePageExtractor} itself validates the key lazily at
 * {@code extract()} time, not at construction, so building one with a possibly-absent
 * key is safe and matches {@link OllamaPageExtractor}'s fail-open convention.</p>
 */
class KnowledgeWiringHelperTest {

    private static EntityExtractorConfig claudeConfig() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "claude" );
        p.setProperty( "wikantik.knowledge.extractor.claude.model", "claude-haiku-4-5" );
        return EntityExtractorConfig.fromProperties( p );
    }

    private static EntityExtractorConfig ollamaConfig() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );
        p.setProperty( "wikantik.knowledge.extractor.ollama.model", "gemma4-assist:latest" );
        return EntityExtractorConfig.fromProperties( p );
    }

    private static PageExtractionResponseParser parser() {
        return new PageExtractionResponseParser( new EvidenceGroundingVerifier(), 12, 8 );
    }

    // ---- buildPageExtractor: the exact gap ----

    @Test
    void buildPageExtractorClaudeBackendReturnsClaudePageExtractor() {
        final PageExtractor extractor = KnowledgeWiringHelper.buildPageExtractor( claudeConfig(), parser() );
        assertInstanceOf( ClaudePageExtractor.class, extractor );
        assertTrue( extractor.code().startsWith( "claude:" ), "code() was: " + extractor.code() );
    }

    @Test
    void buildPageExtractorOllamaBackendReturnsOllamaPageExtractor() {
        // Regression pin — must keep working exactly as before the claude branch was added.
        final PageExtractor extractor = KnowledgeWiringHelper.buildPageExtractor( ollamaConfig(), parser() );
        assertInstanceOf( OllamaPageExtractor.class, extractor );
        assertTrue( extractor.code().startsWith( "ollama:" ), "code() was: " + extractor.code() );
    }

    // ---- wireBootstrapIndexer: claude backend wires a non-null indexer ----

    @Test
    void wireBootstrapIndexerClaudeBackendWiresIndexerWithoutThrowing() {
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = mock( DataSource.class );
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final ChunkEntityMentionRepository mentionRepo = mock( ChunkEntityMentionRepository.class );
        final KgNodeRepository kgNodes = mock( KgNodeRepository.class );
        final KgExcludedPagesRepository excludedPagesRepo = mock( KgExcludedPagesRepository.class );
        final PersistenceSubsystem.Services persistence =
            WikiSubsystemsTestFactory.mockRecord( PersistenceSubsystem.Services.class );

        KnowledgeWiringHelper.wireBootstrapIndexer(
            new Properties(), ds, chunkRepo, mentionRepo, kgNodes, excludedPagesRepo,
            claudeConfig(), persistence, engine );

        verify( engine ).setManager( eq( BootstrapEntityExtractionIndexer.class ), any() );
    }

    // ---- wireEntityExtraction: end-to-end dispatch + prior-work regressions ----

    @Test
    void wireEntityExtractionOllamaBackendWiresBootstrapIndexerEndToEnd() {
        // Ollama needs no external credential, so this exercises the full dispatch
        // (including the if/else this task modified) deterministically.
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final KgExcludedPagesRepository excludedPagesRepo = mock( KgExcludedPagesRepository.class );
        final PersistenceSubsystem.Services persistence =
            WikiSubsystemsTestFactory.mockRecord( PersistenceSubsystem.Services.class );

        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.extractor.backend", "ollama" );

        KnowledgeWiringHelper.wireEntityExtraction(
            props, ds, chunkProjector, chunkRepo, persistence, excludedPagesRepo, engine );

        verify( engine ).setManager( eq( ChunkEntityMentionRepository.class ), any() );
        verify( engine ).setManager( eq( AsyncEntityExtractionListener.class ), any() );
        verify( engine ).setManager( eq( BootstrapEntityExtractionIndexer.class ), any() );
        verify( chunkProjector ).setPostChunkSink( any() );
    }

    @Test
    @EnabledIfEnvironmentVariable( named = "ANTHROPIC_API_KEY", matches = ".+" )
    void wireEntityExtractionClaudeBackendWithRealApiKeyWiresBootstrapIndexerEndToEnd() {
        // Belt-and-suspenders: when a real key is present (developer machine / CI secret),
        // prove the full method — including EntityExtractorFactory's own key check for the
        // chunk-level extractor — wires the batch indexer too. Skipped otherwise, same as
        // this repo's other Claude-backend tests that need real credentials.
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final KgExcludedPagesRepository excludedPagesRepo = mock( KgExcludedPagesRepository.class );
        final PersistenceSubsystem.Services persistence =
            WikiSubsystemsTestFactory.mockRecord( PersistenceSubsystem.Services.class );

        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.extractor.backend", "claude" );

        KnowledgeWiringHelper.wireEntityExtraction(
            props, ds, chunkProjector, chunkRepo, persistence, excludedPagesRepo, engine );

        verify( engine ).setManager( eq( BootstrapEntityExtractionIndexer.class ), any() );
    }

    @Test
    void wireEntityExtractionDisabledBackendWiresNothing() {
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final KgExcludedPagesRepository excludedPagesRepo = mock( KgExcludedPagesRepository.class );
        final PersistenceSubsystem.Services persistence =
            WikiSubsystemsTestFactory.mockRecord( PersistenceSubsystem.Services.class );

        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.extractor.backend", "disabled" );

        KnowledgeWiringHelper.wireEntityExtraction(
            props, ds, chunkProjector, chunkRepo, persistence, excludedPagesRepo, engine );

        verify( engine, never() ).setManager( eq( BootstrapEntityExtractionIndexer.class ), any() );
        verify( engine, never() ).setManager( eq( AsyncEntityExtractionListener.class ), any() );
        verify( engine, never() ).setManager( eq( ChunkEntityMentionRepository.class ), any() );
        verify( chunkProjector, never() ).setPostChunkSink( any() );
    }

    @Test
    void wireEntityExtractionKnowledgeDisabledShortCircuitsEvenWithClaudeBackend() {
        // Regression pin for the other in-flight task's knowledge-enabled gate: it must
        // still short-circuit wireEntityExtraction entirely, before this task's claude
        // dispatch branch is ever reached.
        final WikiEngine engine = mock( WikiEngine.class );
        final DataSource ds = mock( DataSource.class );
        final ChunkProjector chunkProjector = mock( ChunkProjector.class );
        final ContentChunkRepository chunkRepo = mock( ContentChunkRepository.class );
        final KgExcludedPagesRepository excludedPagesRepo = mock( KgExcludedPagesRepository.class );
        final PersistenceSubsystem.Services persistence =
            WikiSubsystemsTestFactory.mockRecord( PersistenceSubsystem.Services.class );

        final Properties props = new Properties();
        props.setProperty( "wikantik.knowledge.enabled", "false" );
        props.setProperty( "wikantik.knowledge.extractor.backend", "claude" );

        KnowledgeWiringHelper.wireEntityExtraction(
            props, ds, chunkProjector, chunkRepo, persistence, excludedPagesRepo, engine );

        verify( engine, never() ).setManager( eq( BootstrapEntityExtractionIndexer.class ), any() );
        verify( engine, never() ).setManager( eq( ChunkEntityMentionRepository.class ), any() );
        verify( chunkProjector, never() ).setPostChunkSink( any() );
    }
}
