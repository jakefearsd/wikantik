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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** D26: Lucene-syntax characters in casual search queries are escaped by default. */
class SearchEscapeTest {

    @Test
    void asteriskIsEscaped() {
        assertEquals( "wei\\*rd", SearchResource.escapeLuceneSpecialChars( "wei*rd" ) );
    }

    @Test
    void questionMarkIsEscaped() {
        assertEquals( "wha\\?t", SearchResource.escapeLuceneSpecialChars( "wha?t" ) );
    }

    @Test
    void parenthesesAreEscaped() {
        assertEquals( "\\(group\\)", SearchResource.escapeLuceneSpecialChars( "(group)" ) );
    }

    @Test
    void doubleAmpersandIsEscaped() {
        assertEquals( "foo \\&\\& bar", SearchResource.escapeLuceneSpecialChars( "foo && bar" ) );
    }

    @Test
    void singleAmpersandIsLeftAlone() {
        // & alone is just a literal in normal text; don't escape it.
        assertEquals( "AT&T", SearchResource.escapeLuceneSpecialChars( "AT&T" ) );
    }

    @Test
    void boolKeywordsAreLowercased() {
        assertEquals( "foo and bar or baz", SearchResource.escapeLuceneSpecialChars( "foo AND bar OR baz" ) );
        assertEquals( "foo not bar",        SearchResource.escapeLuceneSpecialChars( "foo NOT bar" ) );
    }

    @Test
    void mixedCaseLikeAnDIsLowercasedOnlyWhenItsTheKeyword() {
        // "AnD" is not the literal AND keyword, so it should stay as a token.
        assertEquals( "rust AnD go",
                SearchResource.escapeLuceneSpecialChars( "rust AnD go" ).trim() );
    }

    @Test
    void plainQueryIsUnchanged() {
        assertEquals( "encryption keys", SearchResource.escapeLuceneSpecialChars( "encryption keys" ) );
    }

    @Test
    void nullAndEmptyAreReturnedAsIs() {
        assertNull( SearchResource.escapeLuceneSpecialChars( null ) );
        assertEquals( "", SearchResource.escapeLuceneSpecialChars( "" ) );
    }
}
