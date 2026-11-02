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
package com.wikantik.api.pagegraph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class TagAggregationTest {

    private static PageDescriptor page( final String id, final List< String > tags, final List< String > inline ) {
        return new PageDescriptor( id, "Slug" + id, "T", PageType.ARTICLE, null, List.of(), tags, "s", Instant.EPOCH,
                Optional.empty(), false, inline );
    }

    @Test
    void descriptorKeepsTagsAsWrittenAndComparesNormalised() {
        final PageDescriptor p = page( "1", List.of( "Foo", "a/b" ), List.of( "FOO", "Bar/Baz" ) );
        assertEquals( List.of( "Foo", "a/b" ), p.tags() );
        assertEquals( List.of( "foo", "a-b", "bar-baz" ), List.copyOf( p.findableTags() ) );
        assertEquals( List.of( "Foo", "a/b", "Bar/Baz" ), p.writtenTags() );
    }

    @Test
    void countsGroupByNormalisedKeyAndShowTheMostCommonWrittenForm() {
        final List< PageDescriptor > pages = List.of(
                page( "1", List.of( "Foo" ), List.of() ),
                page( "2", List.of( "foo" ), List.of( "FOO" ) ),   // one page, counted once, "foo" and "FOO" collapse
                page( "3", List.of(), List.of( "foo" ) ) );
        final List< TagSummary > s = TagAggregation.summaries( pages, 1 );
        assertEquals( 1, s.size() );
        assertEquals( "foo", s.get( 0 ).tag(), "the form most pages wrote" );
        assertEquals( 3, s.get( 0 ).count() );
        assertEquals( Map.of( "foo", 3 ), TagAggregation.distribution( pages ) );
    }

    @Test
    void minPagesFiltersAndInlineOnlyTagsCount() {
        final List< PageDescriptor > pages = List.of( page( "1", List.of(), List.of( "solo" ) ),
                page( "2", List.of( "x" ), List.of() ), page( "3", List.of( "x" ), List.of() ) );
        assertEquals( List.of( "x" ), TagAggregation.summaries( pages, 2 ).stream().map( TagSummary::tag ).toList() );
        assertEquals( 2, TagAggregation.summaries( pages, 1 ).size() );
    }
}
