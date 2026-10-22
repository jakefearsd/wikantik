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
import com.wikantik.api.pagegraph.Confidence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/**
 * Section-reranker and query-planner selection for the context bundle: which rerank stages
 * {@code wikantik.bundle.rerank.chain} (or the legacy {@code wikantik.bundle.reranker.enabled}) asks for,
 * under the {@code wikantik.genai.mode} chat-inference ceiling, and their {@code /admin/llm-activity}
 * recording. Split out of {@link BundleServiceWiring}, which assembles the service from these parts.
 */
final class BundleRerankers {

    private static final Logger LOG = LogManager.getLogger( BundleRerankers.class );

    private BundleRerankers() {}

    /**
     * Wraps {@code reranker} for {@code /admin/llm-activity} recording when the activity log is
     * enabled AND the reranker actually issues chat inference — a bare {@link LlmSectionReranker},
     * or a {@link SectionRerankChain} carrying one as its {@code llm} stage. Non-LLM rerankers
     * (identity, {@link MmrSectionReranker}, {@link MetadataBoostSectionReranker}) are returned
     * unchanged — there is no call to record. Never called from {@link #rerankerFor} itself, so
     * the genai.mode ceiling logic there stays byte-for-byte unchanged and independently testable.
     */
    static SectionReranker recordReranker( final SectionReranker reranker, final com.wikantik.llm.activity.LlmActivityLog log,
                                           final String model ) {
        if ( log == null || !log.enabled() ) {
            return reranker;
        }
        if ( reranker instanceof LlmSectionReranker ) {
            return new RecordingSectionReranker( reranker, log, "ollama", model );
        }
        if ( reranker instanceof SectionRerankChain chain ) {
            boolean changed = false;
            final List< SectionReranker > stages = new ArrayList<>( chain.stages().size() );
            for ( final SectionReranker stage : chain.stages() ) {
                if ( stage instanceof LlmSectionReranker ) {
                    stages.add( new RecordingSectionReranker( stage, log, "ollama", model ) );
                    changed = true;
                } else {
                    stages.add( stage );
                }
            }
            return changed ? new SectionRerankChain( stages ) : chain;
        }
        return reranker;
    }

    /**
     * Wraps {@code planner} for {@code /admin/llm-activity} recording when the activity log is
     * enabled AND the planner is the LLM-backed {@link LlmQueryPlanner} — the (default)
     * {@link PassthroughQueryPlanner} is returned unchanged, since it never calls out. Never
     * called from {@link BundleDecompositionConfig#fromProperties}, so that method's genai.mode
     * ceiling logic stays byte-for-byte unchanged and independently testable.
     */
    static QueryPlanner recordPlanner( final QueryPlanner planner, final com.wikantik.llm.activity.LlmActivityLog log,
                                       final String model ) {
        if ( log == null || !log.enabled() ) {
            return planner;
        }
        return planner instanceof LlmQueryPlanner ? new RecordingQueryPlanner( planner, log, "ollama", model ) : planner;
    }

    /** Identity reranker: returns the (already relevance-sorted) input untouched. */
    private static final SectionReranker IDENTITY = ( query, sections ) -> sections;

    /**
     * Selects the section reranker from config. If {@code wikantik.bundle.rerank.chain} is set
     * (CSV of stage names: {@code mmr}, {@code llm}), builds an ordered {@link SectionRerankChain}
     * from it. Otherwise falls back to the legacy behavior: {@code wikantik.bundle.reranker.enabled}
     * defaults to {@code false} — the 2026-06-13 live measurement showed the listwise LLM reranker
     * is an ordering lever, not a recall lever (rerank == dense recall), at ~1.5s per request — so
     * the bundle ships dense-ordered by default (identity) and opts in to the LLM reranker.
     */
    static SectionReranker rerankerFor( final Properties props ) {
        return rerankerFor( props, null );
    }

