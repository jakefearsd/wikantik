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
package com.wikantik.search;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.search.SearchResult;
import com.wikantik.api.spi.Wiki;
import jakarta.servlet.http.HttpServletRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Retrieval quality baseline. Not part of the automatic test run —
 * {@code @Disabled} by default. To run it, comment out the annotation and
 * execute:
 *
 * <pre>
 *   mvn test -pl wikantik-main -Dtest=RetrievalEvalTest
 * </pre>
 *
 * Indexes the full {@code docs/wikantik-pages/} corpus through Lucene and
 * measures {@code recall@5}, {@code recall@20}, and Mean Reciprocal Rank
 * against the seed query set at
 * {@code src/test/resources/retrieval-eval/queries.csv}. Writes a formatted
 * report to {@code target/retrieval-eval-report.txt} and stdout.
 *
 * <p>This is a measurement tool, not a gate: it never fails on thresholds.
 * Numbers are the pre-embedding Lucene BM25 baseline and serve as the
 * reference point for future retrieval changes (hybrid BM25+vector,
 * reranker, graph expansion, etc.).
 */
@Disabled( "manual: remove @Disabled locally to capture a baseline" )
class RetrievalEvalTest {

    private record Query( String query, String idealPage, String notes ) {}
    private record Eval( Query q, int rank, int totalHits ) {}

    @Test
    void evaluateCurrentRetrieval() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        final long ts = System.currentTimeMillis();
        // CRITICAL: copy the corpus to an isolated tmp dir rather than point
        // the engine at docs/wikantik-pages directly. TestEngine.shutdown()
        // (called from engine.stop()) calls emptyWikiDir() which deletes the
        // pageDir contents — pointing at the real corpus would wipe it.
        final Path corpusCopy = Path.of( "target",
                "retrieval-eval-corpus-" + ts ).toAbsolutePath();
        copyCorpus( resolveCorpusDir(), corpusCopy );

        // Opt out of TestEngine.cleanTestProps rewriting our paths.
        props.setProperty( "wikantik.test.disable-clean-props", "true" );
        props.setProperty( SearchManager.PROP_SEARCHPROVIDER, "LuceneSearchProvider" );
        props.setProperty( "wikantik.lucene.indexdelay", "0" );
        props.setProperty( "wikantik.lucene.initialdelay", "0" );
        final Path workDir = Path.of( "target", "retrieval-eval-workdir-" + ts )
                .toAbsolutePath();
        Files.createDirectories( workDir );
        props.setProperty( "wikantik.workDir", workDir.toString() );
        final Path attachDir = Path.of( "target", "retrieval-eval-attach-" + ts )
                .toAbsolutePath();
        Files.createDirectories( attachDir );
        props.setProperty( "wikantik.basicAttachmentProvider.storageDir",
                attachDir.toString() );
        props.setProperty( "wikantik.fileSystemProvider.pageDir",
                corpusCopy.toString() );
        final TestEngine engine = TestEngine.build( props );

