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

import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.Verdict;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Executes one extraction batch: page listing, per-page extraction, consolidation,
 * judging, and proposal upsert.
 *
 * <p>Factored out of {@link BootstrapEntityExtractionIndexer} as part of Phase 11
 * Ckpt 6 god-class decomposition. Counter updates are pushed back to the indexer
 * via the {@link Counters} callback interface so the indexer's atomics remain the
 * single source of truth.
 */
class ExtractionBatchRunner {

    private static final Logger LOG = LogManager.getLogger( ExtractionBatchRunner.class );

    /** Per-page bundle: the chunks fetched + the extractor's result. */
    record PageOutcome(
        String pageName,
        List< ContentChunkRepository.MentionableChunk > chunks,
        PageExtractionResult result
    ) {}

    /** Result of a full batch run. */
    record BatchResult(
        List< PageOutcome > outcomes,
        List< ConsolidatedProposal > accepted
    ) {}

    /**
     * Callback interface for counter updates. The indexer implements this so
     * {@link ExtractionBatchRunner} doesn't need direct field access.
     */
    interface Counters {
        void setTotalPages( int n );
        void setTotalChunks( int n );
        void incrementProcessedPages();
        void incrementFailedPages();
        void addProcessedChunks( int n );
        void incrementExcludedSkipped();
        void setConsolidatedCandidates( int n );
        void incrementJudgeAccepted();
        void incrementJudgeRejected();
        void incrementJudgeRewritten();
        void incrementProposalsInserted();
        void incrementProposalsMerged();
        void mergeRejectionReason( String reason );
        void setLastError( String msg );
        boolean isDryRun();
        boolean isCancelRequested();
        int maxPages();
    }

    private final PageExtractor             pageExtractor;
    private final ProposalJudge             judge;
    private final ProposalConsolidator      consolidator;
    private final ProposalUpserter          upserter;
    private final KgNodeEmbeddingService    embeddingService;
    private final KgNodeEmbeddingRepository embeddingRepo;
    private final ContentChunkRepository    chunkRepo;
    private final KgExcludedPagesRepository excludedPages;
    private final ExecutorService           workerPool;
    private final int                       dictionaryTopK;

    ExtractionBatchRunner( final PageExtractor pageExtractor,
                            final ProposalJudge judge,
                            final ProposalConsolidator consolidator,
                            final ProposalUpserter upserter,
                            final KgNodeEmbeddingService embeddingService,
                            final KgNodeEmbeddingRepository embeddingRepo,
                            final ContentChunkRepository chunkRepo,
                            final KgExcludedPagesRepository excludedPages,
                            final ExecutorService workerPool,
                            final int dictionaryTopK ) {
        this.pageExtractor   = pageExtractor;
        this.judge           = judge;
        this.consolidator    = consolidator;
        this.upserter        = upserter;
        this.embeddingService = embeddingService;
        this.embeddingRepo   = embeddingRepo;
        this.chunkRepo       = chunkRepo;
        this.excludedPages   = excludedPages;
        this.workerPool      = workerPool;
        this.dictionaryTopK  = dictionaryTopK;
    }

