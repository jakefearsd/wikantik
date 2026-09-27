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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.KgProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the {@code --only-missing} page filter against a real schema.
 *
 * <p>The sibling {@code BootstrapExtractionCliGlobTest} only covers the pure
 * {@code globToRegex} helper, never the {@code listDistinctPageNames()} override that
 * actually scopes a run — so the wrapping seam itself was untested. This covers the new
 * filter's real behaviour rather than repeating that gap.</p>
 */
@RequiresPostgres
class BootstrapExtractionCliOnlyMissingTest {

    private DataSource ds;
    private KgProposalRepository kgProposals;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "kg_proposal_reviews", "kg_proposals", "kg_nodes",
            "kg_content_chunks" );
        kgProposals = new KgProposalRepository( ds );
    }

    /** Raw INSERT, mirroring PageExtractionPgTestBase — the repository exposes no insert API. */
    private void seedChunk( final String pageName ) throws Exception {
        final String text = "chunk body for " + pageName;
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_content_chunks (page_name, chunk_index, text, char_count, "
                + "token_count_estimate, content_hash) VALUES (?, 0, ?, ?, ?, ?)" ) ) {
            ps.setString( 1, pageName );
            ps.setString( 2, text );
            ps.setInt( 3, text.length() );
            ps.setInt( 4, text.length() / 4 );
            ps.setString( 5, "hash-" + pageName );
            ps.executeUpdate();
        }
    }

    /**
     * {@code --only-missing} must drop pages that already have proposals.
     *
     * <p>The extraction gap is 239 pages with chunks and <b>no proposals at all</b>. Pages
     * that already have proposals do not need the 12B model again — they need the
     * materialisation replay instead. Selecting on "has no nodes" would wrongly pull in
     * ~350 pages whose proposals exist but were never materialised, spending inference to
     * re-derive what the database already holds.</p>
     *
     * <p>Without this filter the CLI processes every chunk-bearing page: 22,001 chunks
     * instead of 2,669, an 8.2x waste against a 12B model.</p>
     */
    @Test
    void only_missing_excludes_pages_that_already_have_proposals() throws Exception {
        seedChunk( "AlphaPage" );
        seedChunk( "BetaPage" );
        seedChunk( "GammaPage" );
        kgProposals.insertProposal( "new-node", "BetaPage",
            Map.< String, Object >of( "name", "Beta", "nodeType", "concept" ), 0.9, "extracted" );

        final List< String > pages = BootstrapExtractionCli
            .selectChunkRepo( ds, /*pagePattern*/ null, /*onlyMissing*/ true )
            .listDistinctPageNames();

        // listDistinctPageNames() orders by page_name, so the expectation is deterministic.
        assertEquals( List.of( "AlphaPage", "GammaPage" ), pages,
            "--only-missing must skip pages that already have proposals" );
    }

    /**
     * {@code --page-pattern} and {@code --only-missing} must <b>compose</b>.
     *
     * <p>Both narrow the same {@code listDistinctPageNames()} seam, so the risk is that
     * wiring the second one causes it to replace the first rather than layer on it — a
     * silent widening (or narrowing) of an expensive run. The fixture is built so that
     * dropping <i>either</i> filter changes the answer: the glob alone would keep
     * {@code AlphaOther}, and {@code --only-missing} alone would keep {@code BetaPage}.</p>
     */
    @Test
    void page_pattern_and_only_missing_compose() throws Exception {
        seedChunk( "AlphaPage" );
        seedChunk( "AlphaOther" );
        seedChunk( "BetaPage" );
        kgProposals.insertProposal( "new-node", "AlphaOther",
            Map.< String, Object >of( "name", "Other", "nodeType", "concept" ), 0.9, "extracted" );

        final List< String > pages = BootstrapExtractionCli
            .selectChunkRepo( ds, "Alpha*", true )
            .listDistinctPageNames();

        assertEquals( List.of( "AlphaPage" ), pages,
            "both filters must apply: the glob drops BetaPage, --only-missing drops AlphaOther" );
    }
}
