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
import com.wikantik.api.knowledge.ExtractedMention;
import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.ProposedEdge;
import com.wikantik.api.knowledge.ProposedNode;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Listener that derives entity/relation proposals + chunk mentions off the
 * save thread. Mirrors {@link com.wikantik.search.embedding.AsyncEmbeddingIndexListener}
 * — same {@code Consumer<List<UUID>>} event, same single-thread-executor
 * queue-and-drain shape, same failure isolation — so operational behavior
 * (queue depth on saturation, log wording, shutdown drain) matches.
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Never writes to {@code kg_nodes} / {@code kg_edges} directly. Node/edge
 *       changes always route through {@code kg_proposals} for admin review.</li>
 *   <li>Consults {@code kg_rejections} before filing an edge proposal so a
 *       rejected pairing isn't re-proposed every page-save.</li>
 *   <li>Per-page rate limit prevents a re-save storm from DoSing the extractor.</li>
 * </ul>
 */
public class AsyncEntityExtractionListener implements Consumer< List< UUID > >, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger( AsyncEntityExtractionListener.class );

    private final EntityExtractor extractor;
    private final EntityExtractorConfig config;
    private final ChunkExtractionPrefilter prefilter;
    private final ContentChunkRepository chunkRepository;
    private final ChunkEntityMentionRepository mentionRepository;
    private final JdbcKnowledgeRepository knowledgeRepository;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    private final Counter requestsCounter;
    private final Counter failuresCounter;
    private final Counter triplesCounter;
    private final Timer latencyTimer;

    private final Map< String, Long > lastExtractedAtMillis = Collections.synchronizedMap(
        new LinkedHashMap< String, Long >( 128, 0.75f, true ) {
            @Override
            protected boolean removeEldestEntry( final Map.Entry< String, Long > eldest ) {
                return size() > 1024;
            }
        } );

    public AsyncEntityExtractionListener( final EntityExtractor extractor,
                                          final EntityExtractorConfig config,
                                          final ContentChunkRepository chunkRepository,
                                          final ChunkEntityMentionRepository mentionRepository,
                                          final JdbcKnowledgeRepository knowledgeRepository,
                                          final MeterRegistry meterRegistry ) {
        this( extractor, config, chunkRepository, mentionRepository, knowledgeRepository,
              meterRegistry, defaultExecutor(), /*ownsExecutor*/ true );
    }

    public AsyncEntityExtractionListener( final EntityExtractor extractor,
                                          final EntityExtractorConfig config,
                                          final ContentChunkRepository chunkRepository,
                                          final ChunkEntityMentionRepository mentionRepository,
                                          final JdbcKnowledgeRepository knowledgeRepository,
                                          final MeterRegistry meterRegistry,
                                          final ExecutorService executor ) {
        this( extractor, config, chunkRepository, mentionRepository, knowledgeRepository,
              meterRegistry, executor, /*ownsExecutor*/ false );
    }

    private AsyncEntityExtractionListener( final EntityExtractor extractor,
                                           final EntityExtractorConfig config,
                                           final ContentChunkRepository chunkRepository,
                                           final ChunkEntityMentionRepository mentionRepository,
                                           final JdbcKnowledgeRepository knowledgeRepository,
                                           final MeterRegistry meterRegistry,
                                           final ExecutorService executor,
                                           final boolean ownsExecutor ) {
        if( extractor == null ) {
            throw new IllegalArgumentException( "extractor must not be null" );
        }
        if( config == null ) {
            throw new IllegalArgumentException( "config must not be null" );
        }
        if( chunkRepository == null || mentionRepository == null || knowledgeRepository == null ) {
            throw new IllegalArgumentException( "repositories must not be null" );
        }
        this.extractor = extractor;
        this.config = config;
        this.prefilter = new ChunkExtractionPrefilter(
            config.prefilterEnabled(),
            config.prefilterDryRun(),
            config.prefilterSkipPureCode(),
            config.prefilterSkipNoProperNoun() );
        this.chunkRepository = chunkRepository;
        this.mentionRepository = mentionRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;

        final String code = extractor.code();
        this.requestsCounter = Counter.builder( "wikantik_kg_extractor_requests_total" )
            .description( "Entity extraction requests accepted by the listener" )
            .tag( "extractor", code )
            .register( meterRegistry );
        this.failuresCounter = Counter.builder( "wikantik_kg_extractor_failures_total" )
            .description( "Entity extraction failures (by category)" )
            .tag( "extractor", code )
            .register( meterRegistry );
        this.triplesCounter = Counter.builder( "wikantik_kg_extractor_triples_emitted_total" )
            .description( "Mentions + proposals emitted by the extractor" )
            .tag( "extractor", code )
            .register( meterRegistry );
        this.latencyTimer = Timer.builder( "wikantik_kg_extractor_latency_seconds" )
            .description( "End-to-end extraction latency for one page save batch" )
            .tag( "extractor", code )
            .register( meterRegistry );
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor( r -> {
            final Thread t = new Thread( r, "wikantik-entity-extractor" );
            t.setDaemon( true );
            return t;
        } );
    }

    @Override
    public void accept( final List< UUID > chunkIds ) {
        if( chunkIds == null || chunkIds.isEmpty() ) {
            return;
        }
        if( !config.enabled() ) {
            return;
        }
        final List< UUID > snapshot = List.copyOf( chunkIds );
        try {
            executor.submit( () -> runExtraction( snapshot, /*bypassRateLimit*/ false ) );
        } catch( final RuntimeException reject ) {
            LOG.warn( "Rejected entity extraction of {} chunks: {}",
                      snapshot.size(), reject.getMessage() );
        }
    }

    /**
     * Synchronously extract mentions for the given chunks and return
     * aggregate counts. Used by the admin batch {@link
     * BootstrapEntityExtractionIndexer} so every page's work can be timed
     * and logged on the caller's thread. The per-page rate limit is bypassed
     * here — the bootstrap job already serializes per-page.
     */
    public RunResult runExtractionSync( final List< UUID > chunkIds ) {
        if( chunkIds == null || chunkIds.isEmpty() ) {
            return RunResult.EMPTY;
        }
        if( !config.enabled() ) {
            return RunResult.EMPTY;
        }
        return runExtraction( List.copyOf( chunkIds ), /*bypassRateLimit*/ true );
    }

    private RunResult runExtraction( final List< UUID > chunkIds, final boolean bypassRateLimit ) {
        requestsCounter.increment();
        final long started = System.nanoTime();
        int mentionsWritten = 0;
        int proposalsFiled = 0;
        try {
            final List< ContentChunkRepository.MentionableChunk > chunks = chunkRepository.findByIds( chunkIds );
            if( chunks.isEmpty() ) {
                return RunResult.EMPTY;
            }
            final String pageName = chunks.get( 0 ).pageName();
            if( !bypassRateLimit && !passesRateLimit( pageName ) ) {
                if( LOG.isDebugEnabled() ) {
                    LOG.debug( "Skipping entity extraction for page '{}': within rate-limit window", pageName );
                }
                return RunResult.EMPTY;
            }

            final List< KgNode > existingNodes = loadExistingNodes();
            final ExtractionContext ctx = new ExtractionContext( pageName, existingNodes, Map.of() );

            for( final ContentChunkRepository.MentionableChunk c : chunks ) {
                final ChunkExtractionPrefilter.Decision d = prefilter.evaluate( c.text(), c.headingPath() );
                if( !d.shouldExtract() ) {
                    if( LOG.isDebugEnabled() ) {
                        LOG.debug( "Prefilter dropped chunk {} on page '{}': reason={}",
                            c.id(), c.pageName(), d.reason() );
                    }
                    continue;
                }
                final ExtractionChunk ec = new ExtractionChunk(
                    c.id(), c.pageName(), c.chunkIndex(), c.headingPath(), c.text() );
                final ExtractionResult result;
                try {
                    result = extractor.extract( ec, ctx );
                } catch( final RuntimeException e ) {
                    // Interface contract says extractors never throw, but defend anyway.
                    failuresCounter.increment();
                    LOG.warn( "Extractor {} threw for chunk {}: {}",
                              extractor.code(), c.id(), e.getMessage() );
                    continue;
                }

                mentionsWritten += persistMentions( c.id(), result.mentions(), existingNodes );
                proposalsFiled += persistProposals( pageName, result );
            }

            triplesCounter.increment( (double) ( mentionsWritten + proposalsFiled ) );
            if( LOG.isDebugEnabled() ) {
                LOG.debug( "Extraction completed for page '{}': mentions={}, proposals={}",
                           pageName, mentionsWritten, proposalsFiled );
            }
        } catch( final RuntimeException e ) {
            failuresCounter.increment();
            LOG.warn( "Async entity extraction failed (chunks={}): {}",
                      chunkIds.size(), e.getMessage(), e );
        } finally {
            latencyTimer.record( System.nanoTime() - started, TimeUnit.NANOSECONDS );
        }
        return new RunResult( mentionsWritten, proposalsFiled );
    }

    /** Aggregate result of an extraction run — shared by sync and async paths. */
    public record RunResult( int mentionsWritten, int proposalsFiled ) {
        public static final RunResult EMPTY = new RunResult( 0, 0 );
    }

    /**
     * @return true if the page has not been extracted recently (or has never been)
     */
    private boolean passesRateLimit( final String pageName ) {
        final long now = System.currentTimeMillis();
        final long minInterval = config.perPageMinIntervalMs();
        if( minInterval <= 0 ) {
            return true;
        }
        final Long last = lastExtractedAtMillis.get( pageName );
        if( last != null && ( now - last ) < minInterval ) {
            return false;
        }
        lastExtractedAtMillis.put( pageName, now );
        return true;
    }

    private List< KgNode > loadExistingNodes() {
        try {
            return knowledgeRepository.queryNodes( Map.of(), null, config.maxExistingNodes(), 0 );
        } catch( final RuntimeException e ) {
            LOG.warn( "Failed to load existing nodes for extractor dictionary: {}", e.getMessage() );
            return List.of();
        }
    }

    private int persistMentions( final UUID chunkId,
                                 final List< ExtractedMention > mentions,
                                 final List< KgNode > existingNodes ) {
        if( mentions.isEmpty() ) {
            return 0;
        }
        final Map< String, UUID > nameToId = new HashMap<>();
        for( final KgNode n : existingNodes ) {
            if( n.name() != null ) {
                nameToId.put( n.name().toLowerCase( Locale.ROOT ), n.id() );
            }
        }
        final List< ChunkEntityMentionRepository.Row > rows = new ArrayList<>();
        for( final ExtractedMention m : mentions ) {
            if( m.nodeName() == null ) {
                continue;
            }
            UUID nodeId = nameToId.get( m.nodeName().toLowerCase( Locale.ROOT ) );
            if( nodeId == null ) {
                // Try a canonical DB lookup — the cap on loadExistingNodes means an unknown node isn't
                // necessarily a proposal candidate; it may just be outside the dictionary window.
                try {
                    final KgNode fresh = knowledgeRepository.getNodeByName( m.nodeName() );
                    if( fresh != null ) {
                        nodeId = fresh.id();
                    }
                } catch( final RuntimeException e ) {
                    LOG.warn( "Mention lookup failed for '{}': {}", m.nodeName(), e.getMessage() );
                }
            }
            if( nodeId != null ) {
                rows.add( new ChunkEntityMentionRepository.Row(
                    chunkId, nodeId, m.confidence(), extractor.code() ) );
            }
        }
        if( rows.isEmpty() ) {
            return 0;
        }
        try {
            return mentionRepository.upsertAll( rows );
        } catch( final RuntimeException e ) {
            failuresCounter.increment();
            LOG.warn( "Failed to upsert {} mentions for chunk {}: {}",
                      rows.size(), chunkId, e.getMessage() );
            return 0;
        }
    }

    private int persistProposals( final String pageName, final ExtractionResult result ) {
        int filed = 0;
        for( final ProposedNode n : result.nodes() ) {
            if( n.confidence() < config.confidenceThreshold() ) {
                continue;
            }
            try {
                knowledgeRepository.insertProposal(
                    "new-node", pageName,
                    Map.of(
                        "name", n.name(),
                        "nodeType", n.nodeType(),
                        "properties", n.properties(),
                        "extractor", extractor.code() ),
                    n.confidence(),
                    n.reasoning() );
                filed++;
            } catch( final RuntimeException e ) {
                failuresCounter.increment();
                LOG.warn( "Failed to insert node proposal '{}': {}", n.name(), e.getMessage() );
            }
        }
        for( final ProposedEdge e : result.edges() ) {
            if( e.confidence() < config.confidenceThreshold() ) {
                continue;
            }
            try {
                if( knowledgeRepository.isRejected( e.sourceName(), e.targetName(), e.relationshipType() ) ) {
                    continue;
                }
                knowledgeRepository.insertProposal(
                    "new-edge", pageName,
                    Map.of(
                        "source", e.sourceName(),
                        "target", e.targetName(),
                        "relationship", e.relationshipType(),
                        "properties", e.properties(),
                        "extractor", extractor.code() ),
                    e.confidence(),
                    e.reasoning() );
                filed++;
            } catch( final RuntimeException ex ) {
                failuresCounter.increment();
                LOG.warn( "Failed to insert edge proposal {}→{}: {}",
                          e.sourceName(), e.targetName(), ex.getMessage() );
            }
        }
        return filed;
    }

    @Override
    public void close() {
        if( !ownsExecutor ) {
            return;
        }
        executor.shutdown();
        try {
            if( !executor.awaitTermination( 5, TimeUnit.SECONDS ) ) {
                LOG.warn( "Entity-extraction executor did not drain within 5s; forcing shutdown" );
                executor.shutdownNow();
            }
        } catch( final InterruptedException ie ) {
            LOG.info( "Interrupted while awaiting entity-extraction executor shutdown — forcing shutdownNow: {}",
                    ie.getMessage() );
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** Exposes extraction timeout for composition purposes; unused internally. */
    public Duration timeout() {
        return Duration.ofMillis( config.timeoutMs() );
    }
}
