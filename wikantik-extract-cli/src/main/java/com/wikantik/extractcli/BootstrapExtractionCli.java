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

import com.wikantik.api.knowledge.EntityExtractor;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.extraction.AsyncEntityExtractionListener;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.extraction.ChunkEntityMentionRepository;
import com.wikantik.knowledge.extraction.ChunkExtractionPrefilter;
import com.wikantik.knowledge.extraction.EntityExtractorConfig;
import com.wikantik.knowledge.extraction.EntityExtractorFactory;

import io.micrometer.core.instrument.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Standalone command-line driver for the save-time entity-extraction pipeline.
 * Wires just enough of {@code wikantik-main} to run
 * {@link BootstrapEntityExtractionIndexer} against an existing database,
 * without booting a servlet container. Intended for multi-hour batch runs that
 * should survive Tomcat restarts / redeploys during local development.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * java -jar wikantik-extract-cli.jar \
 *      --jdbc-url jdbc:postgresql://localhost/jspwiki \
 *      --jdbc-user jspwiki \
 *      --jdbc-password-env PG_PASSWORD \
 *      --ollama-model gemma4-assist:latest \
 *      --concurrency 2 \
 *      --force
 * }</pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — run reached {@code COMPLETED} state.</li>
 *   <li>{@code 1} — run reached {@code ERROR} state or the indexer
 *       failed to start.</li>
 *   <li>{@code 2} — invalid CLI arguments.</li>
 * </ul>
 */
public final class BootstrapExtractionCli {

    private static final Logger LOG = LogManager.getLogger( BootstrapExtractionCli.class );

    private BootstrapExtractionCli() {}

    public static void main( final String[] args ) {
        final Args parsed;
        try {
            parsed = Args.parse( args );
        } catch( final IllegalArgumentException e ) {
            System.err.println( "error: " + e.getMessage() );
            System.err.println();
            printUsage();
            System.exit( 2 );
            return;
        }
        if( parsed.showHelp ) {
            printUsage();
            return;
        }
        final int exit = run( parsed );
        System.exit( exit );
    }

