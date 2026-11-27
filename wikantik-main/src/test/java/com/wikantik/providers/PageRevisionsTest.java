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
package com.wikantik.providers;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link PageRevisions}: when two Page objects are one revision, and the change-feed cutoff. */
class PageRevisionsTest {

    private static TestEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stopEngine() {
        engine.stop();
    }

    private static Page page( final String name, final int version, final long modified, final String author ) {
        final Page p = Wiki.contents().page( engine, name );
        p.setVersion( version );
        p.setLastModified( new Date( modified ) );
        p.setAuthor( author );
        p.setSize( 10L );
        return p;
    }

    @Test
    void identicalRevisionsAreTheSame() {
        Assertions.assertTrue( PageRevisions.same( page( "A", 2, 5000L, "x" ), page( "A", 2, 5000L, "x" ) ) );
    }

    @Test
    void anyDifferenceInVersionSizeTimeOrAuthorMakesADifferentRevision() {
        final Page base = page( "A", 2, 5000L, "x" );
        Assertions.assertFalse( PageRevisions.same( base, page( "A", 3, 5000L, "x" ) ), "version" );
        Assertions.assertFalse( PageRevisions.same( base, page( "A", 2, 5001L, "x" ) ), "modification time" );
        Assertions.assertFalse( PageRevisions.same( base, page( "A", 2, 5000L, "y" ) ), "author" );
        final Page bigger = page( "A", 2, 5000L, "x" );
        bigger.setSize( 11L );
        Assertions.assertFalse( PageRevisions.same( base, bigger ), "size" );
    }

    private static Set< String > names( final java.util.Collection< Page > pages ) {
        return pages.stream().map( Page::getName ).collect( Collectors.toSet() );
    }

    @Test
    void changedSinceKeepsPagesModifiedAtOrAfterTheCutoff() {
        final List< Page > all = List.of( page( "Before", 1, 999L, "x" ), page( "At", 1, 1000L, "x" ), page( "After", 1, 1001L, "x" ) );

        Assertions.assertEquals( Set.of( "At", "After" ), names( PageRevisions.changedSince( all, new Date( 1000L ) ) ) );
    }

    @Test
    void changedSinceWithNoCutoffTheEpochOrNothingAfterItReturnsEverything() {
        final List< Page > all = List.of( page( "Old", 1, 1L, "x" ), page( "New", 1, 5000L, "x" ) );

        Assertions.assertSame( all, PageRevisions.changedSince( all, null ) );
        Assertions.assertSame( all, PageRevisions.changedSince( all, new Date( 0L ) ) );
        Assertions.assertEquals( Set.of( "Old", "New" ), names( PageRevisions.changedSince( all, new Date( 1L ) ) ) );
        Assertions.assertEquals( Set.of( "New" ), names( PageRevisions.changedSince( all, new Date( 2L ) ) ) );
    }

    @Test
    void aPageWithoutAModificationTimeIsAlwaysIncluded() {
        final Page undated = Wiki.contents().page( engine, "Undated" );
        undated.setLastModified( null );

        Assertions.assertEquals( Set.of( "Undated" ), names( PageRevisions.changedSince( List.of( undated ), new Date( 9_999L ) ) ) );
    }
}
