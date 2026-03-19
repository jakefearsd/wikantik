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
package com.wikantik.parser;

import org.jdom2.Element;

import java.io.IOException;

/**
 * Handles inline formatting markup for WikantikMarkupParser.
 * This includes bold, italic, preformatted text, code blocks, and escape sequences.
 *
 * @since 2.12
 */
public class WikiFormattingHandler {

    private final WikantikMarkupParser parser;
    private final boolean wysiwygEditorMode;

    // Formatting state
    private boolean isbold;
    private boolean isitalic;
    private boolean isPre;
    private boolean isEscaping;
    private boolean isPreBlock;

    // Paragraph/block state
    private boolean restartitalic;
    private boolean restartbold;
    private boolean isOpenParagraph;

    /**
     * Constructs a WikiFormattingHandler.
     *
     * @param parser The parent WikantikMarkupParser
     * @param wysiwygEditorMode Whether we're in WYSIWYG editor mode
     */
    public WikiFormattingHandler( final WikantikMarkupParser parser, final boolean wysiwygEditorMode ) {
        this.parser = parser;
        this.wysiwygEditorMode = wysiwygEditorMode;
    }

    // =========================================================================
    // State Accessors
    // =========================================================================

    /**
     * @return true if currently in bold mode
     */
    public boolean isBold() {
        return isbold;
    }

    /**
     * @return true if currently in italic mode
     */
    public boolean isItalic() {
        return isitalic;
    }

    /**
     * @return true if currently in preformatted mode
     */
    public boolean isPre() {
        return isPre;
    }

    /**
     * @return true if currently escaping content
     */
    public boolean isEscaping() {
        return isEscaping;
    }

    /**
     * Sets the escaping state.
     * @param isEscaping true if escaping
     */
    public void setEscaping( final boolean isEscaping ) {
        this.isEscaping = isEscaping;
    }

    /**
     * @return true if preformatted block is block-level (vs inline)
     */
    public boolean isPreBlock() {
        return isPreBlock;
    }

    /**
     * @return true if a paragraph is currently open
     */
    public boolean isOpenParagraph() {
        return isOpenParagraph;
    }

    /**
     * Sets the open paragraph state.
     * @param isOpen true if paragraph is open
     */
    public void setOpenParagraph( final boolean isOpen ) {
        isOpenParagraph = isOpen;
    }

    /**
     * @return true if italic should be restarted after paragraph
     */
    public boolean shouldRestartItalic() {
        return restartitalic;
    }

    /**
     * Sets the restart italic flag.
     * @param restart true to restart italic
     */
    public void setRestartItalic( final boolean restart ) {
        restartitalic = restart;
    }

    /**
     * @return true if bold should be restarted after paragraph
     */
    public boolean shouldRestartBold() {
        return restartbold;
    }

    /**
     * Sets the restart bold flag.
     * @param restart true to restart bold
     */
    public void setRestartBold( final boolean restart ) {
        restartbold = restart;
    }

    // =========================================================================
    // Formatting Handlers
    // =========================================================================

    /**
     * Handles backslash sequences for line breaks.
     * Single backslash = line break, double backslash = line break with clear.
     *
     * @return The created element, or null if not a line break sequence
     * @throws IOException If reading fails
     */
    public Element handleBackslash() throws IOException {
        final int ch = parser.nextToken();
        if( ch == '\\' ) {
            final int ch2 = parser.nextToken();
            if( ch2 == '\\' ) {
                parser.pushElement( new Element( "br" ).setAttribute( "clear", "all" ) );
                return parser.popElement( "br" );
            }
            parser.pushBack( ch2 );
            parser.pushElement( new Element( "br" ) );
            return parser.popElement( "br" );
        }
        parser.pushBack( ch );
        return null;
    }

    /**
     * Handles underscore sequences for bold text.
     * Double underscore toggles bold mode.
     *
     * @return The created element, or null if not a bold sequence
     * @throws IOException If reading fails
     */
    public Element handleUnderscore() throws IOException {
        final int ch = parser.nextToken();
        Element el = null;
        if( ch == '_' ) {
            if( isbold ) {
                el = parser.popElement( "b" );
            } else {
                el = parser.pushElement( new Element( "b" ) );
            }
            isbold = !isbold;
        } else {
            parser.pushBack( ch );
        }

        return el;
    }