    /**
     * Selects the section reranker from config. If {@code wikantik.bundle.rerank.chain} is set
     * (CSV of stage names: {@code mmr}, {@code metadata-boost}, {@code llm}), builds an ordered
     * {@link SectionRerankChain} from it — {@code confidenceOf} (slug → {@link Confidence}) feeds
     * the {@code metadata-boost} stage, which is skipped when null. Otherwise falls back to the
     * legacy behavior: {@code wikantik.bundle.reranker.enabled} defaults to {@code false} — the
     * 2026-06-13 live measurement showed the listwise LLM reranker is an ordering lever, not a
     * recall lever (rerank == dense recall), at ~1.5s per request — so the bundle ships
     * dense-ordered by default (identity) and opts in to the LLM reranker.
     */
    static SectionReranker rerankerFor( final Properties props, final Function< String, Confidence > confidenceOf ) {
        // wikantik.genai.mode ceiling: the LLM reranker is chat inference, so neither
        // activation path below (legacy enabled flag, or an 'llm' token in the chain
        // spec) may construct one when the mode disallows chat inference.
        final GenAiMode mode = props == null ? GenAiMode.FULL : GenAiMode.fromProperties( props );
        final boolean chatAllowed = mode.allowsChatInference();

        final String chainSpec = props == null ? null : props.getProperty( "wikantik.bundle.rerank.chain" );
        if ( chainSpec != null && !chainSpec.isBlank() ) {
            return buildChain( chainSpec, props, confidenceOf, chatAllowed, mode );
        }
        final boolean enabled = props != null && Boolean.parseBoolean(
            props.getProperty( RerankerConfig.PREFIX + "enabled", "false" ) );
        if ( enabled && !chatAllowed ) {
            LOG.warn( "{}={} disallows chat inference; ignoring {}enabled=true, using identity reranker",
                GenAiMode.PROP, mode, RerankerConfig.PREFIX );
            return IDENTITY;
        }
        return enabled ? new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) : IDENTITY;
    }

    /** Builds an ordered reranker chain from a CSV of stage names; unknown names are skipped with a
     *  warn, and an all-unknown/empty spec degrades to {@link #IDENTITY}. The {@code metadata-boost}
     *  stage is skipped (with a warn) when {@code confidenceOf} is null. */
    private static SectionReranker buildChain( final String spec, final Properties props,
                                               final Function< String, Confidence > confidenceOf,
                                               final boolean chatAllowed, final GenAiMode mode ) {
        final List< SectionReranker > stages = new ArrayList<>();
        for ( final String raw : spec.split( "," ) ) {
            final String name = raw.trim().toLowerCase( Locale.ROOT );
            switch ( name ) {
                case "" -> { /* empty token from stray comma — ignore */ }
                case "mmr" -> stages.add( new MmrSectionReranker( mmrLambda( props ) ) );
                case "metadata-boost" -> {
                    if ( confidenceOf == null ) {
                        LOG.warn( "rerank stage 'metadata-boost' requested but no confidence lookup wired; skipping" );
                    } else {
                        stages.add( new MetadataBoostSectionReranker(
                            confidenceOf, metadataBoostPositions( props ), metadataBoostWindow( props ) ) );
                    }
                }
                case "llm" -> {
                    if ( !chatAllowed ) {
                        LOG.warn( "{}={} disallows chat inference; skipping 'llm' rerank stage",
                            GenAiMode.PROP, mode );
                    } else {
                        stages.add( new LlmSectionReranker( RerankerConfig.fromProperties( props ) ) );
                    }
                }
                default -> LOG.warn( "Unknown rerank stage '{}' in wikantik.bundle.rerank.chain; skipping", name );
            }
        }
        return stages.isEmpty() ? IDENTITY : new SectionRerankChain( stages );
    }

    /** MMR λ from {@code wikantik.bundle.rerank.mmr.lambda}, default 0.7 (higher = more relevance-biased). */
    static double mmrLambda( final Properties props ) {
        if ( props == null ) return 0.7;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.mmr.lambda", 0.7 );
    }

    /** Confidence rank-boost magnitude in positions, {@code wikantik.bundle.rerank.metadata_boost.positions},
     *  default 1.5 (an AUTHORITATIVE section overtakes a STALE one only within 2·positions positions). */
    static double metadataBoostPositions( final Properties props ) {
        if ( props == null ) return 1.5;
        return com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.metadata_boost.positions", 1.5 );
    }

    /** Top-N candidates the boost may reorder, {@code wikantik.bundle.rerank.metadata_boost.window}, default 24. */
    static int metadataBoostWindow( final Properties props ) {
        if ( props == null ) return 24;
        return (int) com.wikantik.util.TextUtil.getDoubleProperty( props, "wikantik.bundle.rerank.metadata_boost.window", 24 );
    }

    /** Short reranker description for the wiring log line. */
    static String label( final SectionReranker reranker ) {
        if ( reranker instanceof SectionRerankChain c ) {
            return "chain(" + c.stages().size() + ")";
        }
        return reranker instanceof LlmSectionReranker ? "on" : "off";
    }

    /** The LLM query planner when decomposition is enabled, else the passthrough planner (no call-outs). */
    static QueryPlanner plannerFor( final BundleDecompositionConfig decompositionConfig ) {
        return decompositionConfig.enabled()
            ? new LlmQueryPlanner( HttpClient.newHttpClient(), decompositionConfig )
            : new PassthroughQueryPlanner();
    }
}
