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

package com.wikantik.seo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentChangeRuleTest {

    private final ContentChangeRule rule = new ContentChangeRule( 5, 100 );

    private static String words( final String prefix, final int n ) {
        final StringBuilder sb = new StringBuilder();
        for( int i = 0; i < n; i++ ) {
            sb.append( prefix ).append( i ).append( ' ' );
        }
        return sb.toString().trim();
    }

    @Test
    void frontmatterOnlyChangeIsNotSubstantial() {
        final String body = words( "w", 200 );
        assertFalse( rule.isSubstantial( "---\ntags: [a]\n---\n" + body, "---\ntags: [a, b]\nsummary: x y z\n---\n" + body ) );
    }

    @Test
    void crlfVersusLfIsNotSubstantial() {
        final String lf = "# T\n\nline one\nline two\n";
        assertFalse( rule.isSubstantial( lf, lf.replace( "\n", "\r\n" ) ) );
    }

    @Test
    void clusterHubFooterIsIgnored() {
        final String body = words( "w", 200 );
        final String footer = "\n\n**Cluster Hub:** [X](X)";
        assertFalse( rule.isSubstantial( body, body + footer ) );
        assertFalse( rule.isSubstantial( body + footer, body ) );
    }

    @Test
    void oneWordTypoInLargeBodyIsNotSubstantial() {
        final String old = words( "w", 1000 );
        assertFalse( rule.isSubstantial( old, old.replace( "w500", "wx500" ) ) );
    }

    @Test
    void fiftyChangedWordsInThousandIsSubstantial() {
        final String old = words( "w", 1000 );
        // replacing 25 words changes 25 removed + 25 added = 50
        final String changed = old + " " + words( "n", 50 );
        assertTrue( rule.isSubstantial( old, changed ) );
    }

    @Test
    void hundredWordCapAppliesToHugeBodies() {
        final String old = words( "w", 10000 );
        assertTrue( rule.isSubstantial( old, old + " " + words( "n", 100 ) ) );
        assertFalse( rule.isSubstantial( old, old + " " + words( "n", 99 ) ) );
    }

    @Test
    void newHeadingIsSubstantialEvenWhenSmall() {
        final String old = "# Title\n\n" + words( "w", 1000 );
        assertTrue( rule.isSubstantial( old, old + "\n\n## Brand New Section" ) );
    }

    @Test
    void renamedHeadingIsSubstantial() {
        final String body = words( "w", 1000 );
        assertTrue( rule.isSubstantial( "## Old\n\n" + body, "## New\n\n" + body ) );
    }

    @Test
    void headingLikeLineInsideFenceIsNotAHeading() {
        final String old = words( "w", 1000 );
        final String changed = old + "\n```\n# not a heading\n```";
        assertFalse( rule.isSubstantial( old, changed ) );
    }

    @Test
    void blankBaselineIsSubstantial() {
        assertTrue( rule.isSubstantial( null, "hello" ) );
        assertTrue( rule.isSubstantial( "  \n", "hello" ) );
        assertFalse( rule.isSubstantial( "", "" ) );
    }

    @Test
    void identicalIsNotSubstantial() {
        final String t = "# H\n\n" + words( "w", 50 );
        assertFalse( rule.isSubstantial( t, t ) );
    }

    @Test
    void normalizeStripsFrontmatterCrlfWhitespaceAndFooter() {
        assertEquals( "a b c", ContentChangeRule.normalize( "---\nx: 1\n---\r\na  b\r\n\r\nc\n\n**Cluster Hub:** [X](X)\n" ) );
    }
}