        try {
            final SearchManager mgr = engine.getManager( SearchManager.class );
            final HttpServletRequest req = HttpMockFactory.createHttpRequest();
            final Context ctx = Wiki.context().create( engine, req,
                    ContextEnum.PAGE_VIEW.getRequestContext() );

            // Corpus is ~1000 pages — allow up to 180s for Lucene to index
            // (docs say 2 min should suffice, but indexing is single-threaded).
            Awaitility.await( "lucene index populated against docs/wikantik-pages" )
                    .atMost( 180, TimeUnit.SECONDS )
                    .pollInterval( 2, TimeUnit.SECONDS )
                    .until( () -> {
                        final Collection< SearchResult > r =
                                mgr.findPages( "WikantikDevelopment", ctx );
                        return r != null && !r.isEmpty();
                    } );

            final List< Query > queries = loadQueries();

            final List< Eval > evals = new ArrayList<>();
            for ( final Query q : queries ) {
                final Collection< SearchResult > hits = mgr.findPages( q.query(), ctx );
                final List< SearchResult > ordered = hits == null
                        ? new ArrayList<>() : new ArrayList<>( hits );
                int rank = -1;
                for ( int i = 0; i < ordered.size(); i++ ) {
                    if ( q.idealPage().equals( ordered.get( i ).getPage().getName() ) ) {
                        rank = i + 1;
                        break;
                    }
                }
                evals.add( new Eval( q, rank, ordered.size() ) );
            }

            final String report = formatReport( evals );
            System.out.println( report );
            final Path out = Path.of( "target/retrieval-eval-report.txt" );
            Files.createDirectories( out.getParent() );
            Files.writeString( out, report );
        } finally {
            engine.stop();
        }
    }

    /** Copies every file (non-recursive; the corpus is flat) from src to dst. */
    private void copyCorpus( final Path src, final Path dst ) throws Exception {
        Files.createDirectories( dst );
        try ( var stream = Files.list( src ) ) {
            stream.filter( Files::isRegularFile ).forEach( p -> {
                try {
                    Files.copy( p, dst.resolve( p.getFileName() ),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING );
                } catch ( final Exception e ) {
                    throw new RuntimeException( e );
                }
            } );
        }
    }

    /**
     * Finds {@code docs/wikantik-pages/} by walking up from the current
     * working directory. Tolerates being launched from the repo root or
     * from the {@code wikantik-main} module directory.
     */
    private Path resolveCorpusDir() {
        Path cwd = Path.of( "" ).toAbsolutePath().normalize();
        final Path start = cwd;
        for ( int i = 0; i < 6 && cwd != null; i++ ) {
            final Path candidate = cwd.resolve( "docs/wikantik-pages" );
            if ( Files.isDirectory( candidate ) ) {
                return candidate;
            }
            cwd = cwd.getParent();
        }
        throw new IllegalStateException(
                "Could not locate docs/wikantik-pages searching up from "
              + start + "; retrieval eval requires the real corpus." );
    }

    private List< Query > loadQueries() throws Exception {
        final List< Query > out = new ArrayList<>();
        try ( InputStream in = getClass().getResourceAsStream( "/retrieval-eval/queries.csv" );
              BufferedReader br = new BufferedReader(
                      new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
            String line;
            boolean headerSeen = false;
            while ( ( line = br.readLine() ) != null ) {
                if ( line.isBlank() || line.startsWith( "#" ) ) continue;
                if ( !headerSeen ) { headerSeen = true; continue; }
                final List< String > cols = parseCsv( line );
                if ( cols.size() >= 2 ) {
                    out.add( new Query(
                            cols.get( 0 ).trim(),
                            cols.get( 1 ).trim(),
                            cols.size() > 2 ? cols.get( 2 ).trim() : "" ) );
                }
            }
        }
        return out;
    }

    /** Minimal RFC4180-ish CSV: supports quoted fields with embedded commas. */
    private List< String > parseCsv( final String line ) {
        final List< String > cols = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for ( int i = 0; i < line.length(); i++ ) {
            final char c = line.charAt( i );
            if ( c == '"' ) {
                inQuotes = !inQuotes;
            } else if ( c == ',' && !inQuotes ) {
                cols.add( cur.toString() );
                cur.setLength( 0 );
            } else {
                cur.append( c );
            }
        }
        cols.add( cur.toString() );
        return cols;
    }

    private String formatReport( final List< Eval > evals ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "Retrieval evaluation — Lucene BM25 baseline\n" );
        sb.append( "Date: " ).append( java.time.Instant.now() ).append( '\n' );
        sb.append( "Queries: " ).append( evals.size() ).append( "\n\n" );

        sb.append( String.format( "recall@5  = %.3f%n", recallAtK( evals, 5 ) ) );
        sb.append( String.format( "recall@20 = %.3f%n", recallAtK( evals, 20 ) ) );
        sb.append( String.format( "MRR       = %.3f%n%n", mrr( evals ) ) );

        // Per-category breakdown.
        final java.util.Map< String, List< Eval > > byCat = new java.util.TreeMap<>();
        for ( final Eval e : evals ) {
            byCat.computeIfAbsent(
                    e.q().notes().isEmpty() ? "(uncategorised)" : e.q().notes(),
                    k -> new ArrayList<>() ).add( e );
        }
        sb.append( "Per-category breakdown:\n" );
        sb.append( String.format( "  %-20s %5s %9s %9s %6s%n",
                "category", "n", "recall@5", "recall@20", "MRR" ) );
        for ( final var entry : byCat.entrySet() ) {
            sb.append( String.format( "  %-20s %5d %9.3f %9.3f %6.3f%n",
                    entry.getKey(),
                    entry.getValue().size(),
                    recallAtK( entry.getValue(), 5 ),
                    recallAtK( entry.getValue(), 20 ),
                    mrr( entry.getValue() ) ) );
        }
        sb.append( '\n' );

        sb.append( String.format( "%-5s  %-6s  %-8s  %-40s  %s%n",
                "rank", "@20?", "cat", "ideal_page", "query" ) );
        sb.append( "-----  ------  --------  ----------------------------------------  "
                 + "-----------------------------------------------------------\n" );
        for ( final Eval e : evals ) {
            final String rankStr = e.rank() > 0 ? String.valueOf( e.rank() ) : "—";
            final String inTop20 = e.rank() > 0 && e.rank() <= 20 ? "yes" : "no";
            sb.append( String.format( "%-5s  %-6s  %-8s  %-40s  %s%n",
                    rankStr, inTop20,
                    truncate( e.q().notes(), 8 ),
                    truncate( e.q().idealPage(), 40 ),
                    truncate( e.q().query(), 60 ) ) );
        }
        return sb.toString();
    }

    private double recallAtK( final List< Eval > evals, final int k ) {
        if ( evals.isEmpty() ) return 0.0;
        final long hits = evals.stream()
                .filter( e -> e.rank() > 0 && e.rank() <= k ).count();
        return ( double ) hits / evals.size();
    }

    private double mrr( final List< Eval > evals ) {
        if ( evals.isEmpty() ) return 0.0;
        final double sum = evals.stream()
                .mapToDouble( e -> e.rank() > 0 ? 1.0 / e.rank() : 0.0 ).sum();
        return sum / evals.size();
    }

    private String truncate( final String s, final int n ) {
        return s.length() <= n ? s : s.substring( 0, n - 1 ) + "…";
    }
}
