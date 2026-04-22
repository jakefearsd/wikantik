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
package com.wikantik.search.embedding.experiment;

import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.EmbeddingModel;
import com.wikantik.search.embedding.TextEmbeddingClient;
import com.wikantik.search.embedding.experiment.ExperimentHarness.ChunkCorpus;
import com.wikantik.search.embedding.experiment.QueryCorpus.EvalQuery;
import com.wikantik.search.embedding.experiment.ReciprocalRankFusion.Ranking;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * End-to-end experiment that:
 *  1. Uses per-chunk cosines for each query (cached once).
 *  2. Evaluates aggregation × fusion combos, including normalized-score fusion
 *     (weighted sum of min-max-normalized BM25 and dense scores) as an
 *     alternative to RRF.
 *  3. Reports best-per-model and cross-model winner.
 * Accepts N model codes as args; runs the full pipeline per model and prints
 * a side-by-side summary.
 */
public final class ExperimentGrandFinale {

    private static final Logger LOG = LogManager.getLogger( ExperimentGrandFinale.class );

    private static final int BM25_TOP        = 100;
    private static final int DENSE_TOP       = 100;
    private static final int DENSE_CHUNK_TOP = 500;

    enum Agg { MAX, MEAN_TOP_3, SUM_TOP_3, SUM_TOP_5 }

    /** Decision strategies we evaluate for fusion. */
    enum Fusion {
        RRF_EQUAL,           // RRF k=60, wD=wB=1, trunc=100
        RRF_TUNED,           // RRF k=60, wD=1.5, wB=1, trunc=20  (from exp3 winner)
        RRF_RECALL,          // RRF k=60, wD=1.0, wB=1, trunc=20  (best r@20)
        SCORE_EQUAL,         // min-max normalize each, then sum
        SCORE_DENSE_HEAVY,   // wD=1.5 * dense + 1.0 * bm25 after normalize
        SCORE_BM25_HEAVY     // 1.0 * dense + 1.5 * bm25
    }

    private ExperimentGrandFinale() {}

    @SuppressWarnings("PMD.SystemPrintln")

    public static void main( final String[] args ) throws IOException, SQLException {
        if( args.length < 1 ) {
            System.err.println( "Usage: ExperimentGrandFinale <model-code> [<model-code> ...]" );
            System.exit( 2 );
        }
        final Path csv = Paths.get( "eval/retrieval-queries.csv" );
        final List< EvalQuery > queries = QueryCorpus.load( csv );

        final StringBuilder sb = new StringBuilder();
        sb.append( "Grand-finale sweep: aggregation × fusion strategies\n" );
        sb.append( "Queries: " ).append( queries.size() ).append( "\n" );
        sb.append( "Models: " ).append( Arrays.toString( args ) ).append( "\n\n" );

        for( final String modelCode : args ) {
            LOG.info( "=== model: {} ===", modelCode );
            sb.append( "==================== " ).append( modelCode ).append( " ====================\n" );
            evaluateModel( modelCode, queries, sb );
            sb.append( '\n' );
        }

        System.out.print( sb.toString() );
        final Path out = Paths.get( "eval", "grand-finale.txt" );
        ExperimentHarness.writeReport( out, sb.toString() );
        LOG.info( "grand-finale written to {}", out.toAbsolutePath() );
    }

