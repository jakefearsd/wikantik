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
package com.wikantik.api;

import com.wikantik.api.bundle.BundleCoverage;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.comments.Comment;
import com.wikantik.api.comments.CommentThread;
import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.GoldSection;
import com.wikantik.api.exceptions.WikiI18nException;
import com.wikantik.api.frontmatter.schema.FieldSpec;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.knowledge.PageListFilter;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.PageGraphSnapshot;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.Verification;
import com.wikantik.api.pagegraph.Audience;
import com.wikantik.api.pages.PageSorter;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.parser.InlineLinkMatcher;
import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.api.querylog.QueryLogQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundaries and normalisation of the API value types and small helpers: the limits where a value is clamped or
 * refused, the defaults substituted for absent values, and the equality contracts.
 */
class ValueTypeBoundaryTest {

    // ---------------------------------------------------------------- limits

    @ParameterizedTest
    @CsvSource( { "0,true", "1,true", "200,true", "201,false", "-1,false" } )
    void pageListFilterLimitMustBeWithinZeroAndTwoHundred( final int limit, final boolean ok ) {
        if ( ok ) {
            assertEquals( limit, new PageListFilter( null, null, null, null, null, null, limit, 0 ).limit() );
        } else {
            assertThrows( IllegalArgumentException.class, () -> new PageListFilter( null, null, null, null, null, null, limit, 0 ) );
        }
    }

    @Test
    void pageListFilterOffsetMustNotBeNegative() {
        assertEquals( 0, new PageListFilter( null, null, null, null, null, null, 10, 0 ).offset() );
        assertThrows( IllegalArgumentException.class, () -> new PageListFilter( null, null, null, null, null, null, 10, -1 ) );
    }

    @ParameterizedTest
    @CsvSource( { "-5,100", "0,100", "1,1", "999,999", "1000,1000", "1001,1000", "5000,1000" } )
    void structuralFilterLimitIsClampedIntoOneToAThousandWithAHundredDefault( final int in, final int expected ) {
        assertEquals( expected, new StructuralFilter( null, null, null, null, in, null ).limit() );
    }

    @Test
    void structuralFilterTagsAreNormalisedDeduplicatedAndEmptiesDropped() {
        final List< String > withNull = new ArrayList<>( List.of( "Foo", "foo", "#", "", "Bar/Baz" ) );
        withNull.add( null );
        assertEquals( List.of( "foo", "bar-baz" ), new StructuralFilter( null, null, withNull, null, 10, null ).tags() );
    }

    @ParameterizedTest
    @CsvSource( { "-3,50", "0,50", "1,1", "2,2", "50,50", "500,500" } )
    void queryLogQueryLimitFallsBackToFiftyOnlyBelowOne( final int in, final int expected ) {
        assertEquals( expected, new QueryLogQuery( Instant.EPOCH, null, null, null, 1, in ).limit() );
    }

    @ParameterizedTest
    @CsvSource( { "-1,1", "0,1", "1,1", "2,2", "9,9" } )
    void queryLogQueryMinOccurrencesFloorsAtOne( final int in, final int expected ) {
        assertEquals( expected, new QueryLogQuery( Instant.EPOCH, null, null, null, in, 10 ).minOccurrences() );
    }

    @Test
    void queryLogQueryRequiresASinceInstant() {
        assertThrows( IllegalArgumentException.class, () -> new QueryLogQuery( null, null, null, null, 1, 10 ) );
    }

    // ---------------------------------------------------------------- cluster paths

    @ParameterizedTest
    @CsvSource( value = { "a,a,true", "a/b,a,true", "a/b/c,a,true", "ab,a,false", "a,a/b,false", "abc,abd,false",
            "a/b,a/c,false", "a,ab,false", "'',a,false" } )
    void selfOrDescendantNeverMatchesASiblingSharingAPrefix( final String candidate, final String ancestor, final boolean expected ) {
        assertEquals( expected, ClusterPath.isSelfOrDescendant( candidate, ancestor ) );
    }

    @Test
    void selfOrDescendantOfNullMatchesNothing() {
        assertFalse( ClusterPath.isSelfOrDescendant( null, "a" ) );
        assertFalse( ClusterPath.isSelfOrDescendant( "a", null ) );
    }

    @ParameterizedTest
    @CsvSource( value = { "a/b,a", "a/b/c,a/b", "a,", "'',", "/b," }, nullValues = "" )
    void parentIsTheTextBeforeTheLastSeparatorOrNull( final String path, final String expected ) {
        assertEquals( expected, ClusterPath.parent( path ) );
    }

    @Test
    void parentOfNullIsNull() {
        assertNull( ClusterPath.parent( null ) );
    }

