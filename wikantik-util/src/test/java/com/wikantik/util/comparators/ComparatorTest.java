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

package com.wikantik.util.comparators;

import java.text.Collator;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class ComparatorTest
{
    // ---- JavaNaturalComparator tests ------------------------------------

    @Test
    public void testJavaNaturalComparatorNormalOrder()
    {
        final JavaNaturalComparator cmp = new JavaNaturalComparator();
        Assertions.assertTrue( cmp.compare( "apple", "banana" ) < 0 );
        Assertions.assertTrue( cmp.compare( "banana", "apple" ) > 0 );
        Assertions.assertEquals( 0, cmp.compare( "same", "same" ) );
    }

    @Test
    public void testJavaNaturalComparatorNulls()
    {
        final JavaNaturalComparator cmp = new JavaNaturalComparator();
        Assertions.assertEquals( 0, cmp.compare( null, null ) );
        Assertions.assertTrue( cmp.compare( null, "a" ) < 0 );
        Assertions.assertTrue( cmp.compare( "a", null ) > 0 );
    }

    @Test
    public void testJavaNaturalComparatorSingleton()
    {
        Assertions.assertNotNull( JavaNaturalComparator.DEFAULT_JAVA_COMPARATOR );
        Assertions.assertTrue( JavaNaturalComparator.DEFAULT_JAVA_COMPARATOR.compare( "a", "b" ) < 0 );
    }

    // ---- CollatorComparator tests ---------------------------------------

    @Test
    public void testCollatorComparatorNormalOrder()
    {
        final CollatorComparator cmp = new CollatorComparator();
        Assertions.assertTrue( cmp.compare( "apple", "banana" ) < 0 );
        Assertions.assertTrue( cmp.compare( "banana", "apple" ) > 0 );
        Assertions.assertEquals( 0, cmp.compare( "same", "same" ) );
    }

    @Test
    public void testCollatorComparatorNulls()
    {
        final CollatorComparator cmp = new CollatorComparator();
        Assertions.assertEquals( 0, cmp.compare( null, null ) );
        Assertions.assertTrue( cmp.compare( null, "a" ) < 0 );
        Assertions.assertTrue( cmp.compare( "a", null ) > 0 );
    }

    @Test
    public void testCollatorComparatorWithCustomCollator()
    {
        final Collator collator = Collator.getInstance( Locale.FRENCH );
        final CollatorComparator cmp = new CollatorComparator( collator );
        // Basic ordering still works with French collator
        Assertions.assertTrue( cmp.compare( "abc", "def" ) < 0 );
    }

    @Test
    public void testCollatorComparatorSetCollator()
    {
        final CollatorComparator cmp = new CollatorComparator();
        final Collator germanCollator = Collator.getInstance( Locale.GERMAN );
        cmp.setCollator( germanCollator );
        // After setting German collator, comparison still works
        Assertions.assertTrue( cmp.compare( "abc", "xyz" ) < 0 );
    }

    @Test
    public void testCollatorComparatorSingleton()
    {
        Assertions.assertNotNull( CollatorComparator.DEFAULT_LOCALE_COMPARATOR );
        Assertions.assertEquals( 0, CollatorComparator.DEFAULT_LOCALE_COMPARATOR.compare( "test", "test" ) );
    }

    // ---- LocaleComparator tests -----------------------------------------

    @Test
    public void testLocaleComparatorDefaultLocale()
    {
        final LocaleComparator cmp = new LocaleComparator();
        Assertions.assertTrue( cmp.compare( "abc", "def" ) < 0 );
        Assertions.assertEquals( 0, cmp.compare( "same", "same" ) );
    }

    @Test
    public void testLocaleComparatorSpecificLocale()
    {
        final LocaleComparator cmp = new LocaleComparator( Locale.US );
        Assertions.assertTrue( cmp.compare( "apple", "banana" ) < 0 );
    }

    @Test
    public void testLocaleComparatorSetLocale()
    {
        final LocaleComparator cmp = new LocaleComparator();
        cmp.setLocale( Locale.GERMAN );
        // After setting locale, comparison still works
        Assertions.assertTrue( cmp.compare( "abc", "xyz" ) < 0 );
    }

    @Test
    public void testLocaleComparatorNulls()
    {
        final LocaleComparator cmp = new LocaleComparator();
        Assertions.assertEquals( 0, cmp.compare( null, null ) );
        Assertions.assertTrue( cmp.compare( null, "a" ) < 0 );
        Assertions.assertTrue( cmp.compare( "a", null ) > 0 );
    }
}
