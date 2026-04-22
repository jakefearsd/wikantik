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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sweeps weighted-RRF hyperparameters (k, dense weight, per-list truncation)
 * against cached BM25 + dense rankings for a single model. BM25 and dense
 * rankings are computed once per query; fusion is evaluated many times over
 * the cached lists, so a 100+ parameter grid runs in seconds.
 * Report prints baseline (current production defaults) plus the top-N
 * combos by recall@5, recall@20, and MRR — so you can see which lever
 * moves which metric.
 */
public final class ExperimentRrfSweep {

    private static final Logger LOG = LogManager.getLogger( ExperimentRrfSweep.class );

    private static final int BM25_TOP       = 100;
    private static final int DENSE_TOP      = 100;
    private static final int DENSE_CHUNK_TOP = 500;

    /** Parameter grid — covers the three tuning levers called out in the RFC. */
    private static final int[]    K_VALUES     = { 10, 20, 40, 60, 100 };
    private static final double[] DENSE_WEIGHTS = { 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0 };
    private static final int[]    TRUNCATIONS  = { 10, 20, 50, 100 };

    private ExperimentRrfSweep() {}

    @SuppressWarnings("PMD.SystemPrintln")

    public static void main( final String[] args ) throws IOException, SQLException {
        if( args.length < 1 ) {
            System.err.println( """
                Usage: ExperimentRrfSweep <model-code> [queries.csv] [output.txt]
                Required:  -Dwikantik.experiment.db.password=<pw>
                """ );
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final Path csv = args.length >= 2 ? Paths.get( args[ 1 ] ) : Paths.get( "eval/retrieval-queries.csv" );
        final Path out = args.length >= 3 ? Paths.get( args[ 2 ] )
                                          : Paths.get( "eval", "rrf-sweep-" + modelCode + ".txt" );
        final EmbeddingModel model = EmbeddingModel.fromCode( modelCode );

        final List< EvalQuery > queries = QueryCorpus.load( csv );
        final TextEmbeddingClient client = ExperimentHarness.buildClient( modelCode );
        Bm25Client bm25 = Bm25Client.fromSystemProperties();

        try( Connection conn = ExperimentDb.open() ) {
            final ChunkCorpus corpus = ExperimentHarness.loadCorpus( conn, model.code(), client.dimension() );
            LOG.info( "loaded corpus: {} chunks across {} pages (dim={})",
                      corpus.vectors.size(), corpus.pagesForChunk.values().stream().distinct().count(),
                      client.dimension() );

            // Phase 1 — compute per-query rankings ONCE.
            LOG.info( "phase 1: computing {} bm25 + dense rankings (one-time cost)", queries.size() );
            final List< CachedRankings > cache = new ArrayList<>( queries.size() );
            for( final EvalQuery q : queries ) {
                final List< String > bm25Ranked = bm25.search( q.query(), BM25_TOP );
                final float[] qv = client.embed( List.of( q.query() ), EmbeddingKind.QUERY ).get( 0 );
                final double qn = CosineSimilarity.norm( qv );
                final List< String > denseRanked = denseRanking( corpus, qv, qn );
                cache.add( new CachedRankings( q, bm25Ranked, denseRanked ) );
            }

            // Phase 2 — evaluate the full grid over the cache.
            final List< GridResult > all = new ArrayList<>();
            for( final int k : K_VALUES ) {
                for( final double wDense : DENSE_WEIGHTS ) {
                    for( final int trunc : TRUNCATIONS ) {
                        all.add( scoreCombo( cache, k, wDense, 1.0, trunc ) );
                    }
                }
            }
            // Also emit the single-retriever sanity baselines plus the
            // current-production hybrid (k=60, w=1,1, trunc=100) separately.
            final GridResult bm25Only  = scoreSingle( cache, /*dense=*/false );
            final GridResult denseOnly = scoreSingle( cache, /*dense=*/true );
            final GridResult baseline  = scoreCombo( cache, 60, 1.0, 1.0, 100 );

            final String text = formatReport( modelCode, client.dimension(), queries.size(),
                                              bm25Only, denseOnly, baseline, all );
            System.out.print( text );
            ExperimentHarness.writeReport( out, text );
            LOG.info( "sweep report written to {}", out.toAbsolutePath() );
        }
    }

    // ---- core scoring ----

    private static GridResult scoreCombo( final List< CachedRankings > cache,
                                          final int k, final double wDense, final double wBm25,
                                          final int trunc ) {
        final int[] ranks = new int[ cache.size() ];
        for( int i = 0; i < cache.size(); i++ ) {
            final CachedRankings cr = cache.get( i );
            final List< String > fused = ReciprocalRankFusion.fuseWeighted(
                List.of( new Ranking( cr.bm25Ranked ), new Ranking( cr.denseRanked ) ),
                new double[]{ wBm25, wDense }, k, trunc );
            ranks[ i ] = ExperimentHarness.rankOf( cr.query.idealPage(), fused );
        }
        return new GridResult( k, wDense, wBm25, trunc,
            recallAt( ranks, 5 ), recallAt( ranks, 20 ), mrr( ranks ) );
    }

    private static GridResult scoreSingle( final List< CachedRankings > cache, final boolean dense ) {
        final int[] ranks = new int[ cache.size() ];
        for( int i = 0; i < cache.size(); i++ ) {
            final CachedRankings cr = cache.get( i );
            ranks[ i ] = ExperimentHarness.rankOf( cr.query.idealPage(), dense ? cr.denseRanked : cr.bm25Ranked );
        }
        return new GridResult( 0, 0, 0, 0,
            recallAt( ranks, 5 ), recallAt( ranks, 20 ), mrr( ranks ) );
    }

    private static double recallAt( final int[] ranks, final int k ) {
        int hits = 0;
        for( final int r : ranks ) if( r > 0 && r <= k ) hits++;
        return (double) hits / ranks.length;
    }

    private static double mrr( final int[] ranks ) {
        double sum = 0;
        for( final int r : ranks ) if( r > 0 ) sum += 1.0 / r;
        return sum / ranks.length;
    }

    // ---- dense-ranking helpers (copied from ExperimentEvaluator) ----

    private static List< String > denseRanking( final ChunkCorpus corpus, final float[] queryVec, final double queryNorm ) {
        final int n = corpus.vectors.size();
        final int k = Math.min( DENSE_CHUNK_TOP, n );
        final double[] scores = new double[ n ];
        for( int i = 0; i < n; i++ ) {
            scores[ i ] = CosineSimilarity.cosine( queryVec, queryNorm,
                corpus.vectors.get( i ), corpus.norms.get( i ) );
        }
        final Integer[] idx = new Integer[ n ];
        for( int i = 0; i < n; i++ ) idx[ i ] = i;
        java.util.Arrays.sort( idx, Comparator.comparingDouble( ( Integer a ) -> scores[ a ] ).reversed() );

        final Map< String, Double > pageBest = new LinkedHashMap<>();
        for( int i = 0; i < k; i++ ) {
            final int j = idx[ i ];
            final String page = corpus.pagesForChunk.get( corpus.chunkIds.get( j ) );
            pageBest.merge( page, scores[ j ], Math::max );
        }
        final List< Map.Entry< String, Double > > entries = new ArrayList<>( pageBest.entrySet() );
        entries.sort( Comparator.comparingDouble( Map.Entry< String, Double >::getValue ).reversed() );
        final List< String > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( e.getKey() );
        return out.size() > DENSE_TOP ? out.subList( 0, DENSE_TOP ) : out;
    }

    private record CachedRankings( EvalQuery query, List< String > bm25Ranked, List< String > denseRanked ) {}

    private record GridResult( int k, double wDense, double wBm25, int trunc,
                               double recall5, double recall20, double mrr ) {
        String fmtParams() {
            return k == 0
                ? "(single retriever)"
                : String.format( Locale.ROOT, "k=%3d  wD=%.2f  wB=%.2f  trunc=%3d",
                                 k, wDense, wBm25, trunc );
        }
    }

    // ---- report ----

    private static String formatReport( final String modelCode, final int dim, final int nq,
                                        final GridResult bm25Only, final GridResult denseOnly,
                                        final GridResult baseline, final List< GridResult > all ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "RRF hyperparameter sweep — model: " ).append( modelCode )
          .append( "  dim=" ).append( dim ).append( "  queries=" ).append( nq ).append( "\n\n" );

        sb.append( "Baselines:\n" );
        sb.append( String.format( Locale.ROOT, "  %-30s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
            "bm25-only",             bm25Only.recall5,  bm25Only.recall20,  bm25Only.mrr ) );
        sb.append( String.format( Locale.ROOT, "  %-30s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
            "dense-only",            denseOnly.recall5, denseOnly.recall20, denseOnly.mrr ) );
        sb.append( String.format( Locale.ROOT, "  %-30s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
            "hybrid (k=60,w=1,t=100)", baseline.recall5, baseline.recall20, baseline.mrr ) );

        sb.append( '\n' ).append( "Top 10 by recall@5:\n" );
        appendTop( sb, all, Comparator.comparingDouble( GridResult::recall5 )
                                     .thenComparingDouble( GridResult::mrr ).reversed() );

        sb.append( '\n' ).append( "Top 10 by recall@20:\n" );
        appendTop( sb, all, Comparator.comparingDouble( GridResult::recall20 )
                                     .thenComparingDouble( GridResult::recall5 ).reversed() );

        sb.append( '\n' ).append( "Top 10 by MRR:\n" );
        appendTop( sb, all, Comparator.comparingDouble( GridResult::mrr )
                                     .thenComparingDouble( GridResult::recall5 ).reversed() );

        sb.append( '\n' ).append( "Full grid (" ).append( all.size() ).append( " combos), sorted by (r@5, r@20, MRR):\n" );
        final List< GridResult > sorted = new ArrayList<>( all );
        sorted.sort( Comparator
            .comparingDouble( GridResult::recall5 )
            .thenComparingDouble( GridResult::recall20 )
            .thenComparingDouble( GridResult::mrr )
            .reversed() );
        for( final GridResult g : sorted ) {
            sb.append( String.format( Locale.ROOT, "  %-30s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
                g.fmtParams(), g.recall5, g.recall20, g.mrr ) );
        }
        return sb.toString();
    }

    private static void appendTop( final StringBuilder sb, final List< GridResult > all,
                                   final Comparator< GridResult > cmp ) {
        final List< GridResult > sorted = new ArrayList<>( all );
        sorted.sort( cmp );
        for( int i = 0; i < Math.min( 10, sorted.size() ); i++ ) {
            final GridResult g = sorted.get( i );
            sb.append( String.format( Locale.ROOT, "  %-30s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
                g.fmtParams(), g.recall5, g.recall20, g.mrr ) );
        }
    }

}