    // ---------------------------------------------------------------- wikilink refs

    private static WikiLinkSyntax.WikiLinkRef ref( final String target, final String alias ) {
        return new WikiLinkSyntax.WikiLinkRef( 0, 10, false, target, null, alias, 2, 2 + target.length() );
    }

    @ParameterizedTest
    @CsvSource( value = { "Owner/file.png,true,Owner,file.png", "Page,false,Page,Page", "/lead,false,/lead,/lead",
            "a/b/c,true,a,b/c", "trail/,true,trail,''" } )
    void attachmentTargetsSplitAtTheFirstNonLeadingSlash( final String target, final boolean attachment, final String page,
                                                          final String file ) {
        final WikiLinkSyntax.WikiLinkRef r = ref( target, null );
        assertEquals( attachment, r.isAttachment() );
        assertEquals( page, r.pageName() );
        assertEquals( file, r.fileName() );
    }

    @Test
    void aRefWithNoAliasHasNoSize() {
        assertNull( ref( "Owner/pic.png", null ).size() );
        assertNull( ref( "Owner/pic.png", "caption" ).size() );
    }

    // ---------------------------------------------------------------- link matcher

    @Test
    void theMatcherNeedsAClosingBracketFollowedByAParenthesisedTarget() {
        assertFalse( new InlineLinkMatcher( "[a]" ).find() );
        assertFalse( new InlineLinkMatcher( "[a](" ).find() );
        assertFalse( new InlineLinkMatcher( "[a](b" ).find() );
        assertFalse( new InlineLinkMatcher( "[a] (b)" ).find() );
        final InlineLinkMatcher m = new InlineLinkMatcher( "x [a](b) y" );
        assertTrue( m.find() );
        assertEquals( 2, m.start() );
        assertEquals( 8, m.end() );
        assertEquals( "a", m.text() );
        assertEquals( "b", m.target() );
        assertFalse( m.find() );
    }

    @Test
    void theMatcherRefusesToReadAMatchBeforeOneIsFound() {
        final InlineLinkMatcher m = new InlineLinkMatcher( "[a](b)" );
        assertThrows( IllegalStateException.class, m::start );
        assertThrows( IllegalStateException.class, m::end );
        assertThrows( IllegalStateException.class, m::text );
        assertThrows( IllegalStateException.class, m::target );
        assertTrue( m.find() );
        assertFalse( m.find() );
        assertThrows( IllegalStateException.class, m::end );
        assertThrows( IllegalStateException.class, m::target );
    }

    // ---------------------------------------------------------------- records and defaults

    @Test
    void versionConflictForAGonePageSaysSoAndIsNotAHashConflict() {
        final VersionConflictException gone = VersionConflictException.pageGone( 7 );
        assertTrue( gone.isPageGone() );
        assertEquals( VersionConflictException.PAGE_GONE, gone.getMessage() );
        assertEquals( 0, gone.getCurrentVersion() );
        assertEquals( 7, gone.getExpectedVersion() );
        assertFalse( gone.isHashConflict() );

        final VersionConflictException stale = new VersionConflictException( "Doc", 4, 3, false );
        assertFalse( stale.isPageGone() );
        assertTrue( stale.getMessage().contains( "Doc" ) && stale.getMessage().contains( "4" ) && stale.getMessage().contains( "3" ) );
        assertTrue( new VersionConflictException( "Doc", -1, -1, true ).isHashConflict() );
    }

    @Test
    void aThreadIsActionableOnlyWhenAHumanWhoCouldEditWroteACommentInIt() {
        assertTrue( thread( comment( false, true ) ).actionable() );
        assertFalse( thread( comment( true, true ) ).actionable(), "an agent's comment is not a human request" );
        assertFalse( thread( comment( false, false ) ).actionable(), "a reader without edit rights cannot direct an agent" );
        assertFalse( thread( comment( true, false ) ).actionable() );
        assertTrue( thread( comment( true, true ), comment( false, true ) ).actionable() );
        assertFalse( thread().actionable() );
        assertFalse( thread( ( List< Comment > ) null ).actionable() );
    }

    private static Comment comment( final boolean viaAgent, final boolean authorCouldEdit ) {
        return new Comment( UUID.randomUUID(), UUID.randomUUID(), "a", "b", Instant.EPOCH, null, viaAgent, authorCouldEdit );
    }

    private static CommentThread thread( final Comment... comments ) {
        return thread( List.of( comments ) );
    }

    private static CommentThread thread( final List< Comment > comments ) {
        return new CommentThread( UUID.randomUUID(), "P", null, CommentThread.OPEN, "u", Instant.EPOCH, null, null, comments, false );
    }

