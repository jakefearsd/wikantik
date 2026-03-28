/*
    JSPWiki - a JSP-based WikiWiki clone.

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); fyou may not use this file except in compliance
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
import org.mockito.Mockito;

import jakarta.servlet.ServletContext;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.mockito.Mockito.mock;


/**
 * Unit test for PropertyReader.
 */
class PropertyReaderTest {

    @Test
    void testLocateClassPathResource() {
        Assertions.assertEquals( "/ini/wikantik.properties", PropertyReader.createResourceLocation( "ini", "wikantik.properties" ) );
        Assertions.assertEquals( "/ini/wikantik.properties", PropertyReader.createResourceLocation( null, "ini/wikantik.properties" ) );
        Assertions.assertEquals( "/ini/wikantik.properties", PropertyReader.createResourceLocation( null, "/ini/wikantik.properties" ) );
        Assertions.assertEquals( "/wikantik-custom.properties", PropertyReader.createResourceLocation( null, "/wikantik-custom.properties" ) );
        Assertions.assertEquals( "/wikantik.custom.cascade.1.ini", PropertyReader.createResourceLocation( null, "wikantik.custom.cascade.1.ini" ) );
        Assertions.assertEquals( "/WEB-INF/classes/wikantik-custom.properties", PropertyReader.createResourceLocation( "WEB-INF/classes", PropertyReader.CUSTOM_JSPWIKI_CONFIG ) );
        Assertions.assertEquals( "/WEB-INF/classes/wikantik-custom.properties", PropertyReader.createResourceLocation( "/WEB-INF/classes", PropertyReader.CUSTOM_JSPWIKI_CONFIG ) );
        Assertions.assertEquals( "/WEB-INF/classes/wikantik-custom.properties", PropertyReader.createResourceLocation( "/WEB-INF/classes/", PropertyReader.CUSTOM_JSPWIKI_CONFIG ) );
    }

    @Test
    void testVariableExpansion() {
        final Properties p = new Properties();
        p.put( "var.basedir", "/p/mywiki" );
        p.put( "wikantik.fileSystemProvider.pageDir", "$basedir/www/" );
        p.put( "wikantik.basicAttachmentProvider.storageDir", "$basedir/www/" );
        p.put( "wikantik.workDir", "$basedir/wrk/" );
        p.put( "wikantik.xyz", "test basedir" ); //don't touch this

        PropertyReader.expandVars( p );

        Assertions.assertEquals( "/p/mywiki/www/", p.getProperty( "wikantik.fileSystemProvider.pageDir" ) );
        Assertions.assertEquals( "/p/mywiki/www/", p.getProperty( "wikantik.basicAttachmentProvider.storageDir" ) );
        Assertions.assertEquals( "/p/mywiki/www/", p.getProperty( "wikantik.fileSystemProvider.pageDir" ) );
        Assertions.assertTrue( p.getProperty( "wikantik.workDir" ).endsWith( "/p/mywiki/wrk/" ) );
        Assertions.assertTrue( p.getProperty( "wikantik.xyz" ).endsWith( "test basedir" ) ); //don't touch this
        Assertions.assertFalse( p.getProperty( "wikantik.workDir" ).endsWith( "$basedir/wrk/" ) );
    }

    @Test
    void testVariableExpansion2() {
        final Properties p = new Properties();

        //this time, declare the var at the end... (should overwrite this one);
        p.put( "var.basedir", "xxx" );

        p.put( "wikantik.fileSystemProvider.pageDir", "$basedir/www/" );
        p.put( "wikantik.basicAttachmentProvider.storageDir", "$basedir/www/" );
        p.put( "wikantik.workDir", "$basedir/wrk/" );
        p.put( "wikantik.xyz", "test basedir" ); //don't touch this
        p.put( "wikantik.abc", "test $x2" ); //don't touch this

        p.put( "var.basedir", " /p/mywiki" ); //note that this var has a space at the beginning...
        p.put( "var.x2", " wiki " ); //note that this var has a space at the beginning...

        PropertyReader.expandVars( p );

        Assertions.assertEquals( "/p/mywiki/www/", p.getProperty( "wikantik.fileSystemProvider.pageDir" ) );
        Assertions.assertEquals( "/p/mywiki/www/", p.getProperty( "wikantik.basicAttachmentProvider.storageDir" ) );
        Assertions.assertEquals( "/p/mywiki/www/", p.getProperty( "wikantik.fileSystemProvider.pageDir" ) );
        Assertions.assertTrue( p.getProperty( "wikantik.workDir" ).endsWith( "/p/mywiki/wrk/" ) );
        Assertions.assertTrue( p.getProperty( "wikantik.xyz" ).endsWith( "test basedir" ) ); //don't touch this
        Assertions.assertFalse( p.getProperty( "wikantik.workDir" ).endsWith( "$basedir/wrk/" ) );
        Assertions.assertTrue( p.getProperty( "wikantik.abc" ).endsWith( "test wiki" ) );
    }

