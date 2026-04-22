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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Compares chunk→page aggregation strategies for the dense retriever, using
 * the RRF settings frozen by {@link ExperimentRrfSweep} (k=60, trunc=50,
 * wDense=1.0). For each aggregation, prints recall@5/20 and MRR for dense-
 * only and hybrid so you can see whether aggregation alone moves the ceiling.
 */
public final class ExperimentAggSweep {

    private static final Logger LOG = LogManager.getLogger( ExperimentAggSweep.class );

    private static final int BM25_TOP        = 100;
    private static final int DENSE_TOP       = 100;
    private static final int DENSE_CHUNK_TOP = 500;

    // Frozen from experiment 1.
    private static final int    RRF_K        = 60;
    private static final double W_DENSE      = 1.0;
    private static final double W_BM25       = 1.0;
    private static final int    RRF_TRUNCATE = 50;

    enum Agg {
        MAX,          // score(page) = max(chunk score)
        MEAN_TOP_3,   // mean of top-3 chunks per page (fewer chunks → shorter mean)
        MEAN_TOP_5,
        SUM_TOP_3,    // sum of top-3 (favors pages with many strong chunks)
        SUM_TOP_5,
        MEAN_TOP_3_LOG_NORM; // mean_top_3 / log(1 + chunk_count) — penalizes huge pages
    }

    private ExperimentAggSweep() {}

    @SuppressWarnings("PMD.SystemPrintln")