    @Test
    void verificationDefaultsItsAbsentPartsAndTreatsABlankVerifierAsNone() {
        final Verification v = new Verification( null, "  ", null, null );
        assertEquals( Confidence.PROVISIONAL, v.confidence() );
        assertEquals( Audience.HUMANS_AND_AGENTS, v.audience() );
        assertNull( v.verifiedBy() );
        assertEquals( "alice", new Verification( null, "alice", Confidence.AUTHORITATIVE, Audience.AGENTS ).verifiedBy() );
        assertEquals( Confidence.AUTHORITATIVE, new Verification( null, "alice", Confidence.AUTHORITATIVE, Audience.AGENTS ).confidence() );
        assertEquals( Audience.AGENTS, new Verification( null, "alice", Confidence.AUTHORITATIVE, Audience.AGENTS ).audience() );
        assertNull( new Verification( null, null, null, null ).verifiedBy() );
        assertNull( new Verification( null, "", null, null ).verifiedBy(), "an empty verifier is also none" );
    }

    @Test
    void bundleSectionNeedsASlugAndDefaultsItsCollections() {
        final CitationHandle c = new CitationHandle( "id", 1, null, null, "sha" );
        final com.wikantik.api.bundle.BundleSection s = new com.wikantik.api.bundle.BundleSection( "id", "slug", null, null, 0.5, c, "u" );
        assertEquals( List.of(), s.headingPath() );
        assertEquals( "", s.text() );
        assertThrows( IllegalArgumentException.class,
                () -> new com.wikantik.api.bundle.BundleSection( "id", null, null, "t", 0.5, c, "u" ) );
        assertThrows( IllegalArgumentException.class,
                () -> new com.wikantik.api.bundle.BundleSection( "id", " ", null, "t", 0.5, c, "u" ) );
    }

    @Test
    void citationHandleNeedsACanonicalIdAndDefaultsItsCollections() {
        final CitationHandle c = new CitationHandle( "id", 1, null, null, "sha" );
        assertEquals( List.of(), c.headingPath() );
        assertEquals( "", c.span() );
        assertThrows( IllegalArgumentException.class, () -> new CitationHandle( null, 1, null, null, "s" ) );
        assertThrows( IllegalArgumentException.class, () -> new CitationHandle( "  ", 1, null, null, "s" ) );
    }

    @Test
    void contextBundleDefaultsItsSectionsAndCoverage() {
        final com.wikantik.api.bundle.ContextBundle b = new com.wikantik.api.bundle.ContextBundle( "q", null, null );
        assertEquals( List.of(), b.sections() );
        assertEquals( BundleCoverage.empty(), b.coverage() );
        assertThrows( IllegalArgumentException.class, () -> new com.wikantik.api.bundle.ContextBundle( null, List.of(), null ) );
    }

    @Test
    void evalBundleSectionNeedsAnIdAndTextButDefaultsItsPath() {
        assertEquals( List.of(), new com.wikantik.api.eval.BundleSection( "id", null, "t" ).headingPath() );
        assertThrows( IllegalArgumentException.class, () -> new com.wikantik.api.eval.BundleSection( null, null, "t" ) );
        assertThrows( IllegalArgumentException.class, () -> new com.wikantik.api.eval.BundleSection( " ", null, "t" ) );
        assertThrows( IllegalArgumentException.class, () -> new com.wikantik.api.eval.BundleSection( "id", null, null ) );
    }

