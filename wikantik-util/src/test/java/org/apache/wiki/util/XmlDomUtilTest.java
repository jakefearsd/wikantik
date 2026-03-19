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
package org.apache.wiki.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class XmlDomUtilTest {

    @TempDir
    Path tempDir;

    @Test
    public void testCreateSecureDocumentBuilderFactory() {
        final DocumentBuilderFactory factory = XmlDomUtil.createSecureDocumentBuilderFactory();
        Assertions.assertNotNull( factory );
        Assertions.assertFalse( factory.isValidating() );
        Assertions.assertFalse( factory.isExpandEntityReferences() );
        Assertions.assertFalse( factory.isNamespaceAware() );
    }

    @Test
    public void testCreateEmptyDocument() {
        final Document doc = XmlDomUtil.createEmptyDocument( "testRoot" );
        Assertions.assertNotNull( doc );
        Assertions.assertNotNull( doc.getDocumentElement() );
        Assertions.assertEquals( "testRoot", doc.getDocumentElement().getTagName() );
    }

    @Test
    public void testParseXmlFile() throws Exception {
        // Create a test XML file
        final Path xmlPath = tempDir.resolve( "test.xml" );
        Files.writeString( xmlPath, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root><child>value</child></root>" );

        final Document doc = XmlDomUtil.parseXmlFile( xmlPath.toFile() );
        Assertions.assertNotNull( doc );
        Assertions.assertEquals( "root", doc.getDocumentElement().getTagName() );

        final Element child = (Element) doc.getElementsByTagName( "child" ).item( 0 );
        Assertions.assertNotNull( child );
        Assertions.assertEquals( "value", child.getTextContent() );
    }

    @Test
    public void testParseNonExistentFile() {
        final File nonExistent = new File( tempDir.toFile(), "nonexistent.xml" );
        final Document doc = XmlDomUtil.parseXmlFile( nonExistent );
        Assertions.assertNull( doc );
    }

    @Test
    public void testSaveXmlFile() throws Exception {
        final File xmlFile = new File( tempDir.toFile(), "output.xml" );

        XmlDomUtil.saveXmlFile( xmlFile, io -> {
            io.write( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
            io.write( "<test>content</test>" );
        } );

        Assertions.assertTrue( xmlFile.exists() );
        final String content = Files.readString( xmlFile.toPath() );
        Assertions.assertTrue( content.contains( "<test>content</test>" ) );
    }

    @Test
    public void testSaveXmlFileCreatesBackup() throws Exception {
        final File xmlFile = new File( tempDir.toFile(), "backup-test.xml" );

        // First write
        XmlDomUtil.saveXmlFile( xmlFile, io -> {
            io.write( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
            io.write( "<test>first</test>" );
        } );

        // Second write should create backup
        XmlDomUtil.saveXmlFile( xmlFile, io -> {
            io.write( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
            io.write( "<test>second</test>" );
        } );

        Assertions.assertTrue( xmlFile.exists() );
        final File backup = new File( xmlFile.getAbsolutePath() + ".old" );
        Assertions.assertTrue( backup.exists() );

        final String content = Files.readString( xmlFile.toPath() );
        Assertions.assertTrue( content.contains( "<test>second</test>" ) );

        final String backupContent = Files.readString( backup.toPath() );
        Assertions.assertTrue( backupContent.contains( "<test>first</test>" ) );
    }

    @Test
    public void testSaveXmlFileThrowsOnWriteFailure() {
        final File xmlFile = new File( tempDir.toFile(), "fail-test.xml" );

        Assertions.assertThrows( IOException.class, () -> {
            XmlDomUtil.saveXmlFile( xmlFile, io -> {
                throw new IOException( "Simulated write failure" );
            } );
        } );
    }

}
