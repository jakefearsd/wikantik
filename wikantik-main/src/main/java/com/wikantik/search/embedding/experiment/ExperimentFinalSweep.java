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

import com.wikantik.search.embedding.EmbeddingClientFactory;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.EmbeddingModel;
import com.wikantik.search.embedding.TextEmbeddingClient;
import com.wikantik.search.embedding.experiment.QueryCorpus.EvalQuery;
import com.wikantik.search.embedding.experiment.ReciprocalRankFusion.Ranking;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Joint sweep of page-aggregation ∧ RRF parameters. Cache per-chunk cosines
 * per query once, then score every combo of (aggregation ∈ {MAX, MEAN_TOP_3,
 * SUM_TOP_3}) × (RRF k, wDense, truncation). Prints Pareto-frontier-ish
 * summaries for dense-only and hybrid.
 */
public final class ExperimentFinalSweep {

    private static final Logger LOG = LogManager.getLogger( ExperimentFinalSweep.class );

    private static final int BM25_TOP        = 100;
    private static final int DENSE_TOP       = 100;
    private static final int DENSE_CHUNK_TOP = 500;

    private static final int[]    K_VALUES       = { 40, 60, 100 };
    private static final double[] DENSE_WEIGHTS  = { 1.0, 1.25, 1.5, 2.0 };
    private static final int[]    TRUNCATIONS    = { 20, 50, 100 };

    enum Agg { MAX, MEAN_TOP_3, SUM_TOP_3 }

    private ExperimentFinalSweep() {}

    public static void main( final String[] args ) throws IOException, SQLException {
        if( args.length < 1 ) { System.err.println( "Usage: ExperimentFinalSweep <model-code>" ); System.exit( 2 ); }
        final String modelCode = args[ 0 ];
        final Path csv = Paths.get( "eval/retrieval-queries.csv" );
        final Path out = Paths.get( "eval", "final-sweep-" + modelCode + ".txt" );
        final EmbeddingModel model = EmbeddingModel.fromCode( modelCode );

        final List< EvalQuery > queries = QueryCorpus.load( csv );
        final TextEmbeddingClient client = buildClient( modelCode );
        final Bm25Client bm25 = Bm25Client.fromSystemProperties();

        try( final Connection conn = ExperimentDb.open() ) {
            final ChunkCorpus corpus = loadCorpus( conn, model.code(), client.dimension() );
            LOG.info( "loaded corpus: {} chunks", corpus.vectors.size() );

            LOG.info( "computing bm25 + per-chunk cosines for {} queries", queries.size() );
            final List< CachedQuery > cache = new ArrayList<>( queries.size() );
            for( final EvalQuery q : queries ) {
                final List< String > bm25Ranked = bm25.search( q.query(), BM25_TOP );
                final float[] qv = client.embed( List.of( q.query() ), EmbeddingKind.QUERY ).get( 0 );
                final double qn = CosineSimilarity.norm( qv );
                final double[] scores = new double[ corpus.vectors.size() ];
                for( int i = 0; i < corpus.vectors.size(); i++ ) {
                    scores[ i ] = CosineSimilarity.cosine( qv, qn,
                        corpus.vectors.get( i ), corpus.norms.get( i ) );
                }
                cache.add( new CachedQuery( q, bm25Ranked, scores ) );
            }

            // Precompute dense rankings per aggregation.
            final Map< Agg, List< List< String > > > denseRankingsByAgg = new LinkedHashMap<>();
            for( final Agg agg : Agg.values() ) {
                final List< List< String > > perQ = new ArrayList<>( cache.size() );
                for( final CachedQuery cq : cache ) {
                    perQ.add( denseRankingWithAgg( corpus, cq.chunkScores, agg ) );
                }
                denseRankingsByAgg.put( agg, perQ );
            }

            // Dense-only baselines per aggregation.
            final Map< Agg, Metrics > denseOnly = new LinkedHashMap<>();
            for( final Agg agg : Agg.values() ) {
                final int[] ranks = new int[ cache.size() ];
                for( int i = 0; i < cache.size(); i++ ) {
                    ranks[ i ] = rankOf( cache.get( i ).query.idealPage(), denseRankingsByAgg.get( agg ).get( i ) );
                }
                denseOnly.put( agg, Metrics.of( ranks ) );
            }

            // Hybrid grid: aggregation × k × wDense × trunc.
            final List< GridResult > all = new ArrayList<>();
            for( final Agg agg : Agg.values() ) {
                final List< List< String > > denseRankings = denseRankingsByAgg.get( agg );
                for( final int k : K_VALUES ) {
                    for( final double wD : DENSE_WEIGHTS ) {
                        for( final int trunc : TRUNCATIONS ) {
                            final int[] ranks = new int[ cache.size() ];
                            for( int i = 0; i < cache.size(); i++ ) {
                                final List< String > fused = ReciprocalRankFusion.fuseWeighted(
                                    List.of( new Ranking( cache.get( i ).bm25Ranked ),
                                             new Ranking( denseRankings.get( i ) ) ),
                                    new double[]{ 1.0, wD }, k, trunc );
                                ranks[ i ] = rankOf( cache.get( i ).query.idealPage(), fused );
                            }
                            all.add( new GridResult( agg, k, wD, trunc, Metrics.of( ranks ) ) );
                        }
                    }
                }
            }

            final String text = formatReport( modelCode, client.dimension(), queries.size(), denseOnly, all );
            System.out.print( text );
            Files.createDirectories( out.getParent() );
            Files.writeString( out, text );
            LOG.info( "final-sweep report written to {}", out.toAbsolutePath() );
        }
    }

