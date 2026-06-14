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
package com.wikantik.extractcli;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.knowledge.chunking.Chunk;
import com.wikantik.knowledge.chunking.ContentChunker;
import com.wikantik.knowledge.extraction.ChunkExtractionPrefilter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads every {@code .md} file under {@code --pages-dir}, parses frontmatter,
 * runs the chunker with the supplied {@code --chunker-*} overrides, and reports
 * the chunk-size distribution and the prefilter's effect on those re-chunked
 * chunks. No DB access, no extractor call. Pure in-memory exercise for tuning
 * chunker config before committing to a re-chunk + extraction round-trip.
 *
 * <p>Split out of {@link BootstrapExtractionCli} during the per-page pipeline
 * rewire (Phase 4 of {@code docs/superpowers/plans/2026-05-01-kg-extraction-redesign.md})
 * so the main extractor CLI stays focused on the production pipeline.</p>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — stats printed (or no {@code .md} files were found).</li>
 *   <li>{@code 1} — bad {@code --pages-dir} or filesystem error.</li>
 *   <li>{@code 2} — invalid CLI arguments.</li>
 * </ul>
 */
public final class ChunkerStatsCli {

    private static final Logger LOG = LogManager.getLogger( ChunkerStatsCli.class );

    private ChunkerStatsCli() {}

    public static void main( final String[] argv ) {
        final Args a;
        try {
            a = Args.parse( argv );
        } catch( final IllegalArgumentException e ) {
            System.err.println( "error: " + e.getMessage() );
            System.err.println();
            printUsage();
            System.exit( 2 );
            return;
        }
        if( a.showHelp ) {
            printUsage();
            return;
        }
        System.exit( run( a ) );
    }

    static int run( final Args a ) {
        final Path dir = Paths.get( a.pagesDir ).toAbsolutePath().normalize();
        if( !Files.isDirectory( dir ) ) {
            System.err.println( "error: --pages-dir does not exist or is not a directory: " + dir );
            return 1;
        }

        final ContentChunker chunker = new ContentChunker(
            new ContentChunker.Config( a.chunkerMaxTokens, a.chunkerMergeForwardTokens,
                                       a.chunkerFragmentFloorTokens ) );
        final ChunkExtractionPrefilter prefilter = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ true, /*minTokens*/ 20 );

        LOG.info( "Chunker-stats: scanning {} for *.md (max_tokens={}, merge_forward_tokens={})",
            dir, a.chunkerMaxTokens, a.chunkerMergeForwardTokens );

        final long started = System.nanoTime();
        final List< Integer > sizes = new ArrayList<>();
        final Map< String, Integer > prefilterReasons = new TreeMap<>();
        int pages = 0;
        int prefilterKept = 0;
        int prefilterSkipped = 0;
        try( Stream< Path > walk = Files.walk( dir ) ) {
            for( final Path p : (Iterable< Path >) walk::iterator ) {
                if( !Files.isRegularFile( p ) ) continue;
                final String name = p.getFileName().toString();
                if( !name.endsWith( ".md" ) ) continue;
                final String text;
                try {
                    text = Files.readString( p );
                } catch( final IOException ioe ) {
                    LOG.warn( "Skipping {}: read failed: {}", p, ioe.getMessage() );
                    continue;
                }
                final ParsedPage parsed = FrontmatterParser.parse( text );
                final String pageName = name.substring( 0, name.length() - 3 );
                final List< Chunk > chunks = chunker.chunk( pageName, parsed );
                pages++;
                for( final Chunk c : chunks ) {
                    sizes.add( c.tokenCountEstimate() );
                    final ChunkExtractionPrefilter.Decision d = prefilter.evaluate( c.text(), c.headingPath() );
                    if( d.shouldExtract() ) {
                        prefilterKept++;
                    } else {
                        prefilterSkipped++;
                        prefilterReasons.merge( d.reason(), 1, Integer::sum );
                    }
                }
            }
        } catch( final IOException ioe ) {
            System.err.println( "error: walking pages dir failed: " + ioe.getMessage() );
            return 1;
        }

        final long elapsedMs = ( System.nanoTime() - started ) / 1_000_000L;
        if( sizes.isEmpty() ) {
            LOG.warn( "Chunker-stats: no .md files found under {}", dir );
            return 0;
        }
        Collections.sort( sizes );
        final int total = sizes.size();
        final int min = sizes.get( 0 );
        final int max = sizes.get( total - 1 );
        final double mean = sizes.stream().mapToInt( Integer::intValue ).average().orElse( 0 );
        final int p50 = sizes.get( total / 2 );
        final int p90 = sizes.get( Math.min( total - 1, (int)( total * 0.90 ) ) );
        final int p99 = sizes.get( Math.min( total - 1, (int)( total * 0.99 ) ) );

        LOG.info( "Chunker-stats: pages={} chunks={} elapsedMs={}", pages, total, elapsedMs );
        LOG.info( "Tokens per chunk: min={} mean={} p50={} p90={} p99={} max={}",
            min, String.format( Locale.ROOT, "%.0f", mean ), p50, p90, p99, max );

