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
package com.wikantik.its.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Base class for MCP integration tests. Creates and closes an {@link McpTestClient}
 * for the test class lifecycle. Does not depend on Selenide or any browser.
 *
 * <p>Exposes static accessors for KG curation seed fixture UUIDs inserted by
 * {@code it-test-seed.sql} (fixed UUIDs so tests are deterministic). Also provides
 * helpers for reading the Cargo Tomcat log to verify audit-log emission.</p>
 */
public class WithMcpTestSetup {

    // -----------------------------------------------------------------
    // KG curation seed fixture IDs — match it-test-seed.sql exactly.
    // -----------------------------------------------------------------

    /** UUID of the pending {@code new-node} proposal seeded for approve/inspect tests. */
    private static final String SEEDED_PENDING_NODE_PROPOSAL_ID = "cccccccc-0001-0000-0000-000000000001";

    /** UUID of the pending {@code new-edge} proposal seeded for reject-without-reason tests. */
    private static final String SEEDED_PENDING_EDGE_PROPOSAL_ID = "cccccccc-0002-0000-0000-000000000002";

    /** UUID of the HUMAN_CURATED edge seeded for confirm/delete flow tests. */
    private static final String SEEDED_EDGE_ID = "bbbbbbbb-0001-0000-0000-000000000001";

    /** UUID of the seed node used for merge-self per-op error tests. */
    private static final String SEEDED_NODE_ID = "aaaaaaaa-0001-0000-0000-000000000001";

    /** UUID of the pending new-node proposal whose source_page is on kg_excluded_pages. */
    private static final String SEEDED_EXCLUDED_PAGE_PROPOSAL_ID = "dddddddd-0001-0000-0000-000000000001";

    /** UUID of the pending new-node proposal whose proposed name collides with an existing seed node. */
    private static final String SEEDED_NODE_EXISTS_PROPOSAL_ID = "dddddddd-0002-0000-0000-000000000002";

    /** UUID of the pending new-edge proposal whose triple is already in kg_rejections. */
    private static final String SEEDED_EDGE_REJECTED_PROPOSAL_ID = "dddddddd-0003-0000-0000-000000000003";

    /** Source-node UUID used by curate_edges upsert and delete_and_reject tests. */
    private static final String SEEDED_UPSERT_SRC_NODE_ID = "dddddddd-1001-0000-0000-000000000001";

    /** Target-node UUID used by curate_edges upsert and delete_and_reject tests. */
    private static final String SEEDED_UPSERT_TGT_NODE_ID = "dddddddd-1002-0000-0000-000000000002";

    /** Source-node UUID used by curate_nodes merge happy-path coverage. */
    private static final String SEEDED_MERGE_SRC_NODE_ID = "dddddddd-2001-0000-0000-000000000001";

    /** Target-node UUID used by curate_nodes merge happy-path coverage. */
    private static final String SEEDED_MERGE_TGT_NODE_ID = "dddddddd-2002-0000-0000-000000000002";

    /** UUID of the seed node used by curate_nodes delete happy-path coverage. */
    private static final String SEEDED_DELETABLE_NODE_ID = "dddddddd-3001-0000-0000-000000000001";

    /** UUID of the already-approved proposal used by the §6 re-review guard IT coverage. */
    private static final String SEEDED_ALREADY_APPROVED_PROPOSAL_ID = "eeeeeeee-0001-0000-0000-000000000001";

    // -----------------------------------------------------------------
    // Visibility IT fixture IDs — match it-test-seed.sql exactly.
    // Used by KgCurationVisibilityIT to verify admin-bypass on read paths.
    // -----------------------------------------------------------------

    /** UUID of the node seeded on KgVisibilityExcludedPage (on kg_excluded_pages). */
    public static final String SEEDED_VISIBILITY_EXCLUDED_NODE_ID = "ffffffff-0001-0000-0000-000000000001";

    /** UUID of the node seeded on KgVisibilityAllowedPage (not excluded). */
    public static final String SEEDED_VISIBILITY_ALLOWED_NODE_ID  = "ffffffff-0002-0000-0000-000000000002";

    /** Name of the node on the excluded source page. */
    public static final String SEEDED_VISIBILITY_EXCLUDED_NAME    = "KgVisibilityExcludedNode";

