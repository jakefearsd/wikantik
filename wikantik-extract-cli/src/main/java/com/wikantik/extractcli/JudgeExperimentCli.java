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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ConsolidatedProposal;
import com.wikantik.api.knowledge.JudgeContext;
import com.wikantik.api.knowledge.ProposalJudge;
import com.wikantik.api.knowledge.SupportEvidence;
import com.wikantik.api.knowledge.Verdict;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.knowledge.extraction.ClaudeProposalJudge;
import com.wikantik.knowledge.extraction.NoOpProposalJudge;
import com.wikantik.knowledge.extraction.OllamaProposalJudge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a random sample of pending {@code kg_proposals} rows, runs each
 * through a {@link NoOpProposalJudge} (the production default) plus a
 * comparator judge ({@link OllamaProposalJudge} or
 * {@link ClaudeProposalJudge}), and writes a side-by-side JSON report so the
 * operator can decide whether enabling the comparator in production runs is
 * worth the latency / cost.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * java -cp wikantik-extract-cli.jar \
 *      com.wikantik.extractcli.JudgeExperimentCli \
 *      --jdbc-url jdbc:postgresql://localhost/jspwiki \
 *      --jdbc-user jspwiki --jdbc-password-env PG_PASSWORD \
 *      --judge ollama --judge-model qwen3.5:9b \
 *      --sample 50 --output reports/judge-experiment.json
 * }</pre>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — sample processed and report written.</li>
 *   <li>{@code 1} — DB / judge construction error.</li>
 *   <li>{@code 2} — invalid CLI arguments.</li>
 * </ul>
 */
public final class JudgeExperimentCli {

    private static final Logger LOG = LogManager.getLogger( JudgeExperimentCli.class );

