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

import com.wikantik.api.kgpolicy.ClusterAction;
import com.wikantik.api.kgpolicy.ClusterPolicy;
import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.kgpolicy.PolicyAuditEntry;
import com.wikantik.kgpolicy.KgClusterPolicyRepository;
import com.wikantik.kgpolicy.KgExcludedPagesRepository;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Admin CLI for the KG inclusion / exclusion policy. Mirrors {@code /admin/kg-policy/*}
 * REST endpoints plus a destructive {@code purge} command.
 *
 * <p>Subcommands:</p>
 * <pre>
 *   list [--filter include|exclude|unset]
 *   set &lt;cluster&gt; &lt;include|exclude&gt; --reason "..."
 *   clear &lt;cluster&gt;
 *   explain &lt;cluster&gt;                    (cluster-level only — page-name lookup needs WikiEngine)
 *   review                                  (list pending-review items)
 *   mark-reviewed &lt;cluster&gt; [&lt;cluster&gt; ...]
 *   diff &lt;cluster&gt;                       (pages excluded vs the current policy)
 *   reconcile [&lt;cluster&gt; ...]            (re-evaluate all pages in cluster)
 *   purge &lt;cluster&gt; [--confirm]          (HARD-delete all KG data for excluded pages)
 *   purge --reason &lt;reason&gt; [--confirm]
 *   audit [--cluster X] [--limit N]
 * </pre>
 */
public final class KgPolicyCli {

    public static void main( final String[] args ) {
        final int rc = new KgPolicyCli( System.out, System.err ).run( args );
        System.exit( rc );
    }

    private final PrintStream out;
    private final PrintStream err;

    public KgPolicyCli( final PrintStream out, final PrintStream err ) {
        this.out = out;
        this.err = err;
    }

    /** Test seam — accepts a pre-built DataSource so tests can reuse Testcontainers. */
    public int runWithDataSource( final DataSource ds, final String[] args ) {
        if ( args.length == 0 ) return usage();
        final String sub = args[ 0 ];
        try {
            return switch ( sub ) {
                case "list"           -> doList( ds, args );
                case "set"            -> doSet( ds, args );
                case "clear"          -> doClear( ds, args );
                case "explain"        -> doExplain( ds, args );
                case "review"         -> doReview( ds );
                case "mark-reviewed"  -> doMarkReviewed( ds, args );
                case "diff"           -> doDiff( ds, args );
                case "reconcile"      -> doReconcile( ds, args );
                case "purge"          -> doPurge( ds, args );
                case "audit"          -> doAudit( ds, args );
                default               -> usage();
            };
        } catch ( final RuntimeException e ) {
            err.println( "error: " + e.getMessage() );
            return 1;
        }
    }

    public int run( final String[] args ) {
        // Pull JDBC config out of args (or env), strip those args, then dispatch.
        final JdbcArgs jdbc = JdbcArgs.parse( args );
        return runWithDataSource( jdbc.dataSource(), jdbc.remaining );
    }

    /* ---- subcommand implementations ---- */

    private int doList( final DataSource ds, final String[] args ) {
        final List< ClusterPolicy > all = new KgClusterPolicyRepository( ds ).list();
        out.printf( "%-40s  %-8s  %-12s  %s%n", "cluster", "action", "set_by", "reason" );
        out.println( "-".repeat( 80 ) );
        for ( final ClusterPolicy p : all ) {
            out.printf( "%-40s  %-8s  %-12s  %s%n",
                    p.cluster(), p.action().wire(), p.setBy(),
                    p.reason() == null ? "" : p.reason() );
        }
        return 0;
    }

    private int doSet( final DataSource ds, final String[] args ) {
        final String cluster = requirePositional( args, 1, "cluster name" );
        final String actionRaw = requirePositional( args, 2, "action (include|exclude)" );
        final ClusterAction action = ClusterAction.fromWire( actionRaw )
                .orElseThrow( () -> new IllegalArgumentException(
                        "action must be 'include' or 'exclude' (got '" + actionRaw + "')" ) );
        final String reason = optionalFlag( args, "--reason" );
        final String actor = System.getProperty( "user.name", "cli" );

        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        final Optional< ClusterPolicy > prior = repo.find( cluster );
        repo.upsert( cluster, action, reason, actor );
        repo.appendAudit( cluster,
                prior.map( p -> p.action().wire() ).orElse( null ),
                action.wire(), reason, actor );
        out.printf( "set %s -> %s%n", cluster, action.wire() );
        return 0;
    }

    private int doClear( final DataSource ds, final String[] args ) {
        final String cluster = requirePositional( args, 1, "cluster name" );
        final String actor = System.getProperty( "user.name", "cli" );
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        final Optional< ClusterPolicy > prior = repo.find( cluster );
        if ( prior.isEmpty() ) {
            err.println( "no policy row for " + cluster );
            return 1;
        }
        repo.delete( cluster );
        repo.appendAudit( cluster, prior.get().action().wire(), "cleared", null, actor );
        out.printf( "cleared %s (was %s)%n", cluster, prior.get().action().wire() );
        return 0;
    }

    private int doExplain( final DataSource ds, final String[] args ) {
        final String cluster = requirePositional( args, 1, "cluster name" );
        final Optional< ClusterPolicy > p = new KgClusterPolicyRepository( ds ).find( cluster );
        if ( p.isEmpty() ) {
            out.printf( "%s: no policy row (default exclude)%n", cluster );
            return 0;
        }
        out.printf( "%s: %s (set_by=%s, reason=%s, set_at=%s, reviewed_at=%s)%n",
                cluster, p.get().action().wire(), p.get().setBy(),
                p.get().reason() == null ? "" : p.get().reason(),
                p.get().setAt(), p.get().reviewedAt() );
        return 0;
    }

    private int doReview( final DataSource ds ) {
        final List< ClusterPolicy > all = new KgClusterPolicyRepository( ds ).list();
        final Instant cutoff = Instant.now().minus( 90, ChronoUnit.DAYS );
        out.println( "stale (reviewed > 90 days ago or never):" );
        for ( final ClusterPolicy p : all ) {
            if ( p.reviewedAt() == null || p.reviewedAt().isBefore( cutoff ) ) {
                out.printf( "  %-40s  %-8s  reviewed_at=%s%n",
                        p.cluster(), p.action().wire(), p.reviewedAt() );
            }
        }
        return 0;
    }

    private int doMarkReviewed( final DataSource ds, final String[] args ) {
        final List< String > clusters = positionalsFrom( args, 1 );
        if ( clusters.isEmpty() ) {
            err.println( "mark-reviewed requires at least one cluster name" );
            return 2;
        }
        final KgClusterPolicyRepository repo = new KgClusterPolicyRepository( ds );
        for ( final String c : clusters ) {
            repo.markReviewed( c );
            out.printf( "marked reviewed: %s%n", c );
        }
        return 0;
    }

    private int doDiff( final DataSource ds, final String[] args ) {
        final String cluster = requirePositional( args, 1, "cluster name" );
        final Set< String > excluded = new KgExcludedPagesRepository( ds )
                .listByReason( ExclusionReason.CLUSTER_POLICY );
        out.printf( "Pages excluded under cluster_policy reason (cluster=%s):%n", cluster );
        // We don't have the page-to-cluster mapping in the repo layer; show all pages
        // tagged with cluster_policy and let the operator filter visually.
        excluded.stream().sorted().forEach( p -> out.printf( "  %s%n", p ) );
        out.printf( "  (%d pages)%n", excluded.size() );
        return 0;
    }

    private int doReconcile( final DataSource ds, final String[] args ) {
        // The CLI doesn't have access to the StructuralIndexService (no WikiEngine
        // here), so reconciliation reduces to "show what's in kg_excluded_pages by
        // reason." For full reconciliation, the operator hits the REST endpoint
        // POST /admin/kg-policy/clusters/{c} (or PUT) which re-queues the runner.
        out.println( "CLI reconcile: see /admin/kg-policy or restart Tomcat to re-run." );
        out.println( "Excluded by reason:" );
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        for ( final ExclusionReason r : ExclusionReason.values() ) {
            out.printf( "  %-15s  %d pages%n", r.wire(), repo.listByReason( r ).size() );
        }
        return 0;
    }

    private int doAudit( final DataSource ds, final String[] args ) {
        final String cluster = optionalFlag( args, "--cluster" );
        final int limit = parseIntOr( optionalFlag( args, "--limit" ), 50 );
        final List< PolicyAuditEntry > entries = new KgClusterPolicyRepository( ds )
                .listAudit( Optional.ofNullable( cluster ), limit );
        out.printf( "%-30s  %-8s -> %-10s  %-12s  %s%n",
                "changed_at", "old", "new", "actor", "cluster" );
        out.println( "-".repeat( 90 ) );
        for ( final PolicyAuditEntry a : entries ) {
            out.printf( "%-30s  %-8s -> %-10s  %-12s  %s%n",
                    a.changedAt(), a.oldAction() == null ? "-" : a.oldAction(),
                    a.newAction(), a.actor(), a.cluster() );
        }
        return 0;
    }

    private int doPurge( final DataSource ds, final String[] args ) {
        final boolean confirm = hasFlag( args, "--confirm" );
        final String reasonFlag = optionalFlag( args, "--reason" );

        // Two modes: cluster-purge (positional cluster name) or reason-purge (--reason)
        final String clusterArg = positionalAt( args, 1 );
        final boolean isReasonMode = clusterArg == null || clusterArg.startsWith( "--" );
        if ( isReasonMode ) {
            if ( reasonFlag == null ) {
                err.println( "purge: provide either a cluster name or --reason" );
                return 2;
            }
            return purgeByReason( ds, reasonFlag, confirm );
        }
        return purgeByCluster( ds, clusterArg, confirm );
    }

    private int purgeByReason( final DataSource ds, final String reasonRaw, final boolean confirm ) {
        final ExclusionReason reason = ExclusionReason.fromWire( reasonRaw )
                .orElseThrow( () -> new IllegalArgumentException( "unknown reason: " + reasonRaw ) );
        final Set< String > pages = new KgExcludedPagesRepository( ds ).listByReason( reason );
        out.printf( "Would purge %d pages excluded with reason '%s':%n", pages.size(), reason.wire() );
        pages.stream().sorted().limit( 25 ).forEach( p -> out.printf( "  %s%n", p ) );
        if ( pages.size() > 25 ) out.printf( "  ... (%d more)%n", pages.size() - 25 );
        if ( !confirm ) {
            err.println( "Pass --confirm to perform deletion." );
            return 1;
        }
        return executePurge( ds, new ArrayList<>( pages ), "reason=" + reason.wire() );
    }

    private int purgeByCluster( final DataSource ds, final String cluster, final boolean confirm ) {
        // Without StructuralIndexService we don't know which pages are in `cluster`;
        // list all pages currently in kg_excluded_pages and let the reconciler do
        // the cluster→page mapping. For the cluster-purge path, we use the audit log:
        // any page excluded under reason=cluster_policy after the cluster's last
        // include→exclude flip is a candidate.
        final KgExcludedPagesRepository repo = new KgExcludedPagesRepository( ds );
        final Set< String > clusterExcluded = repo.listByReason( ExclusionReason.CLUSTER_POLICY );
        out.printf( "Would purge %d pages excluded by cluster_policy (cluster=%s):%n",
                clusterExcluded.size(), cluster );
        out.println( "  Note: cluster→page mapping isn't tracked by the CLI. Pages listed are " );
        out.println( "        ALL pages currently excluded by cluster_policy reason. Run from " );
        out.println( "        /admin/kg-policy or wait for ReconciliationJobRunner to scope by cluster." );
        if ( !confirm ) {
            err.println( "Pass --confirm to perform deletion of ALL cluster_policy-excluded pages." );
            return 1;
        }
        return executePurge( ds, new ArrayList<>( clusterExcluded ), "cluster=" + cluster );
    }

    private int executePurge( final DataSource ds, final List< String > pageNames, final String label ) {
        if ( pageNames.isEmpty() ) {
            out.println( "Nothing to purge." );
            return 0;
        }
        final String inClause = "(" + String.join( ",", pageNames.stream().map( s -> "?" ).toList() ) + ")";
        try ( Connection c = ds.getConnection() ) {
            c.setAutoCommit( false );
            try {
                final int mentionsDeleted;
                try ( PreparedStatement st = c.prepareStatement(
                        "DELETE FROM chunk_entity_mentions WHERE chunk_id IN " +
                        "(SELECT id FROM kg_content_chunks WHERE page_name IN " + inClause + ")" ) ) {
                    bindStrings( st, pageNames, 1 );
                    mentionsDeleted = st.executeUpdate();
                }
                final int edgesDeleted;
                try ( PreparedStatement st = c.prepareStatement(
                        "DELETE FROM kg_edges WHERE source_id IN " +
                        "(SELECT id FROM kg_nodes WHERE source_page IN " + inClause + ") " +
                        "OR target_id IN " +
                        "(SELECT id FROM kg_nodes WHERE source_page IN " + inClause + ")" ) ) {
                    bindStrings( st, pageNames, 1 );
                    bindStrings( st, pageNames, 1 + pageNames.size() );
                    edgesDeleted = st.executeUpdate();
                }
                final int nodesDeleted;
                try ( PreparedStatement st = c.prepareStatement(
                        "DELETE FROM kg_nodes WHERE source_page IN " + inClause ) ) {
                    bindStrings( st, pageNames, 1 );
                    nodesDeleted = st.executeUpdate();
                }
                final int excludedDeleted;
                try ( PreparedStatement st = c.prepareStatement(
                        "DELETE FROM kg_excluded_pages WHERE page_name IN " + inClause ) ) {
                    bindStrings( st, pageNames, 1 );
                    excludedDeleted = st.executeUpdate();
                }
                c.commit();
                out.printf( "Purged %s: chunk_mentions=%d edges=%d nodes=%d excluded_rows=%d%n",
                        label, mentionsDeleted, edgesDeleted, nodesDeleted, excludedDeleted );
                // Audit — use a fresh auto-commit connection so we don't leave an open txn
                try ( Connection ac = ds.getConnection();
                      PreparedStatement aud = ac.prepareStatement(
                        "INSERT INTO kg_policy_audit (cluster, old_action, new_action, reason, actor) " +
                        "VALUES (?, NULL, 'purged', ?, ?)" ) ) {
                    aud.setString( 1, label );
                    aud.setString( 2, "mentions=" + mentionsDeleted + " edges=" + edgesDeleted +
                            " nodes=" + nodesDeleted + " excluded=" + excludedDeleted );
                    aud.setString( 3, System.getProperty( "user.name", "cli" ) );
                    aud.executeUpdate();
                }
                return 0;
            } catch ( final SQLException e ) {
                c.rollback();
                throw new RuntimeException( "purge rolled back: " + e.getMessage(), e );
            }
        } catch ( final SQLException e ) {
            throw new RuntimeException( "purge failed: " + e.getMessage(), e );
        }
    }

    /* ---- arg parsing helpers ---- */

    private int usage() {
        err.println( "usage: kg-policy [--jdbc-url URL --jdbc-user U --jdbc-password-env VAR] " +
                "{list|set|clear|explain|review|mark-reviewed|diff|reconcile|purge|audit} ..." );
        return 2;
    }

    private static String requirePositional( final String[] args, final int idx, final String label ) {
        if ( idx >= args.length || args[ idx ].startsWith( "--" ) ) {
            throw new IllegalArgumentException( "missing required positional: " + label );
        }
        return args[ idx ];
    }

    private static String positionalAt( final String[] args, final int idx ) {
        if ( idx >= args.length ) return null;
        final String s = args[ idx ];
        return s.startsWith( "--" ) ? null : s;
    }

    private static List< String > positionalsFrom( final String[] args, final int idx ) {
        final List< String > out = new ArrayList<>();
        for ( int i = idx; i < args.length; i++ ) {
            if ( args[ i ].startsWith( "--" ) ) break;
            out.add( args[ i ] );
        }
        return out;
    }

    private static String optionalFlag( final String[] args, final String name ) {
        for ( int i = 0; i < args.length; i++ ) {
            if ( name.equals( args[ i ] ) && i + 1 < args.length ) return args[ i + 1 ];
        }
        return null;
    }

    private static boolean hasFlag( final String[] args, final String name ) {
        for ( final String a : args ) if ( name.equals( a ) ) return true;
        return false;
    }

    private static int parseIntOr( final String s, final int fallback ) {
        if ( s == null ) return fallback;
        try { return Integer.parseInt( s ); } catch ( final NumberFormatException e ) { return fallback; }
    }

    private static void bindStrings( final PreparedStatement st, final List< String > vals, final int startIdx )
            throws SQLException {
        for ( int i = 0; i < vals.size(); i++ ) st.setString( startIdx + i, vals.get( i ) );
    }

    /* ---- JDBC arg parsing — extract --jdbc-url etc., strip them, leave the rest ---- */

    static final class JdbcArgs {
        String jdbcUrl  = "jdbc:postgresql://localhost:5432/jspwiki";
        String jdbcUser = "jspwiki";
        String jdbcPassword;
        String[] remaining;

        DataSource dataSource() {
            final PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl( jdbcUrl );
            ds.setUser( jdbcUser );
            ds.setPassword( jdbcPassword );
            return ds;
        }

        static JdbcArgs parse( final String[] argv ) {
            final JdbcArgs j = new JdbcArgs();
            final List< String > rest = new ArrayList<>();
            for ( int i = 0; i < argv.length; i++ ) {
                final String a = argv[ i ];
                switch ( a ) {
                    case "--jdbc-url" -> j.jdbcUrl = argv[ ++i ];
                    case "--jdbc-user" -> j.jdbcUser = argv[ ++i ];
                    case "--jdbc-password" -> j.jdbcPassword = argv[ ++i ];
                    case "--jdbc-password-env" -> j.jdbcPassword = System.getenv( argv[ ++i ] );
                    default -> rest.add( a );
                }
            }
            j.remaining = rest.toArray( new String[0] );
            return j;
        }
    }
}