    @Test
    void evalQuestionRefusesEachMissingPart() {
        final List< GoldSection > gold = List.of( new GoldSection( "id", List.of() ) );
        assertEquals( "q1", new BundleEvalQuestion( "q1", "text", BundleCategory.SIMILARITY, gold ).queryId() );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( null, "t", BundleCategory.SIMILARITY, gold ) );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( " ", "t", BundleCategory.SIMILARITY, gold ) );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( "q", null, BundleCategory.SIMILARITY, gold ) );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( "q", " ", BundleCategory.SIMILARITY, gold ) );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( "q", "t", null, gold ) );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( "q", "t", BundleCategory.SIMILARITY, null ) );
        assertThrows( IllegalArgumentException.class, () -> new BundleEvalQuestion( "q", "t", BundleCategory.SIMILARITY, List.of() ) );
    }

    @Test
    void fieldSpecDefaultsItsCollections() {
        final FieldSpec f = new FieldSpec( "k", "K", null, null, true, null, null, null, null );
        assertEquals( List.of(), f.canonicalValues() );
        assertEquals( Map.of(), f.suggestionMap() );
        final FieldSpec g = new FieldSpec( "k", "K", null, List.of( "a" ), true, null, null, null, Map.of( "x", "a" ) );
        assertEquals( List.of( "a" ), g.canonicalValues() );
        assertEquals( Map.of( "x", "a" ), g.suggestionMap() );
    }

    @Test
    void pageGraphSnapshotDefaultsItsNodeAndEdgeLists() {
        final PageGraphSnapshot s = new PageGraphSnapshot( "t", 0, 0, 5, null, null );
        assertEquals( List.of(), s.nodes() );
        assertEquals( List.of(), s.edges() );
    }

    @Test
    void i18nExceptionArgumentsAreDefensivelyCopiedAndNullSafe() {
        final Object[] given = { "a", 1 };
        final WikiI18nException e = new WikiI18nException( "key", given );
        given[ 0 ] = "changed";
        assertEquals( "a", e.getArgs()[ 0 ] );
        e.getArgs()[ 1 ] = 99;
        assertEquals( 1, e.getArgs()[ 1 ], "getArgs hands out a copy" );
        assertEquals( 0, new WikiI18nException( "key", ( Object[] ) null ).getArgs().length );
        assertEquals( 0, new WikiI18nException( "key" ).getArgs().length );
        assertEquals( "key", e.getMessage() );
    }

    // ---------------------------------------------------------------- warning sink

    @AfterEach
    void clearSink() {
        FrontmatterWarningSink.clear();
    }

    @Test
    void warningSinkIsKeyedByPageNameAndDrainRemovesOnlyThatPage() {
        final FieldViolation a = new FieldViolation( "f", Severity.WARNING, "c", "m", null );
        final FieldViolation b = new FieldViolation( "g", Severity.WARNING, "c", "m", null );
        FrontmatterWarningSink.put( "PageA", List.of( a ) );
        FrontmatterWarningSink.put( "PageB", List.of( b ) );
        assertEquals( List.of( a ), FrontmatterWarningSink.drain( "PageA" ) );
        assertEquals( List.of(), FrontmatterWarningSink.drain( "PageA" ), "drain removes" );
        assertEquals( List.of( b ), FrontmatterWarningSink.drain( "PageB" ), "the other page's warnings survived" );
    }

    @Test
    void warningSinkStoresANullPageNameUnderAStableKeyDistinctFromRealPages() {
        final FieldViolation a = new FieldViolation( "f", Severity.WARNING, "c", "m", null );
        FrontmatterWarningSink.put( null, List.of( a ) );
        assertEquals( List.of(), FrontmatterWarningSink.drain( "Real" ) );
        assertEquals( List.of( a ), FrontmatterWarningSink.drain( null ) );
        assertEquals( List.of(), FrontmatterWarningSink.drain( null ) );
    }

    @Test
    void warningSinkClearDropsEveryPage() {
        final FieldViolation a = new FieldViolation( "f", Severity.WARNING, "c", "m", null );
        FrontmatterWarningSink.put( "A", List.of( a ) );
        FrontmatterWarningSink.put( "B", List.of( a ) );
        FrontmatterWarningSink.clear();
        assertEquals( List.of(), FrontmatterWarningSink.drain( "A" ) );
        assertEquals( List.of(), FrontmatterWarningSink.drain( "B" ) );
    }

    // ---------------------------------------------------------------- page sorter equality

    @Test
    void pageSortersAreEqualWhenTheirComparatorsAreTheSameOrEqual() {
        final Comparator< String > natural = Comparator.naturalOrder();
        final PageSorter a = new PageSorter( natural );
        assertEquals( a, a );
        assertEquals( new PageSorter( natural ), a );
        assertEquals( new PageSorter( natural ).hashCode(), a.hashCode() );
        assertNotEquals( new PageSorter( Comparator.reverseOrder() ), a );
        assertNotEquals( null, a );
        assertNotEquals( "text", a );
        assertNotEquals( 0, a.hashCode(), "the hash is derived from the comparator, not constant" );
    }

    @Test
    void pageSortersWithEqualButDistinctComparatorsAreEqualWithTheSameHash() {
        final Comparator< String > one = new EqualComparator();
        final Comparator< String > two = new EqualComparator();
        assertNotSame( one, two );
        assertEquals( new PageSorter( one ), new PageSorter( two ) );
        assertEquals( new PageSorter( one ).hashCode(), new PageSorter( two ).hashCode() );
    }

    private static final class EqualComparator implements Comparator< String > {
        @Override public int compare( final String a, final String b ) { return a.compareTo( b ); }
        @Override public boolean equals( final Object o ) { return o instanceof EqualComparator; }
        @Override public int hashCode() { return 17; }
    }
}