    /**
     * Handles apostrophe sequences for italic text.
     * Double apostrophe toggles italic mode.
     *
     * @return The created element, or null if not an italic sequence
     * @throws IOException If reading fails
     */
    public Element handleApostrophe() throws IOException {
        final int ch = parser.nextToken();
        Element el = null;

        if( ch == '\'' ) {
            if( isitalic ) {
                el = parser.popElement( "i" );
            } else {
                el = parser.pushElement( new Element( "i" ) );
            }
            isitalic = !isitalic;
        } else {
            parser.pushBack( ch );
        }

        return el;
    }

    /**
     * Handles open brace sequences for preformatted/code blocks.
     * {{{ = preformatted block, {{ = monospace text
     *
     * @param isBlock true if at start of line (block-level context)
     * @return The created element, or null if not a brace sequence
     * @throws IOException If reading fails
     */
    public Element handleOpenbrace( final boolean isBlock ) throws IOException {
        final int ch = parser.nextToken();
        if( ch == '{' ) {
            final int ch2 = parser.nextToken();
            if( ch2 == '{' ) {
                isPre = true;
                isEscaping = true;
                isPreBlock = isBlock;
                if( isBlock ) {
                    startBlockLevel();
                    return parser.pushElement( new Element( "pre" ) );
                }

                return parser.pushElement( new Element( "span" ).setAttribute( "class", "inline-code" ) );
            }
            parser.pushBack( ch2 );
            return parser.pushElement( new Element( "tt" ) );
        }
        parser.pushBack( ch );
        return null;
    }

    /**
     * Handles close brace sequences.
     * }}} = end preformatted block, }} = end monospace text
     *
     * @return The created element, or null if not a close brace sequence
     * @throws IOException If reading fails
     */
    public Element handleClosebrace() throws IOException {
        final int ch2 = parser.nextToken();
        if( ch2 == '}' ) {
            final int ch3 = parser.nextToken();
            if( ch3 == '}' ) {
                if( isPre ) {
                    if( isPreBlock ) {
                        parser.popElement( "pre" );
                    } else {
                        parser.popElement( "span" );
                    }
                    isPre = false;
                    isEscaping = false;
                    return parser.getCurrentElement();
                }
                parser.getPlainTextBuf().append( "}}}" );
                return parser.getCurrentElement();
            }
            parser.pushBack( ch3 );
            if( !isEscaping ) {
                return parser.popElement( "tt" );
            }
        }
        parser.pushBack( ch2 );
        return null;
    }

    /**
     * Handles tilde escape sequences.
     * Tilde followed by certain characters escapes them from wiki interpretation.
     *
     * @return The current element, or null if not an escape sequence
     * @throws IOException If reading fails
     */
    public Element handleTilde() throws IOException {
        final int ch = parser.nextToken();

        if( ch == ' ' ) {
            if( wysiwygEditorMode ) {
                parser.getPlainTextBuf().append( "~ " );
            }
            return parser.getCurrentElement();
        }

        if( ch == '|' || ch == '~' || ch == '\\' || ch == '*' || ch == '#' ||
            ch == '-' || ch == '!' || ch == '\'' || ch == '_' || ch == '[' ||
            ch == '{' || ch == ']' || ch == '}' || ch == '%' ) {
            if( wysiwygEditorMode ) {
                parser.getPlainTextBuf().append( '~' );
            }
            parser.getPlainTextBuf().append( ( char ) ch );
            parser.getPlainTextBuf().append( parser.readWhile( "" + ( char ) ch ) );
            return parser.getCurrentElement();
        }
        // No escape.
        parser.pushBack( ch );
        return null;
    }

    /**
     * Starts a block level element, closing any open inline formatting.
     * This is called when entering block-level constructs like lists, tables, headings.
     */
    public void startBlockLevel() {
        // These may not continue over block level limits in XHTML
        parser.popElement( "i" );
        parser.popElement( "b" );
        parser.popElement( "tt" );
        if( isOpenParagraph ) {
            isOpenParagraph = false;
            parser.popElement( "p" );
            parser.getPlainTextBuf().append( "\n" ); // Just small beautification
        }
        restartitalic = isitalic;
        restartbold = isbold;
        isitalic = false;
        isbold = false;
    }

    /**
     * Restarts italic formatting after a paragraph break if needed.
     */
    public void restartItalicIfNeeded() {
        if( restartitalic ) {
            parser.pushElement( new Element( "i" ) );
            isitalic = true;
            restartitalic = false;
        }
    }

    /**
     * Restarts bold formatting after a paragraph break if needed.
     */
    public void restartBoldIfNeeded() {
        if( restartbold ) {
            parser.pushElement( new Element( "b" ) );
            isbold = true;
            restartbold = false;
        }
    }

}
