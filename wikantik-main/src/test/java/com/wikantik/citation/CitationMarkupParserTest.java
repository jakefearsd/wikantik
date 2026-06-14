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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationMarkupParserTest {

    private final CitationMarkupParser parser = new CitationMarkupParser();

    @Test
    void parsesTargetHeadingSpanAndClaim() {
        final String body = "Intro.\n\n"
            + "Rollback is destructive [you must drain first]"
            + "(cite://abc123/Deploy/Rollback%20Steps \"Always drain the queue before rollback\").\n";
        final List< ParsedCitation > out = parser.parse( body );
        assertEquals( 1, out.size() );
        final ParsedCitation c = out.get( 0 );
        assertEquals( "abc123", c.targetCanonicalId() );
        assertEquals( "Deploy > Rollback Steps", c.targetHeadingPath() );
        assertEquals( "Always drain the queue before rollback", c.spanText() );
        assertEquals( "you must drain first", c.claimText() );
        assertEquals( 0, c.ordinal() );
        assertEquals( Spans.hash( Spans.normalize( c.spanText() ) ), c.spanHash() );
    }

    @Test
    void pageLevelCitationHasEmptyHeadingPath() {
        final List< ParsedCitation > out = parser.parse( "x [c](cite://pg1 \"span\") y" );
        assertEquals( 1, out.size() );
        assertEquals( "pg1", out.get( 0 ).targetCanonicalId() );
        assertEquals( "", out.get( 0 ).targetHeadingPath() );
    }

    @Test
    void titlelessCitationHasEmptySpan() {
        final List< ParsedCitation > out = parser.parse( "[c](cite://pg1/Heading)" );
        assertEquals( 1, out.size() );
        assertEquals( "", out.get( 0 ).spanText() );
        assertEquals( Spans.hash( "" ), out.get( 0 ).spanHash() );
    }

    @Test
    void duplicateSameTargetSpanGetsIncrementingOrdinals() {
        final String body = "[a](cite://t1/H \"s\") and again [b](cite://t1/H \"s\")";
        final List< ParsedCitation > out = parser.parse( body );
        assertEquals( 2, out.size() );
        assertEquals( 0, out.get( 0 ).ordinal() );
        assertEquals( 1, out.get( 1 ).ordinal() );
    }

    @Test
    void ignoresOrdinaryMarkdownLinks() {
        assertTrue( parser.parse( "[home](https://example.com) [p](/wiki/Page)" ).isEmpty() );
    }
}