    /** Name of the node on the allowed source page. */
    public static final String SEEDED_VISIBILITY_ALLOWED_NAME     = "KgVisibilityAllowedNode";

    // -----------------------------------------------------------------

    protected static McpTestClient mcp;

    @BeforeAll
    public static void setUpMcpClient() {
        mcp = McpTestClient.create();
    }

    @AfterAll
    @SuppressWarnings( "PMD.NonThreadSafeSingleton" ) // JUnit 5 runs @AfterAll after parallel tests finish; no concurrent access at teardown.
    public static void tearDownMcpClient() {
        if ( mcp != null ) {
            mcp.close();
            mcp = null;
        }
    }

    protected static String uniquePageName( final String prefix ) {
        return prefix + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
    }

    // -----------------------------------------------------------------
    // KG curation seed accessors
    // -----------------------------------------------------------------

    public static String seededPendingNodeProposalId() {
        return SEEDED_PENDING_NODE_PROPOSAL_ID;
    }

    public static String seededPendingEdgeProposalId() {
        return SEEDED_PENDING_EDGE_PROPOSAL_ID;
    }

    public static String seededEdgeId() {
        return SEEDED_EDGE_ID;
    }

    public static String seededNodeId() {
        return SEEDED_NODE_ID;
    }

    public static String seededExcludedPageProposalId() {
        return SEEDED_EXCLUDED_PAGE_PROPOSAL_ID;
    }

    public static String seededNodeExistsProposalId() {
        return SEEDED_NODE_EXISTS_PROPOSAL_ID;
    }

    public static String seededEdgeRejectedProposalId() {
        return SEEDED_EDGE_REJECTED_PROPOSAL_ID;
    }

    public static String seededUpsertSrcNodeId() {
        return SEEDED_UPSERT_SRC_NODE_ID;
    }

    public static String seededUpsertTgtNodeId() {
        return SEEDED_UPSERT_TGT_NODE_ID;
    }

    public static String seededMergeSrcNodeId() {
        return SEEDED_MERGE_SRC_NODE_ID;
    }

    public static String seededMergeTgtNodeId() {
        return SEEDED_MERGE_TGT_NODE_ID;
    }

    public static String seededDeletableNodeId() {
        return SEEDED_DELETABLE_NODE_ID;
    }

    public static String seededAlreadyApprovedProposalId() {
        return SEEDED_ALREADY_APPROVED_PROPOSAL_ID;
    }

    public static String seededVisibilityExcludedNodeId() {
        return SEEDED_VISIBILITY_EXCLUDED_NODE_ID;
    }

    public static String seededVisibilityAllowedNodeId() {
        return SEEDED_VISIBILITY_ALLOWED_NODE_ID;
    }

    // -----------------------------------------------------------------
    // Cargo Tomcat log helpers
    // The SecurityLog appender in wikantik-custom.properties writes MCP
    // audit lines to a per-module security log at
    // target/test-classes/security-${it-wikantik.context}.log, NOT to
    // the Cargo stdout capture (target/tomcat.log). The parent IT pom
    // injects the resolved path as the "it.security.log" system property.
    // The legacy "cargo.tomcat.log" override is still honoured for CI
    // environments that route everything to one file.
    // -----------------------------------------------------------------

    public static Path catalinaOutPath() {
        final String securityLog = System.getProperty( "it.security.log" );
        if ( securityLog != null && !securityLog.isBlank() ) {
            return Path.of( securityLog );
        }
        return Path.of( System.getProperty( "cargo.tomcat.log",
                "target/tomcat.log" ) );
    }

    public static String readCatalinaOutSince( final long offsetBytes ) throws IOException {
        final Path p = catalinaOutPath();
        if ( !p.toFile().exists() ) {
            return "";
        }
        try ( final RandomAccessFile raf = new RandomAccessFile( p.toFile(), "r" ) ) {
            final long len = raf.length();
            if ( offsetBytes >= len ) {
                return "";
            }
            raf.seek( offsetBytes );
            final byte[] buf = new byte[ (int) ( len - offsetBytes ) ];
            raf.readFully( buf );
            return new String( buf, StandardCharsets.UTF_8 );
        }
    }
}