    /**
     * Warm the node-embedding cache. Best-effort: failures are logged as warnings.
     */
    void warmNodeEmbeddings( final KgNodeRepository kgNodes ) {
        if ( embeddingService == null ) return;
        try {
            final List< KgNode > all = kgNodes.getAllNodes();
            final KgNodeEmbeddingService.Result r = embeddingService.warmUp( all );
            LOG.info( "Bootstrap extraction: node embedding warmup cached={} reEmbedded={} errors={}",
                r.cached(), r.reEmbedded(), r.errors() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: node embedding warmup failed: {}", e.getMessage() );
        }
    }

    /**
     * Run the full batch: list pages → extract in parallel → consolidate → judge →
     * upsert. Returns a {@link BatchResult} carrying outcomes and accepted proposals
     * so the caller can run mention attribution.
     */
    BatchResult runBatch( final boolean overwrite,
                           final PageEmbeddingProvider pageEmbeddings,
                           final Counters counters ) throws InterruptedException {
        // Step 2 — list pages, apply --max-pages cap.
        List< String > pages = chunkRepo.listDistinctPageNames();
        final int cap = counters.maxPages();
        if ( cap > 0 && cap < pages.size() ) {
            pages = pages.subList( 0, cap );
            LOG.info( "Bootstrap extraction: --max-pages={} cap applied (full corpus has {} pages)",
                cap, chunkRepo.listDistinctPageNames().size() );
        }
        counters.setTotalPages( pages.size() );
        try {
            counters.setTotalChunks( chunkRepo.stats().totalChunks() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: failed to precompute total chunk count: {}", e.getMessage() );
        }
        LOG.info( "Bootstrap entity extraction starting: pages={}, chunks={}, forceOverwrite={}, dryRun={}",
            pages.size(), counters.isDryRun() ? "?" : "?", overwrite, counters.isDryRun() );

        // Step 3 — extract per page, in parallel up to concurrency.
        final List< Future< PageOutcome > > futures = new ArrayList<>( pages.size() );
        boolean cancelledMidRun = false;
        for ( final String page : pages ) {
            if ( Thread.currentThread().isInterrupted() ) {
                throw new InterruptedException( "bootstrap extraction interrupted before page '" + page + "'" );
            }
            if ( counters.isCancelRequested() ) {
                LOG.info( "Bootstrap extraction: cancellation acknowledged before submitting more pages" );
                cancelledMidRun = true;
                break;
            }
            if ( excludedPages != null && excludedPages.findReason( page ).isPresent() ) {
                LOG.debug( "Bootstrap extraction: skip excluded page '{}'", page );
                counters.incrementExcludedSkipped();
                continue;
            }
            futures.add( workerPool.submit( () -> extractOnePage( page, pageEmbeddings, counters ) ) );
        }

        final List< PageOutcome > outcomes = new ArrayList<>( futures.size() );
        for ( final Future< PageOutcome > f : futures ) {
            try {
                outcomes.add( f.get() );
            } catch ( final InterruptedException ie ) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch ( final ExecutionException ee ) {
                final Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                counters.setLastError( "extract task failed: " + cause.getMessage() );
                LOG.warn( "Bootstrap extraction: extract task threw: {}", cause.getMessage() );
                counters.incrementFailedPages();
            }
        }

        // Steps 4–5 — consolidate then judge.
        final List< ConsolidatedProposal > consolidated = consolidator.consolidate(
            outcomes.stream().map( PageOutcome::result ) );
        counters.setConsolidatedCandidates( consolidated.size() );
        LOG.info( "Bootstrap extraction: consolidated {} candidates from {} page results",
            consolidated.size(), outcomes.size() );

        final JudgeContext judgeCtx = new JudgeContext( Map.of(), List.of() );
        final List< ConsolidatedProposal > accepted = new ArrayList<>();
        for ( final ConsolidatedProposal cp : consolidated ) {
            if ( counters.isCancelRequested() ) {
                cancelledMidRun = true;
                break;
            }
            final Verdict v;
            try {
                v = judge.judge( cp, judgeCtx );
            } catch ( final RuntimeException e ) {
                LOG.warn( "Judge '{}' threw on signature {}: {} — accepting fail-open",
                    judge.code(), cp.signature(), e.getMessage() );
                accepted.add( cp );
                counters.incrementJudgeAccepted();
                continue;
            }
            if ( v instanceof Verdict.Accept ) {
                counters.incrementJudgeAccepted();
                accepted.add( cp );
            } else if ( v instanceof Verdict.Rewrite rw ) {
                counters.incrementJudgeRewritten();
                accepted.add( rw.rewritten() );
            } else if ( v instanceof Verdict.Reject r ) {
                counters.incrementJudgeRejected();
                counters.mergeRejectionReason( r.reasonCode() );
            }
        }

        // Step 6 — upsert accepted proposals (skipped on dry-run).
        if ( !counters.isDryRun() ) {
            for ( final ConsolidatedProposal cp : accepted ) {
                if ( counters.isCancelRequested() ) {
                    cancelledMidRun = true;
                    break;
                }
                try {
                    final ProposalUpserter.Result r = upserter.upsert( cp );
                    if ( r.inserted() ) {
                        counters.incrementProposalsInserted();
                    } else {
                        counters.incrementProposalsMerged();
                    }
                } catch ( final RuntimeException e ) {
                    counters.setLastError( "upsert failed for " + cp.signature() + ": " + e.getMessage() );
                    LOG.warn( "Bootstrap extraction: upsert failed for {}: {}", cp.signature(), e.getMessage() );
                }
            }
        } else {
            LOG.info( "Bootstrap extraction: dry-run — skipping upsert of {} accepted proposals", accepted.size() );
        }

        if ( cancelledMidRun ) {
            LOG.info( "Bootstrap extraction: cancellation acknowledged" );
        }

        // Return both outcomes and accepted proposals for mention attribution.
        return new BatchResult( outcomes, accepted );
    }

    // ---- private per-page extraction ----

    private PageOutcome extractOnePage( final String pageName,
                                         final PageEmbeddingProvider pageEmbeddings,
                                         final Counters counters ) {
        final long startNs = System.nanoTime();
        final List< UUID > chunkIds;
        try {
            chunkIds = chunkRepo.listChunkIdsForPage( pageName );
        } catch ( final RuntimeException e ) {
            counters.incrementFailedPages();
            LOG.warn( "Bootstrap extraction: failed to list chunks for page '{}': {}",
                pageName, e.getMessage() );
            return new PageOutcome( pageName, List.of(), emptyResult( pageName ) );
        }
        if ( chunkIds.isEmpty() ) {
            counters.incrementProcessedPages();
            return new PageOutcome( pageName, List.of(), emptyResult( pageName ) );
        }

        final List< ContentChunkRepository.MentionableChunk > chunks;
        try {
            chunks = chunkRepo.findByIds( chunkIds );
        } catch ( final RuntimeException e ) {
            counters.incrementFailedPages();
            LOG.warn( "Bootstrap extraction: failed to hydrate chunks for page '{}': {}",
                pageName, e.getMessage() );
            return new PageOutcome( pageName, List.of(), emptyResult( pageName ) );
        }

        final String body = stitchBody( chunks );
        final Page page = new Page( pageName, /*pageId*/ null, body, /*summary*/ "", List.of() );

        List< KgNode > dictionary = List.of();
        try {
            final Optional< float[] > mean = pageEmbeddings.meanFor( pageName );
            if ( mean.isPresent() && embeddingRepo != null && embeddingService != null
                                 && dictionaryTopK > 0 ) {
                dictionary = embeddingRepo.findTopKByPageEmbedding(
                    mean.get(), dictionaryTopK, embeddingService.modelTag() );
            }
        } catch ( final RuntimeException e ) {
            LOG.warn( "Bootstrap extraction: dictionary lookup failed for page '{}': {}",
                pageName, e.getMessage() );
        }

        final ExtractionContext ctx = new ExtractionContext( pageName, dictionary, Map.of() );
        final PageExtractionResult result;
        try {
            result = pageExtractor.extract( page, ctx );
        } catch ( final RuntimeException e ) {
            counters.incrementFailedPages();
            counters.setLastError( "page=" + pageName + ": " + e.getMessage() );
            LOG.warn( "Bootstrap extraction: extractor threw on page '{}': {}", pageName, e.getMessage() );
            return new PageOutcome( pageName, chunks, emptyResult( pageName ) );
        }

        counters.incrementProcessedPages();
        counters.addProcessedChunks( chunks.size() );
        final long elapsedMs = ( System.nanoTime() - startNs ) / 1_000_000L;
        LOG.info( "Bootstrap extraction: page='{}' chunks={} entities={} relations={} elapsedMs={}",
            pageName, chunks.size(), result.entities().size(), result.relations().size(), elapsedMs );
        return new PageOutcome( pageName, chunks, result );
    }

    private static String stitchBody( final List< ContentChunkRepository.MentionableChunk > chunks ) {
        final List< ContentChunkRepository.MentionableChunk > ordered = new ArrayList<>( chunks );
        ordered.sort( ( a, b ) -> Integer.compare( a.chunkIndex(), b.chunkIndex() ) );
        final StringBuilder sb = new StringBuilder();
        for ( final ContentChunkRepository.MentionableChunk c : ordered ) {
            if ( sb.length() > 0 ) sb.append( "\n\n" );
            sb.append( c.text() );
        }
        return sb.toString();
    }

    private PageExtractionResult emptyResult( final String pageName ) {
        return PageExtractionResult.empty( pageExtractor.code(), pageName, java.time.Duration.ZERO );
    }
}
