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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.wikantik.api.knowledge.PageExtractor;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingRepository;
import com.wikantik.knowledge.embedding.KgNodeEmbeddingService;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.extraction.ChunkEntityMentionRepository;
import com.wikantik.knowledge.extraction.EntityExtractorConfig;
import com.wikantik.knowledge.extraction.EvidenceGroundingVerifier;
import com.wikantik.knowledge.extraction.MentionAttributor;
import com.wikantik.knowledge.extraction.NoOpProposalJudge;
import com.wikantik.knowledge.extraction.OllamaPageExtractor;
import com.wikantik.knowledge.extraction.OllamaProposalJudge;
import com.wikantik.knowledge.extraction.PageEmbeddingProvider;
import com.wikantik.knowledge.extraction.PageExtractionResponseParser;
import com.wikantik.knowledge.extraction.ProposalConsolidator;
import com.wikantik.knowledge.extraction.ProposalUpserter;
import com.wikantik.search.embedding.EmbeddingClient;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.EmbeddingKind;
import com.wikantik.search.embedding.EmbeddingModel;
import com.wikantik.search.embedding.OllamaEmbeddingClient;
import com.wikantik.search.embedding.TextEmbeddingClient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * Standalone command-line driver for the per-page entity-extraction pipeline.
 * Wires {@link BootstrapEntityExtractionIndexer} (and the Phase 2
 * collaborators it depends on) against an existing Postgres database without
 * booting a servlet container, so multi-hour batch runs survive Tomcat
 * redeploys during local development.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * java -jar wikantik-extract-cli.jar \
 *      --jdbc-url jdbc:postgresql://localhost/jspwiki \
 *      --jdbc-user jspwiki \
 *      --jdbc-password-env PG_PASSWORD \
 *      --ollama-model gemma4-assist:latest \
 *      --concurrency 2 \
 *      --report reports/run.json
 * }</pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — run reached {@code COMPLETED} state.</li>
 *   <li>{@code 1} — run reached {@code ERROR}, the indexer refused to start,
 *       or {@code --judge claude} was requested without
 *       {@code -Dwikantik.kg.judge.allow_claude=true}.</li>
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
        final ProposalJudge judge;
        try {
            judge = buildJudge( a );
        } catch( final IllegalStateException e ) {
            System.err.println( "error: " + e.getMessage() );
            return 1;
        }

        final DataSource ds = dataSource( a );

        if( a.rebuildNodeEmbeddings ) {
            try {
                truncateNodeEmbeddings( ds );
                LOG.info( "Cleared kg_node_embeddings (--rebuild-node-embeddings)" );
            } catch( final RuntimeException e ) {
                System.err.println( "error: --rebuild-node-embeddings failed to truncate kg_node_embeddings: "
                                  + e.getMessage() );
                return 1;
            }
        }

        // Pipeline collaborators — see plan §4.1 step 2 for the construction recipe.
        final PageExtractionResponseParser parser = new PageExtractionResponseParser(
            new EvidenceGroundingVerifier(), a.maxEntitiesPerPage, a.maxRelationsPerPage );
        final HttpClient http = HttpClient.newHttpClient();
        final OllamaPageExtractor extractor = new OllamaPageExtractor(
            http, a.ollamaUrl, a.ollamaModel, a.timeoutMs, parser );

        final JdbcKnowledgeRepository kgRepo = new JdbcKnowledgeRepository( ds );
        final KgNodeEmbeddingRepository embRepo = new KgNodeEmbeddingRepository( ds );
        final KgNodeEmbeddingService embService = buildEmbeddingService( a, http, embRepo );

        // --page-pattern is implemented by wrapping ContentChunkRepository so
        // listDistinctPageNames() applies a glob filter — same seam the
        // PageExtractionPgTestBase uses to scope the IT to its own pages
        // without modifying the indexer.
        final ContentChunkRepository chunkRepo = a.pagePattern == null
            ? new ContentChunkRepository( ds )
            : pagePatternFiltered( ds, a.pagePattern );

        final ChunkEntityMentionRepository mentionRepo = new ChunkEntityMentionRepository( ds );
        final ProposalConsolidator consolidator = new ProposalConsolidator();
        final ProposalUpserter upserter = new ProposalUpserter( kgRepo );

        final BootstrapEntityExtractionIndexer indexer = new BootstrapEntityExtractionIndexer(
            extractor, judge, consolidator, upserter,
            embService, embRepo,
            chunkRepo, mentionRepo, kgRepo, new MentionAttributor(),
            PageEmbeddingProvider.EMPTY, /*excludedPages*/ null,
            a.concurrency, a.dictionaryTopK, a.maxEntitiesPerPage, a.maxRelationsPerPage );

        if( a.dryRun ) {
            indexer.setDryRun( true );
        }

        LOG.info( "Extract-CLI starting: model={}, judge={}, concurrency={}, maxPages={}, dryRun={}, report={}",
            a.ollamaModel, judge.code(), a.concurrency, a.maxPages, a.dryRun, a.report );

        final boolean started = indexer.start( /*forceOverwrite*/ false, a.maxPages );
        if( !started ) {
            System.err.println( "error: indexer refused to start (already running?)" );
            return 1;
        }

        final int exit = waitAndReport( indexer, a.pollSeconds );
        if( a.report != null ) {
            writeReport( a.report, indexer.status() );
        }
        return exit;
    }

    /** Throws {@link IllegalStateException} when an opt-in judge is requested but not yet implemented. */
    private static ProposalJudge buildJudge( final Args a ) {
        return switch( a.judge ) {
            case "none" -> new NoOpProposalJudge();
            case "ollama" -> new OllamaProposalJudge(
                HttpClient.newHttpClient(), a.ollamaUrl, a.judgeModel, a.timeoutMs );
            case "claude" -> {
                if( !Boolean.parseBoolean(
                    System.getProperty( "wikantik.kg.judge.allow_claude", "false" ) ) ) {
                    throw new IllegalStateException(
                        "--judge claude requires -Dwikantik.kg.judge.allow_claude=true (gated cost guard)." );
                }
                throw new IllegalStateException(
                    "--judge claude is not yet implemented (Phase 6 of the redesign). "
                  + "Re-run with --judge none, or wait for ClaudeProposalJudge to land." );
            }
            default -> throw new IllegalStateException(
                "unknown --judge value '" + a.judge + "' (expected: none|ollama|claude)" );
        };
    }

    private static KgNodeEmbeddingService buildEmbeddingService( final Args a,
                                                                 final HttpClient http,
                                                                 final KgNodeEmbeddingRepository repo ) {
        final String tag = a.nodeEmbeddingModel;
        final EmbeddingModel model = resolveEmbeddingModel( tag );
        final EmbeddingConfig cfg = new EmbeddingConfig(
            /*enabled*/ true,
            EmbeddingConfig.BACKEND_OLLAMA,
            a.ollamaUrl,
            /*apiKey*/ null,
            model,
            /*ollamaTagOverride*/ tag,
            /*timeoutMs*/ Math.max( 1, (int) Math.min( a.timeoutMs, Integer.MAX_VALUE ) ),
            EmbeddingConfig.DEFAULT_BATCH_SIZE );
        final TextEmbeddingClient batched = new OllamaEmbeddingClient( http, cfg );
        // Single-text adapter — KgNodeEmbeddingService never batches.
        final EmbeddingClient single = text ->
            batched.embed( List.of( text ), EmbeddingKind.DOCUMENT ).get( 0 );
        return new KgNodeEmbeddingService( repo, single, tag );
    }

    /**
     * Resolves an Ollama-style tag (e.g. {@code qwen3-embedding:0.6b}) or a
     * bare model code (e.g. {@code qwen3-embedding-0.6b}) back to the
     * matching {@link EmbeddingModel} entry, so that the model's prefix and
     * dimension metadata are applied even when the user passes a tag rather
     * than a code. Falls back to {@link EmbeddingModel#QWEN3_EMBEDDING_06B}
     * with a warning when nothing matches — node embeddings are best-effort
     * and we'd rather degrade than abort.
     */
    static EmbeddingModel resolveEmbeddingModel( final String tagOrCode ) {
        if( tagOrCode != null ) {
            for( final EmbeddingModel m : EmbeddingModel.values() ) {
                if( tagOrCode.equalsIgnoreCase( m.code() )
                 || tagOrCode.equalsIgnoreCase( m.defaultOllamaTag() ) ) {
                    return m;
                }
            }
        }
        LOG.warn( "Unknown --node-embedding-model '{}'; falling back to qwen3-embedding-0.6b "
                + "metadata (dimension/prefix). The tag '{}' is still passed through verbatim.",
            tagOrCode, tagOrCode );
        return EmbeddingModel.QWEN3_EMBEDDING_06B;
    }

    private static ContentChunkRepository pagePatternFiltered( final DataSource ds, final String glob ) {
        final Pattern regex = globToRegex( glob );
        return new ContentChunkRepository( ds ) {
            @Override
            public List< String > listDistinctPageNames() {
                return super.listDistinctPageNames().stream()
                    .filter( name -> regex.matcher( name ).matches() )
                    .toList();
            }
        };
    }

    /** Minimal glob → regex (supports {@code *} and {@code ?} only). */
    static Pattern globToRegex( final String glob ) {
        final StringBuilder sb = new StringBuilder( glob.length() + 8 ).append( '^' );
        for( int i = 0; i < glob.length(); i++ ) {
            final char c = glob.charAt( i );
            switch( c ) {
                case '*' -> sb.append( ".*" );
                case '?' -> sb.append( '.' );
                case '.', '(', ')', '[', ']', '{', '}', '+', '|', '^', '$', '\\' ->
                    sb.append( '\\' ).append( c );
                default -> sb.append( c );
            }
        }
        return Pattern.compile( sb.append( '$' ).toString() );
    }

    /** Wipe the KG-node embedding cache. {@code DELETE FROM} (not
     * {@code TRUNCATE}) because the {@code jspwiki} app role only has
     * SELECT/INSERT/UPDATE/DELETE — TRUNCATE requires owner privileges.
     * The cache is at most ~1k rows, so the row-level cost is negligible.
     * <p>The method name still reads "truncate" because that's the public
     * intent ({@code --rebuild-node-embeddings} = empty the cache); the
     * underlying SQL choice is an implementation detail. */
    private static void truncateNodeEmbeddings( final DataSource ds ) {
        try( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.executeUpdate( "DELETE FROM kg_node_embeddings" );
        } catch( final java.sql.SQLException e ) {
            throw new RuntimeException( "wipe kg_node_embeddings failed: " + e.getMessage(), e );
        }
    }

    static void writeReport( final String path, final BootstrapEntityExtractionIndexer.Status s ) {
        try {
            Files.writeString( Path.of( path ), reportJson( s ) );
            LOG.info( "Wrote run report → {}", path );
        } catch( final IOException ioe ) {
            LOG.warn( "Failed to write --report {}: {}", path, ioe.getMessage() );
        }
    }

    /** Serialize a {@link BootstrapEntityExtractionIndexer.Status} to JSON.
     * {@link Instant} fields render as ISO-8601 because Gson's default reflective
     * adapter cannot reach {@code java.time}'s private fields under JDK17+. */
    static String reportJson( final BootstrapEntityExtractionIndexer.Status s ) {
        final JsonSerializer< Instant > instantToIso =
            ( src, type, ctx ) -> src == null ? null : new JsonPrimitive( src.toString() );
        final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .registerTypeAdapter( Instant.class, instantToIso )
            .create();
        return gson.toJson( s );
    }

    /**
     * Polls the indexer at {@code pollSeconds} cadence, logs an aggregate
     * progress line at INFO, and returns the exit code when state leaves RUNNING.
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

    /** One-line progress digest covering the per-page pipeline counters. */
    private static String formatProgress( final BootstrapEntityExtractionIndexer.Status s ) {
        final int totalPages = s.totalPages();
        final int donePages  = s.processedPages();
        final double pct = totalPages > 0 ? ( 100.0 * donePages / totalPages ) : 0.0;
        final long elapsedSec = s.elapsedMs() / 1000L;
        final long perPageMs = donePages > 0 ? s.elapsedMs() / donePages : 0L;
        return String.format( Locale.ROOT,
            "state=%s pages=%d/%d (%.1f%%) failed=%d excluded=%d "
          + "consolidated=%d accepted=%d rejected=%d rewritten=%d "
          + "inserted=%d merged=%d mentions=%d elapsed=%ds perPageMs=%d rejectionReasons=%s",
            s.state(), donePages, totalPages, pct, s.failedPages(), s.excludedSkipped(),
            s.consolidatedCandidates(), s.judgeAccepted(), s.judgeRejected(), s.judgeRewritten(),
            s.proposalsInserted(), s.proposalsMerged(), s.mentionsWritten(),
            elapsedSec, perPageMs, s.rejectionReasons() );
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
            wikantik-extract-cli — standalone driver for the per-page entity-extraction pipeline.

            Usage:
              java -jar wikantik-extract-cli.jar [options]

            Database:
              --jdbc-url <url>               (default jdbc:postgresql://localhost:5432/jspwiki)
              --jdbc-user <name>             (default jspwiki)
              --jdbc-password <value>        password literal (not recommended)
              --jdbc-password-env <VAR>      read password from env var (preferred)

            Extractor:
              --ollama-url <url>             (default http://inference.jakefear.com:11434)
              --ollama-model <tag>           (default gemma4-assist:latest)
              --timeout-ms <ms>              per-page extractor timeout (default 120000)

            Judge (opt-in proposal review; see Phase 6 of the redesign plan):
              --judge <none|ollama|claude>   (default none — accept everything verbatim)
              --judge-model <tag/id>         model used by the judge (defaults vary per backend)
              --anthropic-key-env <VAR>      env var holding the Anthropic API key (claude only)
              -Dwikantik.kg.judge.allow_claude=true   required to actually use --judge claude

            Pipeline tuning:
              --concurrency <1..6>                  (default 2; clamped)
              --confidence-threshold <0.0..1.0>     judge cutoff (default 0.55)
              --dictionary-top-k <N>                top-K existing nodes injected per page (default 12, 0=off)
              --max-entities-per-page <N>           hard cap per LLM response (default 12)
              --max-relations-per-page <N>          hard cap per LLM response (default 8)
              --node-embedding-model <tag>          model for the kg_node_embeddings cache (default qwen3-embedding:0.6b)
              --rebuild-node-embeddings             TRUNCATE the embedding cache before warmup
              --max-pages <N>                       stop after first N pages, 0 = unlimited
              --page-pattern <glob>                 limit to page names matching glob (* and ? supported)
              --dry-run                             skip the kg_proposals upsert step (smoke runs)
              --report <path>                       on completion, write the final Status as JSON
              --poll-seconds <N>                    progress-log cadence (default 30)
              -h, --help                            show this message

            Exit codes: 0 = completed, 1 = errored / refused to start, 2 = bad arguments.
            """;
        System.out.println( usage );
    }

    // ---- args ----

    /**
     * CLI argument bag. Made {@code public} so {@link BootstrapExtractionCliArgsTest}
     * can drive {@link #parse(String[])} directly without reflection.
     */
    public static final class Args {
        public String jdbcUrl              = "jdbc:postgresql://localhost:5432/jspwiki";
        public String jdbcUser             = "jspwiki";
        public String jdbcPassword         = "";
        public String ollamaUrl            = "http://inference.jakefear.com:11434";
        public String ollamaModel          = "gemma4-assist:latest";
        public int    concurrency          = 2;
        public double confThreshold        = 0.55;
        public long   timeoutMs            = 120_000L;
        public int    pollSeconds          = 30;
        public boolean showHelp            = false;
        public int    maxPages             = 0;

        // ---- new per-page pipeline knobs ----
        public String judge                = "none";
        public String judgeModel           = "qwen3.5:9b";
        public String anthropicKeyEnv      = null;
        public int    maxEntitiesPerPage   = 12;
        public int    maxRelationsPerPage  = 8;
        public int    dictionaryTopK       = 12;
        public String nodeEmbeddingModel   = "qwen3-embedding:0.6b";
        public String pagePattern          = null;
        public boolean rebuildNodeEmbeddings = false;
        public boolean dryRun              = false;
        public String report               = null;

        /** Maximum allowed concurrency for the per-page pipeline (clamped on parse). */
        public static final int CLI_CONCURRENCY_MAX = 6;

        public static Args parse( final String[] argv ) {
            final Args a = new Args();
            for( int i = 0; i < argv.length; i++ ) {
                final String k = argv[ i ];
                switch( k ) {
                    case "-h", "--help"              -> a.showHelp = true;
                    case "--jdbc-url"                -> a.jdbcUrl = req( argv, ++i, k );
                    case "--jdbc-user"               -> a.jdbcUser = req( argv, ++i, k );
                    case "--jdbc-password"           -> a.jdbcPassword = req( argv, ++i, k );
                    case "--jdbc-password-env"       -> a.jdbcPassword = env( req( argv, ++i, k ) );
                    case "--ollama-url"              -> a.ollamaUrl = req( argv, ++i, k );
                    case "--ollama-model"            -> a.ollamaModel = req( argv, ++i, k );
                    case "--concurrency"             -> a.concurrency = parseInt( req( argv, ++i, k ), k );
                    case "--confidence-threshold"    -> a.confThreshold = parseDouble( req( argv, ++i, k ), k );
                    case "--timeout-ms"              -> a.timeoutMs = parseLong( req( argv, ++i, k ), k );
                    case "--poll-seconds"            -> a.pollSeconds = parseInt( req( argv, ++i, k ), k );
                    case "--max-pages"               -> a.maxPages = parseInt( req( argv, ++i, k ), k );

                    case "--judge"                   -> a.judge = req( argv, ++i, k ).toLowerCase( Locale.ROOT );
                    case "--judge-model"             -> a.judgeModel = req( argv, ++i, k );
                    case "--anthropic-key-env"       -> a.anthropicKeyEnv = req( argv, ++i, k );
                    case "--max-entities-per-page"   -> a.maxEntitiesPerPage = parseInt( req( argv, ++i, k ), k );
                    case "--max-relations-per-page"  -> a.maxRelationsPerPage = parseInt( req( argv, ++i, k ), k );
                    case "--dictionary-top-k"        -> a.dictionaryTopK = parseInt( req( argv, ++i, k ), k );
                    case "--node-embedding-model"    -> a.nodeEmbeddingModel = req( argv, ++i, k );
                    case "--page-pattern"            -> a.pagePattern = req( argv, ++i, k );
                    case "--rebuild-node-embeddings" -> a.rebuildNodeEmbeddings = true;
                    case "--dry-run"                 -> a.dryRun = true;
                    case "--report"                  -> a.report = req( argv, ++i, k );

                    default -> throw new IllegalArgumentException( "unknown argument: " + k );
                }
            }
            if( !a.showHelp ) {
                if( a.jdbcUrl.isBlank() ) throw new IllegalArgumentException( "--jdbc-url is required" );
                if( a.jdbcUser.isBlank() ) throw new IllegalArgumentException( "--jdbc-user is required" );
                if( a.pollSeconds < 1 ) throw new IllegalArgumentException( "--poll-seconds must be >= 1" );
                if( a.maxPages < 0 ) throw new IllegalArgumentException( "--max-pages must be >= 0 (0 = unlimited)" );
                if( a.maxEntitiesPerPage < 1 ) throw new IllegalArgumentException( "--max-entities-per-page must be >= 1" );
                if( a.maxRelationsPerPage < 0 ) throw new IllegalArgumentException( "--max-relations-per-page must be >= 0" );
                if( a.dictionaryTopK < 0 ) throw new IllegalArgumentException( "--dictionary-top-k must be >= 0 (0 = off)" );
                if( a.confThreshold < 0.0 || a.confThreshold > 1.0 ) {
                    throw new IllegalArgumentException( "--confidence-threshold must be in [0.0, 1.0]" );
                }
                if( !a.judge.equals( "none" ) && !a.judge.equals( "ollama" ) && !a.judge.equals( "claude" ) ) {
                    throw new IllegalArgumentException( "--judge must be one of: none, ollama, claude" );
                }
                a.concurrency = clamp( a.concurrency, 1, CLI_CONCURRENCY_MAX );
                // The indexer's own clamp is wider (1..CONCURRENCY_MAX=10); ours is
                // tighter so the CLI can't accidentally over-saturate the inference
                // backend even if a typo gets past the operator.
            }
            return a;
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

        private static int clamp( final int v, final int lo, final int hi ) {
            return Math.max( lo, Math.min( hi, v ) );
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
