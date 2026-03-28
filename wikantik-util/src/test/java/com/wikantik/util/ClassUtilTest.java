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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


public class ClassUtilTest
{

    /**
     * tests various kinds of searches on classpath items
     */
    @Test
    public void testClasspathSearch() {
        final List< String > jarSearch = ClassUtil.classpathEntriesUnder( "META-INF" );
        Assertions.assertNotNull( jarSearch );
        Assertions.assertTrue( jarSearch.size() > 0 );

        final List< String > fileSearch = ClassUtil.classpathEntriesUnder( "templates" );
        Assertions.assertNotNull( fileSearch );
        Assertions.assertTrue( fileSearch.size() > 0 );

        final List< String > nullSearch = ClassUtil.classpathEntriesUnder( "blurb" );
        Assertions.assertNotNull( nullSearch );
        Assertions.assertEquals( 0, nullSearch.size() );

        final List< String > nullInputSearch = ClassUtil.classpathEntriesUnder( null );
        Assertions.assertNotNull( nullInputSearch );
        Assertions.assertEquals( 0, nullSearch.size() );
    }

    /**
     *  Tries to find an existing class.
     */
    @Test
    public void testFindClass() throws Exception {
        final Class< List< ? > > foo = ClassUtil.findClass( "java.util", "List" );

        Assertions.assertEquals( foo.getName(), "java.util.List" );
    }

    /**
     *  Non-existant classes should throw ClassNotFoundEx.
     */
    @Test
    public void testFindClassNoClass() {
        Assertions.assertThrows( ClassNotFoundException.class, () ->  ClassUtil.findClass( "com.wikantik", "MubbleBubble" ) );
    }

    @Test
    public void testAssignable() {
        Assertions.assertTrue( ClassUtil.assignable( "java.util.ArrayList", "java.util.List" ) );
        Assertions.assertFalse( ClassUtil.assignable( "java.util.List", "java.util.ArrayList" ) );
        Assertions.assertFalse( ClassUtil.assignable( null, "java.util.ArrayList" ) );
        Assertions.assertFalse( ClassUtil.assignable( "java.util.List", null ) );
        Assertions.assertFalse( ClassUtil.assignable( "java.util.List", "java.util.HashMap" ) );
    }

    @Test
    public void testExists() {
        Assertions.assertTrue( ClassUtil.exists( "java.util.List" ) );
        Assertions.assertFalse( ClassUtil.exists( "org.apache.wiski.FrisFrus" ) );
    }

    @Test
    public void testBuildInstance() throws Exception {
        Assertions.assertTrue( ClassUtil.buildInstance( "java.util.ArrayList" ) instanceof List );
        Assertions.assertThrows( NoSuchMethodException.class, () -> ClassUtil.buildInstance( "java.util.List" ) );
    }

    // --- getMappedClass tests ---

    @Test
    public void testGetMappedClassWithUnmappedClass() throws Exception {
        // When no mapping exists, it should fall back to using the class name directly
        Class< ? > clazz = ClassUtil.getMappedClass( "java.util.ArrayList" );
        assertEquals( "java.util.ArrayList", clazz.getName() );
    }

    @Test
    public void testGetMappedClassNotFound() {
        assertThrows( ClassNotFoundException.class, () -> ClassUtil.getMappedClass( "com.nonexistent.Foo" ) );
    }

    // --- buildInstance with package tests ---

    @Test
    public void testBuildInstanceWithPackage() throws Exception {
        Object obj = ClassUtil.buildInstance( "java.util", "ArrayList" );
        assertNotNull( obj );
        assertTrue( obj instanceof List );
    }

    @Test
    public void testBuildInstanceWithFullyQualifiedName() throws Exception {
        Object obj = ClassUtil.buildInstance( "", "java.util.ArrayList" );
        assertTrue( obj instanceof List );
    }

    @Test
    public void testBuildInstanceNotFoundThrows() {
        assertThrows( ClassNotFoundException.class, () -> ClassUtil.buildInstance( "com.nonexistent", "FooBar" ) );
    }

    // --- buildInstance with constructor args ---

    @Test
    public void testBuildInstanceWithArgs() throws Exception {
        // ArrayList has a constructor that takes an int (initial capacity)
        Object obj = ClassUtil.buildInstance( ArrayList.class, 10 );
        assertTrue( obj instanceof List );
    }

    @Test
    public void testBuildInstanceDefaultCtor() throws Exception {
        Object obj = ClassUtil.buildInstance( HashMap.class );
        assertTrue( obj instanceof Map );
    }

    // --- getMappedObject tests ---

    @Test
    public void testGetMappedObjectNoArgs() throws Exception {
        Object obj = ClassUtil.getMappedObject( "java.util.ArrayList" );
        assertTrue( obj instanceof List );
    }

    // --- findClass with packages list ---

    @Test
    public void testFindClassWithPackagesList() throws Exception {
        List< String > packages = List.of( "java.util", "java.io" );
        List< String > jars = List.of();
        Class< ? > clazz = ClassUtil.findClass( packages, jars, "ArrayList" );
        assertEquals( "java.util.ArrayList", clazz.getName() );
    }

    @Test
    public void testFindClassWithPackagesListFullyQualified() throws Exception {
        List< String > packages = List.of();
        List< String > jars = List.of();
        Class< ? > clazz = ClassUtil.findClass( packages, jars, "java.util.HashMap" );
        assertEquals( "java.util.HashMap", clazz.getName() );
    }

    @Test
    public void testFindClassWithPackagesListNotFound() {
        List< String > packages = List.of( "java.util" );
        List< String > jars = List.of();
        assertThrows( ClassNotFoundException.class, () ->
            ClassUtil.findClass( packages, jars, "NonExistentClass" ) );
    }

    // --- getExtraClassMappings ---

    @Test
    public void testGetExtraClassMappings() {
        Map< String, String > extra = ClassUtil.getExtraClassMappings();
        assertNotNull( extra );
        // Should be a map (possibly empty if classmappings-extra.xml is not present or has no entries)
    }

}
