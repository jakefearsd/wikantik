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
package com.wikantik.mcp.tools;

import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the audit-time cluster-slug rule to the save-time one.
 *
 * <p>Two places decide whether a {@code cluster:} value is well formed: the schema's
 * {@link FrontmatterSchema#CLUSTER_SLUG_PATTERN}, enforced when a page is saved, and
 * {@code PageChecks.ClusterPresentCheck}, which backs the {@code verify_pages} audit and
 * {@code preview_structured_data}. They used to be two independently written regexes that
 * differed in exactly one character — the schema ends {@code (...)?} (at most one
 * {@code parent/sub} level) while the check ended {@code (...)*} (unlimited depth).
 *
 * <p>So {@code machine-learning/ops/deep} warned on every save yet came back clean from
 * the audit that exists to find exactly that kind of page. CLAUDE.md is explicit that the
 * depth limit "lives only in CLUSTER_SLUG_PATTERN"; the second regex made that untrue.
 * The check now compiles the schema constant, so there is one rule and the audit cannot
 * disagree with the validator again.
 */
class ClusterSlugParityTest {

    /** The save-time authority, used here to derive what the audit must agree with. */
    private static final Pattern SCHEMA = Pattern.compile( FrontmatterSchema.CLUSTER_SLUG_PATTERN );

    private static PageCheckContext ctx( final String cluster ) {
        return new PageCheckContext( "TestPage", Map.of( "cluster", cluster ), "", null, null );
    }

    private static boolean auditFlagsIt( final String cluster ) {
        return new PageChecks.ClusterPresentCheck().check( ctx( cluster ) ).stream()
            .anyMatch( r -> "cluster_not_kebab".equals( r.issue() ) );
    }

    /**
     * The regression this class exists for. Three segments is one level too deep; the save
     * path says so, and before this fix the audit did not.
     */
    @Test
    void subClusterDeeperThanOneLevel_isFlaggedByTheAuditToo() {
        assertTrue( auditFlagsIt( "machine-learning/ops/deep" ),
            "A cluster deeper than one parent/sub level warns at save time, so the audit "
          + "must report it too — otherwise verify_pages gives a clean bill of health to a "
          + "page that cannot be saved without a warning." );
    }

    @Test
    void singleSubClusterLevel_isAcceptedByBoth() {
        assertTrue( SCHEMA.matcher( "retrieval/hybrid" ).matches(), "Baseline: the schema allows one level." );
        assertEquals( false, auditFlagsIt( "retrieval/hybrid" ),
            "One parent/sub level is legal and must not be flagged." );
    }

    /**
     * The general invariant, so any future edit to either side has to move both. Every value
     * the schema rejects must be flagged by the audit, and every value it accepts must not be.
     */
    @Test
    void auditAgreesWithTheSchemaOnEveryValue() {
        final List< String > values = List.of(
            "ai",
            "machine-learning",
            "retrieval/hybrid",
            "machine-learning/ops",
            "machine-learning/ops/deep",   // too deep
            "a/b/c/d",                     // far too deep
            "Hybrid Retrieval",            // spaces + capitals
            "Hybrid-Retrieval",            // capitals
            "hybrid_retrieval",            // underscore
            "-leading-hyphen",
            "trailing-hyphen-",
            "double--hyphen" );

        for ( final String cluster : values ) {
            final boolean schemaRejects = !SCHEMA.matcher( cluster ).matches();
            assertEquals( schemaRejects, auditFlagsIt( cluster ),
                "Audit and save-time validator disagree about cluster '" + cluster + "': "
              + "schema " + ( schemaRejects ? "rejects" : "accepts" ) + " it but the audit "
              + ( schemaRejects ? "stayed silent" : "flagged it" ) + "." );
        }
    }

    /**
     * {@code PageChecksTest} and {@code VerifyPagesToolTest} both assert on the wording, and
     * the phrase is what tells a curator what to actually do. Kept explicit so a future
     * rewording is a deliberate act.
     */
    @Test
    void rejectionMessageStillNamesKebabCaseAndTheOffendingValue() {
        final PageCheckResult result = new PageChecks.ClusterPresentCheck()
            .check( ctx( "machine-learning/ops/deep" ) ).get( 0 );

        assertEquals( "cluster_not_kebab", result.issue() );
        assertTrue( result.detail().contains( "kebab-case" ),
            "Existing tests and curators rely on this phrase: " + result.detail() );
        assertTrue( result.detail().contains( "machine-learning/ops/deep" ),
            "The message must quote the offending value: " + result.detail() );
    }
}