    private static List< String > denseRankingWithAgg( final ChunkCorpus corpus, final double[] chunkScores,
                                                       final Agg agg ) {
        final int n = chunkScores.length;
        final int topN = Math.min( DENSE_CHUNK_TOP, n );
        final Integer[] idx = new Integer[ n ];
        for( int i = 0; i < n; i++ ) idx[ i ] = i;
        java.util.Arrays.sort( idx, Comparator.comparingDouble( ( Integer a ) -> chunkScores[ a ] ).reversed() );

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
        final List< String > out = new ArrayList<>( entries.size() );
        for( final Map.Entry< String, Double > e : entries ) out.add( e.getKey() );
        return out.size() > DENSE_TOP ? out.subList( 0, DENSE_TOP ) : out;
    }

    private static double applyAgg( final List< Double > scoresDescOrder, final Agg agg ) {
        return switch( agg ) {
            case MAX -> scoresDescOrder.get( 0 );
            case MEAN_TOP_3 -> {
                final int k = Math.min( 3, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                yield s / k;
            }
            case SUM_TOP_3 -> {
                final int k = Math.min( 3, scoresDescOrder.size() );
                double s = 0; for( int i = 0; i < k; i++ ) s += scoresDescOrder.get( i );
                yield s;
            }
        };
    }

    private static int rankOf( final String target, final List< String > ranked ) {
        for( int i = 0; i < ranked.size(); i++ ) {
            if( ranked.get( i ).equals( target ) ) return i + 1;
        }
        return 0;
    }

    private static ChunkCorpus loadCorpus( final Connection conn, final String modelCode, final int expectedDim )
            throws SQLException {
        final String sql = """
            SELECT e.chunk_id, e.dim, e.vec, c.page_name
            FROM experiment_embeddings e
            JOIN kg_content_chunks c ON c.id = e.chunk_id
            WHERE e.model_code = ?
            """;
        final List< UUID >    chunkIds = new ArrayList<>();
        final List< float[] > vectors  = new ArrayList<>();
        final List< Double >  norms    = new ArrayList<>();
        final Map< UUID, String > pagesForChunk = new HashMap<>();
        try( final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            ps.setString( 1, modelCode );
            ps.setFetchSize( 500 );
            try( final ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final UUID id = (UUID) rs.getObject( 1 );
                    final int dim = rs.getInt( 2 );
                    if( dim != expectedDim ) throw new IllegalStateException( "dim mismatch" );
                    final float[] v = VectorCodec.decode( rs.getBytes( 3 ), dim );
                    chunkIds.add( id );
                    vectors.add( v );
                    norms.add( CosineSimilarity.norm( v ) );
                    pagesForChunk.put( id, rs.getString( 4 ) );
                }
            }
        }
        return new ChunkCorpus( chunkIds, vectors, norms, pagesForChunk );
    }

    private record ChunkCorpus( List< UUID > chunkIds, List< float[] > vectors,
                                List< Double > norms, Map< UUID, String > pagesForChunk ) {}

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