    public static void main( final String[] args ) throws IOException, SQLException {
        if( args.length < 1 ) {
            System.err.println( "Usage: ExperimentAggSweep <model-code> [queries.csv] [output.txt]" );
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final Path csv = args.length >= 2 ? Paths.get( args[ 1 ] ) : Paths.get( "eval/retrieval-queries.csv" );
        final Path out = args.length >= 3 ? Paths.get( args[ 2 ] )
                                          : Paths.get( "eval", "agg-sweep-" + modelCode + ".txt" );
        final EmbeddingModel model = EmbeddingModel.fromCode( modelCode );

        final List< EvalQuery > queries = QueryCorpus.load( csv );
        final TextEmbeddingClient client = ExperimentHarness.buildClient( modelCode );
        Bm25Client bm25 = Bm25Client.fromSystemProperties();

        try( final Connection conn = ExperimentDb.open() ) {
            final ChunkCorpus corpus = ExperimentHarness.loadCorpus( conn, model.code(), client.dimension() );
            LOG.info( "loaded corpus: {} chunks across {} pages",
                      corpus.vectors.size(), corpus.pagesForChunk.values().stream().distinct().count() );

            // Cache per-query: (bm25 ranking, per-chunk cosines).
            LOG.info( "phase 1: computing bm25 + per-chunk cosines for {} queries", queries.size() );
            final List< CachedQuery > cache = new ArrayList<>( queries.size() );
            for( final EvalQuery q : queries ) {
                final List< String > bm25Ranked = bm25.search( q.query(), BM25_TOP );
                final float[] qv = client.embed( List.of( q.query() ), EmbeddingKind.QUERY ).get( 0 );
                final double qn = CosineSimilarity.norm( qv );
                final double[] chunkScores = new double[ corpus.vectors.size() ];
                for( int i = 0; i < corpus.vectors.size(); i++ ) {
                    chunkScores[ i ] = CosineSimilarity.cosine( qv, qn,
                        corpus.vectors.get( i ), corpus.norms.get( i ) );
                }
                cache.add( new CachedQuery( q, bm25Ranked, chunkScores ) );
            }

            // Evaluate each aggregation.
            final Map< Agg, Metrics > denseMetrics = new LinkedHashMap<>();
            final Map< Agg, Metrics > hybridMetrics = new LinkedHashMap<>();
            for( final Agg agg : Agg.values() ) {
                final int[] denseRanks  = new int[ cache.size() ];
                final int[] hybridRanks = new int[ cache.size() ];
                for( int i = 0; i < cache.size(); i++ ) {
                    final CachedQuery cq = cache.get( i );
                    final List< String > denseRanked = denseRankingWithAgg( corpus, cq.chunkScores, agg );
                    denseRanks[ i ] = ExperimentHarness.rankOf( cq.query.idealPage(), denseRanked );

                    final List< String > hybridRanked = ReciprocalRankFusion.fuseWeighted(
                        List.of( new Ranking( cq.bm25Ranked ), new Ranking( denseRanked ) ),
                        new double[]{ W_BM25, W_DENSE }, RRF_K, RRF_TRUNCATE );
                    hybridRanks[ i ] = ExperimentHarness.rankOf( cq.query.idealPage(), hybridRanked );
                }
                denseMetrics.put( agg, Metrics.of( denseRanks ) );
                hybridMetrics.put( agg, Metrics.of( hybridRanks ) );
            }

            final String text = formatReport( modelCode, client.dimension(), queries.size(),
                                              denseMetrics, hybridMetrics );
            System.out.print( text );
            ExperimentHarness.writeReport( out, text );
            LOG.info( "agg-sweep report written to {}", out.toAbsolutePath() );
        }
    }

    /** Returns page ranking by aggregated cosine score over chunks. */
    private static List< String > denseRankingWithAgg( final ChunkCorpus corpus, final double[] chunkScores,
                                                       final Agg agg ) {
        // First, get top-N chunks to consider (matches ExperimentEvaluator behavior).
        final int n = chunkScores.length;
        final int topN = Math.min( DENSE_CHUNK_TOP, n );
        final Integer[] idx = new Integer[ n ];
        for( int i = 0; i < n; i++ ) idx[ i ] = i;
        java.util.Arrays.sort( idx, Comparator.comparingDouble( ( Integer a ) -> chunkScores[ a ] ).reversed() );

        // Group top chunks by page.
        final Map< String, List< Double > > perPage = new LinkedHashMap<>();
        final Map< String, Integer > chunkCountPerPage = new HashMap<>();
        // chunkCountPerPage needs the global count for this page — computed lazily.
        for( int i = 0; i < topN; i++ ) {
            final int j = idx[ i ];
            final String page = corpus.pagesForChunk.get( corpus.chunkIds.get( j ) );
            perPage.computeIfAbsent( page, p -> new ArrayList<>() ).add( chunkScores[ j ] );
        }
        if( agg == Agg.MEAN_TOP_3_LOG_NORM ) {
            // Count total chunks per page across corpus (not just in top-500).
            for( final String page : perPage.keySet() ) {
                int cc = 0;
                for( final UUID id : corpus.chunkIds ) {
                    if( page.equals( corpus.pagesForChunk.get( id ) ) ) cc++;
                }
                chunkCountPerPage.put( page, cc );
            }
        }

        final Map< String, Double > pageScore = new LinkedHashMap<>();
        for( final Map.Entry< String, List< Double > > e : perPage.entrySet() ) {
            pageScore.put( e.getKey(), applyAgg( e.getValue(), agg, chunkCountPerPage.get( e.getKey() ) ) );
        }
        final List< Map.Entry< String, Double > > entries = new ArrayList<>( pageScore.entrySet() );
        entries.sort( Comparator.comparingDouble( Map.Entry< String, Double >::getValue ).reversed() );
        final List< String > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( e.getKey() );
        return out.size() > DENSE_TOP ? out.subList( 0, DENSE_TOP ) : out;
    }

    private static double applyAgg( final List< Double > scoresDescOrder, final Agg agg, final Integer chunkCount ) {
        switch( agg ) {
            case MAX:
                return scoresDescOrder.get( 0 );
            case MEAN_TOP_3: {
                final int k = Math.min( 3, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                return s / k;
            }
            case MEAN_TOP_5: {
                final int k = Math.min( 5, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                return s / k;
            }
            case SUM_TOP_3: {
                final int k = Math.min( 3, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                return s;
            }
            case SUM_TOP_5: {
                final int k = Math.min( 5, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                return s;
            }
            case MEAN_TOP_3_LOG_NORM: {
                final int k = Math.min( 3, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                final double mean = s / k;
                final int cc = chunkCount == null ? 1 : chunkCount;
                return mean / Math.log( 1 + cc );
            }
            default:
                throw new IllegalStateException( agg.toString() );
        }
    }

    // ---- corpus + helpers ----

    private record CachedQuery( EvalQuery query, List< String > bm25Ranked, double[] chunkScores ) {}

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

    private static String formatReport( final String modelCode, final int dim, final int nq,
                                        final Map< Agg, Metrics > dense,
                                        final Map< Agg, Metrics > hybrid ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "Chunk→page aggregation sweep — model: " ).append( modelCode )
          .append( "  dim=" ).append( dim ).append( "  queries=" ).append( nq ).append( '\n' );
        sb.append( "Fusion: RRF k=" ).append( RRF_K ).append( ", wD=" ).append( W_DENSE )
          .append( ", wB=" ).append( W_BM25 ).append( ", trunc=" ).append( RRF_TRUNCATE ).append( "\n\n" );

        sb.append( String.format( Locale.ROOT, "  %-22s  %-10s  %7s  %7s  %7s%n",
            "aggregation", "retriever", "r@5", "r@20", "MRR" ) );
        sb.append( "  " ).append( "-".repeat( 65 ) ).append( '\n' );
        for( final Agg agg : Agg.values() ) {
            final Metrics d = dense.get( agg );
            final Metrics h = hybrid.get( agg );
            sb.append( String.format( Locale.ROOT, "  %-22s  %-10s  %7.3f  %7.3f  %7.3f%n",
                agg.name(), "dense",  d.recall5, d.recall20, d.mrr ) );
            sb.append( String.format( Locale.ROOT, "  %-22s  %-10s  %7.3f  %7.3f  %7.3f%n",
                agg.name(), "hybrid", h.recall5, h.recall20, h.mrr ) );
        }
        return sb.toString();
    }

}
