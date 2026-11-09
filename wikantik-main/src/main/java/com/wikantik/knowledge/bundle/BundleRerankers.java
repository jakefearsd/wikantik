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

import com.wikantik.api.pagegraph.Confidence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/**
 * Section-reranker selection for the context bundle: which rerank stages
 * {@code wikantik.bundle.rerank.chain} asks for. Split out of {@link BundleServiceWiring},
 * which assembles the service from these parts. All stages are local, deterministic
 * re-orderings (the LLM listwise reranker was removed in 2026-11).
 */
final class BundleRerankers {

    private static final Logger LOG = LogManager.getLogger( BundleRerankers.class );

    private BundleRerankers() {}

    /** Identity reranker: returns the (already relevance-sorted) input untouched. */
    private static final SectionReranker IDENTITY = ( query, sections ) -> sections;

    /** Selects the section reranker from config with no confidence lookup (the {@code metadata-boost} stage is skipped). */
    static SectionReranker rerankerFor( final Properties props ) {
        return rerankerFor( props, null );
    }

    /**
     * Selects the section reranker from config. If {@code wikantik.bundle.rerank.chain} is set
     * (CSV of stage names: {@code mmr}, {@code metadata-boost}), builds an ordered
     * {@link SectionRerankChain} from it — {@code confidenceOf} (slug → {@link Confidence}) feeds
     * the {@code metadata-boost} stage, which is skipped when null. Otherwise the bundle ships
     * dense-ordered (identity).
     */
    static SectionReranker rerankerFor( final Properties props, final Function< String, Confidence > confidenceOf ) {
        final String chainSpec = props == null ? null : props.getProperty( "wikantik.bundle.rerank.chain" );
        if ( chainSpec != null && !chainSpec.isBlank() ) {
            return buildChain( chainSpec, props, confidenceOf );
        }
        return IDENTITY;
    }

    /** Builds an ordered reranker chain from a CSV of stage names; unknown names are skipped with a
     *  warn, and an all-unknown/empty spec degrades to {@link #IDENTITY}. The {@code metadata-boost}
     *  stage is skipped (with a warn) when {@code confidenceOf} is null. */
    private static SectionReranker buildChain( final String spec, final Properties props,
                                               final Function< String, Confidence > confidenceOf ) {
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
        return reranker instanceof SectionRerankChain c ? "chain(" + c.stages().size() + ")" : "off";
    }
}
