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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


public class CommentedPropertiesTest
{

    Properties m_props = new CommentedProperties();
    // file size of the properties test file in bytes
    private int propFileSize;

    @BeforeEach
    public void setUp() throws IOException
    {
        final InputStream in = CommentedPropertiesTest.class.getClassLoader().getResourceAsStream( "test.properties" );
        m_props.load( in );
        // CommentedProperties always internally uses \n as EOL, as opposed to a File which uses, well, the given EOL of the File.  
        // Thus, executing this test when test.properties has another EOL (like f.ex when git cloning having core.autocrlf=true on
        // windows) using File.length() would fail this test. 
        propFileSize = m_props.toString().length();
        in.close();
    }

    @Test
    public void testLoadProperties()
    {
        Assertions.assertEquals( 5, m_props.keySet().size() );
        Assertions.assertEquals( "Foo", m_props.get( "testProp1" ) );
        Assertions.assertEquals( "Bar", m_props.get( "testProp2" ) );
        Assertions.assertEquals( "", m_props.get( "testProp3" ) );
        Assertions.assertEquals( "FooAgain", m_props.get( "testProp4" ) );
        Assertions.assertEquals( "BarAgain", m_props.get( "testProp5" ) );
        Assertions.assertNull( m_props.get( "testProp6" ) );

        // String we read in, including comments is c_stringOffset bytes
        Assertions.assertEquals( propFileSize, m_props.toString().length() );
    }

    @Test
    public void testSetProperty()
    {
        m_props.setProperty( "testProp1", "newValue" );

        // Length of stored string should now be 5 bytes more
        Assertions.assertEquals( propFileSize+5, m_props.toString().length() );
        Assertions.assertTrue( m_props.toString().indexOf( "newValue" ) != -1 );

        // Create new property; should add 21 (1+7+3+9+1) bytes
        m_props.setProperty( "newProp", "newValue2" );
        m_props.containsKey( "newProp" );
        m_props.containsValue( "newValue2" );
        Assertions.assertEquals( propFileSize+5+21, m_props.toString().length() );
        Assertions.assertTrue( m_props.toString().indexOf( "newProp = newValue2" ) != -1 );
    }

    @Test
    public void testRemove()
    {
        // Remove prop 1; length of stored string should be 14 (1+9+1+3) bytes less
        m_props.remove( "testProp1" );
        Assertions.assertFalse( m_props.containsKey( "testProp1" ) );
        Assertions.assertEquals( propFileSize-14, m_props.toString().length() );

        // Remove prop 2; length of stored string should be 15 (1+9+2+3) bytes less
        m_props.remove( "testProp2" );
        Assertions.assertFalse( m_props.containsKey( "testProp2" ) );
        Assertions.assertEquals( propFileSize-14-15, m_props.toString().length() );

        // Remove prop 3; length of stored string should be 11 (1+9+1) bytes less
        m_props.remove( "testProp3" );
        Assertions.assertFalse( m_props.containsKey( "testProp3" ) );
        Assertions.assertEquals( propFileSize-14-15-11, m_props.toString().length() );

        // Remove prop 4; length of stored string should be 19 (1+9+1+8) bytes less
        m_props.remove( "testProp4" );
        Assertions.assertFalse( m_props.containsKey( "testProp4" ) );
        Assertions.assertEquals( propFileSize-14-15-11-19, m_props.toString().length() );

        // Remove prop 5; length of stored string should be 19 (1+9+1+8) bytes less
        m_props.remove( "testProp5" );
        Assertions.assertFalse( m_props.containsKey( "testProp5" ) );
        Assertions.assertEquals( propFileSize-14-15-11-19-19, m_props.toString().length() );
    }

    @Test
    public void testStore() throws Exception
    {
        // Write results to a new file
        File outFile = createFile( "test2.properties" );
        OutputStream out = new FileOutputStream( outFile );
        m_props.store( out, null );
        out.close();

        // Load the file into new props object; should return identical strings
        final Properties props2 = new CommentedProperties();
        InputStream in = CommentedPropertiesTest.class.getClassLoader().getResourceAsStream( "test2.properties" );
        props2.load( in );
        in.close();
        Assertions.assertEquals( m_props.toString(), props2.toString() );

        // Remove props1, 2, 3 & resave props to new file
        m_props.remove( "testProp1" );
        m_props.remove( "testProp2" );
        m_props.remove( "testProp3" );
        outFile = createFile( "test3.properties" );
        out = new FileOutputStream( outFile );
        m_props.store( out, null );
        out.close();

        // Load the new file; should not have props1/2/3 & is shorter
        final Properties props3 = new CommentedProperties();
        in = CommentedPropertiesTest.class.getClassLoader().getResourceAsStream( "test3.properties" );
        props3.load( in );
        in.close();
        Assertions.assertNotSame( m_props.toString(), props3.toString() );
        Assertions.assertFalse( props3.containsKey( "testProp1" ) );
        Assertions.assertFalse( props3.containsKey( "testProp2" ) );
        Assertions.assertFalse( props3.containsKey( "testProp3" ) );
        Assertions.assertTrue( props3.containsKey( "testProp4" ) );
        Assertions.assertTrue( props3.containsKey( "testProp5" ) );

        // Clean up
        File file = getFile( "test2.properties" );
        if ( file != null && file.exists() )
        {
            file.delete();
        }
        file = getFile( "test3.properties" );
        if ( file != null && file.exists() )
        {
            file.delete();
        }
    }

