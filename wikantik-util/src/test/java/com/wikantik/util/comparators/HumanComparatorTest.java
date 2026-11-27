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

import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import com.wikantik.util.comparators.HumanComparator.CharType;


public class HumanComparatorTest
{

    @Test
    public void testCharOrder()
    {
        final HumanComparator comparator = new HumanComparator();

        // Default order first
        Assertions.assertTrue( comparator.compare( "a c", "a1c" ) < 0 );
        Assertions.assertTrue( comparator.compare( "a1c", "abc" ) < 0 );

        // Now letters then numbers then other
        final HumanComparator.CharType[] sortOrder = { HumanComparator.CharType.TYPE_LETTER, HumanComparator.CharType.TYPE_DIGIT,
                                                HumanComparator.CharType.TYPE_OTHER };
        comparator.setSortOrder( sortOrder );
        Assertions.assertTrue( comparator.compare( "a c", "a1c" ) > 0 );
        Assertions.assertTrue( comparator.compare( "a1c", "abc" ) > 0 );

        // Now numbers then letters then other
        sortOrder[0] = HumanComparator.CharType.TYPE_DIGIT;
        sortOrder[1] = HumanComparator.CharType.TYPE_LETTER;
        sortOrder[2] = HumanComparator.CharType.TYPE_OTHER;
        comparator.setSortOrder( sortOrder );
        Assertions.assertTrue( comparator.compare( "a c", "a1c" ) > 0 );
        Assertions.assertTrue( comparator.compare( "a1c", "abc" ) < 0 );

        // Finally try to break it
        try
        {
            sortOrder[0] = HumanComparator.CharType.TYPE_DIGIT;
            sortOrder[1] = HumanComparator.CharType.TYPE_DIGIT;
            sortOrder[2] = HumanComparator.CharType.TYPE_OTHER;
            comparator.setSortOrder( sortOrder );
            Assertions.fail( "Expected IllegalArgumentException" );
        }
        catch( final IllegalArgumentException e )
        {
            // All worked
        }
    }

    @Test
    public void testCompare()
    {
        final Comparator<String> comparator = new HumanComparator();

        Assertions.assertTrue( comparator.compare( "abcd001", "ABCD001" ) > 0 );
        Assertions.assertTrue( comparator.compare( "abcd001a", "ABCD001z" ) < 0 );
        Assertions.assertTrue( comparator.compare( "abc8", "abcd1" ) < 0 );
        Assertions.assertTrue( comparator.compare( "abc 8", "abc1" ) < 0 );
        Assertions.assertTrue( comparator.compare( "abc  8", "abc 1" ) < 0 );
        Assertions.assertTrue( comparator.compare( "abdc001", "ABCD001" ) > 0 );
        Assertions.assertTrue( comparator.compare( "ab cd001", "ABDC001" ) < 0 );
        Assertions.assertTrue( comparator.compare( "10", "01" ) > 0 );
        Assertions.assertTrue( comparator.compare( "10", "00000001" ) > 0 );
        Assertions.assertTrue( comparator.compare( "01", "00000001" ) < 0 );
    }

    private final HumanComparator cmp = new HumanComparator();

    @Test
    void nullSortsBeforeAnyString() {
        Assertions.assertEquals( -1, cmp.compare( null, "a" ) );
        Assertions.assertEquals( 1, cmp.compare( "a", null ) );
        Assertions.assertEquals( 0, cmp.compare( null, null ) );
    }

    @Test
    void aStringThatIsAPrefixIsTheSmallerByTheDifferenceInLength() {
        Assertions.assertEquals( -1, cmp.compare( "ab", "abc" ) );
        Assertions.assertEquals( 1, cmp.compare( "abc", "ab" ) );
        Assertions.assertEquals( -3, cmp.compare( "a", "abcd" ) );
    }

    @Test
    void caseIsOnlyATieBreakerAndLowerCaseSortsFirst() {
        Assertions.assertEquals( 1, cmp.compare( "a", "A" ) );
        Assertions.assertEquals( -1, cmp.compare( "A", "a" ) );
        Assertions.assertTrue( cmp.compare( "a", "B" ) < 0 );
    }

