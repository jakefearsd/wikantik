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

    private ChunkExtractionPrefilter both() {
        return new ChunkExtractionPrefilter( /*enabled*/ true, /*dryRun*/ false,
            /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
    }

    @Test
    void disabledFlagAlwaysReturnsPassthrough() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ false, /*dryRun*/ false, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx = 1\n```", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "disabled", d.reason() );
    }

    @Test
    void pureFencedCodeBlockIsSkipped() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "```python\ndef foo():\n    return 1\n```", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "pure_code", d.reason() );
    }

    @Test
    void fenceWithProseAfterIsKept() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "```\ncode\n```\nThis paragraph mentions PostgreSQL.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void proseWithNoCapsIsSkipped() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "this is just lowercase prose with no proper nouns at all.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void proseWithRealProperNounIsKept() {
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "PostgreSQL stores rows in heap files.", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void twoLetterCapsLikeOfDoNotCountAsProperNouns() {
        // \b[A-Z]\w{2,}\b excludes "Of", "In", "On" — sentence-initial articles
        // shouldn't keep an otherwise-empty chunk alive.
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "Of all the things on offer, none stood out.", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void compoundCaseLikeIPadIsTreatedAsNoProperNoun() {
        // Documented v1 false-negative; iPad does not match \b[A-Z]\w{2,}\b
        // because it starts lowercase, not because of the post-capital class.
        // If the chunk has no other capitalised noun, it gets skipped.
        final ChunkExtractionPrefilter.Decision d = both().evaluate(
            "the iPad is a tablet", NO_HEADINGS );
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void skipPureCodeFlagOffPassesCodeThrough() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false, /*skipPureCode*/ false, /*skipNoProperNoun*/ true );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx\n```", NO_HEADINGS );
        // pure code -> caught by no_proper_noun instead since flag #1 is off
        assertFalse( d.shouldExtract() );
        assertEquals( "no_proper_noun", d.reason() );
    }

    @Test
    void bothSubFlagsOffNeverSkips() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ false, /*skipPureCode*/ false, /*skipNoProperNoun*/ false );
        final ChunkExtractionPrefilter.Decision d = f.evaluate( "```\nx\n```", NO_HEADINGS );
        assertTrue( d.shouldExtract() );
        assertEquals( "ok", d.reason() );
    }

    @Test
    void dryRunReturnsTrueButReportsSkipReason() {
        final ChunkExtractionPrefilter f = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
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
        assertTrue( both().isEnabled() );
        assertFalse( ChunkExtractionPrefilter.passthrough().isEnabled() );
        final ChunkExtractionPrefilter dry = new ChunkExtractionPrefilter(
            /*enabled*/ true, /*dryRun*/ true, /*skipPureCode*/ true, /*skipNoProperNoun*/ true );
        assertTrue( dry.isEnabled(), "dry-run is still 'enabled' from the wiring perspective" );
    }
}
