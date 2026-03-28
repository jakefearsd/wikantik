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

package com.wikantik.util;

import java.util.Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class PropertiesUtilsTest
{
    // ---- saveConvert tests ----------------------------------------------

    @Test
    public void testSaveConvertPlainString()
    {
        Assertions.assertEquals( "hello", PropertiesUtils.saveConvert( "hello", false ) );
    }

    @Test
    public void testSaveConvertBackslash()
    {
        Assertions.assertEquals( "a\\\\b", PropertiesUtils.saveConvert( "a\\b", false ) );
    }

    @Test
    public void testSaveConvertTab()
    {
        Assertions.assertEquals( "a\\tb", PropertiesUtils.saveConvert( "a\tb", false ) );
    }

    @Test
    public void testSaveConvertNewline()
    {
        Assertions.assertEquals( "a\\nb", PropertiesUtils.saveConvert( "a\nb", false ) );
    }

    @Test
    public void testSaveConvertCarriageReturn()
    {
        Assertions.assertEquals( "a\\rb", PropertiesUtils.saveConvert( "a\rb", false ) );
    }

    @Test
    public void testSaveConvertFormFeed()
    {
        Assertions.assertEquals( "a\\fb", PropertiesUtils.saveConvert( "a\014b", false ) );
    }

    @Test
    public void testSaveConvertSpaceNoEncode()
    {
        // Space in the middle without encodeWhiteSpace should not be escaped
        Assertions.assertEquals( "a b", PropertiesUtils.saveConvert( "a b", false ) );
    }

    @Test
    public void testSaveConvertSpaceEncode()
    {
        // All spaces escaped when encodeWhiteSpace is true
        Assertions.assertEquals( "\\ a\\ b", PropertiesUtils.saveConvert( " a b", true ) );
    }

    @Test
    public void testSaveConvertLeadingSpaceNoEncode()
    {
        // Leading space is always escaped even without encodeWhiteSpace
        Assertions.assertEquals( "\\ hello", PropertiesUtils.saveConvert( " hello", false ) );
    }

    @Test
    public void testSaveConvertUnicode()
    {
        // Characters outside 32-126 range are unicode-escaped
        final String result = PropertiesUtils.saveConvert( "\u00E9", false );
        Assertions.assertEquals( "\\u00E9", result );
    }

    // ---- toLine tests ---------------------------------------------------

    @Test
    public void testToLineSimple()
    {
        final String line = PropertiesUtils.toLine( "key", "value" );
        Assertions.assertEquals( "key=value", line );
    }

    @Test
    public void testToLineWithSpaces()
    {
        final String line = PropertiesUtils.toLine( "my key", "my value" );
        // key spaces are encoded (encodeWhiteSpace=true), value leading space is escaped
        Assertions.assertTrue( line.contains( "=" ) );
        Assertions.assertTrue( line.startsWith( "my\\ key=" ) );
    }

    // ---- toSortedString tests -------------------------------------------

    @Test
    public void testToSortedStringEmpty()
    {
        final Properties props = new Properties();
        final String result = PropertiesUtils.toSortedString( props );
        Assertions.assertEquals( "", result );
    }

    @Test
    public void testToSortedStringOrder()
    {
        final Properties props = new Properties();
        props.setProperty( "zebra", "z" );
        props.setProperty( "apple", "a" );
        props.setProperty( "mango", "m" );
        final String result = PropertiesUtils.toSortedString( props );
        final int applePos = result.indexOf( "apple" );
        final int mangoPos = result.indexOf( "mango" );
        final int zebraPos = result.indexOf( "zebra" );
        Assertions.assertTrue( applePos < mangoPos );
        Assertions.assertTrue( mangoPos < zebraPos );
    }

    @Test
    public void testToSortedStringContainsAllEntries()
    {
        final Properties props = new Properties();
        props.setProperty( "a", "1" );
        props.setProperty( "b", "2" );
        final String result = PropertiesUtils.toSortedString( props );
        Assertions.assertTrue( result.contains( "a=1" ) );
        Assertions.assertTrue( result.contains( "b=2" ) );
        // Each line ends with newline
        Assertions.assertTrue( result.endsWith( "\n" ) );
    }
}
