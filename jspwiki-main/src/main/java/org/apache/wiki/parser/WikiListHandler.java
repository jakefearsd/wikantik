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

import org.apache.wiki.InternalWikiException;
import org.jdom2.Element;

import java.io.IOException;

/**
 * Handles list markup (ordered, unordered, and definition lists) for JSPWikiMarkupParser.
 *
 * @since 2.12
 */
public class WikiListHandler {

    private final JSPWikiMarkupParser parser;
    private final WikiFormattingHandler formattingHandler;

    // List state
    private int m_genlistlevel;
    private final StringBuilder m_genlistBulletBuffer = new StringBuilder( 10 );
    private final boolean m_allowPHPWikiStyleLists = true;

    // Definition list state
    private boolean m_isdefinition;

    /**
     * Constructs a WikiListHandler.
     *
     * @param parser The parent JSPWikiMarkupParser
     * @param formattingHandler The formatting handler for block-level operations
     */
    public WikiListHandler( final JSPWikiMarkupParser parser, final WikiFormattingHandler formattingHandler ) {
        this.parser = parser;
        this.formattingHandler = formattingHandler;
    }

    // =========================================================================
    // State Accessors
    // =========================================================================

    /**
     * @return The current list nesting level
     */
    public int getListLevel() {
        return m_genlistlevel;
    }

    /**
     * @return true if currently in a definition list
     */
    public boolean isDefinition() {
        return m_isdefinition;
    }

    /**
     * Sets the definition list state.
     * @param isDefinition true if in a definition list
     */
    public void setDefinition( final boolean isDefinition ) {
        m_isdefinition = isDefinition;
    }

    // =========================================================================
    // List Handlers
    // =========================================================================

    /**
     * Returns the HTML list type for the given bullet character.
     *
     * @param c The bullet character ('*' or '#')
     * @return "ul" for unordered lists, "ol" for ordered lists
     * @throws InternalWikiException if the character is not a valid list type
     */
    private static String getListType( final char c ) {
        if( c == '*' ) {
            return "ul";
        } else if( c == '#' ) {
            return "ol";
        }
        throw new InternalWikiException( "Parser got faulty list type: " + c );
    }

    /**
     * Handles both ordered ('#') and unordered ('*') lists, including mixed nesting.
     * Supports PHPWiki-style list continuation.
     *
     * @return The current element after list processing
     * @throws IOException If reading fails
     */
    public Element handleGeneralList() throws IOException {
        formattingHandler.startBlockLevel();
        String strBullets = parser.readWhile( "*#" );
        final int numBullets = strBullets.length();

        // Override the beginning portion of bullet pattern to be like the previous
        // to simulate PHPWiki style lists
        if( m_allowPHPWikiStyleLists ) {
            // only substitute if different
            if( !( strBullets.substring( 0, Math.min( numBullets, m_genlistlevel ) )
                    .equals( m_genlistBulletBuffer.substring( 0, Math.min( numBullets, m_genlistlevel ) ) ) ) ) {
                if( numBullets <= m_genlistlevel ) {
                    // Substitute all but the last character (keep the expressed bullet preference)
                    strBullets = ( numBullets > 1 ? m_genlistBulletBuffer.substring( 0, numBullets - 1 ) : "" ) +
                                 strBullets.charAt( numBullets - 1 );
                } else {
                    strBullets = m_genlistBulletBuffer + strBullets.substring( m_genlistlevel, numBullets );
                }
            }
        }

        // Check if this is still of the same type
        if( strBullets.substring( 0, Math.min( numBullets, m_genlistlevel ) )
                .equals( m_genlistBulletBuffer.substring( 0, Math.min( numBullets, m_genlistlevel ) ) ) ) {
            if( numBullets > m_genlistlevel ) {
                parser.pushElement( new Element( getListType( strBullets.charAt( m_genlistlevel++ ) ) ) );
                for( ; m_genlistlevel < numBullets; m_genlistlevel++ ) {
                    // bullets are growing, get from new bullet list
                    parser.pushElement( new Element( "li" ) );
                    parser.pushElement( new Element( getListType( strBullets.charAt( m_genlistlevel ) ) ) );
                }
            } else if( numBullets < m_genlistlevel ) {
                // Close the previous list item.
                parser.popElement( "li" );
                for( ; m_genlistlevel > numBullets; m_genlistlevel-- ) {
                    // bullets are shrinking, get from old bullet list
                    parser.popElement( getListType( m_genlistBulletBuffer.charAt( m_genlistlevel - 1 ) ) );
                    if( m_genlistlevel > 0 ) {
                        parser.popElement( "li" );
                    }
                }
            } else {
                if( m_genlistlevel > 0 ) {
                    parser.popElement( "li" );
                }
            }
        } else {
            // The pattern has changed, unwind and restart
            int numEqualBullets;
            final int numCheckBullets;

            // find out how much is the same
            numEqualBullets = 0;
            numCheckBullets = Math.min( numBullets, m_genlistlevel );

            while( numEqualBullets < numCheckBullets ) {
                // if the bullets are equal so far, keep going
                if( strBullets.charAt( numEqualBullets ) == m_genlistBulletBuffer.charAt( numEqualBullets ) )
                    numEqualBullets++;
                // otherwise give up, we have found how many are equal
                else
                    break;
            }

            // unwind
            for( ; m_genlistlevel > numEqualBullets; m_genlistlevel-- ) {
                parser.popElement( getListType( m_genlistBulletBuffer.charAt( m_genlistlevel - 1 ) ) );
                if( m_genlistlevel > numBullets ) {
                    parser.popElement( "li" );
                }
            }

            // rewind
            parser.pushElement( new Element( getListType( strBullets.charAt( numEqualBullets++ ) ) ) );
            for( int i = numEqualBullets; i < numBullets; i++ ) {
                parser.pushElement( new Element( "li" ) );
                parser.pushElement( new Element( getListType( strBullets.charAt( i ) ) ) );
            }
            m_genlistlevel = numBullets;
        }

        // Push a new list item, and eat away any extra whitespace
        parser.pushElement( new Element( "li" ) );
        parser.readWhile( " " );

        // work done, remember the new bullet list (in place of old one)
        m_genlistBulletBuffer.setLength( 0 );
        m_genlistBulletBuffer.append( strBullets );
        return parser.getCurrentElement();
    }

    /**
     * Unwinds (closes) all open list levels.
     * Called when a list ends (e.g., empty line or different block element).
     *
     * @return null (list unwinding doesn't produce an element)
     */
    public Element unwindGeneralList() {
        // unwind
        for( ; m_genlistlevel > 0; m_genlistlevel-- ) {
            parser.popElement( "li" );
            parser.popElement( getListType( m_genlistBulletBuffer.charAt( m_genlistlevel - 1 ) ) );
        }
        m_genlistBulletBuffer.setLength( 0 );
        return null;
    }

    /**
     * Handles definition list markup (;term:definition).
     *
     * @return The created element, or null if already in a definition
     */
    public Element handleDefinitionList() {
        if( !m_isdefinition ) {
            m_isdefinition = true;
            formattingHandler.startBlockLevel();
            parser.pushElement( new Element( "dl" ) );
            return parser.pushElement( new Element( "dt" ) );
        }
        return null;
    }

}
