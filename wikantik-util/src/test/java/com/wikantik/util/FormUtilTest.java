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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;


public class FormUtilTest
{
    // ---- getValues tests ------------------------------------------------

    @Test
    public void testGetValuesNullParams()
    {
        final List<?> result = FormUtil.getValues( null, "key" );
        Assertions.assertNotNull( result );
        Assertions.assertTrue( result.isEmpty() );
    }

    @Test
    public void testGetValuesNullKey()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "key", "value" );
        final List<?> result = FormUtil.getValues( params, null );
        Assertions.assertNotNull( result );
        Assertions.assertTrue( result.isEmpty() );
    }

    @Test
    public void testGetValuesDirectHit()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "color", "red" );
        final List<?> result = FormUtil.getValues( params, "color" );
        Assertions.assertEquals( 1, result.size() );
        Assertions.assertEquals( "red", result.get( 0 ) );
    }

    @Test
    public void testGetValuesFallsBackToNumbered()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "color.0", "red" );
        params.put( "color.1", "blue" );
        final List<?> result = FormUtil.getValues( params, "color" );
        Assertions.assertEquals( 2, result.size() );
        Assertions.assertEquals( "red", result.get( 0 ) );
        Assertions.assertEquals( "blue", result.get( 1 ) );
    }

    // ---- getNumberedValues tests ----------------------------------------

    @Test
    public void testGetNumberedValuesNullParams()
    {
        final List<?> result = FormUtil.getNumberedValues( null, "key" );
        Assertions.assertTrue( result.isEmpty() );
    }

    @Test
    public void testGetNumberedValuesEmptyParams()
    {
        final List<?> result = FormUtil.getNumberedValues( Collections.emptyMap(), "key" );
        Assertions.assertTrue( result.isEmpty() );
    }

    @Test
    public void testGetNumberedValuesNullPrefix()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "a.0", "x" );
        final List<?> result = FormUtil.getNumberedValues( params, null );
        Assertions.assertTrue( result.isEmpty() );
    }

    @Test
    public void testGetNumberedValuesEmptyPrefix()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "a.0", "x" );
        final List<?> result = FormUtil.getNumberedValues( params, "" );
        Assertions.assertTrue( result.isEmpty() );
    }

    @Test
    public void testGetNumberedValuesZeroIndexed()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "item.0", "a" );
        params.put( "item.1", "b" );
        params.put( "item.2", "c" );
        final List<?> result = FormUtil.getNumberedValues( params, "item" );
        Assertions.assertEquals( 3, result.size() );
        Assertions.assertEquals( "a", result.get( 0 ) );
        Assertions.assertEquals( "b", result.get( 1 ) );
        Assertions.assertEquals( "c", result.get( 2 ) );
    }

    @Test
    public void testGetNumberedValuesOneIndexed()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "item.1", "x" );
        params.put( "item.2", "y" );
        final List<?> result = FormUtil.getNumberedValues( params, "item" );
        Assertions.assertEquals( 2, result.size() );
        Assertions.assertEquals( "x", result.get( 0 ) );
        Assertions.assertEquals( "y", result.get( 1 ) );
    }

    @Test
    public void testGetNumberedValuesPrefixWithDot()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "item.0", "a" );
        params.put( "item.1", "b" );
        final List<?> result = FormUtil.getNumberedValues( params, "item." );
        Assertions.assertEquals( 2, result.size() );
    }

    @Test
    public void testGetNumberedValuesStopsAtGap()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "x.0", "a" );
        params.put( "x.1", "b" );
        // gap at x.2
        params.put( "x.3", "d" );
        final List<?> result = FormUtil.getNumberedValues( params, "x" );
        Assertions.assertEquals( 2, result.size() );
    }

    @Test
    public void testGetNumberedValuesNoMatch()
    {
        final Map<String, String> params = new HashMap<>();
        params.put( "other.0", "a" );
        final List<?> result = FormUtil.getNumberedValues( params, "missing" );
        Assertions.assertTrue( result.isEmpty() );
    }

    // ---- requestToMap tests ---------------------------------------------

    @Test
    public void testRequestToMapSingleValues()
    {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameterNames() )
               .thenReturn( Collections.enumeration( List.of( "name", "age" ) ) );
        Mockito.when( req.getParameterValues( "name" ) ).thenReturn( new String[]{ "Alice" } );
        Mockito.when( req.getParameterValues( "age" ) ).thenReturn( new String[]{ "30" } );

        final Map<String, String> result = FormUtil.requestToMap( req, null );
        Assertions.assertEquals( "Alice", result.get( "name" ) );
        Assertions.assertEquals( "30", result.get( "age" ) );
    }

    @Test
    public void testRequestToMapMultipleValues()
    {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameterNames() )
               .thenReturn( Collections.enumeration( List.of( "color" ) ) );
        Mockito.when( req.getParameterValues( "color" ) ).thenReturn( new String[]{ "red", "blue", "green" } );

        final Map<String, String> result = FormUtil.requestToMap( req, null );
        Assertions.assertEquals( "red", result.get( "color.0" ) );
        Assertions.assertEquals( "blue", result.get( "color.1" ) );
        Assertions.assertEquals( "green", result.get( "color.2" ) );
    }

    @Test
    public void testRequestToMapWithPrefix()
    {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameterNames() )
               .thenReturn( Collections.enumeration( List.of( "wiki_name", "other" ) ) );
        Mockito.when( req.getParameterValues( "wiki_name" ) ).thenReturn( new String[]{ "TestPage" } );

        final Map<String, String> result = FormUtil.requestToMap( req, "wiki_" );
        Assertions.assertEquals( "TestPage", result.get( "name" ) );
        Assertions.assertFalse( result.containsKey( "other" ) );
    }

    @Test
    public void testRequestToMapMultipleValuesSkipsEmptyStrings()
    {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.when( req.getParameterNames() )
               .thenReturn( Collections.enumeration( List.of( "items" ) ) );
        Mockito.when( req.getParameterValues( "items" ) ).thenReturn( new String[]{ "a", "", "c" } );

        final Map<String, String> result = FormUtil.requestToMap( req, null );
        Assertions.assertEquals( "a", result.get( "items.0" ) );
        Assertions.assertFalse( result.containsKey( "items.1" ) );
        Assertions.assertEquals( "c", result.get( "items.2" ) );
    }
}