    @Test
    void theFirstCaseDifferenceDecidesTheTie() {
        Assertions.assertEquals( 1, cmp.compare( "aB", "AB" ) );
        Assertions.assertEquals( 1, cmp.compare( "ab", "aB" ) );
        Assertions.assertEquals( -1, cmp.compare( "aB", "ab" ) );
    }

    @Test
    void numbersAreComparedByValueNotByText() {
        Assertions.assertTrue( cmp.compare( "file2x", "file10x" ) < 0 );
        Assertions.assertTrue( cmp.compare( "file10x", "file2x" ) > 0 );
        Assertions.assertEquals( -8, cmp.compare( "file2x", "file10x" ) );
    }

    @Test
    void numbersOfEqualValueAreOrderedByTheirLengthAndThenComparisonContinues() {
        Assertions.assertTrue( cmp.compare( "a01b", "a1b" ) > 0 );
        Assertions.assertTrue( cmp.compare( "a1b", "a01b" ) < 0 );
        Assertions.assertEquals( 1, cmp.compare( "a00", "a0" ) );
        Assertions.assertEquals( -1, cmp.compare( "a00", "a000" ) );
        // equal digit runs are skipped and the text after them decides
        Assertions.assertTrue( cmp.compare( "x007b", "x007c" ) < 0 );
        Assertions.assertTrue( cmp.compare( "x007c", "x007b" ) > 0 );
        Assertions.assertEquals( 0, cmp.compare( "x007b", "x007b" ) );
    }

    @Test
    void aDigitRunAtTheEndOfTheShorterStringDoesNotRunOffIt() {
        Assertions.assertTrue( cmp.compare( "a12", "a123" ) < 0 );
        Assertions.assertTrue( cmp.compare( "a123", "a12" ) > 0 );
        Assertions.assertTrue( cmp.compare( "a007", "a0070" ) < 0 );
    }

    @Test
    void theConstructorAppliesTheGivenSortOrder() {
        final HumanComparator lettersFirst = new HumanComparator(
                new CharType[] { CharType.TYPE_LETTER, CharType.TYPE_DIGIT, CharType.TYPE_OTHER } );
        Assertions.assertTrue( lettersFirst.compare( "a c", "a1c" ) > 0 );
        Assertions.assertTrue( lettersFirst.compare( "a1c", "abc" ) > 0 );
        Assertions.assertTrue( cmp.compare( "a c", "a1c" ) < 0 );
    }

    @Test
    void theSharedInstanceIsTheDefaultOneAndCannotBeReconfigured() {
        final var shared = HumanComparator.getInstance();
        Assertions.assertNotNull( shared );
        Assertions.assertEquals( HumanComparator.DEFAULT_HUMAN_COMPARATOR, shared );
        Assertions.assertThrows( IllegalArgumentException.class, () -> ( ( HumanComparator )shared ).setSortOrder(
                new CharType[] { CharType.TYPE_LETTER, CharType.TYPE_DIGIT, CharType.TYPE_OTHER } ) );
    }

    @Test
    void aSortOrderMustBeNonNullOfExactlyThreeDistinctTypes() {
        Assertions.assertThrows( IllegalArgumentException.class, () -> cmp.setSortOrder( null ) );
        Assertions.assertThrows( IllegalArgumentException.class,
                () -> cmp.setSortOrder( new CharType[] { CharType.TYPE_LETTER, CharType.TYPE_DIGIT } ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> cmp.setSortOrder(
                new CharType[] { CharType.TYPE_LETTER, CharType.TYPE_LETTER, CharType.TYPE_OTHER } ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> cmp.setSortOrder(
                new CharType[] { CharType.TYPE_LETTER, CharType.TYPE_OTHER, CharType.TYPE_LETTER } ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> cmp.setSortOrder(
                new CharType[] { CharType.TYPE_OTHER, CharType.TYPE_LETTER, CharType.TYPE_LETTER } ) );
    }
}
