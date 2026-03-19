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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Utility class for DOM-based XML file operations.
 * <p>
 * Provides secure XML parsing with XXE (XML External Entity) protection
 * and atomic file write operations with backup support.
 * </p>
 *
 * @since 2.12
 */
public final class XmlDomUtil {

    private static final Logger LOG = LogManager.getLogger( XmlDomUtil.class );

    private XmlDomUtil() {
    }

    /**
     * Creates a secure {@link DocumentBuilderFactory} with XXE protection enabled.
     * <p>
     * The factory is configured with the following security settings:
     * <ul>
     *     <li>Validation disabled</li>
     *     <li>Entity reference expansion disabled</li>
     *     <li>Comments ignored</li>
     *     <li>Namespace awareness disabled</li>
     *     <li>External DTD access disabled</li>
     *     <li>External schema access disabled</li>
     * </ul>
     * </p>
     *
     * @return a securely configured DocumentBuilderFactory
     */
    public static DocumentBuilderFactory createSecureDocumentBuilderFactory() {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating( false );
        factory.setExpandEntityReferences( false );
        factory.setIgnoringComments( true );
        factory.setNamespaceAware( false );
        factory.setAttribute( XMLConstants.ACCESS_EXTERNAL_DTD, "" );
        factory.setAttribute( XMLConstants.ACCESS_EXTERNAL_SCHEMA, "" );
        return factory;
    }

    /**
     * Parses an XML file into a DOM Document using secure settings.
     * <p>
     * If the file does not exist or cannot be parsed, returns {@code null}.
     * The caller is responsible for handling the null case appropriately.
     * </p>
     *
     * @param file the XML file to parse
     * @return the parsed Document, or {@code null} if parsing fails
     */
    public static Document parseXmlFile( final File file ) {
        return parseXmlFile( file, createSecureDocumentBuilderFactory() );
    }

    /**
     * Parses an XML file into a DOM Document using the provided factory.
     * <p>
     * If the file does not exist or cannot be parsed, returns {@code null}.
     * The caller is responsible for handling the null case appropriately.
     * </p>
     *
     * @param file    the XML file to parse
     * @param factory the DocumentBuilderFactory to use for parsing
     * @return the parsed Document, or {@code null} if parsing fails
     */
    public static Document parseXmlFile( final File file, final DocumentBuilderFactory factory ) {
        try {
            return factory.newDocumentBuilder().parse( file );
        } catch( final ParserConfigurationException e ) {
            LOG.error( "Configuration error: {}", e.getMessage() );
        } catch( final SAXException e ) {
            LOG.error( "SAX error: {}", e.getMessage() );
        } catch( final FileNotFoundException e ) {
            LOG.info( "XML file not found: {}", file.getAbsolutePath() );
        } catch( final IOException e ) {
            LOG.error( "IO error: {}", e.getMessage() );
        }
        return null;
    }

    /**
     * Creates a new empty DOM Document with the specified root element.
     *
     * @param rootElementName the name of the root element
     * @return a new Document with the specified root element, or {@code null} if creation fails
     */
    public static Document createEmptyDocument( final String rootElementName ) {
        return createEmptyDocument( rootElementName, createSecureDocumentBuilderFactory() );
    }

    /**
     * Creates a new empty DOM Document with the specified root element using the provided factory.
     *
     * @param rootElementName the name of the root element
     * @param factory         the DocumentBuilderFactory to use
     * @return a new Document with the specified root element, or {@code null} if creation fails
     */
    public static Document createEmptyDocument( final String rootElementName, final DocumentBuilderFactory factory ) {
        try {
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document doc = builder.newDocument();
            doc.appendChild( doc.createElement( rootElementName ) );
            return doc;
        } catch( final ParserConfigurationException e ) {
            LOG.fatal( "Could not create in-memory DOM: {}", e.getMessage() );
            return null;
        }
    }

    /**
     * Functional interface for writing XML content to a BufferedWriter.
     */
    @FunctionalInterface
    public interface XmlContentWriter {
        /**
         * Writes XML content to the provided writer.
         *
         * @param writer the BufferedWriter to write to
         * @throws IOException if writing fails
         */
        void write( BufferedWriter writer ) throws IOException;
    }

    /**
     * Saves XML content to a file atomically using a write-to-temp-then-rename strategy.
     * <p>
     * This method ensures data integrity by:
     * <ol>
     *     <li>Writing content to a temporary file (original.xml.new)</li>
     *     <li>Backing up the original file (original.xml.old)</li>
     *     <li>Renaming the temporary file to the original name</li>
     *     <li>If rename fails, restoring from backup</li>
     * </ol>
     * </p>
     *
     * @param file          the target file to save to
     * @param contentWriter a functional interface that writes the XML content
     * @throws IOException if writing or file operations fail
     */
    public static void saveXmlFile( final File file, final XmlContentWriter contentWriter ) throws IOException {
        final File newFile = new File( file.getAbsolutePath() + ".new" );

        try( final BufferedWriter io = new BufferedWriter(
                new OutputStreamWriter( Files.newOutputStream( newFile.toPath() ), StandardCharsets.UTF_8 ) ) ) {
            contentWriter.write( io );
        }

        // Atomic rename with backup
        final File backup = new File( file.getAbsolutePath() + ".old" );
        if( backup.exists() && !backup.delete() ) {
            LOG.error( "Could not delete old backup: {}", backup );
        }
        if( file.exists() && !file.renameTo( backup ) ) {
            LOG.error( "Could not create backup: {}", backup );
        }
        if( !newFile.renameTo( file ) ) {
            LOG.error( "Could not save file: {} - restoring backup.", file );
            if( backup.exists() && !backup.renameTo( file ) ) {
                LOG.error( "Restore failed. Check the file permissions." );
            }
            throw new IOException( "Could not save file: " + file + ". Check the file permissions" );
        }
    }

}