    @Test
    void testMultipleVariableExpansion() {
        final Properties p = new Properties();

        //this time, declare the var at the end... (should overwrite this one);
        p.put( "var.x1", "a" );
        p.put( "var.x2", "b" );

        p.put( "wikantik.x1", "$x1" );
        p.put( "wikantik.x2", "$x2" );
        p.put( "wikantik.x3", "$x1/$x2" );

        PropertyReader.expandVars( p );

        Assertions.assertEquals( "a", p.getProperty( "wikantik.x1" ) );
        Assertions.assertEquals( "b", p.getProperty( "wikantik.x2" ) );
        Assertions.assertEquals( "a/b", p.getProperty( "wikantik.x3" ) );
    }

    @Test
    void testCollectPropertiesFrom() {
        final Map< String, String > sut = new HashMap<>();
        sut.put( "wikantik_frontPage", "Main" );
        sut.put( "secretEnv", "asdasd" );

        final Map< String, String > test = PropertyReader.collectPropertiesFrom( sut );

        Assertions.assertEquals( "Main", test.get( "wikantik.frontPage" ) );
        Assertions.assertNull( test.get( "secretEnv" ) );
    }

    @Test
    void testSetWorkDir() {
        final Properties properties = new Properties();
        final ServletContext servletContext = mock(ServletContext.class);
        final File tmp = new File( "/tmp" );
        Mockito.when(servletContext.getAttribute( "jakarta.servlet.context.tempdir" ) ).thenReturn( tmp );

        PropertyReader.setWorkDir( servletContext, properties );

        // Test when the "wikantik.workDir" is not set, it should get set to servlet's temporary directory
        PropertyReader.setWorkDir(servletContext, properties);
        String workDir = properties.getProperty("wikantik.workDir");
        Assertions.assertEquals(tmp.getAbsolutePath(), workDir);

        // Test when the "wikantik.workDir" is set, it should remain as it is
        properties.setProperty("wikantik.workDir", "/custom/dir");
        PropertyReader.setWorkDir(servletContext, properties);
        workDir = properties.getProperty("wikantik.workDir");
        Assertions.assertEquals("/custom/dir", workDir);

        // Test when the servlet's temporary directory is null, it should get set to system's temporary directory
        Mockito.when( servletContext.getAttribute( "jakarta.servlet.context.tempdir" ) ).thenReturn( null );
        properties.remove( "wikantik.workDir" );
        PropertyReader.setWorkDir( servletContext, properties );
        workDir = properties.getProperty( "wikantik.workDir" );
        Assertions.assertEquals( System.getProperty( "java.io.tmpdir" ), workDir );
    }

    @Test
    void testSystemPropertyExpansion() {
        try {
            System.setProperty( "FOO", "BAR" );
            System.setProperty( "TEST", "VAL" );
            final Properties p = new Properties();
            p.put( "wikantik.fileSystemProvider.pageDir", "${FOO}/www/" );
            p.put( "wikantik.fileSystemProvider.workDir", "${FOO}/www/${TEST}" );
            p.put( "wikantik.fileSystemProvider.badVal1", "${FOO/www/${TEST}" );
            p.put( "wikantik.fileSystemProvider.badVal2", "}${FOO/www/${TEST}" );
            p.put( "wikantik.fileSystemProvider.badVal3", "${NONEXISTANTPROP}" );
            p.put( "wikantik.fileSystemProvider.badVal4", "${NONEXISTANTPROP}/${FOO}" );
            PropertyReader.propertyExpansion( p );
            Assertions.assertEquals( "BAR/www/", p.getProperty( "wikantik.fileSystemProvider.pageDir" ) );
            Assertions.assertEquals( "BAR/www/VAL", p.getProperty( "wikantik.fileSystemProvider.workDir" ) );
            Assertions.assertEquals( "${FOO/www/${TEST}", p.getProperty( "wikantik.fileSystemProvider.badVal1" ) );
            Assertions.assertEquals( "}${FOO/www/${TEST}", p.getProperty( "wikantik.fileSystemProvider.badVal2" ) );
            Assertions.assertEquals( "${NONEXISTANTPROP}", p.getProperty( "wikantik.fileSystemProvider.badVal3" ) );
            Assertions.assertEquals( "${NONEXISTANTPROP}/${FOO}", p.getProperty( "wikantik.fileSystemProvider.badVal4" ) );
        } finally {
            System.setProperty( "FOO", "" );
            System.setProperty( "TEST", "" );
        }
    }

