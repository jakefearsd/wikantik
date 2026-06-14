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
package com.wikantik.ingest;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Extracts markdown content from a document stream using Apache Tika.
 * Parses to XHTML via Tika's {@link AutoDetectParser} and then converts
 * the XHTML to markdown using flexmark-html2md-converter.
 */
public class TikaSourceExtractor implements SourceExtractor {

    private static final Logger LOG = LogManager.getLogger( TikaSourceExtractor.class );

    /** MIME types supported in v1. Case-insensitive. */
    private static final Set< String > SUPPORTED_TYPES = Set.of(
        "application/pdf",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    @Override
    public boolean supports( final String contentType ) {
        if ( contentType == null ) { return false; }
        return SUPPORTED_TYPES.contains( contentType.toLowerCase() );
    }

    @Override
    public ExtractionResult extract( final InputStream source, final String contentType, final String filename )
            throws ExtractionException {
        final AutoDetectParser parser = new AutoDetectParser();
        final ToXMLContentHandler handler = new ToXMLContentHandler();
        final Metadata md = new Metadata();

        if ( filename != null ) {
            md.set( TikaCoreProperties.RESOURCE_NAME_KEY, filename );
        }
        if ( contentType != null ) {
            md.set( HttpHeaders.CONTENT_TYPE, contentType );
        }

        try ( source ) {
            parser.parse( source, handler, md, new ParseContext() );
        } catch ( final IOException | SAXException | TikaException e ) {
            LOG.warn( "Tika parse failed for '{}' (type={}): {}", filename, contentType, e.getMessage() );
            throw new ExtractionException( "Failed to parse document '" + filename + "': " + e.getMessage(), e );
        }

        final String xhtml = handler.toString();
        final String markdown = FlexmarkHtmlConverter.builder().build().convert( xhtml ).strip();
        final String title = md.get( TikaCoreProperties.TITLE );

        return new ExtractionResult( markdown, title, Map.of() );
    }
}
