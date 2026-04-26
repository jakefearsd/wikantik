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
package com.wikantik.knowledge.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkExtractionPrefilterTest {

    private static final List< String > NO_HEADINGS = List.of();

    /** Helper: prefilter with code + proper-noun rules on, length rule off.
     *  Used for tests that exercise only the regex-based predicates. */
    private ChunkExtractionPrefilter codeAndProper() {
        return new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ false, /*minTokens*/ 0 );
    }

    /** Helper: all three predicates on, with the supplied min-token threshold. */
    private ChunkExtractionPrefilter all( final int minTokens ) {
        return new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ true, minTokens );
    }

    @Test
    void disabledFlagAlwaysReturnsPassthrough() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ false, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ true, /*minTokens*/ 20 );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx = 1\n```", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "disabled", d.reason() );
    }

    @Test
    void pureFencedCodeBlockIsSkipped() {
        final ChunkExtractionPrefilter.Decision d = codeAndProper().evaluate(
            "```python\ndef foo():\n    return 1\n```", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "pure_code", d.reason() );
    }

    @Test
    void fenceWithProseAfterIsKept() {
        final ChunkExtractionPrefilter.Decision d = codeAndProper().evaluate(
            "```\ncode\n```\nThis paragraph mentions PostgreSQL.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void proseWithNoCapsIsSkipped() {
        final ChunkExtractionPrefilter.Decision d = codeAndProper().evaluate(
            "this is just lowercase prose with no proper nouns at all.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void proseWithRealProperNounIsKept() {
        final ChunkExtractionPrefilter.Decision d = codeAndProper().evaluate(
            "PostgreSQL stores rows in heap files.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void twoLetterCapsLikeOfDoNotCountAsProperNouns() {
        // \b\w*[A-Z]\w{2,}\b excludes "Of", "In", "On" — sentence-initial articles
        // shouldn't keep an otherwise-empty chunk alive.
        final ChunkExtractionPrefilter.Decision d = codeAndProper().evaluate(
            "Of all the things on offer, none stood out.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void compoundCaseLikeIPadIsNowMatched() {
        // The broadened \b\w*[A-Z]\w{2,}\b regex catches mixed-case names that
        // start lowercase: iPad, gRPC, eBay, myAPI. Previously these were
        // documented v1 false-negatives; the broader pattern fixes that.
        assertTrue( codeAndProper().evaluate( "the iPad is a tablet from Apple",
            NO_HEADINGS ).shouldExtract() );
        assertTrue( codeAndProper().evaluate( "uses gRPC for transport",
            NO_HEADINGS ).shouldExtract() );
        assertTrue( codeAndProper().evaluate( "the eBay marketplace listings",
            NO_HEADINGS ).shouldExtract() );
    }

    @Test
    void pureLowercaseToolsStillRejected() {
        // Tools like kubectl, npm, psql have no capital — the regex still
        // skips them. (This is intentional: they're not proper nouns.)
        final ChunkExtractionPrefilter.Decision d = codeAndProper().evaluate(
            "run kubectl apply with the npm and psql plugins", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void skipPureCodeFlagOffPassesCodeThrough() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ false, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ false, /*minTokens*/ 0 );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx\n```", NO_HEADINGS );
        // pure code -> caught by no_proper_noun instead since flag #1 is off
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void allSubFlagsOffNeverSkips() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ false, /*skipNoProperNoun*/ false,
            /*skipTooShort*/ false, /*minTokens*/ 999 );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx\n```", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void dryRunReturnsTrueButReportsSkipReason() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ false, /*minTokens*/ 0 );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "lower case only", NO_HEADINGS );
        assertTrue( d.shouldExtract(), "dry-run never blocks extraction" );
        assertEquals( "dry_run:no_proper_noun", d.reason() );
    }

    @Test
    void passthroughFactoryShortcutAlwaysExtracts() {
        final ChunkExtractionPrefilter f = ChunkExtractionPrefilter.passthrough();
        assertTrue( f.evaluate( "```\nx\n```", NO_HEADINGS ).shouldExtract() );
        assertTrue( f.evaluate( "lower case only", NO_HEADINGS ).shouldExtract() );
        assertFalse( f.isEnabled() );
    }

    @Test
    void isEnabledReflectsMasterFlag() {
        assertTrue( codeAndProper().isEnabled() );
        assertFalse( ChunkExtractionPrefilter.passthrough().isEnabled() );
        final ChunkExtractionPrefilter dry = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ false, /*minTokens*/ 0 );
        assertTrue( dry.isEnabled(), "dry-run is still 'enabled' from the wiring perspective" );
    }

    @Test
    void shortChunkWithProperNounIsSkippedAsTooShort() {
        // "PostgreSQL is fast." — 19 chars → ceil(19/4) = 5 tokens. With
        // min=20 and a proper noun present, the most-specific reason is
        // too_short (the chunk passes the other two predicates).
        final ChunkExtractionPrefilter.Decision d = all( 20 ).evaluate(
            "PostgreSQL is fast.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "too_short", d.reason() );
    }

    @Test
    void shortChunkWithNoProperNounReportsNoProperNounNotTooShort() {
        // Predicate ordering: no_proper_noun fires before too_short, so a
        // chunk that fails both gets the more-specific diagnostic.
        final ChunkExtractionPrefilter.Decision d = all( 20 ).evaluate(
            "tiny lowercase only.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void shortPureCodeChunkReportsPureCodeNotTooShort() {
        // pure_code fires before too_short for the same reason.
        final ChunkExtractionPrefilter.Decision d = all( 20 ).evaluate(
            "```\nx\n```", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "pure_code", d.reason() );
    }

    @Test
    void chunkAtExactlyMinTokensIsKept() {
        // 80 chars → ceil(80/4) = 20 tokens, exactly at threshold; not below.
        final String text = "PostgreSQL is a relational database used widely in production today.aaaaaaaaaaaa";
        assertEquals( 80, text.length(), "fixture must be exactly 80 chars" );
        final ChunkExtractionPrefilter.Decision d = all( 20 ).evaluate( text, NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void minTokensZeroDisablesTheTooShortRule() {
        // Even with skip_too_short=true, a min of 0 means no chunk is "below"
        // the threshold — the rule never fires.
        final ChunkExtractionPrefilter.Decision d = all( 0 ).evaluate(
            "PostgreSQL.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void skipTooShortFlagOffIgnoresMinTokens() {
        // skip_too_short=false: even tiny chunks with proper nouns pass.
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ false, /*minTokens*/ 999 );
        assertTrue( f.evaluate( "PostgreSQL.", NO_HEADINGS ).shouldExtract() );
    }

    @Test
    void dryRunReportsTooShortReasonWhenLengthFires() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true,
            /*skipTooShort*/ true, /*minTokens*/ 50 );
        final ChunkExtractionPrefilter.Decision d = f.evaluate(
            "PostgreSQL is fast.", NO_HEADINGS );
        assertTrue( d.shouldExtract(), "dry-run never blocks" );
        assertEquals( "dry_run:too_short", d.reason() );
    }
}
