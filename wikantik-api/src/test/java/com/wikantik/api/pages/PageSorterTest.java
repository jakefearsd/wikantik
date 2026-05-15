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
package com.wikantik.api.pages;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class PageSorterTest {

    @Test
    void defaultConstructorComparesByNaturalOrder() {
        final PageSorter sorter = new PageSorter();
        assertTrue( sorter.compare( "Apple", "Banana" ) < 0 );
        assertTrue( sorter.compare( "Banana", "Apple" ) > 0 );
        assertEquals( 0, sorter.compare( "Apple", "Apple" ) );
    }

    @Test
    void customComparatorIsUsed() {
        final PageSorter reverse = new PageSorter( Comparator.reverseOrder() );
        assertTrue( reverse.compare( "Apple", "Banana" ) > 0 );
    }

    @Test
    void sortOrdersListInPlace() {
        final PageSorter sorter = new PageSorter();
        final List< String > names = new ArrayList<>( List.of( "Charlie", "Alpha", "Bravo" ) );
        sorter.sort( names );
        assertEquals( List.of( "Alpha", "Bravo", "Charlie" ), names );
    }

    @Test
    void sortOrdersArrayInPlace() {
        final PageSorter sorter = new PageSorter();
        final String[] names = { "Charlie", "Alpha", "Bravo" };
        sorter.sort( names );
        assertArrayEquals( new String[] { "Alpha", "Bravo", "Charlie" }, names );
    }

    @Test
    void equalsAndHashCodeTrackTheUnderlyingComparator() {
        final Comparator< String > shared = Comparator.naturalOrder();
        final PageSorter a = new PageSorter( shared );
        final PageSorter b = new PageSorter( shared );
        assertEquals( a, b );
        assertEquals( a.hashCode(), b.hashCode() );
        assertEquals( a, a );
    }

    @Test
    void equalsRejectsNullAndForeignTypes() {
        final PageSorter sorter = new PageSorter();
        assertNotEquals( sorter, null );
        assertNotEquals( sorter, "not a sorter" );
    }

    @Test
    void initializeWithoutPropertyKeepsAWorkingComparator() {
        final PageSorter sorter = new PageSorter();
        sorter.initialize( new Properties() );
        assertTrue( sorter.compare( "Apple", "Banana" ) < 0 );
    }

    @Test
    void initializeFallsBackToDefaultOnBadComparatorClass() {
        final PageSorter sorter = new PageSorter();
        final Properties props = new Properties();
        props.setProperty( PageSorter.PROP_PAGE_NAME_COMPARATOR, "NoSuchComparatorClass" );
        sorter.initialize( props );
        // Falls back silently to the natural-order comparator.
        assertTrue( sorter.compare( "Apple", "Banana" ) < 0 );
    }
}