    private record GridResult( Agg agg, int k, double wDense, int trunc, Metrics m ) {
        String fmt() {
            return String.format( Locale.ROOT, "agg=%-10s k=%3d wD=%.2f trunc=%3d", agg, k, wDense, trunc );
        }
    }

    private static String formatReport( final String modelCode, final int dim, final int nq,
                                        final Map< Agg, Metrics > denseOnly, final List< GridResult > all ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "Joint aggregation × RRF sweep — model: " ).append( modelCode )
          .append( "  dim=" ).append( dim ).append( "  queries=" ).append( nq ).append( "\n\n" );

        sb.append( "Dense-only baselines by aggregation:\n" );
        for( final Map.Entry< Agg, Metrics > e : denseOnly.entrySet() ) {
            sb.append( String.format( Locale.ROOT, "  %-10s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
                e.getKey().name(), e.getValue().recall5, e.getValue().recall20, e.getValue().mrr ) );
        }

        sb.append( '\n' ).append( "Top 10 hybrid combos by recall@5 (tiebreak MRR):\n" );
        appendTop( sb, all, Comparator.comparingDouble( ( GridResult g ) -> g.m.recall5 )
                                      .thenComparingDouble( g -> g.m.mrr ).reversed() );

        sb.append( '\n' ).append( "Top 10 hybrid combos by MRR (tiebreak r@5):\n" );
        appendTop( sb, all, Comparator.comparingDouble( ( GridResult g ) -> g.m.mrr )
                                      .thenComparingDouble( g -> g.m.recall5 ).reversed() );

        sb.append( '\n' ).append( "Top 10 hybrid combos by recall@20 (tiebreak r@5):\n" );
        appendTop( sb, all, Comparator.comparingDouble( ( GridResult g ) -> g.m.recall20 )
                                      .thenComparingDouble( g -> g.m.recall5 ).reversed() );

        sb.append( '\n' ).append( "Best combo per aggregation (lexicographic r@5, r@20, MRR):\n" );
        for( final Agg agg : Agg.values() ) {
            final GridResult best = all.stream().filter( g -> g.agg == agg )
                .max( Comparator.comparingDouble( ( GridResult g ) -> g.m.recall5 )
                                .thenComparingDouble( g -> g.m.recall20 )
                                .thenComparingDouble( g -> g.m.mrr ) ).orElseThrow();
            sb.append( String.format( Locale.ROOT, "  %s  →  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
                best.fmt(), best.m.recall5, best.m.recall20, best.m.mrr ) );
        }
        return sb.toString();
    }

    private static void appendTop( final StringBuilder sb, final List< GridResult > all,
                                   final Comparator< GridResult > cmp ) {
        final List< GridResult > sorted = new ArrayList<>( all );
        sorted.sort( cmp );
        for( int i = 0; i < Math.min( 10, sorted.size() ); i++ ) {
            final GridResult g = sorted.get( i );
            sb.append( String.format( Locale.ROOT, "  %s  r@5=%.3f  r@20=%.3f  MRR=%.3f%n",
                g.fmt(), g.m.recall5, g.m.recall20, g.m.mrr ) );
        }
    }

    private static TextEmbeddingClient buildClient( final String modelCode ) throws IOException {
        final Properties p = new Properties();
        try( final InputStream in = ExperimentFinalSweep.class.getResourceAsStream( "/ini/wikantik.properties" ) ) {
            if( in != null ) p.load( in );
        }
        for( final String key : List.of(
            EmbeddingConfig.PROP_BACKEND, EmbeddingConfig.PROP_BASE_URL, EmbeddingConfig.PROP_API_KEY,
            EmbeddingConfig.PROP_OLLAMA_TAG, EmbeddingConfig.PROP_TIMEOUT_MS, EmbeddingConfig.PROP_BATCH_SIZE ) ) {
            final String v = System.getProperty( key );
            if( v != null && !v.isBlank() ) p.setProperty( key, v );
        }
        p.setProperty( EmbeddingConfig.PROP_ENABLED, "true" );
        p.setProperty( EmbeddingConfig.PROP_MODEL, modelCode );
        return EmbeddingClientFactory.create( EmbeddingConfig.fromProperties( p ) ).orElseThrow();
    }
}
