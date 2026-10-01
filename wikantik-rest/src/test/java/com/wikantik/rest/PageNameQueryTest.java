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

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageNameQueryTest {

    private static final Function< String, String > ID = Function.identity();

    @Test
    void blankQueryKeepsEverythingAlphabetically() {
        assertEquals( List.of( "Alpha", "Beta", "Gamma" ),
                PageNameQuery.rankBySubstring( List.of( "Gamma", "Alpha", "Beta" ), ID, "  " ) );
        assertEquals( List.of( "Alpha", "Beta" ),
                PageNameQuery.rankBySubstring( List.of( "Beta", "Alpha" ), ID, null ) );
    }

    @Test
    void ranksExactThenPrefixThenSubstringCaseInsensitively() {
        final List< String > names = List.of( "MachineLearning", "Learning", "LearningRate", "DeepLearningNotes", "Unrelated" );
        assertEquals( List.of( "Learning", "LearningRate", "DeepLearningNotes", "MachineLearning" ),
                PageNameQuery.rankBySubstring( names, ID, "learning" ) );
    }

    @Test
    void alphabeticalWithinARank() {
        assertEquals( List.of( "Abc", "AbcOne", "AbcTwo", "XAbc", "YAbc" ),
                PageNameQuery.rankBySubstring( List.of( "YAbc", "AbcTwo", "XAbc", "Abc", "AbcOne" ), ID, "ABC" ) );
    }

    @Test
    void parseNamesTrimsDropsBlanksAndDuplicatesKeepingOrder() {
        assertEquals( List.of( "B", "A", "C" ), PageNameQuery.parseNames( " B, A,,B , C ," ) );
        assertEquals( List.of(), PageNameQuery.parseNames( null ) );
        assertEquals( List.of(), PageNameQuery.parseNames( " , " ) );
    }

    @Test
    void rankUsesTheTitleLookupWhenPresent() {
        final com.wikantik.api.pagegraph.PageTitleLookup lookup = new com.wikantik.api.pagegraph.PageTitleLookup() {
            @Override public java.util.List< String > rank( final java.util.Collection< String > names, final String q ) {
                return java.util.List.of( "Zed", "Alpha" ); // deliberately not alphabetical
            }
            @Override public java.util.List< TitleEntry > entries() { return java.util.List.of(); }
        };
        assertEquals( java.util.List.of( "Zed", "Alpha" ),
                PageNameQuery.rank( java.util.List.of( "Alpha", "Zed", "Unmatched" ), s -> s, "x",
                        java.util.Optional.of( lookup ) ) );
    }

    @Test
    void rankFallsBackToNameSubstringWhileWarming() {
        assertEquals( java.util.List.of( "Index", "IndexFunds", "LowIndex" ),
                PageNameQuery.rank( java.util.List.of( "LowIndex", "Index", "IndexFunds", "Other" ), s -> s, "index",
                        java.util.Optional.empty() ) );
    }
}
