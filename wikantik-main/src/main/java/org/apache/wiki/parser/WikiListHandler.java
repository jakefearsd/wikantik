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
    private int genlistlevel;
    private final StringBuilder genlistBulletBuffer = new StringBuilder( 10 );
    private final boolean allowPHPWikiStyleLists = true;

    // Definition list state
    private boolean isdefinition;

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
        return genlistlevel;
    }

    /**
     * @return true if currently in a definition list
     */
    public boolean isDefinition() {
        return isdefinition;
    }

    /**
     * Sets the definition list state.
     * @param isDefinition true if in a definition list
     */
    public void setDefinition( final boolean isDefinition ) {
        isdefinition = isDefinition;
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
        if( allowPHPWikiStyleLists ) {
            // only substitute if different
            if( !( strBullets.substring( 0, Math.min( numBullets, genlistlevel ) )
                    .equals( genlistBulletBuffer.substring( 0, Math.min( numBullets, genlistlevel ) ) ) ) ) {
                if( numBullets <= genlistlevel ) {
                    // Substitute all but the last character (keep the expressed bullet preference)
                    strBullets = ( numBullets > 1 ? genlistBulletBuffer.substring( 0, numBullets - 1 ) : "" ) +
                                 strBullets.charAt( numBullets - 1 );
                } else {
                    strBullets = genlistBulletBuffer + strBullets.substring( genlistlevel, numBullets );
                }
            }
        }

        // Check if this is still of the same type
        if( strBullets.substring( 0, Math.min( numBullets, genlistlevel ) )
                .equals( genlistBulletBuffer.substring( 0, Math.min( numBullets, genlistlevel ) ) ) ) {
            if( numBullets > genlistlevel ) {
                parser.pushElement( new Element( getListType( strBullets.charAt( genlistlevel++ ) ) ) );
                for( ; genlistlevel < numBullets; genlistlevel++ ) {
                    // bullets are growing, get from new bullet list
                    parser.pushElement( new Element( "li" ) );
                    parser.pushElement( new Element( getListType( strBullets.charAt( genlistlevel ) ) ) );
                }
            } else if( numBullets < genlistlevel ) {
                // Close the previous list item.
                parser.popElement( "li" );
                for( ; genlistlevel > numBullets; genlistlevel-- ) {
                    // bullets are shrinking, get from old bullet list
                    parser.popElement( getListType( genlistBulletBuffer.charAt( genlistlevel - 1 ) ) );
                    if( genlistlevel > 0 ) {
                        parser.popElement( "li" );
                    }
                }
            } else {
                if( genlistlevel > 0 ) {
                    parser.popElement( "li" );
                }
            }
        } else {
            // The pattern has changed, unwind and restart
            int numEqualBullets;
            final int numCheckBullets;

            // find out how much is the same
            numEqualBullets = 0;
            numCheckBullets = Math.min( numBullets, genlistlevel );

            while( numEqualBullets < numCheckBullets ) {
                // if the bullets are equal so far, keep going
                if( strBullets.charAt( numEqualBullets ) == genlistBulletBuffer.charAt( numEqualBullets ) )
                    numEqualBullets++;
                // otherwise give up, we have found how many are equal
                else
                    break;
            }

            // unwind
            for( ; genlistlevel > numEqualBullets; genlistlevel-- ) {
                parser.popElement( getListType( genlistBulletBuffer.charAt( genlistlevel - 1 ) ) );
                if( genlistlevel > numBullets ) {
                    parser.popElement( "li" );
                }
            }

            // rewind
            parser.pushElement( new Element( getListType( strBullets.charAt( numEqualBullets++ ) ) ) );
            for( int i = numEqualBullets; i < numBullets; i++ ) {
                parser.pushElement( new Element( "li" ) );
                parser.pushElement( new Element( getListType( strBullets.charAt( i ) ) ) );
            }
            genlistlevel = numBullets;
        }

        // Push a new list item, and eat away any extra whitespace
        parser.pushElement( new Element( "li" ) );
        parser.readWhile( " " );

        // work done, remember the new bullet list (in place of old one)
        genlistBulletBuffer.setLength( 0 );
        genlistBulletBuffer.append( strBullets );
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
        for( ; genlistlevel > 0; genlistlevel-- ) {
            parser.popElement( "li" );
            parser.popElement( getListType( genlistBulletBuffer.charAt( genlistlevel - 1 ) ) );
        }
        genlistBulletBuffer.setLength( 0 );
        return null;
    }

    /**
     * Handles definition list markup (;term:definition).
     *
     * @return The created element, or null if already in a definition
     */
    public Element handleDefinitionList() {
        if( !isdefinition ) {
            isdefinition = true;
            formattingHandler.startBlockLevel();
            parser.pushElement( new Element( "dl" ) );
            return parser.pushElement( new Element( "dt" ) );
        }
        return null;
    }

}
