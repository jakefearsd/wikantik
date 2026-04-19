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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Scores a single embedding model against the seed eval set and a running
 * wiki's BM25 endpoint. Produces a report with per-retriever (dense-only,
 * bm25-only, hybrid) recall@5, recall@20, MRR, both overall and per category.
 */
public final class ExperimentEvaluator {

    private static final Logger LOG = LogManager.getLogger( ExperimentEvaluator.class );

    private static final int BM25_TOP       = 100;
    private static final int DENSE_TOP      = 100;
    private static final int DENSE_CHUNK_TOP = 500;  // chunks considered before page aggregation

    private ExperimentEvaluator() {}

    public static void main( final String[] args ) throws IOException, SQLException {
        if( args.length < 1 ) {
            System.err.println( """
                Usage: ExperimentEvaluator <model-code> [queries.csv] [output.txt]
                  model-code: nomic-embed-v1.5 | bge-m3 | qwen3-embedding-0.6b
                  queries.csv: defaults to eval/retrieval-queries.csv
                  output.txt:  defaults to eval/report-<model>-<timestamp>.txt
                Required:   -Dwikantik.experiment.db.password=<pw>
                Optional:   -Dwikantik.experiment.wiki.base-url   (default http://localhost:8080)
                            -Dwikantik.experiment.wiki.user|password (basic-auth for protected pages)
                """ );
            System.exit( 2 );
        }
        final String modelCode = args[ 0 ];
        final Path csv = args.length >= 2 ? Paths.get( args[ 1 ] ) : Paths.get( "eval/retrieval-queries.csv" );
        final EmbeddingModel model = EmbeddingModel.fromCode( modelCode );

        final List< EvalQuery > queries = QueryCorpus.load( csv );
        LOG.info( "loaded {} queries from {}", queries.size(), csv );

        final TextEmbeddingClient client = buildClient( modelCode );
        final Bm25Client bm25 = Bm25Client.fromSystemProperties();

        try( final Connection conn = ExperimentDb.open() ) {
            final ChunkCorpus corpus = loadCorpus( conn, model.code(), client.dimension() );
            LOG.info( "loaded corpus: {} chunks across {} pages (dim={})",
                      corpus.vectors.size(), corpus.pagesForChunk.values().stream().distinct().count(),
                      client.dimension() );

            final List< QueryResult > results = new ArrayList<>();
            for( final EvalQuery q : queries ) {
                results.add( evaluateOne( q, client, bm25, corpus ) );
            }

            final Report report = new Report( modelCode, client.dimension(), queries.size(), results );
            final String text = report.format();
            System.out.print( text );
            final Path out = args.length >= 3 ? Paths.get( args[ 2 ] ) : defaultOutputPath( modelCode );
            Files.createDirectories( out.getParent() );
            Files.writeString( out, text );
            LOG.info( "report written to {}", out.toAbsolutePath() );
        }
    }

    // ---- retriever / fusion ----

    private static QueryResult evaluateOne( final EvalQuery q, final TextEmbeddingClient client,
                                            final Bm25Client bm25, final ChunkCorpus corpus ) {
        final List< String > bm25Ranked = bm25.search( q.query(), BM25_TOP );

        final float[] queryVec = client.embed( List.of( q.query() ), EmbeddingKind.QUERY ).get( 0 );
        final double queryNorm = CosineSimilarity.norm( queryVec );
        final List< String > densePagesRanked = denseRanking( corpus, queryVec, queryNorm );

        final List< String > hybridRanked = ReciprocalRankFusion.fuse(
            List.of( new Ranking( bm25Ranked ), new Ranking( densePagesRanked ) ),
            ReciprocalRankFusion.DEFAULT_K );

        return new QueryResult( q,
            rankOf( q.idealPage(), bm25Ranked ),
            rankOf( q.idealPage(), densePagesRanked ),
            rankOf( q.idealPage(), hybridRanked ) );
    }