    private File createFile(final String file ) throws URISyntaxException
    {
        // Get the test.properties file
        final URL url = CommentedPropertiesTest.class.getClassLoader().getResource( "test.properties" );
        if ( url == null )
        {
            throw new IllegalStateException( "Very odd. We can't find test.properties!" );
        }

        // Construct new file in same directory
        final File testFile = new File( new URI(url.toString()) );
        final File dir = testFile.getParentFile();
        return new File( dir, file );
    }

    private File getFile(final String name )
    {
        // Get the test.properties file
        final URL url = CommentedPropertiesTest.class.getClassLoader().getResource( name );
        if ( url == null )
        {
            throw new IllegalStateException( "Very odd. We can't find test.properties!" );
        }
        // Return the file
        File file = null;

        try
        {
            file = new File( new URI(url.toString()) );
        }
        catch (final URISyntaxException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return file;
    }

    // --- Additional coverage tests ---

    @Test
    public void testLoadFromReader() throws IOException {
        final String propContent = "# Comment line\nkey1 = value1\nkey2 = value2\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new StringReader( propContent ) );

        assertEquals( "value1", props.getProperty( "key1" ) );
        assertEquals( "value2", props.getProperty( "key2" ) );
        // toString should preserve the comment
        assertTrue( props.toString().contains( "# Comment line" ) );
    }

    @Test
    public void testPutAll() throws IOException {
        final String propContent = "existingKey = existingValue\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        final Map< String, String > newEntries = new HashMap<>();
        newEntries.put( "newKey1", "newVal1" );
        newEntries.put( "newKey2", "newVal2" );
        props.putAll( newEntries );

        assertEquals( "newVal1", props.getProperty( "newKey1" ) );
        assertEquals( "newVal2", props.getProperty( "newKey2" ) );
        assertEquals( "existingValue", props.getProperty( "existingKey" ) );
        assertTrue( props.toString().contains( "newKey1" ) );
    }

    @Test
    public void testSetPropertyNewKey() throws IOException {
        final String propContent = "key1 = value1\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        props.setProperty( "newKey", "newValue" );
        assertEquals( "newValue", props.getProperty( "newKey" ) );
        assertTrue( props.toString().contains( "newKey = newValue" ) );
    }

    @Test
    public void testPutNullKeyThrows() throws IOException {
        final String propContent = "key1 = value1\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        assertThrows( IllegalArgumentException.class, () -> props.put( null, "value" ) );
    }

    @Test
    public void testRemoveNullKeyThrows() throws IOException {
        final String propContent = "key1 = value1\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        assertThrows( IllegalArgumentException.class, () -> props.remove( null ) );
    }

    @Test
    public void testStorePreservesComments() throws IOException {
        final String propContent = "# This is a comment\nkey1 = value1\n# Another comment\nkey2 = value2\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        props.store( out, null );

        final String stored = out.toString( StandardCharsets.ISO_8859_1 );
        assertTrue( stored.contains( "# This is a comment" ) );
        assertTrue( stored.contains( "# Another comment" ) );
        assertTrue( stored.contains( "key1" ) );
        assertTrue( stored.contains( "value1" ) );
    }

    @Test
    public void testDefaultValuesConstructor() {
        final Properties defaults = new Properties();
        defaults.setProperty( "defaultKey", "defaultVal" );

        final CommentedProperties props = new CommentedProperties( defaults );
        assertEquals( "defaultVal", props.getProperty( "defaultKey" ) );
    }

    @Test
    public void testSetPropertyUpdatesExistingInString() throws IOException {
        final String propContent = "key1 = oldValue\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        props.setProperty( "key1", "newValue" );
        assertEquals( "newValue", props.getProperty( "key1" ) );
        assertTrue( props.toString().contains( "newValue" ) );
        assertFalse( props.toString().contains( "oldValue" ) );
    }

    @Test
    public void testRemoveNonexistentKey() throws IOException {
        final String propContent = "key1 = value1\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        // Removing a key that doesn't exist should not fail
        props.remove( "nonexistent" );
        assertEquals( "value1", props.getProperty( "key1" ) );
    }

    @Test
    public void testCommentedLineSkippedDuringWrite() throws IOException {
        // Property key appears in a comment line - comments should be skipped during writeProperty
        final String propContent = "\n# key1 = commented out\nkey1 = realValue\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        // Update key1 should change the real value, not the commented one
        props.setProperty( "key1", "updatedValue" );
        assertEquals( "updatedValue", props.getProperty( "key1" ) );
        // The comment should still be in the string
        assertTrue( props.toString().contains( "# key1 = commented out" ) );
        assertTrue( props.toString().contains( "updatedValue" ) );
    }

    @Test
    public void testUnicodeValueRoundTrip() throws IOException {
        final String propContent = "key1 = value1\n";
        final CommentedProperties props = new CommentedProperties();
        props.load( new ByteArrayInputStream( propContent.getBytes( StandardCharsets.ISO_8859_1 ) ) );

        props.setProperty( "key1", "\u00e4\u00f6\u00fc" );
        // native2Ascii should convert unicode to \\uXXXX notation
        assertTrue( props.toString().contains( "\\u00E4" ) );
    }

}
