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
package org.apache.wiki.parser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.InternalWikiException;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.util.XmlUtil;
import org.jdom2.Element;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles heading markup for JSPWikiMarkupParser.
 * Headings are created using exclamation marks (!, !!, !!!).
 *
 * @since 2.12
 */
public class WikiHeadingHandler {

    private static final Logger LOG = LogManager.getLogger( WikiHeadingHandler.class );

    private final JSPWikiMarkupParser parser;
    private final Context context;
    private final Engine engine;
    private final boolean wysiwygEditorMode;
    private final ArrayList<HeadingListener> headingListenerChain;

    /** Keep track of duplicate header names. */
    private final Map<String, Integer> m_titleSectionCounter = new HashMap<>();

    /** The last heading that was processed */
    private Heading m_lastHeading;

    /** Used for cleaning section titles */
    private JSPWikiMarkupParser m_cleanTranslator;

    /**
     * Constructs a WikiHeadingHandler.
     *
     * @param parser The parent JSPWikiMarkupParser
     * @param context The wiki context
     * @param wysiwygEditorMode Whether we're in WYSIWYG editor mode
     * @param headingListenerChain The chain of heading listeners
     */
    public WikiHeadingHandler( final JSPWikiMarkupParser parser,
                               final Context context,
                               final boolean wysiwygEditorMode,
                               final ArrayList<HeadingListener> headingListenerChain ) {
        this.parser = parser;
        this.context = context;
        this.engine = context.getEngine();
        this.wysiwygEditorMode = wysiwygEditorMode;
        this.headingListenerChain = headingListenerChain;
    }

    // =========================================================================
    // Heading Methods
    // =========================================================================

    /**
     * Calls the heading listeners.
     *
     * @param param A Heading object.
     */
    private void callHeadingListenerChain( final Heading param ) {
        for( final HeadingListener h : headingListenerChain ) {
            h.headingAdded( context, param );
        }
    }

    /**
     * Does a lazy init of the clean translator.
     * Otherwise, we would get into a situation where HTMLRenderer would try and boot
     * a TranslatorReader before the TranslatorReader it is contained by is up.
     */
    private JSPWikiMarkupParser getCleanTranslator() {
        if( m_cleanTranslator == null ) {
            final Context dummyContext = Wiki.context().create( engine, context.getHttpRequest(), context.getPage() );
            m_cleanTranslator = new JSPWikiMarkupParser( dummyContext, null );
            m_cleanTranslator.enableRawHtml( true );
        }
        return m_cleanTranslator;
    }

    /**
     * Modifies the "hd" parameter to contain proper values. Because
     * an "id" tag may only contain [a-zA-Z0-9:_-], we'll replace the
     * % after url encoding with '_'.
     * <p>
     * Counts also duplicate headings (= headings with similar name), and
     * attaches a counter.
     *
     * @param baseName The base page name
     * @param title The heading title
     * @param hd The Heading object to populate
     * @return The generated anchor string
     */
    protected String makeHeadingAnchor( final String baseName, String title, final Heading hd ) {
        hd.m_titleText = title;
        title = MarkupParser.wikifyLink( title );
        hd.m_titleSection = engine.encodeName( title );
        if( m_titleSectionCounter.containsKey( hd.m_titleSection ) ) {
            final Integer count = m_titleSectionCounter.get( hd.m_titleSection ) + 1;
            m_titleSectionCounter.put( hd.m_titleSection, count );
            hd.m_titleSection += "-" + count;
        } else {
            m_titleSectionCounter.put( hd.m_titleSection, 1 );
        }

        hd.m_titleAnchor = "section-" + engine.encodeName( baseName ) + "-" + hd.m_titleSection;
        hd.m_titleAnchor = hd.m_titleAnchor.replace( '%', '_' );
        hd.m_titleAnchor = hd.m_titleAnchor.replace( '/', '_' );

        return hd.m_titleAnchor;
    }

    /**
     * Cleans the section title by parsing it and extracting plain text.
     *
     * @param title The raw title text
     * @return The cleaned title text
     */
    private String makeSectionTitle( String title ) {
        title = title.trim();
        try {
            final JSPWikiMarkupParser dtr = getCleanTranslator();
            dtr.setInputReader( new StringReader( title ) );
            final WikiDocument doc = dtr.parse();
            doc.setContext( context );

            return XmlUtil.extractTextFromDocument( doc );
        } catch( final IOException e ) {
            LOG.fatal( "Title parsing not working", e );
            throw new InternalWikiException( "Xml text extraction not working as expected when cleaning title" + e.getMessage(), e );
        }
    }

    /**
     * Returns XHTML for the heading.
     *
     * @param level The level of the heading. @see Heading
     * @param title the title for the heading
     * @param hd a Heading object to populate
     * @return An Element containing the heading
     */
    public Element makeHeading( final int level, final String title, final Heading hd ) {
        final Element el;
        final String pageName = context.getPage().getName();
        final String outTitle = makeSectionTitle( title );
        hd.m_level = level;

        switch( level ) {
            case Heading.HEADING_SMALL:
                el = new Element( "h4" ).setAttribute( "id", makeHeadingAnchor( pageName, outTitle, hd ) );
                break;

            case Heading.HEADING_MEDIUM:
                el = new Element( "h3" ).setAttribute( "id", makeHeadingAnchor( pageName, outTitle, hd ) );
                break;

            case Heading.HEADING_LARGE:
                el = new Element( "h2" ).setAttribute( "id", makeHeadingAnchor( pageName, outTitle, hd ) );
                break;

            default:
                throw new InternalWikiException( "Illegal heading type " + level );
        }

        return el;
    }

    /**
     * Handles the heading markup (!, !!, !!!).
     *
     * @return The created heading element
     * @throws IOException If reading fails
     */
    public Element handleHeading() throws IOException {
        final Element el;
        final int ch = parser.nextToken();
        final Heading hd = new Heading();
        if( ch == '!' ) {
            final int ch2 = parser.nextToken();
            if( ch2 == '!' ) {
                final String title = parser.peekAheadLine();
                el = makeHeading( Heading.HEADING_LARGE, title, hd );
            } else {
                parser.pushBack( ch2 );
                final String title = parser.peekAheadLine();
                el = makeHeading( Heading.HEADING_MEDIUM, title, hd );
            }
        } else {
            parser.pushBack( ch );
            final String title = parser.peekAheadLine();
            el = makeHeading( Heading.HEADING_SMALL, title, hd );
        }

        callHeadingListenerChain( hd );
        m_lastHeading = hd;
        if( el != null ) {
            parser.pushElement( el );
        }
        return el;
    }

    /**
     * Closes any open heading elements and adds the hash anchor link.
     */
    public void closeHeadings() {
        if( m_lastHeading != null && !wysiwygEditorMode ) {
            // Add the hash anchor element at the end of the heading
            parser.addElement( new Element( "a" ).setAttribute( "class", MarkupParser.HASHLINK )
                                                 .setAttribute( "href", "#" + m_lastHeading.m_titleAnchor )
                                                 .setText( "#" ) );
            m_lastHeading = null;
        }
        parser.popElement( "h2" );
        parser.popElement( "h3" );
        parser.popElement( "h4" );
    }

}
