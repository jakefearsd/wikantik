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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagExtractorTest {

    @Test
    void extractsTopNKeywords() {
        final String text = "PostgreSQL is a powerful relational database system. "
            + "Database administrators use PostgreSQL for storing and querying data. "
            + "The PostgreSQL database supports advanced SQL queries and indexing.";
        final List< String > tags = TagExtractor.extract( text, 3 );
        assertEquals( 3, tags.size() );
        tags.forEach( tag -> assertEquals( tag, tag.toLowerCase(),
            "Tag should be lowercase: " + tag ) );
    }

    @Test
    void returnsFewerTagsWhenContentIsShort() {
        final List< String > tags = TagExtractor.extract( "Hello world", 3 );
        assertTrue( tags.size() <= 3, "Should return at most 3 tags" );
        assertFalse( tags.isEmpty(), "Should return at least one tag for non-blank input" );
    }

    @Test
    void returnsEmptyForBlank() {
        assertTrue( TagExtractor.extract( "", 5 ).isEmpty() );
        assertTrue( TagExtractor.extract( null, 5 ).isEmpty() );
    }

    @Test
    void stripsMarkdown() {
        final String markdown = "## Database Systems\n"
            + "**PostgreSQL** is a [relational database](https://postgresql.org) used for "
            + "storing structured data. See also `SQL` queries and indexing strategies.";
        final List< String > tags = TagExtractor.extract( markdown, 5 );
        assertFalse( tags.isEmpty() );
        for( final String tag : tags ) {
            assertFalse( tag.contains( "http" ), "Tags should not contain URLs: " + tag );
            assertFalse( tag.contains( "**" ), "Tags should not contain markdown bold: " + tag );
            assertFalse( tag.contains( "##" ), "Tags should not contain markdown headings: " + tag );
            assertFalse( tag.contains( "[" ), "Tags should not contain markdown link syntax: " + tag );
        }
    }

    @Test
    void respectsMaxCount() {
        final String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa";
        final List< String > tags = TagExtractor.extract( text, 2 );
        assertEquals( 2, tags.size() );
    }

    @Test
    void excludesStopwords() {
        final String text = "The quick brown fox jumps over the lazy dog near the river bank";
        final List< String > tags = TagExtractor.extract( text, 5 );
        assertFalse( tags.isEmpty() );
        assertFalse( tags.contains( "the" ), "Should not contain stopword 'the'" );
        assertFalse( tags.contains( "over" ), "Should not contain stopword 'over'" );
    }
}