        final int[] cuts   = { 50, 150, 300, 500, 1000, Integer.MAX_VALUE };
        final String[] lbl = { "  0-50 ", " 51-150", "151-300", "301-500", "501-1k ", "1001+  " };
        final int[] counts = new int[ cuts.length ];
        for( final int s : sizes ) {
            for( int i = 0; i < cuts.length; i++ ) {
                if( s <= cuts[ i ] ) { counts[ i ]++; break; }
            }
        }
        LOG.info( "Distribution:" );
        for( int i = 0; i < counts.length; i++ ) {
            final double pct = 100.0 * counts[ i ] / total;
            LOG.info( "  {} : {} ({}%)", lbl[ i ], counts[ i ], String.format( Locale.ROOT, "%.1f", pct ) );
        }

        final double skipPct = 100.0 * prefilterSkipped / total;
        LOG.info( "Prefilter on these chunks: kept={} skipped={} ({}%)",
            prefilterKept, prefilterSkipped, String.format( Locale.ROOT, "%.1f", skipPct ) );
        for( final Map.Entry< String, Integer > e : prefilterReasons.entrySet() ) {
            final double rPct = 100.0 * e.getValue() / total;
            LOG.info( "  reason={} count={} ({}%)", e.getKey(), e.getValue(),
                String.format( Locale.ROOT, "%.1f", rPct ) );
        }
        return 0;
    }

    private static void printUsage() {
        final String usage = """
            wikantik-chunker-stats — re-chunk a markdown corpus in memory and report stats.

            Usage:
              java -cp wikantik-extract-cli.jar com.wikantik.extractcli.ChunkerStatsCli [options]

            Options:
              --pages-dir <path>                   markdown source root (default docs/wikantik-pages)
              --chunker-max-tokens <N>             chunker hard ceiling (default 512)
              --chunker-merge-forward-tokens <N>   chunker floor below which chunks merge into the next
                                                   section (default 150). Must be <= --chunker-max-tokens.
              --chunker-fragment-floor-tokens <N>  cross-section fragment floor (default 24): a section
                                                   below this merges forward and adopts the next heading.
                                                   Must be <= --chunker-merge-forward-tokens.
              -h, --help                           show this message

            No DB access, no LLM. Reads pages from disk and re-chunks in memory.
            Exit codes: 0 = stats printed, 1 = bad pages-dir, 2 = bad arguments.
            """;
        System.out.println( usage );
    }

    /** Minimal argument bag — package-private so tests can drive {@link #parse(String[])} directly. */
    static final class Args {
        String  pagesDir                  = "docs/wikantik-pages";
        int     chunkerMaxTokens          = 512;
        int     chunkerMergeForwardTokens = 150;
        int     chunkerFragmentFloorTokens = 24;
        boolean showHelp                  = false;

        static Args parse( final String[] argv ) {
            final Args a = new Args();
            for( int i = 0; i < argv.length; i++ ) {
                final String k = argv[ i ];
                switch( k ) {
                    case "-h", "--help"                   -> a.showHelp = true;
                    case "--pages-dir"                    -> a.pagesDir = req( argv, ++i, k );
                    case "--chunker-max-tokens"           -> a.chunkerMaxTokens = parseInt( req( argv, ++i, k ), k );
                    case "--chunker-merge-forward-tokens" -> a.chunkerMergeForwardTokens = parseInt( req( argv, ++i, k ), k );
                    case "--chunker-fragment-floor-tokens" -> a.chunkerFragmentFloorTokens = parseInt( req( argv, ++i, k ), k );
                    default -> throw new IllegalArgumentException( "unknown argument: " + k );
                }
            }
            if( !a.showHelp ) {
                if( a.pagesDir.isBlank() ) throw new IllegalArgumentException( "--pages-dir must not be blank" );
                if( a.chunkerMaxTokens < 1 ) throw new IllegalArgumentException( "--chunker-max-tokens must be >= 1" );
                if( a.chunkerMergeForwardTokens < 0 ) {
                    throw new IllegalArgumentException( "--chunker-merge-forward-tokens must be >= 0" );
                }
                if( a.chunkerMergeForwardTokens > a.chunkerMaxTokens ) {
                    throw new IllegalArgumentException(
                        "--chunker-merge-forward-tokens must be <= --chunker-max-tokens "
                      + "(floor cannot exceed ceiling)" );
                }
                if( a.chunkerFragmentFloorTokens < 0 ) {
                    throw new IllegalArgumentException( "--chunker-fragment-floor-tokens must be >= 0" );
                }
                if( a.chunkerFragmentFloorTokens > a.chunkerMergeForwardTokens ) {
                    throw new IllegalArgumentException(
                        "--chunker-fragment-floor-tokens must be <= --chunker-merge-forward-tokens" );
                }
            }
            return a;
        }

        private static String req( final String[] argv, final int i, final String flag ) {
            if( i >= argv.length ) throw new IllegalArgumentException( flag + " requires a value" );
            return argv[ i ];
        }

        private static int parseInt( final String s, final String flag ) {
            try { return Integer.parseInt( s ); }
            catch( final NumberFormatException e ) {
                throw new IllegalArgumentException( flag + " expects an integer, got: " + s );
            }
        }
    }
}
