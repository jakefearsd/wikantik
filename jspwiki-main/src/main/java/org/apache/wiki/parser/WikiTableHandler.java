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

import org.jdom2.Element;

import java.io.IOException;

/**
 * Handles table markup for JSPWikiMarkupParser.
 * Tables are created using the pipe character (|) syntax.
 *
 * @since 2.12
 */
public class WikiTableHandler {

    private final JSPWikiMarkupParser parser;
    private final WikiFormattingHandler formattingHandler;

    // Table state
    private boolean istable;
    private int rowNum = 1;

    /**
     * Constructs a WikiTableHandler.
     *
     * @param parser The parent JSPWikiMarkupParser
     * @param formattingHandler The formatting handler for block-level operations
     */
    public WikiTableHandler( final JSPWikiMarkupParser parser, final WikiFormattingHandler formattingHandler ) {
        this.parser = parser;
        this.formattingHandler = formattingHandler;
    }

    // =========================================================================
    // State Accessors
    // =========================================================================

    /**
     * @return true if currently inside a table
     */
    public boolean isInTable() {
        return istable;
    }

    /**
     * Ends the current table.
     * Called when a line doesn't start with a pipe character.
     */
    public void endTable() {
        if( istable ) {
            parser.popElement( "table" );
            istable = false;
        }
    }

    // =========================================================================
    // Table Handler
    // =========================================================================

    /**
     * Handles the pipe character (|) for table markup.
     * <ul>
     * <li>| at start of line begins a new row</li>
     * <li>|| creates a header cell (th)</li>
     * <li>| creates a regular cell (td)</li>
     * </ul>
     *
     * @param newLine true if we're at the start of a new line
     * @return The created element, or null if not valid table context
     * @throws IOException If reading fails
     */
    public Element handleBar( final boolean newLine ) throws IOException {
        Element el;
        if( !istable && !newLine ) {
            return null;
        }

        // If the bar is in the first column, we will either start a new table or continue the old one.
        if( newLine ) {
            if( !istable ) {
                formattingHandler.startBlockLevel();
                el = parser.pushElement( new Element( "table" ).setAttribute( "class", "wikitable" ).setAttribute( "border", "1" ) );
                istable = true;
                rowNum = 0;
            }

            rowNum++;
            final Element tr = ( rowNum % 2 != 0 )
                       ? new Element( "tr" ).setAttribute( "class", "odd" )
                       : new Element( "tr" );
            el = parser.pushElement( tr );
        }

        // Check out which table cell element to start; a header element (th) or a regular element (td).
        final int ch = parser.nextToken();
        if( ch == '|' ) {
            if( !newLine ) {
                el = parser.popElement( "th" );
                if( el == null ) parser.popElement( "td" );
            }
            el = parser.pushElement( new Element( "th" ) );
        } else {
            if( !newLine ) {
                el = parser.popElement( "td" );
                if( el == null ) parser.popElement( "th" );
            }
            el = parser.pushElement( new Element( "td" ) );
            parser.pushBack( ch );
        }
        return el;
    }

}