    private static List< String > denseRanking( final ChunkCorpus corpus, final float[] queryVec, final double queryNorm ) {
        final int n = corpus.vectors.size();
        final int k = Math.min( DENSE_CHUNK_TOP, n );
        // Min-heap of top-k by cosine similarity.
        final double[] scores = new double[ n ];
        for( int i = 0; i < n; i++ ) {
            scores[ i ] = CosineSimilarity.cosine( queryVec, queryNorm,
                corpus.vectors.get( i ), corpus.norms.get( i ) );
        }
        // Simple sort — n=10K is trivial.
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

    private static int rankOf( final String target, final List< String > ranked ) {
        for( int i = 0; i < ranked.size(); i++ ) {
            if( ranked.get( i ).equals( target ) ) return i + 1; // 1-based; 0 = not found
        }
        return 0;
    }

    // ---- corpus loading ----

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
                    if( dim != expectedDim ) {
                        throw new IllegalStateException( "dim mismatch for chunk " + id
                            + ": stored=" + dim + " expected=" + expectedDim );
                    }
                    final float[] v = VectorCodec.decode( rs.getBytes( 3 ), dim );
                    chunkIds.add( id );
                    vectors.add( v );
                    norms.add( CosineSimilarity.norm( v ) );
                    pagesForChunk.put( id, rs.getString( 4 ) );
                }
            }
        }
        if( chunkIds.isEmpty() ) {
            throw new IllegalStateException( "no embeddings found for model_code=" + modelCode
                + " — did you run ExperimentIndexer first?" );
        }
        return new ChunkCorpus( chunkIds, vectors, norms, pagesForChunk );
    }

    private record ChunkCorpus( List< UUID > chunkIds, List< float[] > vectors,
                                List< Double > norms, Map< UUID, String > pagesForChunk ) {}

    // ---- client helpers ----

    private static TextEmbeddingClient buildClient( final String modelCode ) throws IOException {
        final Properties p = new Properties();
        try( final InputStream in = ExperimentEvaluator.class.getResourceAsStream( "/ini/wikantik.properties" ) ) {
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

    private static Path defaultOutputPath( final String modelCode ) {
        final String stamp = Instant.now().toString().replaceAll( "[:.]", "-" );
        return Paths.get( "eval", "report-" + modelCode + "-" + stamp + ".txt" );
    }

    // ---- report model ----

    record QueryResult( EvalQuery query, int bm25Rank, int denseRank, int hybridRank ) {
        boolean hitAt( final int k, final int rank ) { return rank > 0 && rank <= k; }
    }

    record Report( String modelCode, int dim, int nQueries, List< QueryResult > results ) {
        String format() {
            final StringBuilder sb = new StringBuilder();
            sb.append( "Retrieval evaluation — model: " ).append( modelCode )
              .append( "  dim=" ).append( dim ).append( '\n' )
              .append( "Date: " ).append( Instant.now() ).append( '\n' )
              .append( "Queries: " ).append( nQueries ).append( "\n\n" );

            appendOverall( sb );
            sb.append( '\n' );
            appendPerCategory( sb );
            sb.append( '\n' );
            appendPerQuery( sb );
            return sb.toString();
        }

        private void appendOverall( final StringBuilder sb ) {
            sb.append( "Overall:\n" );
            sb.append( String.format( Locale.ROOT, "  %-8s  %8s  %8s  %8s%n", "retriever", "recall@5", "recall@20", "MRR" ) );
            for( final String r : List.of( "bm25", "dense", "hybrid" ) ) {
                sb.append( String.format( Locale.ROOT, "  %-8s  %8.3f  %8.3f  %8.3f%n",
                    r, recallAt( r, 5 ), recallAt( r, 20 ), mrr( r ) ) );
            }
        }

        private void appendPerCategory( final StringBuilder sb ) {
            sb.append( "Per-category:\n" );
            sb.append( String.format( Locale.ROOT, "  %-20s  %3s  %-8s  %8s  %8s  %8s%n",
                "category", "n", "retriever", "recall@5", "recall@20", "MRR" ) );
            final Map< String, List< QueryResult > > byCat = new TreeMap<>();
            for( final QueryResult qr : results ) {
                byCat.computeIfAbsent( qr.query().category(), x -> new ArrayList<>() ).add( qr );
            }
            for( final Map.Entry< String, List< QueryResult > > e : byCat.entrySet() ) {
                for( final String r : List.of( "bm25", "dense", "hybrid" ) ) {
                    sb.append( String.format( Locale.ROOT, "  %-20s  %3d  %-8s  %8.3f  %8.3f  %8.3f%n",
                        e.getKey(), e.getValue().size(), r,
                        recallAt( e.getValue(), r, 5 ),
                        recallAt( e.getValue(), r, 20 ),
                        mrr( e.getValue(), r ) ) );
                }
            }
        }

        private void appendPerQuery( final StringBuilder sb ) {
            sb.append( "Per-query (rank of ideal_page; 0 = miss):\n" );
            sb.append( String.format( Locale.ROOT, "  %4s  %4s  %6s  %-20s  %-50s  %s%n",
                "bm25", "dens", "hybrid", "category", "ideal_page", "query" ) );
            for( final QueryResult qr : results ) {
                sb.append( String.format( Locale.ROOT, "  %4s  %4s  %6s  %-20s  %-50s  %s%n",
                    rankCell( qr.bm25Rank() ), rankCell( qr.denseRank() ), rankCell( qr.hybridRank() ),
                    qr.query().category(), qr.query().idealPage(), qr.query().query() ) );
            }
        }

        private static String rankCell( final int r ) { return r == 0 ? "—" : Integer.toString( r ); }

        private double recallAt( final String retriever, final int k ) {
            return recallAt( results, retriever, k );
        }

        private double mrr( final String retriever ) { return mrr( results, retriever ); }

        private static int rank( final QueryResult qr, final String retriever ) {
            return switch( retriever ) {
                case "bm25"   -> qr.bm25Rank();
                case "dense"  -> qr.denseRank();
                case "hybrid" -> qr.hybridRank();
                default -> throw new IllegalArgumentException( retriever );
            };
        }

        private static double recallAt( final List< QueryResult > rs, final String retriever, final int k ) {
            if( rs.isEmpty() ) return 0;
            int hits = 0;
            for( final QueryResult qr : rs ) {
                final int r = rank( qr, retriever );
                if( r > 0 && r <= k ) hits++;
            }
            return (double) hits / rs.size();
        }

        private static double mrr( final List< QueryResult > rs, final String retriever ) {
            if( rs.isEmpty() ) return 0;
            double sum = 0;
            for( final QueryResult qr : rs ) {
                final int r = rank( qr, retriever );
                if( r > 0 ) sum += 1.0 / r;
            }
            return sum / rs.size();
        }
    }
}