    @Test
    void testPropertyExpansionWithSysPrefix() {
        try {
            System.setProperty( "MY_SYS_PROP", "sysvalue" );
            final Properties p = new Properties();
            p.put( "wikantik.test", "${sys:MY_SYS_PROP}/data" );
            PropertyReader.propertyExpansion( p );
            Assertions.assertEquals( "sysvalue/data", p.getProperty( "wikantik.test" ) );
        } finally {
            System.clearProperty( "MY_SYS_PROP" );
        }
    }

    @Test
    void testPropertyExpansionWithEnvPrefix() {
        // Use a system property as fallback since env vars can't be set in tests
        try {
            System.setProperty( "MY_ENV_PROP", "envvalue" );
            final Properties p = new Properties();
            p.put( "wikantik.test", "${env:MY_ENV_PROP}/data" );
            PropertyReader.propertyExpansion( p );
            Assertions.assertEquals( "envvalue/data", p.getProperty( "wikantik.test" ) );
        } finally {
            System.clearProperty( "MY_ENV_PROP" );
        }
    }

    @Test
    void testPropertyExpansionNoChange() {
        final Properties p = new Properties();
        p.put( "wikantik.test", "no-expansion-needed" );
        PropertyReader.propertyExpansion( p );
        Assertions.assertEquals( "no-expansion-needed", p.getProperty( "wikantik.test" ) );
    }

    @Test
    void testGetDefaultProperties() {
        final Properties props = PropertyReader.getDefaultProperties();
        Assertions.assertNotNull( props );
        // The default properties file should contain at least some wikantik properties
        Assertions.assertFalse( props.isEmpty(), "Default properties should not be empty" );
    }

    @Test
    void testGetCombinedPropertiesWithNonexistentFile() {
        final Properties props = PropertyReader.getCombinedProperties( "/nonexistent-file.properties" );
        Assertions.assertNotNull( props );
        // Should still have defaults even though custom file was not found
        Assertions.assertFalse( props.isEmpty() );
    }

    @Test
    void testGetCombinedPropertiesWithExistingResource() {
        // test.properties exists on the test classpath
        final Properties props = PropertyReader.getCombinedProperties( "/test.properties" );
        Assertions.assertNotNull( props );
        // Should have merged default props + test props
        Assertions.assertEquals( "Foo", props.getProperty( "testProp1" ) );
    }

    @Test
    void testCollectPropertiesFromFiltersNonWikantik() {
        Map< String, String > input = new HashMap<>();
        input.put( "wikantik_test_key", "val1" );
        input.put( "WIKANTIK_UPPER", "val2" );
        input.put( "other_key", "val3" );
        input.put( "wikantik", "val4" );

        Map< String, String > result = PropertyReader.collectPropertiesFrom( input );

        Assertions.assertEquals( "val1", result.get( "wikantik.test.key" ) );
        Assertions.assertNull( result.get( "other_key" ) );
        Assertions.assertNull( result.get( "other.key" ) );
        Assertions.assertEquals( "val4", result.get( "wikantik" ) );
    }

    @Test
    void testExpandVarsNoVarsDefined() {
        final Properties p = new Properties();
        p.put( "wikantik.test", "no vars here" );
        PropertyReader.expandVars( p );
        Assertions.assertEquals( "no vars here", p.getProperty( "wikantik.test" ) );
    }

    @Test
    void testExpandVarsUndefinedVarLeftAlone() {
        final Properties p = new Properties();
        p.put( "wikantik.test", "$undefinedVar/path" );
        PropertyReader.expandVars( p );
        // $undefinedVar has no matching var.undefinedVar, so it stays
        Assertions.assertEquals( "$undefinedVar/path", p.getProperty( "wikantik.test" ) );
    }

}
