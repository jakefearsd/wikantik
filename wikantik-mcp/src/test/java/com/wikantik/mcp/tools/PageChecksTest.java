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

import com.wikantik.TestEngine;
import com.wikantik.mcp.tools.PageCheckResult.Severity;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PageChecksTest {

    private TestEngine engine;
    private PageManager pm;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        pm = engine.getManager( PageManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private PageCheckContext ctx( final Map< String, Object > metadata ) {
        return new PageCheckContext( "TestPage", metadata, "", null, pm );
    }

    private PageCheckContext ctx( final Map< String, Object > metadata, final String body ) {
        return new PageCheckContext( "TestPage", metadata, body, null, pm );
    }

    // ========== SummaryCheck ==========

    @Nested
    class SummaryCheckTests {

        @Test
        void passesWithGoodSummary() {
            final PageCheck check = new PageChecks.SummaryCheck( true );
            final var results = check.check( ctx( Map.of( "summary", "A good summary that is over fifty characters long for SEO" ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsMissingSummary() {
            final PageCheck check = new PageChecks.SummaryCheck( false );
            final var results = check.check( ctx( Map.of() ) );
            assertEquals( 1, results.size() );
            assertEquals( "missing_summary", results.get( 0 ).issue() );
            assertEquals( Severity.WARNING, results.get( 0 ).severity() );
        }

        @Test
        void flagsBlankSummary() {
            // This was a bug: old code used .isEmpty() which missed whitespace-only strings
            final PageCheck check = new PageChecks.SummaryCheck( false );
            final var results = check.check( ctx( Map.of( "summary", "   " ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "missing_summary", results.get( 0 ).issue() );
        }

        @Test
        void flagsShortSummaryWhenBoundsEnabled() {
            final PageCheck check = new PageChecks.SummaryCheck( true );
            final var results = check.check( ctx( Map.of( "summary", "Too short" ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "summary_too_short", results.get( 0 ).issue() );
        }

        @Test
        void flagsLongSummaryWhenBoundsEnabled() {
            final PageCheck check = new PageChecks.SummaryCheck( true );
            final String longSummary = "A".repeat( 161 );
            final var results = check.check( ctx( Map.of( "summary", longSummary ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "summary_too_long", results.get( 0 ).issue() );
        }

        @Test
        void skipsBoundsCheckWhenDisabled() {
            final PageCheck check = new PageChecks.SummaryCheck( false );
            final var results = check.check( ctx( Map.of( "summary", "Short" ) ) );
            // "Short" is < 50 chars but bounds checking is disabled
            assertTrue( results.isEmpty() );
        }

        @Test
        void handlesNonStringSummary() {
            // YAML might parse a numeric summary
            final PageCheck check = new PageChecks.SummaryCheck( true );
            final var results = check.check( ctx( Map.of( "summary", 42 ) ) );
            // "42" is 2 chars — should be flagged as too short
            assertEquals( 1, results.size() );
            assertEquals( "summary_too_short", results.get( 0 ).issue() );
        }
    }

    // ========== TagsCheck ==========

    @Nested
    class TagsCheckTests {

        @Test
        void passesWithTags() {
            final var results = new PageChecks.TagsCheck().check(
                    ctx( Map.of( "tags", List.of( "ai", "ml" ) ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsMissingTags() {
            final var results = new PageChecks.TagsCheck().check( ctx( Map.of() ) );
            assertEquals( 1, results.size() );
            assertEquals( "no_tags", results.get( 0 ).issue() );
        }

        @Test
        void flagsEmptyTagsList() {
            final var results = new PageChecks.TagsCheck().check(
                    ctx( Map.of( "tags", List.of() ) ) );
            assertEquals( 1, results.size() );
        }

        @Test
        void flagsNonListTags() {
            // A string value for tags should still trigger the check
            final var results = new PageChecks.TagsCheck().check(
                    ctx( Map.of( "tags", "just-a-string" ) ) );
            assertEquals( 1, results.size() );
        }
    }

    // ========== HubRelatedCheck ==========

    @Nested
    class HubRelatedCheckTests {

        @Test
        void skipsNonHubPages() {
            final var results = new PageChecks.HubRelatedCheck( false ).check(
                    ctx( Map.of( "type", "article" ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsHubWithNoRelated() {
            final var results = new PageChecks.HubRelatedCheck( false ).check(
                    ctx( Map.of( "type", "hub" ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "hub_empty_related", results.get( 0 ).issue() );
        }

        @Test
        void passesHubWithRelated() {
            final var results = new PageChecks.HubRelatedCheck( false ).check(
                    ctx( Map.of( "type", "hub", "related", List.of( "PageA" ) ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void verifyExistenceFlagsMissingPages() throws Exception {
            engine.saveText( "ExistingPage", "Content." );
            final var check = new PageChecks.HubRelatedCheck( true );
            final var results = check.check( ctx(
                    Map.of( "type", "hub", "related", List.of( "ExistingPage", "NonExistentPage" ) ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "hub_related_missing", results.get( 0 ).issue() );
            assertTrue( results.get( 0 ).detail().contains( "NonExistentPage" ) );
        }
    }

    // ========== DateCheck ==========

    @Nested
    class DateCheckTests {

        @Test
        void passesWithDate() {
            final var results = new PageChecks.DateCheck().check(
                    ctx( Map.of( "date", "2026-03-20" ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsMissingDate() {
            final var results = new PageChecks.DateCheck().check( ctx( Map.of() ) );
            assertEquals( 1, results.size() );
            assertEquals( "no_date", results.get( 0 ).issue() );
        }
    }

    // ========== ClusterTypeCheck ==========

    @Nested
    class ClusterTypeCheckTests {

        @Test
        void passesWithBothClusterAndType() {
            final var results = new PageChecks.ClusterTypeCheck().check(
                    ctx( Map.of( "cluster", "tech", "type", "article" ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsClusterWithoutType() {
            final var results = new PageChecks.ClusterTypeCheck().check(
                    ctx( Map.of( "cluster", "tech" ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "cluster_without_type", results.get( 0 ).issue() );
        }

        @Test
        void passesWithoutCluster() {
            final var results = new PageChecks.ClusterTypeCheck().check( ctx( Map.of() ) );
            assertTrue( results.isEmpty() );
        }
    }

    // ========== MetadataFieldsCheck ==========

    @Nested
    class MetadataFieldsCheckTests {

        @Test
        void passesWhenAllFieldsPresent() {
            final var check = new PageChecks.MetadataFieldsCheck( Set.of( "type", "tags" ) );
            final var results = check.check( ctx(
                    Map.of( "type", "article", "tags", List.of( "ai" ) ) ) );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsMissingFields() {
            final var check = new PageChecks.MetadataFieldsCheck( Set.of( "type", "tags", "summary" ) );
            final var results = check.check( ctx( Map.of( "type", "article" ) ) );
            assertEquals( 2, results.size() );
        }

        @Test
        void flagsBlankStringAsEmpty() {
            final var check = new PageChecks.MetadataFieldsCheck( Set.of( "summary" ) );
            final var results = check.check( ctx( Map.of( "summary", "   " ) ) );
            assertEquals( 1, results.size() );
            assertEquals( "missing_summary", results.get( 0 ).issue() );
        }

        @Test
        void flagsEmptyListAsEmpty() {
            final var check = new PageChecks.MetadataFieldsCheck( Set.of( "tags" ) );
            final var results = check.check( ctx( Map.of( "tags", List.of() ) ) );
            assertEquals( 1, results.size() );
        }

        @Test
        void generatesAutoFixForStatus() {
            final var check = new PageChecks.MetadataFieldsCheck(
                    Set.of( "status" ), true, "tech" );
            final var results = check.check( ctx( Map.of() ) );
            assertEquals( 1, results.size() );
            assertNotNull( results.get( 0 ).autoFix() );
            assertEquals( "set_metadata", results.get( 0 ).autoFix().get( "action" ) );
            assertEquals( "active", results.get( 0 ).autoFix().get( "proposedValue" ) );
        }
    }

    // ========== StalenessCheck ==========

    @Nested
    class StalenessCheckTests {

        @Test
        void passesRecentPage() throws Exception {
            engine.saveText( "FreshPage", "---\nstatus: active\n---\nBody." );
            final var page = pm.getPage( "FreshPage" );
            final var ctx = new PageCheckContext( "FreshPage",
                    Map.of( "status", "active" ), "", page, pm );

            final var check = new PageChecks.StalenessCheck( 90, 30 );
            final var results = check.check( ctx );
            assertTrue( results.isEmpty() );
        }

        @Test
        void flagsStalePage() throws Exception {
            engine.saveText( "OldPage", "---\nstatus: active\n---\nBody." );
            final var page = pm.getPage( "OldPage" );
            // Simulate a page modified 100 days ago by using a fixed "now" in the future
            final Instant futureNow = Instant.now().plus( 100, ChronoUnit.DAYS );
            final var ctx = new PageCheckContext( "OldPage",
                    Map.of( "status", "active" ), "", page, pm );

            final var check = new PageChecks.StalenessCheck( 90, 30, futureNow );
            final var results = check.check( ctx );
            assertEquals( 1, results.size() );
            assertEquals( "stale", results.get( 0 ).issue() );
            assertEquals( Severity.SUGGESTION, results.get( 0 ).severity() );
        }

        @Test
        void flagsStalledDraft() throws Exception {
            engine.saveText( "DraftPage", "---\nstatus: draft\n---\nBody." );
            final var page = pm.getPage( "DraftPage" );
            final Instant futureNow = Instant.now().plus( 45, ChronoUnit.DAYS );
            final var ctx = new PageCheckContext( "DraftPage",
                    Map.of( "status", "draft" ), "", page, pm );

            final var check = new PageChecks.StalenessCheck( 90, 30, futureNow );
            final var results = check.check( ctx );
            assertEquals( 1, results.size() );
            assertEquals( "stalled_draft", results.get( 0 ).issue() );
        }

        @Test
        void exemptArchivedPages() throws Exception {
            engine.saveText( "ArchivedPage", "---\nstatus: archived\n---\nBody." );
            final var page = pm.getPage( "ArchivedPage" );
            final Instant futureNow = Instant.now().plus( 365, ChronoUnit.DAYS );
            final var ctx = new PageCheckContext( "ArchivedPage",
                    Map.of( "status", "archived" ), "", page, pm );

            final var check = new PageChecks.StalenessCheck( 90, 30, futureNow );
            final var results = check.check( ctx );
            assertTrue( results.isEmpty() );
        }
    }
}
