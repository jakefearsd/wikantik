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

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.knowledge.KgEdgeRepository;
import com.wikantik.knowledge.KgNodeRepository;
import com.wikantik.knowledge.KgProposalRepository;
import com.wikantik.knowledge.KgRejectionRepository;
import com.wikantik.knowledge.judge.KgMaterializationService;
import com.wikantik.ontology.OntologyShaclValidator;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.util.List;

/**
 * One-shot replay: materialise {@code new-node} proposals that a machine judge already
 * approved but which never became {@code kg_nodes} rows.
 *
 * <p>Until 2026-09-26 {@code KgMaterializationService.materialize} handled only
 * {@code new-edge}, so approving a {@code new-node} proposal wrote nothing. Fixing the
 * materialiser does not retroactively create the missing rows, hence this replay. It
 * performs <b>no LLM inference</b> — every proposal was already extracted and judged.</p>
 *
 * <p>Scope is deliberately {@code status='pending' AND machine_status='approved' AND
 * proposal_type='new-node'}. Predicating on {@code status='pending'} is what keeps
 * human verdicts authoritative: proposals a human has since <i>rejected</i> are excluded
 * by construction, even though the machine approved them.</p>
 *
 * <p>Safe to re-run: {@code upsertNodeWithProvenance} is {@code ON CONFLICT ( name )},
 * pinned by {@code KgMaterializationServiceMaterializeTest.materializeMachine_new_node_is_idempotent}.</p>
 */
public final class MaterializeApprovedProposalsCli {

    public static void main( final String[] args ) {
        System.exit( new MaterializeApprovedProposalsCli( System.out, System.err ).run( args ) );
    }

    private final PrintStream out;
    private final PrintStream err;

    public MaterializeApprovedProposalsCli( final PrintStream out, final PrintStream err ) {
        this.out = out;
        this.err = err;
    }

    /** Human verdict still outstanding — excludes anything a human has since rejected. */
    private static final String PENDING = "pending";
    /** The machine judge approved it. */
    private static final String APPROVED = "approved";
    /** The only type this replay materialises; see the class javadoc. */
    private static final String NEW_NODE = "new-node";
    private static final int PAGE_SIZE = 500;

    /**
     * Test seam — accepts a pre-built {@link DataSource} so tests drive the real logic
     * without reflection and without {@code System.exit}, mirroring
     * {@code KgPolicyCli.runWithDataSource}.
     *
     * @return process exit code: 0 completed, 1 failed, 2 bad arguments
     */
    public int runWithDataSource( final DataSource ds, final String[] args ) {
        final Args a;
        try {
            a = Args.parse( args );
        } catch ( final IllegalArgumentException e ) {
            err.println( "error: " + e.getMessage() );
            return 2;
        }

        final KgProposalRepository proposals = new KgProposalRepository( ds );
        final KgMaterializationService materialization = new KgMaterializationService(
            new KgNodeRepository( ds ), new KgEdgeRepository( ds ), proposals,
            new KgRejectionRepository( ds ), new OntologyShaclValidator() );

        final long inScope = proposals.countProposalsFiltered( PENDING, null, APPROVED, false, null );
        out.printf( "replay: %d pending machine-approved proposals of all types in scope%n", inScope );

        final Tally t = replay( proposals, materialization, a );

        out.printf( "replay %s: materialized=%d, skipped(other type)=%d, failed=%d%n",
            a.dryRun ? "DRY RUN (nothing written)" : "complete",
            t.materialized, t.skippedOtherType, t.failed );
        return t.failed > 0 ? 1 : 0;
    }

    /** Running totals for one replay pass. */
    private static final class Tally {
        private int materialized;
        private int skippedOtherType;
        private int failed;
    }

    /**
     * Pages through the in-scope proposals, materialising each.
     *
     * <p>Offset paging is safe here: {@code materializeMachine} writes {@code kg_nodes} /
     * {@code kg_edges} but never {@code status} or {@code machine_status}, so rows cannot drop
     * out of the filter mid-run, and the query already orders {@code created DESC, id DESC}
     * with a deliberate stable-pagination tiebreak.</p>
     */
    private Tally replay( final KgProposalRepository proposals,
                          final KgMaterializationService materialization, final Args a ) {
        final Tally t = new Tally();
        int offset = 0;
        while ( true ) {
            final List< KgProposal > page = proposals.listProposalsFiltered(
                PENDING, null, APPROVED, false, null, PAGE_SIZE, offset );
            if ( page.isEmpty() || !replayPage( page, materialization, a, t ) ) {
                return t;
            }
            offset += page.size();
        }
    }

    /** Materialises one page. Returns {@code false} when {@code --limit} is reached. */
    private boolean replayPage( final List< KgProposal > page,
                                final KgMaterializationService materialization,
                                final Args a, final Tally t ) {
        for ( final KgProposal p : page ) {
            if ( !NEW_NODE.equals( p.proposalType() ) ) {
                t.skippedOtherType++;
                continue;
            }
            if ( a.limit > 0 && t.materialized >= a.limit ) {
                return false;
            }
            if ( a.dryRun ) {
                t.materialized++;
                continue;
            }
            try {
                materialization.materializeMachine( p );
                t.materialized++;
            } catch ( final RuntimeException e ) {
                // One bad proposal must not cap the replay — mirrors PgVectorBackfillCli.
                t.failed++;
                err.printf( "  failed proposal %s: %s%n", p.id(), e.getMessage() );
            }
        }
        return true;
    }

    public int run( final String[] args ) {
        final Args a;
        try {
            a = Args.parse( args );
        } catch ( final IllegalArgumentException e ) {
            err.println( "error: " + e.getMessage() );
            return 2;
        }
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( a.jdbcUrl );
        ds.setUser( a.jdbcUser );
        ds.setPassword( a.jdbcPassword );
        return runWithDataSource( ds, args );
    }

    /** CLI arguments. Public so tests can drive {@link #parse} directly. */
    public static final class Args extends CommonCliArgs {
        /** Stop after this many proposals; 0 = unlimited. */
        public int limit = 0;
        /** Resolve and report what would be materialised without writing nodes. */
        public boolean dryRun = false;

        public static Args parse( final String[] argv ) {
            final Args a = new Args();
            for ( int i = 0; i < argv.length; i++ ) {
                final String k = argv[ i ];
                final int consumed = a.applyCommonFlag( k, argv, i );
                if ( consumed >= 0 ) {
                    i = consumed;
                    continue;
                }
                switch ( k ) {
                    case "--limit"   -> a.limit = Integer.parseInt( req( argv, ++i, k ) );
                    case "--dry-run" -> a.dryRun = true;
                    default          -> throw new IllegalArgumentException( "unknown argument: " + k );
                }
            }
            return a;
        }
    }
}
