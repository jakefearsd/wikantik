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
package com.wikantik.plantuml;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/** Hardened (DOCTYPE-free, no external entities) DOM parse of PlantUML SVG. */
final class SvgParser {

    private static final Logger LOG = LogManager.getLogger( SvgParser.class );

    /** Keeps the JDK parser off stderr; errors still abort the parse and surface as {@link Rejected}. */
    private static final class QuietErrorHandler implements ErrorHandler {
        @Override
        public void warning( final SAXParseException e ) {
            LOG.debug( "SVG parse warning: {}", e.toString() );
        }

        @Override
        public void error( final SAXParseException e ) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError( final SAXParseException e ) throws SAXException {
            throw e;
        }
    }

    static Document parse( final String svg ) {
        if( svg == null || svg.isBlank() ) {
            throw new SvgScrubber.Rejected( "empty svg", null );
        }
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature( XMLConstants.FEATURE_SECURE_PROCESSING, true );
            f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
            f.setFeature( "http://xml.org/sax/features/external-general-entities", false );
            f.setFeature( "http://xml.org/sax/features/external-parameter-entities", false );
            f.setFeature( "http://apache.org/xml/features/nonvalidating/load-external-dtd", false );
            f.setXIncludeAware( false );
            f.setExpandEntityReferences( false );
            f.setNamespaceAware( false );
            f.setCoalescing( true );
            f.setIgnoringComments( true );
            final DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler( new QuietErrorHandler() );
            return b.parse( new InputSource( new StringReader( svg ) ) );
        } catch( final SAXException | IOException e ) {
            LOG.warn( "Rejected unparsable PlantUML SVG: {}", e.toString() );
            throw new SvgScrubber.Rejected( "unparsable svg: " + e.getMessage(), e );
        } catch( final ParserConfigurationException e ) {
            LOG.warn( "XML parser cannot be hardened; refusing SVG: {}", e.toString() );
            throw new SvgScrubber.Rejected( "xml parser cannot be hardened", e );
        }
    }

    private SvgParser() {
    }
}