    private static void evaluateModel( final String modelCode, final List< EvalQuery > queries,
                                       final StringBuilder sb ) throws IOException, SQLException {
        final EmbeddingModel model = EmbeddingModel.fromCode( modelCode );
        final TextEmbeddingClient client = ExperimentHarness.buildClient( modelCode );
        Bm25Client bm25 = Bm25Client.fromSystemProperties();

        try( Connection conn = ExperimentDb.open() ) {
            final ChunkCorpus corpus = ExperimentHarness.loadCorpus( conn, model.code(), client.dimension() );

            // Cache per-query: BM25 results with scores, and per-chunk cosines.
            final List< CachedQuery > cache = new ArrayList<>( queries.size() );
            for( final EvalQuery q : queries ) {
                final List< Bm25Client.Scored > bm25Scored = bm25.searchWithScores( q.query(), BM25_TOP );
                final float[] qv = client.embed( List.of( q.query() ), EmbeddingKind.QUERY ).get( 0 );
                final double qn = CosineSimilarity.norm( qv );
                final double[] scores = new double[ corpus.vectors.size() ];
                for( int i = 0; i < corpus.vectors.size(); i++ ) {
                    scores[ i ] = CosineSimilarity.cosine( qv, qn,
                        corpus.vectors.get( i ), corpus.norms.get( i ) );
                }
                cache.add( new CachedQuery( q, bm25Scored, scores ) );
            }

            // Per-aggregation: precompute dense ranked + scored pages per query.
            final Map< Agg, List< List< ScoredPage > > > denseByAgg = new LinkedHashMap<>();
            for( final Agg agg : Agg.values() ) {
                final List< List< ScoredPage > > perQ = new ArrayList<>( cache.size() );
                for( final CachedQuery cq : cache ) {
                    perQ.add( denseRankingWithAgg( corpus, cq.chunkScores, agg ) );
                }
                denseByAgg.put( agg, perQ );
            }

            // Evaluate all agg × fusion combos.
            final List< Result > results = new ArrayList<>();
            for( final Agg agg : Agg.values() ) {
                final List< List< ScoredPage > > denseRankings = denseByAgg.get( agg );
                // dense-only baseline
                {
                    final int[] ranks = new int[ cache.size() ];
                    for( int i = 0; i < cache.size(); i++ ) {
                        ranks[ i ] = ExperimentHarness.rankOf( cache.get( i ).query.idealPage(), namesOf( denseRankings.get( i ) ) );
                    }
                    results.add( new Result( agg, null, Metrics.of( ranks ) ) );
                }
                for( final Fusion f : Fusion.values() ) {
                    final int[] ranks = new int[ cache.size() ];
                    for( int i = 0; i < cache.size(); i++ ) {
                        final List< String > fused = applyFusion( f,
                            cache.get( i ).bm25Scored, denseRankings.get( i ) );
                        ranks[ i ] = ExperimentHarness.rankOf( cache.get( i ).query.idealPage(), fused );
                    }
                    results.add( new Result( agg, f, Metrics.of( ranks ) ) );
                }
            }

            // Also the BM25-only sanity baseline (independent of aggregation).
            {
                final int[] ranks = new int[ cache.size() ];
                for( int i = 0; i < cache.size(); i++ ) {
                    final List< String > names = new ArrayList<>( cache.get( i ).bm25Scored.size() );
                    for( final Bm25Client.Scored s : cache.get( i ).bm25Scored ) names.add( s.name() );
                    ranks[ i ] = ExperimentHarness.rankOf( cache.get( i ).query.idealPage(), names );
                }
                results.add( new Result( null, null, Metrics.of( ranks ) ) );
            }

            // Render table.
            sb.append( String.format( Locale.ROOT, "  %-12s  %-18s  %7s  %7s  %7s%n",
                "aggregation", "fusion", "r@5", "r@20", "MRR" ) );
            sb.append( "  " ).append( "-".repeat( 64 ) ).append( '\n' );
            for( final Result r : results ) {
                final String aggStr = r.agg == null ? "bm25-only" : r.agg.name();
                final String fusStr = r.agg == null ? "—" : ( r.fusion == null ? "dense-only" : r.fusion.name() );
                sb.append( String.format( Locale.ROOT, "  %-12s  %-18s  %7.3f  %7.3f  %7.3f%n",
                    aggStr, fusStr, r.m.recall5, r.m.recall20, r.m.mrr ) );
            }
            // Pareto-best by lexicographic (r@5, r@20, MRR).
            final Result best = results.stream()
                .max( Comparator.comparingDouble( ( Result r ) -> r.m.recall5 )
                                .thenComparingDouble( r -> r.m.recall20 )
                                .thenComparingDouble( r -> r.m.mrr ) ).orElseThrow();
            sb.append( String.format( Locale.ROOT, "  BEST  %s  %s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
                best.agg == null ? "bm25" : best.agg,
                best.fusion == null ? "dense-only" : best.fusion.name(),
                best.m.recall5, best.m.recall20, best.m.mrr ) );
        }
    }

    // ---- fusion strategies ----

    private static List< String > applyFusion( final Fusion f,
                                               final List< Bm25Client.Scored > bm25,
                                               final List< ScoredPage > dense ) {
        final List< String > bm25Names = new ArrayList<>( bm25.size() );
        for( final Bm25Client.Scored s : bm25 ) bm25Names.add( s.name() );
        final List< String > denseNames = new ArrayList<>( dense.size() );
        for( final ScoredPage s : dense ) denseNames.add( s.name );

        switch( f ) {
            case RRF_EQUAL:
                return ReciprocalRankFusion.fuseWeighted(
                    List.of( new Ranking( bm25Names ), new Ranking( denseNames ) ),
                    new double[]{ 1.0, 1.0 }, 60, 100 );
            case RRF_TUNED:
                return ReciprocalRankFusion.fuseWeighted(
                    List.of( new Ranking( bm25Names ), new Ranking( denseNames ) ),
                    new double[]{ 1.0, 1.5 }, 60, 20 );
            case RRF_RECALL:
                return ReciprocalRankFusion.fuseWeighted(
                    List.of( new Ranking( bm25Names ), new Ranking( denseNames ) ),
                    new double[]{ 1.0, 1.0 }, 60, 20 );
            case SCORE_EQUAL:
                return scoreFusion( bm25, dense, 1.0, 1.0 );
            case SCORE_DENSE_HEAVY:
                return scoreFusion( bm25, dense, 1.0, 1.5 );
            case SCORE_BM25_HEAVY:
                return scoreFusion( bm25, dense, 1.5, 1.0 );
            default:
                throw new IllegalStateException( f.toString() );
        }
    }

