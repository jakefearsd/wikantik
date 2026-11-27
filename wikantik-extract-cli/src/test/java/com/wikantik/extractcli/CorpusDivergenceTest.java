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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusDivergenceTest {

    private static PageFacts page( final String slug, final String id, final String cluster, final String type ) {
        return new PageFacts( slug, id, cluster, type );
    }

    private static CorpusSnapshot snap( final String name, final PageFacts... pages ) {
        final java.util.Map< String, PageFacts > m = new java.util.HashMap<>();
        for ( final PageFacts p : pages ) {
            m.put( p.slug(), p );
        }
        return new CorpusSnapshot( name, m, List.of() );
    }

    @Test
    void nullListsBecomeEmptyAndCountAsNoFindings() {
        final CorpusDivergence d = new CorpusDivergence( null, null, null );
        assertEquals( List.of(), d.onlyLocal() );
        assertEquals( List.of(), d.onlyRemote() );
        assertEquals( List.of(), d.deltas() );
        assertTrue( d.isEmpty() );
        assertEquals( 0, d.findingCount() );
        assertEquals( "corpora agree: no divergence", d.render() );
    }

    @Test
    void findingCountSumsAllThreeKinds() {
        final CorpusDivergence d = new CorpusDivergence( List.of( "A", "B" ), List.of( "C" ),
                List.of( new FieldDelta( "D", "type", "x", "y" ), new FieldDelta( "D", "cluster", "p", "q" ),
                         new FieldDelta( "E", "type", null, "z" ) ) );
        assertEquals( 6, d.findingCount() );
        assertFalse( d.isEmpty() );
    }

    @Test
    void anyOneKindOfFindingMakesItNonEmpty() {
        assertFalse( new CorpusDivergence( List.of( "A" ), null, null ).isEmpty() );
        assertFalse( new CorpusDivergence( null, List.of( "A" ), null ).isEmpty() );
        assertFalse( new CorpusDivergence( null, null, List.of( new FieldDelta( "A", "type", "a", "b" ) ) ).isEmpty() );
    }

    @Test
    void renderListsEachFindingOnItsOwnLineInOrder() {
        final CorpusDivergence d = new CorpusDivergence( List.of( "A" ), List.of( "B" ),
                List.of( new FieldDelta( "C", "cluster", "x", null ) ) );
        assertEquals( "only-in-repo  A\nonly-in-prod  B\ndiffers       C.cluster: repo=x prod=null\n", d.render() );
    }

    @Test
    void theInputListsAreCopiedDefensively() {
        final java.util.List< String > src = new java.util.ArrayList<>( List.of( "A" ) );
        final CorpusDivergence d = new CorpusDivergence( src, null, null );
        src.add( "B" );
        assertEquals( List.of( "A" ), d.onlyLocal() );
    }

    @Test
    void diffReportsOnlySidesSortedAndEachDifferingField() {
        final CorpusSnapshot local = snap( "repo",
                page( "Zeta", "1", "c", "article" ), page( "Alpha", "2", "c", "article" ),
                page( "Both", "3", "c1", "hub" ) );
        final CorpusSnapshot remote = snap( "prod",
                page( "Both", "4", "c2", "article" ), page( "Yankee", "5", "c", "article" ),
                page( "Beta", "6", "c", "article" ) );

        final CorpusDivergence d = CorpusDiff.compare( local, remote );

        assertEquals( List.of( "Alpha", "Zeta" ), d.onlyLocal() );
        assertEquals( List.of( "Beta", "Yankee" ), d.onlyRemote() );
        assertEquals( List.of(
                new FieldDelta( "Both", "canonical_id", "3", "4" ),
                new FieldDelta( "Both", "cluster", "c1", "c2" ),
                new FieldDelta( "Both", "type", "hub", "article" ) ), d.deltas() );
    }

    @Test
    void diffTreatsNullAndValueAsDifferentButNullAndNullAsSame() {
        final CorpusSnapshot local = snap( "repo", page( "A", null, null, "article" ) );
        final CorpusSnapshot remote = snap( "prod", page( "A", null, "c", "article" ) );
        assertEquals( List.of( new FieldDelta( "A", "cluster", null, "c" ) ),
                      CorpusDiff.compare( local, remote ).deltas() );
        assertTrue( CorpusDiff.compare( local, local ).isEmpty() );
    }

    @Test
    void refusalNamesTheIncompleteSideCountAndFirstError() {
        final CorpusSnapshot partial = new CorpusSnapshot( "prod", Map.of(), List.of( "HTTP 502", "HTTP 503" ) );
        final IllegalStateException e = assertThrows( IllegalStateException.class,
                () -> CorpusDiff.compare( snap( "repo" ), partial ) );
        assertTrue( e.getMessage().contains( "'prod' snapshot is incomplete (2 page(s)" ), e.getMessage() );
        assertTrue( e.getMessage().contains( "First error: HTTP 502" ), e.getMessage() );
    }

    @Test
    void anIncompleteLocalSideIsRefusedToo() {
        final CorpusSnapshot partial = new CorpusSnapshot( "repo", Map.of(), List.of( "unreadable" ) );
        final IllegalStateException e = assertThrows( IllegalStateException.class,
                () -> CorpusDiff.compare( partial, snap( "prod" ) ) );
        assertTrue( e.getMessage().contains( "'repo'" ), e.getMessage() );
    }

    @Test
    void snapshotNullsNormaliseToEmptyAndCompleteMeansNoErrors() {
        final CorpusSnapshot s = new CorpusSnapshot( "x", null, null );
        assertEquals( Map.of(), s.pages() );
        assertEquals( List.of(), s.errors() );
        assertTrue( s.complete() );
        assertFalse( new CorpusSnapshot( "x", null, List.of( "e" ) ).complete() );
    }

    // ---- CLI exit codes and exact output ----

    private static String runCli( final CorpusSnapshot l, final CorpusSnapshot r, final boolean check, final int[] rc ) {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream( buf, true, StandardCharsets.UTF_8 );
        rc[ 0 ] = CorpusDivergenceCli.run( l, r, check, out );
        return buf.toString( StandardCharsets.UTF_8 );
    }

    @Test
    void cliPrintsTheReportThenASummaryLineAndExitsOneUnderCheck() {
        final int[] rc = new int[ 1 ];
        final String out = runCli(
                snap( "repo", page( "A", "1", "c", "article" ), page( "L", "2", "c", "article" ) ),
                snap( "prod", page( "A", "1", "d", "article" ), page( "R", "3", "c", "article" ) ), true, rc );
        assertEquals( 1, rc[ 0 ] );
        assertEquals( "only-in-repo  L\nonly-in-prod  R\ndiffers       A.cluster: repo=c prod=d\n\n"
                + "3 finding(s): 1 only-in-repo, 1 only-in-prod, 1 differing field(s)\n", out );
    }

    @Test
    void cliWithoutCheckPrintsTheSameSummaryButExitsZero() {
        final int[] rc = new int[ 1 ];
        final String out = runCli( snap( "repo", page( "L", "2", "c", "article" ) ), snap( "prod" ), false, rc );
        assertEquals( 0, rc[ 0 ] );
        assertTrue( out.endsWith( "1 finding(s): 1 only-in-repo, 0 only-in-prod, 0 differing field(s)\n" ), out );
    }

    @Test
    void cliAgreementPrintsOnlyTheAgreeLineAndNoSummary() {
        final int[] rc = new int[ 1 ];
        final String out = runCli( snap( "repo" ), snap( "prod" ), true, rc );
        assertEquals( 0, rc[ 0 ] );
        assertEquals( "corpora agree: no divergence\n", out );
    }

    @Test
    void cliRefusalIsExactlyOneLineStartingWithRefused() {
        final int[] rc = new int[ 1 ];
        final String out = runCli( snap( "repo" ), new CorpusSnapshot( "prod", Map.of(), List.of( "boom" ) ), false, rc );
        assertEquals( 2, rc[ 0 ] );
        assertTrue( out.startsWith( "REFUSED: refusing to compare" ), out );
        assertEquals( 1, out.lines().count(), out );
    }
}