    private static int run( final Args a ) {
        final DataSource ds = dataSource( a );
        final ContentChunkRepository chunkRepo = new ContentChunkRepository( ds );
        final ChunkEntityMentionRepository mentionRepo = new ChunkEntityMentionRepository( ds );
        final JdbcKnowledgeRepository kgRepo = new JdbcKnowledgeRepository( ds );

        final EntityExtractorConfig cfg = a.toExtractorConfig();

        // --stats-only short-circuits before we touch any extractor: walk every
        // chunk, evaluate the prefilter, print reason-by-reason counts, exit.
        // No LLM calls, no persistence — useful for sizing a run before
        // committing GPU hours to it.
        if( a.statsOnly ) {
            return runStatsOnly( cfg, chunkRepo );
        }

        final Optional< EntityExtractor > ext = EntityExtractorFactory.create( cfg );
        if( ext.isEmpty() ) {
            System.err.println( "error: extractor backend '" + cfg.backend()
                + "' is not available — missing credentials or unsupported value." );
            return 1;
        }

        final AsyncEntityExtractionListener listener = new AsyncEntityExtractionListener(
            ext.get(), cfg, chunkRepo, mentionRepo, kgRepo, Metrics.globalRegistry );
        final ChunkExtractionPrefilter prefilter = new ChunkExtractionPrefilter(
            cfg.prefilterEnabled(),
            cfg.prefilterDryRun(),
            cfg.prefilterSkipPureCode(),
            cfg.prefilterSkipNoProperNoun(),
            cfg.prefilterSkipTooShort(),
            cfg.prefilterMinTokens() );
        try( BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
                 listener, chunkRepo, mentionRepo, cfg.concurrency(), prefilter ) ) {
            LOG.info( "Extract-CLI starting (backend={}, model={}, concurrency={}, force={}, prefilter={}{})",
                cfg.backend(), modelLabel( cfg ), cfg.concurrency(), a.force,
                cfg.prefilterEnabled(), cfg.prefilterDryRun() ? " dry-run" : "" );
            // Ctrl-C / SIGTERM → request graceful cancel. The indexer finishes
            // any in-flight chunks (the Ollama RPC is blocking) but does not
            // submit new ones. The JVM shutdown sequence then waits on the
            // executor via the indexer's close() inside try-with-resources.
            Runtime.getRuntime().addShutdownHook( new Thread( () -> {
                if( indexer.isRunning() ) {
                    System.out.println();
                    LOG.warn( "Extract-CLI received shutdown signal — cancelling between chunks." );
                    indexer.cancel();
                }
            }, "extract-cli-shutdown" ) );
            final boolean started = indexer.start( a.force );
            if( !started ) {
                System.err.println( "error: indexer refused to start — another run is already in flight." );
                return 1;
            }
            return waitAndReport( indexer, a.pollSeconds );
        } finally {
            try( listener ) { /* try-with-resources drains the async executor */ }
        }
    }

    /**
     * Walks every chunk in the corpus, evaluates the configured prefilter
     * against each, prints reason-by-reason counts, and exits. No extractor
     * call, no persistence, no LLM. Lets an operator size a filter run before
     * committing GPU hours to it.
     *
     * <p>The prefilter is built with {@code skipTooShort} forced on (regardless
     * of {@code --no-prefilter-skip-short}) so the {@code too_short} reason
     * still appears in the report — the operator can see what each rule would
     * catch independently. Dry-run is forced off here because we never persist
     * anything anyway.
     */
    private static int runStatsOnly( final EntityExtractorConfig cfg,
                                     final ContentChunkRepository chunkRepo ) {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            cfg.prefilterSkipPureCode(),
            cfg.prefilterSkipNoProperNoun(),
            cfg.prefilterSkipTooShort(),
            cfg.prefilterMinTokens() );

        final long started = System.nanoTime();
        final java.util.List< String > pages = chunkRepo.listDistinctPageNames();
        LOG.info( "Stats-only: walking {} pages…", pages.size() );

        int total = 0;
        int kept = 0;
        final java.util.Map< String, Integer > reasons = new java.util.TreeMap<>();

        for( final String page : pages ) {
            final java.util.List< java.util.UUID > ids = chunkRepo.listChunkIdsForPage( page );
            if( ids.isEmpty() ) continue;
            for( final ContentChunkRepository.MentionableChunk c : chunkRepo.findByIds( ids ) ) {
                total++;
                final ChunkExtractionPrefilter.Decision d = f.evaluate( c.text(), c.headingPath() );
                if( d.shouldExtract() ) {
                    kept++;
                } else {
                    reasons.merge( d.reason(), 1, Integer::sum );
                }
            }
        }

        final long elapsedMs = ( System.nanoTime() - started ) / 1_000_000L;
        final int skipped = total - kept;
        final double pct = total > 0 ? ( 100.0 * skipped / total ) : 0.0;
        LOG.info( "Stats-only: total={} kept={} skipped={} ({}%) in {}ms",
            total, kept, skipped, String.format( Locale.ROOT, "%.1f", pct ), elapsedMs );
        for( final java.util.Map.Entry< String, Integer > e : reasons.entrySet() ) {
            final double rPct = total > 0 ? ( 100.0 * e.getValue() / total ) : 0.0;
            LOG.info( "  reason={} count={} ({}%)", e.getKey(), e.getValue(),
                String.format( Locale.ROOT, "%.1f", rPct ) );
        }
        if( !cfg.prefilterEnabled() ) {
            LOG.info( "(Master prefilter switch is OFF in config; numbers above show "
                    + "what would happen if --prefilter were passed at run time.)" );
        }
        return 0;
    }

    /**
     * Polls the indexer at {@code pollSeconds} cadence, logs an aggregate
     * progress line at INFO (goes to stdout via the CLI's log4j2.xml), and
     * returns the exit code when the state leaves RUNNING.
     */
    private static int waitAndReport( final BootstrapEntityExtractionIndexer indexer,
                                      final int pollSeconds ) {
        while( indexer.isRunning() ) {
            try {
                TimeUnit.SECONDS.sleep( pollSeconds );
            } catch( final InterruptedException ie ) {
                Thread.currentThread().interrupt();
                LOG.warn( "Extract-CLI interrupted — waiting for current page to finish so the " +
                         "partial run is loggable; re-run with the same flags to resume." );
            }
            LOG.info( "Extract-CLI progress: {}", formatProgress( indexer.status() ) );
        }
        final BootstrapEntityExtractionIndexer.Status finalStatus = indexer.status();
        LOG.info( "Extract-CLI final: {}", formatProgress( finalStatus ) );
        return finalStatus.state() == BootstrapEntityExtractionIndexer.State.COMPLETED ? 0 : 1;
    }

    /**
     * Renders the indexer status as a single line that includes both page and
     * chunk progress, plus throughput counters — detailed enough to answer
     * "how many of what total" at a glance without opening the JSON.
     */
    private static String formatProgress( final BootstrapEntityExtractionIndexer.Status s ) {
        final int totalChunks    = s.totalChunks();
        final int extractedChunks = s.processedChunks(); // includes failed; skipped are disjoint
        final int doneChunks     = extractedChunks + s.skippedChunks();
        final double pct = totalChunks > 0
            ? ( 100.0 * doneChunks / totalChunks )
            : 0.0;
        final long elapsedSec = s.elapsedMs() / 1000L;
        final long perChunkMs = extractedChunks > 0 ? s.elapsedMs() / extractedChunks : 0L;
        final String skipSuffix = s.skippedChunks() > 0
            ? String.format( Locale.ROOT, " skipped=%d %s", s.skippedChunks(), s.skipReasons() )
            : "";
        return String.format( Locale.ROOT,
            "state=%s pages=%d/%d chunks=%d/%d (%.1f%%) failedChunks=%d "
          + "mentions=%d proposals=%d elapsed=%ds perChunkMs=%d%s",
            s.state(), s.processedPages(), s.totalPages(),
            doneChunks, totalChunks, pct, s.failedChunks(),
            s.mentionsWritten(), s.proposalsFiled(), elapsedSec, perChunkMs, skipSuffix );
    }

    private static String modelLabel( final EntityExtractorConfig cfg ) {
        return EntityExtractorConfig.BACKEND_CLAUDE.equalsIgnoreCase( cfg.backend() )
            ? cfg.claudeModel()
            : cfg.ollamaModel();
    }

    private static DataSource dataSource( final Args a ) {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( a.jdbcUrl );
        ds.setUser( a.jdbcUser );
        ds.setPassword( a.jdbcPassword );
        return ds;
    }

    private static void printUsage() {
        final String usage = """
            wikantik-extract-cli — standalone driver for the entity-extraction batch.

            Usage:
              java -jar wikantik-extract-cli.jar [options]

            Database:
              --jdbc-url <url>               (default jdbc:postgresql://localhost:5432/jspwiki)
              --jdbc-user <name>             (default jspwiki)
              --jdbc-password <value>        password literal (not recommended)
              --jdbc-password-env <VAR>      read password from env var (preferred)

            Extractor backend:
              --backend <ollama|claude|disabled>   (default ollama)
              --ollama-url <url>                   (default http://inference.jakefear.com:11434)
              --ollama-model <tag>                 (default gemma4-assist:latest)
              --claude-model <id>                  (default claude-haiku-4-5)
              --anthropic-key-env <VAR>            (Claude only)

            Run tuning:
              --concurrency <1..10>                (default 2; silently clamped)
              --confidence-threshold <0.0..1.0>    (default 0.6)
              --max-existing-nodes <N>             (default 200)
              --timeout-ms <ms>                    (default 120000)
              --force                              replace existing mentions per chunk before extracting
              --poll-seconds <N>                   progress-log cadence (default 30)
              -h, --help                           show this message

            Pre-filter (optional, opt-in):
              --prefilter                        enable chunk prefilter (skip pure code + no-proper-noun + too-short chunks)
              --prefilter-dry-run                enable filter but log decisions only — no chunks are skipped
              --no-prefilter-skip-code           keep the prefilter on but disable the pure-code rule
              --no-prefilter-skip-nopn           keep the prefilter on but disable the no-proper-noun rule
              --no-prefilter-skip-short          keep the prefilter on but disable the too-short rule
              --prefilter-min-tokens <N>         token threshold for too-short rule (default 20; chars/4 estimate)
              --stats-only                       walk every chunk, evaluate the prefilter, print reason
                                                 counts, and exit. No extractor calls — useful for sizing
                                                 a run before committing GPU hours to it.

            Exit codes: 0 = completed, 1 = errored / refused to start, 2 = bad arguments.
            """;
        System.out.println( usage );
    }

    // ---- args ----

    static final class Args {
        String jdbcUrl       = "jdbc:postgresql://localhost:5432/jspwiki";
        String jdbcUser      = "jspwiki";
        String jdbcPassword  = "";
        String backend       = EntityExtractorConfig.BACKEND_OLLAMA;
        String ollamaUrl     = "http://inference.jakefear.com:11434";
        String ollamaModel   = "gemma4-assist:latest";
        String claudeModel   = "claude-haiku-4-5";
        int    concurrency   = 2;
        double confThreshold = 0.6;
        int    maxNodes      = 200;
        long   timeoutMs     = 120_000L;
        int    pollSeconds   = 30;
        boolean force                 = false;
        boolean showHelp              = false;
        boolean prefilterEnabled      = false;
        boolean prefilterDryRun       = false;
        boolean prefilterSkipCode     = true;
        boolean prefilterSkipNoProper = true;
        boolean prefilterSkipShort    = true;
        int     prefilterMinTokens    = 20;
        boolean statsOnly             = false;

        static Args parse( final String[] argv ) {
            final Args a = new Args();
            for( int i = 0; i < argv.length; i++ ) {
                final String k = argv[ i ];
                switch( k ) {
                    case "-h", "--help"              -> a.showHelp = true;
                    case "--force"                   -> a.force = true;
                    case "--jdbc-url"                -> a.jdbcUrl = req( argv, ++i, k );
                    case "--jdbc-user"               -> a.jdbcUser = req( argv, ++i, k );
                    case "--jdbc-password"           -> a.jdbcPassword = req( argv, ++i, k );
                    case "--jdbc-password-env"       -> a.jdbcPassword = env( req( argv, ++i, k ) );
                    case "--backend"                 -> a.backend = req( argv, ++i, k );
                    case "--ollama-url"              -> a.ollamaUrl = req( argv, ++i, k );
                    case "--ollama-model"            -> a.ollamaModel = req( argv, ++i, k );
                    case "--claude-model"            -> a.claudeModel = req( argv, ++i, k );
                    case "--anthropic-key-env"       -> System.setProperty( "ANTHROPIC_API_KEY",
                                                          env( req( argv, ++i, k ) ) );
                    case "--concurrency"             -> a.concurrency = parseInt( req( argv, ++i, k ), k );
                    case "--confidence-threshold"    -> a.confThreshold = parseDouble( req( argv, ++i, k ), k );
                    case "--max-existing-nodes"      -> a.maxNodes = parseInt( req( argv, ++i, k ), k );
                    case "--timeout-ms"              -> a.timeoutMs = parseLong( req( argv, ++i, k ), k );
                    case "--poll-seconds"            -> a.pollSeconds = parseInt( req( argv, ++i, k ), k );
                    case "--prefilter"               -> a.prefilterEnabled = true;
                    case "--prefilter-dry-run"       -> { a.prefilterEnabled = true; a.prefilterDryRun = true; }
                    case "--no-prefilter-skip-code"  -> a.prefilterSkipCode = false;
                    case "--no-prefilter-skip-nopn"  -> a.prefilterSkipNoProper = false;
                    case "--no-prefilter-skip-short" -> a.prefilterSkipShort = false;
                    case "--prefilter-min-tokens"    -> a.prefilterMinTokens = parseInt( req( argv, ++i, k ), k );
                    case "--stats-only"              -> a.statsOnly = true;
                    default -> throw new IllegalArgumentException( "unknown argument: " + k );
                }
            }
            if( !a.showHelp ) {
                if( a.jdbcUrl.isBlank() ) throw new IllegalArgumentException( "--jdbc-url is required" );
                if( a.jdbcUser.isBlank() ) throw new IllegalArgumentException( "--jdbc-user is required" );
                if( a.pollSeconds < 1 ) throw new IllegalArgumentException( "--poll-seconds must be >= 1" );
                // --stats-only synthesises an enabled prefilter internally, so
                // sub-flags are meaningful there even without --prefilter.
                final boolean anySubFlagFlipped =
                    !a.prefilterSkipCode || !a.prefilterSkipNoProper || !a.prefilterSkipShort
                    || a.prefilterMinTokens != 20;
                if( anySubFlagFlipped && !a.prefilterEnabled && !a.statsOnly ) {
                    throw new IllegalArgumentException(
                        "--no-prefilter-skip-* / --prefilter-min-tokens have no effect "
                      + "without --prefilter (or --prefilter-dry-run or --stats-only); "
                      + "pass one of those too." );
                }
                if( a.prefilterMinTokens < 0 ) {
                    throw new IllegalArgumentException( "--prefilter-min-tokens must be >= 0" );
                }
            }
            return a;
        }

        EntityExtractorConfig toExtractorConfig() {
            // Route through EntityExtractorConfig.fromProperties so CLI defaults
            // pick up every validation / clamp the save-time path already applies.
            final Properties p = new Properties();
            p.setProperty( EntityExtractorConfig.PREFIX + "backend", backend );
            p.setProperty( EntityExtractorConfig.PREFIX + "claude.model", claudeModel );
            p.setProperty( EntityExtractorConfig.PREFIX + "ollama.model", ollamaModel );
            p.setProperty( EntityExtractorConfig.PREFIX + "ollama.base_url", ollamaUrl );
            p.setProperty( EntityExtractorConfig.PREFIX + "timeout_ms", Long.toString( timeoutMs ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "confidence_threshold", Double.toString( confThreshold ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "max_existing_nodes", Integer.toString( maxNodes ) );
            // Per-page rate limit is irrelevant in batch mode — we drive chunks directly, not via save events.
            p.setProperty( EntityExtractorConfig.PREFIX + "per_page_min_interval_ms", "0" );
            p.setProperty( EntityExtractorConfig.PREFIX + "concurrency", Integer.toString( concurrency ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.enabled", Boolean.toString( prefilterEnabled ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.dry_run", Boolean.toString( prefilterDryRun ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.skip_pure_code", Boolean.toString( prefilterSkipCode ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.skip_no_proper_noun", Boolean.toString( prefilterSkipNoProper ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.skip_too_short", Boolean.toString( prefilterSkipShort ) );
            p.setProperty( EntityExtractorConfig.PREFIX + "prefilter.min_tokens", Integer.toString( prefilterMinTokens ) );
            return EntityExtractorConfig.fromProperties( p );
        }

        private static String req( final String[] argv, final int i, final String flag ) {
            if( i >= argv.length ) throw new IllegalArgumentException( flag + " requires a value" );
            return argv[ i ];
        }

        private static String env( final String name ) {
            final String v = System.getenv( name );
            if( v == null || v.isEmpty() ) {
                throw new IllegalArgumentException( "environment variable '" + name + "' is unset or empty" );
            }
            return v;
        }

        private static int parseInt( final String s, final String flag ) {
            try { return Integer.parseInt( s ); }
            catch( final NumberFormatException e ) {
                throw new IllegalArgumentException( flag + " expects an integer, got: " + s );
            }
        }

        private static long parseLong( final String s, final String flag ) {
            try { return Long.parseLong( s ); }
            catch( final NumberFormatException e ) {
                throw new IllegalArgumentException( flag + " expects a long integer, got: " + s );
            }
        }

        private static double parseDouble( final String s, final String flag ) {
            try { return Double.parseDouble( s ); }
            catch( final NumberFormatException e ) {
                throw new IllegalArgumentException( flag + " expects a number, got: " + s );
            }
        }
    }
}