    /** Min-max normalize both score lists per query, then weighted sum. */
    private static List< String > scoreFusion( final List< Bm25Client.Scored > bm25,
                                               final List< ScoredPage > dense,
                                               final double wBm25, final double wDense ) {
        final Map< String, Double > norm = new HashMap<>();
        if( !bm25.isEmpty() ) {
            final double max = bm25.stream().mapToDouble( Bm25Client.Scored::score ).max().orElse( 1.0 );
            final double min = bm25.stream().mapToDouble( Bm25Client.Scored::score ).min().orElse( 0.0 );
            final double range = Math.max( 1e-9, max - min );
            for( final Bm25Client.Scored s : bm25 ) {
                norm.merge( s.name(), wBm25 * ( s.score() - min ) / range, Double::sum );
            }
        }
        if( !dense.isEmpty() ) {
            final double max = dense.stream().mapToDouble( sp -> sp.score ).max().orElse( 1.0 );
            final double min = dense.stream().mapToDouble( sp -> sp.score ).min().orElse( 0.0 );
            final double range = Math.max( 1e-9, max - min );
            for( final ScoredPage sp : dense ) {
                norm.merge( sp.name, wDense * ( sp.score - min ) / range, Double::sum );
            }
        }
        final List< Map.Entry< String, Double > > entries = new ArrayList<>( norm.entrySet() );
        entries.sort( Comparator.comparingDouble( Map.Entry< String, Double >::getValue ).reversed() );
        final List< String > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( e.getKey() );
        return out;
    }

    // ---- dense ranking with aggregation ----

    private static List< ScoredPage > denseRankingWithAgg( final ChunkCorpus corpus,
                                                           final double[] chunkScores, final Agg agg ) {
        final int n = chunkScores.length;
        final int topN = Math.min( DENSE_CHUNK_TOP, n );
        final Integer[] idx = new Integer[ n ];
        for( int i = 0; i < n; i++ ) idx[ i ] = i;
        Arrays.sort( idx, Comparator.comparingDouble( ( Integer a ) -> chunkScores[ a ] ).reversed() );

        final Map< String, List< Double > > perPage = new LinkedHashMap<>();
        for( int i = 0; i < topN; i++ ) {
            final int j = idx[ i ];
            final String page = corpus.pagesForChunk.get( corpus.chunkIds.get( j ) );
            perPage.computeIfAbsent( page, p -> new ArrayList<>() ).add( chunkScores[ j ] );
        }
        final Map< String, Double > pageScore = new LinkedHashMap<>();
        for( final Map.Entry< String, List< Double > > e : perPage.entrySet() ) {
            pageScore.put( e.getKey(), applyAgg( e.getValue(), agg ) );
        }
        final List< Map.Entry< String, Double > > entries = new ArrayList<>( pageScore.entrySet() );
        entries.sort( Comparator.comparingDouble( Map.Entry< String, Double >::getValue ).reversed() );
        final List< ScoredPage > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( new ScoredPage( e.getKey(), e.getValue() ) );
        return out.size() > DENSE_TOP ? out.subList( 0, DENSE_TOP ) : out;
    }

    private static double applyAgg( final List< Double > scoresDesc, final Agg agg ) {
        return switch( agg ) {
            case MAX -> scoresDesc.get( 0 );
            case MEAN_TOP_3 -> meanTop( scoresDesc, 3 );
            case SUM_TOP_3  -> sumTop( scoresDesc, 3 );
            case SUM_TOP_5  -> sumTop( scoresDesc, 5 );
        };
    }

    private static double meanTop( final List< Double > s, final int k ) {
        final int kk = Math.min( k, s.size() );
        double sum = 0; for( int i = 0; i < kk; i++ ) sum += s.get( i );
        return sum / kk;
    }

    private static double sumTop( final List< Double > s, final int k ) {
        final int kk = Math.min( k, s.size() );
        double sum = 0; for( int i = 0; i < kk; i++ ) sum += s.get( i );
        return sum;
    }

    // ---- bookkeeping ----

    private static List< String > namesOf( final List< ScoredPage > ps ) {
        final List< String > out = new ArrayList<>( ps.size() );
        for( final ScoredPage p : ps ) out.add( p.name );
        return out;
    }

    private record ScoredPage( String name, double score ) {}

    private record CachedQuery( EvalQuery query,
                                List< Bm25Client.Scored > bm25Scored, double[] chunkScores ) {}

    private record Metrics( double recall5, double recall20, double mrr ) {
        static Metrics of( final int[] ranks ) {
            int h5 = 0, h20 = 0;
            double s = 0;
            for( final int r : ranks ) {
                if( r > 0 && r <= 5 ) h5++;
                if( r > 0 && r <= 20 ) h20++;
                if( r > 0 ) s += 1.0 / r;
            }
            return new Metrics( (double) h5 / ranks.length, (double) h20 / ranks.length, s / ranks.length );
        }
    }

    private record Result( Agg agg, Fusion fusion, Metrics m ) {}

}