    private JudgeExperimentCli() {}

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
        System.exit( run( parsed ) );
    }

    static int run( final Args a ) {
        final ProposalJudge comparator;
        try {
            comparator = buildComparator( a );
        } catch( final IllegalStateException e ) {
            System.err.println( "error: " + e.getMessage() );
            return 1;
        }

        final DataSource ds = dataSource( a );
        final ContentChunkRepository chunkRepo = new ContentChunkRepository( ds );

        final List< Sampled > sample;
        try {
            sample = sampleProposals( ds, a.sample );
        } catch( final RuntimeException e ) {
            System.err.println( "error: failed to sample proposals: " + e.getMessage() );
            return 1;
        }
        LOG.info( "Sampled {} pending proposal(s) for judge comparison ({} requested).",
            sample.size(), a.sample );

        final ProposalJudge noop = new NoOpProposalJudge();
        final Map< String, String > pageBodyCache = new HashMap<>();

        final JudgeStats noopStats = new JudgeStats( noop.code() );
        final JudgeStats compStats = new JudgeStats( comparator.code() );
        final List< ExampleRow > examples = new ArrayList<>();

        for( final Sampled s : sample ) {
            final JudgeContext ctx = buildContext( s.proposal, chunkRepo, pageBodyCache );
            final Verdict noopVerdict = noop.judge( s.proposal, ctx );
            final Verdict compVerdict = comparator.judge( s.proposal, ctx );
            noopStats.record( noopVerdict );
            compStats.record( compVerdict );
            examples.add( new ExampleRow(
                s.proposal.signature(),
                s.proposal.kind().name(),
                s.proposal.displayName(),
                s.proposal.type(),
                s.proposal.source(),
                s.proposal.target(),
                s.proposal.predicate(),
                s.proposal.aggregateConfidence(),
                s.proposal.support().size(),
                summarizeVerdict( noopVerdict ),
                summarizeVerdict( compVerdict ) ) );
        }

        final Report report = new Report(
            Instant.now().toString(),
            a.judge,
            comparator.code(),
            sample.size(),
            noopStats.toMap(),
            compStats.toMap(),
            examples );
        try {
            writeReport( a.output, report );
        } catch( final IOException ioe ) {
            LOG.warn( "Failed to write --output {}: {}", a.output, ioe.getMessage() );
            return 1;
        }
        LOG.info( "Wrote judge-experiment report → {} (noop={}, {}={})",
            a.output, noopStats.summary(), compStats.judgeCode, compStats.summary() );
        return 0;
    }

    // ---------------- judge construction ----------------

    private static ProposalJudge buildComparator( final Args a ) {
        return switch( a.judge ) {
            case "ollama" -> new OllamaProposalJudge(
                HttpClient.newHttpClient(), a.ollamaUrl, a.judgeModel, a.timeoutMs );
            case "claude" -> {
                if( !Boolean.parseBoolean(
                    System.getProperty( "wikantik.kg.judge.allow_claude", "false" ) ) ) {
                    throw new IllegalStateException(
                        "--judge claude requires -Dwikantik.kg.judge.allow_claude=true (gated cost guard)." );
                }
                if( a.anthropicKeyEnv == null || a.anthropicKeyEnv.isBlank() ) {
                    throw new IllegalStateException(
                        "--judge claude requires --anthropic-key-env <VAR> naming the env var." );
                }
                final String key = System.getenv( a.anthropicKeyEnv );
                if( key == null || key.isBlank() ) {
                    throw new IllegalStateException(
                        "environment variable '" + a.anthropicKeyEnv + "' is unset or empty." );
                }
                yield new ClaudeProposalJudge( key, a.judgeModel, a.timeoutMs );
            }
            default -> throw new IllegalStateException(
                "--judge must be 'ollama' or 'claude' (the experiment requires a real comparator)." );
        };
    }

    // ---------------- sampling ----------------

    /** A pending proposal hydrated back into a {@link ConsolidatedProposal}. */
    record Sampled(ConsolidatedProposal proposal) {}

    static List< Sampled > sampleProposals( final DataSource ds, final int sample ) {
        // ORDER BY random() is fine at this scale (few thousand rows). LIMIT in
        // SQL keeps memory bounded even if a future run sees a much larger queue.
        final String sql = """
            SELECT signature, proposal_type, proposed_data, support, source_page, confidence
            FROM kg_proposals
            WHERE status = 'pending'
            ORDER BY random()
            LIMIT ?
            """;
        final List< Sampled > out = new ArrayList<>();
        try( Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement( sql ) ) {
            ps.setInt( 1, sample );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final ConsolidatedProposal p = hydrate(
                        rs.getString( "signature" ),
                        rs.getString( "proposal_type" ),
                        rs.getString( "proposed_data" ),
                        rs.getString( "support" ),
                        rs.getString( "source_page" ),
                        rs.getDouble( "confidence" ) );
                    if( p != null ) out.add( new Sampled( p ) );
                }
            }
        } catch( final SQLException e ) {
            throw new RuntimeException( "sample query failed: " + e.getMessage(), e );
        }
        return out;
    }

    /** Reconstruct a {@link ConsolidatedProposal} from a {@code kg_proposals} row. */
    static ConsolidatedProposal hydrate( final String signature, final String proposalType,
                                          final String proposedDataJson, final String supportJson,
                                          final String sourcePage, final double confidence ) {
        if( signature == null || signature.isBlank() ) {
            LOG.warn( "Skipping proposal with blank signature (proposalType={})", proposalType );
            return null;
        }
        final List< SupportEvidence > support = parseSupport( supportJson, sourcePage );
        final JsonObject pd;
        try {
            pd = proposedDataJson == null
                ? new JsonObject()
                : JsonParser.parseString( proposedDataJson ).getAsJsonObject();
        } catch( final RuntimeException e ) {
            LOG.warn( "Skipping proposal {} — proposed_data is not a JSON object: {}",
                signature, e.getMessage() );
            return null;
        }
        if( "new-edge".equalsIgnoreCase( proposalType ) ) {
            final String src = stringOrNull( pd, "source" );
            final String tgt = stringOrNull( pd, "target" );
            final String rel = stringOrNull( pd, "relationship" );
            if( src == null || tgt == null || rel == null ) {
                LOG.warn( "Skipping edge proposal {} — missing source/target/relationship", signature );
                return null;
            }
            return ConsolidatedProposal.newEdge( signature, src, tgt, rel, support, confidence );
        }
        // Default to NEW_NODE — covers "new-node" as well as legacy / unknown types.
        final String name = stringOrNull( pd, "name" );
        final String type = stringOrNull( pd, "nodeType" );
        if( name == null ) {
            LOG.warn( "Skipping node proposal {} — missing name", signature );
            return null;
        }
        return ConsolidatedProposal.newNode( signature, name, type == null ? "Concept" : type,
            support, confidence );
    }

    private static List< SupportEvidence > parseSupport( final String json, final String fallbackPage ) {
        if( json == null || json.isBlank() ) {
            // No support array on the row — synthesize a single placeholder so
            // the proposal can still be judged. The fallback page is the
            // source_page column, which is always populated for v0.x rows.
            return fallbackPage == null ? List.of()
                : List.of( new SupportEvidence( fallbackPage, "", 0.0, "experiment:hydrated" ) );
        }
        try {
            final JsonElement root = JsonParser.parseString( json );
            if( !root.isJsonArray() ) return List.of();
            final List< SupportEvidence > out = new ArrayList<>();
            for( final JsonElement el : root.getAsJsonArray() ) {
                if( !el.isJsonObject() ) continue;
                final JsonObject o = el.getAsJsonObject();
                final String page = stringOrNull( o, "sourcePage" );
                final String span = stringOrNull( o, "evidenceSpan" );
                final double conf = o.has( "confidence" ) && !o.get( "confidence" ).isJsonNull()
                    ? o.get( "confidence" ).getAsDouble() : 0.0;
                final String code = stringOrNull( o, "extractorCode" );
                if( page == null || code == null ) continue;
                final String safeSpan = span == null ? "" : span;
                final double safeConf = Math.max( 0.0, Math.min( 1.0, conf ) );
                out.add( new SupportEvidence( page, safeSpan, safeConf, code ) );
            }
            return out;
        } catch( final RuntimeException e ) {
            LOG.warn( "Skipping malformed support JSON ({}): {}", e.getMessage(), json );
            return List.of();
        }
    }

    private static String stringOrNull( final JsonObject o, final String key ) {
        if( !o.has( key ) ) return null;
        final JsonElement v = o.get( key );
        if( v == null || v.isJsonNull() ) return null;
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    // ---------------- context construction ----------------

    private static JudgeContext buildContext( final ConsolidatedProposal p,
                                              final ContentChunkRepository chunkRepo,
                                              final Map< String, String > cache ) {
        final Map< String, String > bodies = new HashMap<>();
        for( final SupportEvidence ev : p.support() ) {
            final String page = ev.sourcePage();
            if( bodies.containsKey( page ) ) continue;
            final String body = cache.computeIfAbsent( page, name -> stitchPage( chunkRepo, name ) );
            if( body != null ) bodies.put( page, body );
        }
        // Neighborhood lookup (KG node similarity) is intentionally omitted —
        // pulling it in would mean depending on the embedding service and
        // doubling the experiment's runtime. The judge prompt already lets
        // the model fail open on canonicalization checks when no neighborhood
        // is present.
        return new JudgeContext( bodies, List.of() );
    }

    private static String stitchPage( final ContentChunkRepository chunkRepo, final String pageName ) {
        try {
            final List< ContentChunkRepository.FullChunk > chunks = chunkRepo.findFullByPage( pageName );
            if( chunks.isEmpty() ) return null;
            final StringBuilder sb = new StringBuilder();
            for( final ContentChunkRepository.FullChunk c : chunks ) {
                if( sb.length() > 0 ) sb.append( "\n\n" );
                sb.append( c.text() == null ? "" : c.text() );
            }
            return sb.toString();
        } catch( final RuntimeException e ) {
            LOG.warn( "Failed to stitch chunks for page '{}': {}", pageName, e.getMessage() );
            return null;
        }
    }

    // ---------------- stats / report ----------------

    private static String summarizeVerdict( final Verdict v ) {
        if( v instanceof Verdict.Accept a ) {
            return "accept" + ( a.rationale() == null || a.rationale().isBlank() ? "" : ": " + a.rationale() );
        }
        if( v instanceof Verdict.Reject r ) {
            return "reject:" + r.reasonCode()
                + ( r.rationale() == null || r.rationale().isBlank() ? "" : " (" + r.rationale() + ")" );
        }
        if( v instanceof Verdict.Rewrite ) {
            return "rewrite";
        }
        return v.toString();
    }

    /** Per-judge accumulator. */
    static final class JudgeStats {
        final String judgeCode;
        int accepted;
        int rejected;
        int rewritten;
        int judgeFailed;
        final Map< String, Integer > rejectReasons = new TreeMap<>();

        JudgeStats( final String judgeCode ) {
            this.judgeCode = judgeCode;
        }

        void record( final Verdict v ) {
            if( v instanceof Verdict.Accept a ) {
                accepted++;
                if( a.rationale() != null && a.rationale().startsWith( "judge_failed" ) ) judgeFailed++;
            } else if( v instanceof Verdict.Reject r ) {
                rejected++;
                rejectReasons.merge( r.reasonCode(), 1, Integer::sum );
            } else if( v instanceof Verdict.Rewrite ) {
                rewritten++;
            }
        }

        Map< String, Object > toMap() {
            final Map< String, Object > m = new TreeMap<>();
            m.put( "code", judgeCode );
            m.put( "accepted", accepted );
            m.put( "rejected", rejected );
            m.put( "rewritten", rewritten );
            m.put( "judge_failed_accepts", judgeFailed );
            m.put( "reject_reasons", rejectReasons );
            return m;
        }

        String summary() {
            return String.format( Locale.ROOT, "accept=%d reject=%d rewrite=%d judge_failed=%d",
                accepted, rejected, rewritten, judgeFailed );
        }
    }

    record ExampleRow(String signature, String kind, String displayName, String type,
                      String source, String target, String predicate,
                      double aggregateConfidence, int supportCount,
                      String noopVerdict, String comparatorVerdict) {}

    record Report(String generatedAt, String judge, String judgeCode, int sampleSize,
                  Map< String, Object > noopVerdicts, Map< String, Object > comparatorVerdicts,
                  List< ExampleRow > examples) {}

    static void writeReport( final String path, final Report report ) throws IOException {
        final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        Files.writeString( Path.of( path ), gson.toJson( report ) );
    }

    // ---------------- args ----------------

    private static DataSource dataSource( final Args a ) {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( a.jdbcUrl );
        ds.setUser( a.jdbcUser );
        ds.setPassword( a.jdbcPassword );
        return ds;
    }

    private static void printUsage() {
        final String usage = """
            wikantik-extract-cli — JudgeExperimentCli (Phase 6.3)

            Reads a random sample of pending kg_proposals rows and judges each row
            with both NoOpProposalJudge and a comparator (ollama or claude). Writes
            a side-by-side JSON report so the operator can decide whether to flip
            the production extractor's --judge default.

            Usage:
              java -cp wikantik-extract-cli.jar com.wikantik.extractcli.JudgeExperimentCli [options]

            Required:
              --judge <ollama|claude>          comparator backend (no 'none' here)
              --output <path.json>             where to write the report

            Database:
              --jdbc-url <url>                 (default jdbc:postgresql://localhost:5432/jspwiki)
              --jdbc-user <name>               (default jspwiki)
              --jdbc-password <value>          password literal (not recommended)
              --jdbc-password-env <VAR>        read password from env var (preferred)

            Comparator config:
              --judge-model <tag/id>           default qwen3.5:9b (ollama) or claude-haiku-4-5 (claude)
              --ollama-url <url>               default http://inference.jakefear.com:11434
              --anthropic-key-env <VAR>        env var holding the Anthropic API key (claude only)
              -Dwikantik.kg.judge.allow_claude=true   required for --judge claude

            Sampling:
              --sample <N>                     number of pending proposals to draw (default 100)
              --timeout-ms <ms>                per-judge HTTP timeout (default 60000)
              -h, --help                       show this message

            Exit codes: 0 = report written, 1 = DB or judge error, 2 = bad arguments.
            """;
        System.out.println( usage );
    }

    /** CLI argument bag, parseable in tests. */
    public static final class Args {
        public String jdbcUrl       = "jdbc:postgresql://localhost:5432/jspwiki";
        public String jdbcUser      = "jspwiki";
        public String jdbcPassword  = "";
        public String judge         = null;
        public String judgeModel    = "qwen3.5:9b";
        public String ollamaUrl     = "http://inference.jakefear.com:11434";
        public String anthropicKeyEnv = null;
        public int    sample        = 100;
        public long   timeoutMs     = 60_000L;
        public String output        = null;
        public boolean showHelp     = false;

        public static Args parse( final String[] argv ) {
            final Args a = new Args();
            for( int i = 0; i < argv.length; i++ ) {
                final String k = argv[ i ];
                switch( k ) {
                    case "-h", "--help"        -> a.showHelp = true;
                    case "--jdbc-url"          -> a.jdbcUrl = req( argv, ++i, k );
                    case "--jdbc-user"         -> a.jdbcUser = req( argv, ++i, k );
                    case "--jdbc-password"     -> a.jdbcPassword = req( argv, ++i, k );
                    case "--jdbc-password-env" -> a.jdbcPassword = env( req( argv, ++i, k ) );
                    case "--judge"             -> a.judge = req( argv, ++i, k ).toLowerCase( Locale.ROOT );
                    case "--judge-model"       -> a.judgeModel = req( argv, ++i, k );
                    case "--ollama-url"        -> a.ollamaUrl = req( argv, ++i, k );
                    case "--anthropic-key-env" -> a.anthropicKeyEnv = req( argv, ++i, k );
                    case "--sample"            -> a.sample = parseInt( req( argv, ++i, k ), k );
                    case "--timeout-ms"        -> a.timeoutMs = parseLong( req( argv, ++i, k ), k );
                    case "--output"            -> a.output = req( argv, ++i, k );
                    default -> throw new IllegalArgumentException( "unknown argument: " + k );
                }
            }
            if( !a.showHelp ) {
                if( a.judge == null ) throw new IllegalArgumentException( "--judge is required (ollama|claude)" );
                if( !a.judge.equals( "ollama" ) && !a.judge.equals( "claude" ) ) {
                    throw new IllegalArgumentException( "--judge must be 'ollama' or 'claude'" );
                }
                if( a.output == null || a.output.isBlank() ) {
                    throw new IllegalArgumentException( "--output is required" );
                }
                if( a.sample < 1 ) throw new IllegalArgumentException( "--sample must be >= 1" );
                if( a.timeoutMs < 1_000L ) throw new IllegalArgumentException( "--timeout-ms must be >= 1000" );
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

        private static int parseInt( final String s, final String flag ) {
            try { return Integer.parseInt( s ); }
            catch( final NumberFormatException e ) {
                throw new IllegalArgumentException( flag + " requires an integer, got: " + s );
            }
        }

        private static long parseLong( final String s, final String flag ) {
            try { return Long.parseLong( s ); }
            catch( final NumberFormatException e ) {
                throw new IllegalArgumentException( flag + " requires an integer, got: " + s );
            }
        }
    }
}
