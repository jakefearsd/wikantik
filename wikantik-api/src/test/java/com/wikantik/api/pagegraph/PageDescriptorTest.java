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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation, defaulting and tag handling of {@link PageDescriptor}. */
class PageDescriptorTest {

    private static PageDescriptor of( final String id, final String slug, final String title, final PageType type,
                                      final List< String > tags, final List< String > inline ) {
        return new PageDescriptor( id, slug, title, type, null, null, tags, null, Instant.EPOCH, false, inline );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource( strings = { " ", "\t" } )
    void aBlankCanonicalIdIsRefusedWithAnIllegalArgument( final String id ) {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> of( id, "Slug", "T", PageType.ARTICLE, List.of(), List.of() ) );
        assertEquals( "canonicalId required", e.getMessage() );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource( strings = { " ", "\t" } )
    void aBlankSlugIsRefusedWithAnIllegalArgument( final String slug ) {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> of( "01H", slug, "T", PageType.ARTICLE, List.of(), List.of() ) );
        assertEquals( "slug required", e.getMessage() );
    }

    @Test
    void anAbsentTitleDefaultsToTheSlugAndAnAbsentTypeToUnknown() {
        final PageDescriptor p = of( "01H", "Slug", null, null, null, null );
        assertEquals( "Slug", p.title() );
        assertEquals( PageType.UNKNOWN, p.type() );
        assertEquals( List.of(), p.tags() );
        assertEquals( List.of(), p.inlineTags() );
        assertTrue( p.clusters().isEmpty() );
    }

    @Test
    void aSuppliedTitleAndTypeAreKept() {
        final PageDescriptor p = of( "01H", "Slug", "A Title", PageType.HUB, List.of(), List.of() );
        assertEquals( "A Title", p.title() );
        assertEquals( PageType.HUB, p.type() );
    }

    @Test
    void anEmptyTitleIsKeptNotReplacedBySlug() {
        assertEquals( "", of( "01H", "Slug", "", PageType.ARTICLE, List.of(), List.of() ).title() );
    }

    @Test
    void tagListsAreDefensivelyCopied() {
        final List< String > tags = new ArrayList<>( List.of( "a" ) );
        final List< String > inline = new ArrayList<>( List.of( "b" ) );
        final PageDescriptor p = of( "01H", "Slug", "T", PageType.ARTICLE, tags, inline );
        tags.add( "later" );
        inline.add( "later" );
        assertEquals( List.of( "a" ), p.tags() );
        assertEquals( List.of( "b" ), p.inlineTags() );
        assertThrows( UnsupportedOperationException.class, () -> p.tags().add( "x" ) );
        assertThrows( UnsupportedOperationException.class, () -> p.inlineTags().add( "x" ) );
    }

    @Test
    void findableTagsAreNormalisedDeduplicatedAndFrontmatterFirst() {
        final PageDescriptor p = of( "01H", "Slug", "T", PageType.ARTICLE,
                List.of( "Machine-Learning", "ml" ), List.of( "machine-learning", "Extra" ) );
        assertEquals( List.of( "machine-learning", "ml", "extra" ), List.copyOf( p.findableTags() ) );
    }

    @Test
    void writtenTagsKeepTheFirstWrittenFormPerNormalisedIdentity() {
        final PageDescriptor p = of( "01H", "Slug", "T", PageType.ARTICLE,
                List.of( "Machine-Learning" ), List.of( "machine-learning", "Extra" ) );
        assertEquals( List.of( "Machine-Learning", "Extra" ), p.writtenTags() );
    }

    @Test
    void tagsThatNormaliseToNothingAreDropped() {
        final PageDescriptor p = of( "01H", "Slug", "T", PageType.ARTICLE, List.of( "", "#", "ok" ), List.of( " " ) );
        assertEquals( List.of( "ok" ), List.copyOf( p.findableTags() ) );
        assertEquals( List.of( "ok" ), p.writtenTags() );
    }
}
