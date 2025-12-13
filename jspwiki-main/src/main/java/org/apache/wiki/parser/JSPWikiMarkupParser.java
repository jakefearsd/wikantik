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

    import org.apache.commons.text.StringEscapeUtils;
    import org.apache.logging.log4j.LogManager;
    import org.apache.logging.log4j.Logger;
    import org.apache.wiki.StringTransmutator;
    import org.apache.wiki.api.core.Acl;
    import org.apache.wiki.api.core.Context;
    import org.apache.wiki.api.core.Page;
    import org.apache.wiki.auth.AuthorizationManager;
    import org.apache.wiki.auth.UserManager;
    import org.apache.wiki.auth.WikiSecurityException;
    import org.apache.wiki.auth.acl.AclManager;
    import org.apache.wiki.i18n.InternationalizationManager;
    import org.apache.wiki.preferences.Preferences;
    import org.apache.wiki.util.TextUtil;
    import org.apache.wiki.variables.VariableManager;
    import org.jdom2.Attribute;
    import org.jdom2.Content;
    import org.jdom2.Element;
    import org.jdom2.IllegalDataException;
    import org.jdom2.ProcessingInstruction;
    import org.jdom2.Verifier;

    import javax.xml.transform.Result;
    import java.io.IOException;
    import java.io.Reader;
    import java.text.MessageFormat;
    import java.util.ArrayDeque;
    import java.util.ArrayList;
    import java.util.Arrays;
    import java.util.Collection;
    import java.util.Deque;
    import java.util.Iterator;
    import java.util.List;
    import java.util.NoSuchElementException;
    import java.util.Properties;
    import java.util.ResourceBundle;
    import java.util.regex.Matcher;
    import java.util.regex.Pattern;

/**
 * Parses JSPWiki-style markup into a WikiDocument DOM tree.  This class is the heart and soul of JSPWiki : make
 * sure you test properly anything that is added, or else it breaks down horribly.
 *
 *  @since  2.4
 */
public class JSPWikiMarkupParser extends MarkupParser {

    protected static final int              READ          = 0;
    protected static final int              EDIT          = 1;
    protected static final int              EMPTY         = 2;  // Empty message
    protected static final int              LOCAL         = 3;
    protected static final int              LOCALREF      = 4;
    protected static final int              IMAGE         = 5;
    protected static final int              EXTERNAL      = 6;
    protected static final int              INTERWIKI     = 7;
    protected static final int              IMAGELINK     = 8;
    protected static final int              IMAGEWIKILINK = 9;
    protected static final int              ATTACHMENT    = 10;

    private static final Logger LOG = LogManager.getLogger( JSPWikiMarkupParser.class );

    /** Contains style information, in multiple forms. */
    private final Deque< Boolean > styleStack = new ArrayDeque<>();

    /** Parser for extended link functionality. */
    private final LinkParser linkParser = new LinkParser();

    /** Keeps track of any plain text that gets put in the Text nodes */
    private StringBuilder plainTextBuf = new StringBuilder( 20 );

    private Element currentElement;

    /** The link handler that manages all link-related operations */
    private WikiLinkHandler linkHandler;

    /** The formatting handler that manages inline formatting */
    private WikiFormattingHandler formattingHandler;

    /** The list handler that manages list markup */
    private WikiListHandler listHandler;

    /** The table handler that manages table markup */
    private WikiTableHandler tableHandler;

    /** The heading handler that manages heading markup */
    private WikiHeadingHandler headingHandler;

    /** If true, then considers CamelCase links as well. */
    private boolean camelCaseLinks;

    /** If true, then generate special output for wysiwyg editing in certain cases */
    private boolean wysiwygEditorMode;

    /** If true, consider URIs that have no brackets as well. */
    // FIXME: Currently reserved, but not used.
    private boolean plainUris;

    /** If true, all outward links use a small link image. */
    private boolean useOutlinkImage = true;

    private boolean useAttachmentImage = true;

    /** If true, allows raw HTML. */
    private boolean allowHTML;

    private boolean useRelNofollow;

    // Java regex version of WIKIWORD_REGEX - POSIX classes converted to Java Unicode properties
    static final String WIKIWORD_REGEX = "(^|[^\\p{Alnum}]+)(\\p{Upper}+\\p{Lower}+\\p{Upper}+\\p{Alnum}*|(http://|https://|mailto:)([A-Za-z0-9_/\\.\\+\\?\\#\\-\\@=&;~%]+))";

    private Pattern camelCasePattern;

    private static final String CAMELCASE_PATTERN = "JSPWikiMarkupParser.camelCasePattern";

    /**
     *  Creates a markup parser.
     *
     *  @param context The WikiContext which controls the parsing
     *  @param in Where the data is read from.
     */
    public JSPWikiMarkupParser( final Context context, final Reader in ) {
        super( context, in );
        initialize();
    }

    // FIXME: parsers should be pooled for better performance.
    private void initialize() {
        initInlineImagePatterns();

        camelCasePattern = engine.getAttribute( CAMELCASE_PATTERN );
        if( camelCasePattern == null ) {
            camelCasePattern = Pattern.compile( WIKIWORD_REGEX, Pattern.UNICODE_CHARACTER_CLASS );
            engine.setAttribute( CAMELCASE_PATTERN, camelCasePattern );
        }

        //  Set the properties.
        final Properties props = engine.getWikiProperties();
        final String cclinks = context.getPage().getAttribute( PROP_CAMELCASELINKS );

        if( cclinks != null ) {
            camelCaseLinks = TextUtil.isPositive( cclinks );
        } else {
            camelCaseLinks  = TextUtil.getBooleanProperty( props, PROP_CAMELCASELINKS, camelCaseLinks );
        }

        final Boolean wysiwygVariable = context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE );
        if( wysiwygVariable != null ) {
            wysiwygEditorMode = wysiwygVariable;
        }

        plainUris          = context.getBooleanWikiProperty( PROP_PLAINURIS, plainUris );
        useOutlinkImage    = context.getBooleanWikiProperty( PROP_USEOUTLINKIMAGE, useOutlinkImage );
        useAttachmentImage = context.getBooleanWikiProperty( PROP_USEATTACHMENTIMAGE, useAttachmentImage );
        allowHTML          = context.getBooleanWikiProperty( PROP_ALLOWHTML, allowHTML );
        useRelNofollow     = context.getBooleanWikiProperty( PROP_USERELNOFOLLOW, useRelNofollow );

        if( engine.getManager( UserManager.class ).getUserDatabase() == null || engine.getManager( AuthorizationManager.class ) == null ) {
            disableAccessRules();
        }

        context.getPage().setHasMetadata();

        // Initialize the link handler
        linkHandler = new WikiLinkHandler( this, context, linkParsingOperations,
                                             useOutlinkImage, useAttachmentImage,
                                             allowHTML, useRelNofollow, wysiwygEditorMode );

        // Initialize the formatting handler
        formattingHandler = new WikiFormattingHandler( this, wysiwygEditorMode );

        // Initialize the list handler
        listHandler = new WikiListHandler( this, formattingHandler );

        // Initialize the table handler
        tableHandler = new WikiTableHandler( this, formattingHandler );

        // Initialize the heading handler
        headingHandler = new WikiHeadingHandler( this, context, wysiwygEditorMode, headingListenerChain );
    }

    /**
     * Enables or disables raw HTML mode.
     * Package-private for use by WikiHeadingHandler's clean translator.
     *
     * @param allow true to allow raw HTML
     */
    void enableRawHtml( final boolean allow ) {
        allowHTML = allow;
    }

    // =========================================================================
    // Accessor methods for WikiLinkHandler (package-private)
    // =========================================================================

    /**
     * Returns the collection of link mutators.
     * @return The link mutators collection
     */
    Collection< StringTransmutator > getLinkMutators() {
        return linkMutators;
    }

    /**
     * Returns the local link mutator chain.
     * @return The local link mutator chain
     */
    Collection< StringTransmutator > getLocalLinkMutatorChain() {
        return localLinkMutatorChain;
    }

    /**
     * Returns the external link mutator chain.
     * @return The external link mutator chain
     */
    Collection< StringTransmutator > getExternalLinkMutatorChain() {
        return externalLinkMutatorChain;
    }

    /**
     * Returns the attachment link mutator chain.
     * @return The attachment link mutator chain
     */
    Collection< StringTransmutator > getAttachmentLinkMutatorChain() {
        return attachmentLinkMutatorChain;
    }

    /**
     * Returns the current element being processed.
     * @return The current element
     */
    Element getCurrentElement() {
        return currentElement;
    }

    /**
     * Returns the plain text buffer.
     * @return The plain text buffer
     */
    StringBuilder getPlainTextBuf() {
        return plainTextBuf;
    }

    /**
     *  Calls a transmutator chain.
     *
     *  @param list Chain to call
     *  @param text Text that should be passed to the mutate() method of each of the mutators in the chain.
     *  @return The result of the mutation.
     */
    protected String callMutatorChain( final Collection< StringTransmutator > list, String text ) {
        if( list == null || list.isEmpty()) {
            return text;
        }

        for( final StringTransmutator m : list ) {
            text = m.mutate( context, text );
        }

        return text;
    }


    /**
     *  Creates a link element. Delegates to WikiLinkHandler.
     *
     *  @param type One of the link type constants (READ, EDIT, etc.)
     *  @param link URL or page name to link to
     *  @param text Link text
     *  @param section If a particular section identifier is required.
     *  @param attributes Additional attributes for the element
     *  @return The created element
     */
    private Element makeLink( final int type, final String link, final String text, final String section, final Iterator< Attribute > attributes ) {
        return linkHandler.makeLink( type, link, text, section, attributes );
    }

    /**
     *  These are all the HTML 4.01 block-level elements.
     */
    private static final String[] BLOCK_ELEMENTS = {
        "address", "blockquote", "div", "dl", "fieldset", "form",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "hr", "noscript", "ol", "p", "pre", "table", "ul"
    };

    private static boolean isBlockLevel( final String name ) {
        return Arrays.binarySearch( BLOCK_ELEMENTS, name ) >= 0;
    }

    /**
     *  This method peeks ahead in the stream until EOL and returns the result. It will keep the buffers untouched.
     *  Package-private for use by WikiHeadingHandler.
     *
     *  @return The string from the current position to the end of line.
     */
    // FIXME: Always returns an empty line, even if the stream is full.
    String peekAheadLine() throws IOException {
        final String s = readUntilEOL().toString();
        if( s.length() > PUSHBACK_BUFFER_SIZE ) {
            LOG.warn( "Line is longer than maximum allowed size (" + PUSHBACK_BUFFER_SIZE + " characters.  Attempting to recover..." );
            pushBack( s.substring( 0, PUSHBACK_BUFFER_SIZE - 1 ) );
        } else {
            try {
                pushBack( s );
            } catch( final IOException e ) {
                LOG.warn( "Pushback failed: the line is probably too long.  Attempting to recover." );
            }
        }
        return s;
    }

    /**
     * Flushes any pending plain text to the current element.
     * Package-private for use by WikiLinkHandler.
     *
     * @return The number of characters flushed
     */
    int flushPlainText() {
        final int numChars = plainTextBuf.length();
        if( numChars > 0 ) {
            String buf;

            if( !allowHTML ) {
                buf = TextUtil.escapeHTMLEntities( plainTextBuf.toString() );
            } else {
                buf = plainTextBuf.toString();
            }
            //  We must first empty the buffer because the side effect of calling makeCamelCaseLink() is to call this routine.
            plainTextBuf = new StringBuilder(20);
            try {
                // This is the heaviest part of parsing, and therefore we can do some optimization here.
                // 1) Only when the length of the buffer is big enough, we try to do the match
                if( camelCaseLinks && !formattingHandler.isEscaping() && buf.length() > 3 ) {
                    Matcher matcher = camelCasePattern.matcher( buf );
                    while( matcher.find() ) {
                        final String firstPart = buf.substring( 0, matcher.start() );
                        String prefix = matcher.group( 1 );
                        if( prefix == null ) {
                            prefix = "";
                        }

                        final String camelCase = matcher.group(2);
                        final String protocol  = matcher.group(3);
                        String uri       = protocol+matcher.group(4);
                        buf              = buf.substring(matcher.end());
                        matcher = camelCasePattern.matcher( buf );

                        currentElement.addContent( firstPart );
                        //  Check if the user does not wish to do URL or WikiWord expansion
                        if( prefix.endsWith( "~" ) || prefix.indexOf( '[' ) != -1 ) {
                            if( prefix.endsWith( "~" ) ) {
                                if( wysiwygEditorMode ) {
                                    currentElement.addContent( "~" );
                                }
                                prefix = prefix.substring( 0, prefix.length() - 1 );
                            }
                            if( camelCase != null ) {
                                currentElement.addContent( prefix + camelCase );
                            } else if( protocol != null ) {
                                currentElement.addContent( prefix + uri );
                            }
                            continue;
                        }

                        // Fine, then let's check what kind of link this was and emit the proper elements
                        if( protocol != null ) {
                            final char c = uri.charAt( uri.length() - 1 );
                            if( c == '.' || c == ',' ) {
                                uri = uri.substring( 0, uri.length() - 1 );
                                buf = c + buf;
                            }
                            // System.out.println("URI match "+uri);
                            currentElement.addContent( prefix );
                            makeDirectURILink( uri );
                        } else {
                            // System.out.println("Matched: '"+camelCase+"'");
                            // System.out.println("Split to '"+firstPart+"', and '"+buf+"'");
                            // System.out.println("prefix="+prefix);
                            currentElement.addContent( prefix );
                            makeCamelCaseLink( camelCase );
                        }
                    }
                    currentElement.addContent( buf );
                } else {
                    //  No camelcase asked for, just add the elements
                    currentElement.addContent( buf );
                }
            } catch( final IllegalDataException e ) {
                // Sometimes it's possible that illegal XML chars is added to the data. Here we make sure it does not stop parsing.
                currentElement.addContent( makeError(cleanupSuspectData( e.getMessage() )) );
            }
        }

        return numChars;
    }

    /**
     * Pushes an element onto the element stack.
     * Package-private for use by WikiLinkHandler.
     *
     * @param e The element to push
     * @return The pushed element
     */
    Element pushElement( final Element e ) {
        flushPlainText();
        currentElement.addContent( e );
        currentElement = e;

        return e;
    }

    /**
     * Adds content to the current element.
     * Package-private for use by WikiLinkHandler.
     *
     * @param e The content to add
     * @return The current element
     */
    Element addElement( final Content e ) {
        if( e != null ) {
            flushPlainText();
            currentElement.addContent( e );
        }
        return currentElement;
    }

    /**
     *  All elements that can be empty by the HTML DTD.
     */
    //  Keep sorted.
    private static final String[] EMPTY_ELEMENTS = {
        "area", "base", "br", "col", "hr", "img", "input", "link", "meta", "p", "param"
    };

    /**
     *  Goes through the current element stack and pops all elements until this
     *  element is found - this essentially "closes" an element.
     *  Package-private for use by WikiLinkHandler.
     *
     *  @param s element to be found.
     *  @return The new current element, or null, if there was no such element in the entire stack.
     */
    Element popElement( final String s ) {
        final int flushedBytes = flushPlainText();
        Element currEl = currentElement;
        while( currEl.getParentElement() != null ) {
            if( currEl.getName().equals( s ) && !currEl.isRootElement() ) {
                currentElement = currEl.getParentElement();

                //  Check if it's okay for this element to be empty.  Then we will
                //  trick the JDOM generator into not generating an empty element,
                //  by putting an empty string between the tags.  Yes, it's a kludge
                //  but what'cha gonna do about it. :-)
                if( flushedBytes == 0 && Arrays.binarySearch( EMPTY_ELEMENTS, s ) < 0 ) {
                    currEl.addContent( "" );
                }
                return currentElement;
            }
            currEl = currEl.getParentElement();
        }
        return null;
    }


    /**
     * Reads the stream until it meets one of the specified ending characters, or stream end. The ending
     * character will be left in the stream.
     */
    private String readUntil( final String endChars ) throws IOException {
        final StringBuilder sb = new StringBuilder( 80 );
        int ch = nextToken();
        while( ch != -1 ) {
            if( ch == '\\' ) {
                ch = nextToken();
                if( ch == -1 ) {
                    break;
                }
            } else {
                if( endChars.indexOf( ( char )ch ) != -1 ) {
                    pushBack( ch );
                    break;
                }
            }
            sb.append( ( char )ch );
            ch = nextToken();
        }

        return sb.toString();
    }

    /**
     *  Reads the stream while the characters that have been specified are
     *  in the stream, returning then the result as a String.
     *  Package-private for use by WikiFormattingHandler.
     */
    String readWhile( final String endChars ) throws IOException {
        final StringBuilder sb = new StringBuilder( 80 );
        int ch = nextToken();
        while( ch != -1 ) {
            if( endChars.indexOf( ( char ) ch ) == -1 ) {
                pushBack( ch );
                break;
            }
            sb.append( ( char ) ch );
            ch = nextToken();
        }

        return sb.toString();
    }


    /**
     *  When given a link to a WikiName, we just return a proper HTML link for it.
     *  Delegates to WikiLinkHandler.
     */
    private Element makeCamelCaseLink( final String wikiname ) {
        return linkHandler.makeCamelCaseLink( wikiname );
    }

    /**
     * Returns an element for the external link image (out.png).
     * Delegates to WikiLinkHandler.
     *
     * @return An element containing the HTML for the outlink image.
     */
    private Element outlinkImage() {
        return linkHandler.outlinkImage();
    }

    /**
     *  Takes a URL and turns it into a regular wiki link.
     *  Delegates to WikiLinkHandler.
     *
     * @param url provided url.
     * @return An anchor Element containing the link.
     */
    private Element makeDirectURILink( final String url ) {
        return linkHandler.makeDirectURILink( url );
    }

    /**
     *  Image links are handled differently:
     *  1. If the text is a WikiName of an existing page, it gets linked.
     *  2. If the text is an external link, then it is inlined.
     *  3. Otherwise, it becomes an ALT text.
     *  Delegates to WikiLinkHandler.
     *
     *  @param reallink The link to the image.
     *  @param link     Link text portion, may be a link to somewhere else.
     *  @param hasLinkText If true, then the defined link had a link text available.
     */
    private Element handleImageLink( final String reallink, final String link, final boolean hasLinkText ) {
        return linkHandler.handleImageLink( reallink, link, hasLinkText );
    }

    /**
     * Handles access rule parsing [{ALLOW ...}] or [{DENY ...}].
     * Package-private for use by WikiLinkHandler.
     *
     * @param ruleLine The rule line to process
     * @return The current element
     */
    Element handleAccessRule( String ruleLine ) {
        if( wysiwygEditorMode ) {
            currentElement.addContent( "[" + ruleLine + "]" );
        }
        if( !parseAccessRules ) {
            return currentElement;
        }
        final Page page = context.getRealPage();
        // UserDatabase db = context.getEngine().getUserDatabase();

        if( ruleLine.startsWith( "{" ) ) {
            ruleLine = ruleLine.substring( 1 );
        }

        if( ruleLine.endsWith( "}" ) ) {
            ruleLine = ruleLine.substring( 0, ruleLine.length() - 1 );
        }

        LOG.debug("page={}, ACL = {}", page.getName(), ruleLine);

        try {
            final Acl acl = engine.getManager( AclManager.class ).parseAcl( page, ruleLine );
            page.setAcl( acl );
            LOG.debug( acl.toString() );
        } catch( final WikiSecurityException wse ) {
            return makeError( wse.getMessage() );
        }

        return currentElement;
    }

    /**
     *  Handles metadata setting [{SET foo=bar}].
     *  Package-private for use by WikiLinkHandler.
     *
     *  @param link The metadata link text
     *  @return The current element
     */
    Element handleMetadata( final String link ) {
        if( wysiwygEditorMode ) {
            currentElement.addContent( "[" + link + "]" );
        }

        try {
            final String args = link.substring( link.indexOf(' '), link.length()-1 );
            final String name = args.substring( 0, args.indexOf('=') ).trim();
            String val  = args.substring( args.indexOf('=')+1 ).trim();

            if( val.startsWith("'") ) {
                val = val.substring( 1 );
            }
            if( val.endsWith("'") ) {
                val = val.substring( 0, val.length()-1 );
            }

            // LOG.debug("SET name='"+name+"', value='"+val+"'.");

            if( !name.isEmpty() && !val.isEmpty() ) {
                val = engine.getManager( VariableManager.class ).expandVariables( context, val );
                context.getPage().setAttribute( name, val );
            }
        } catch( final Exception e ) {
            final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );
            return makeError( MessageFormat.format( rb.getString( "markupparser.error.invalidset" ), link ) );
        }

        return currentElement;
    }

    /**
     *  Emits a processing instruction that will disable markup escaping. This is
     *  very useful if you want to emit HTML directly into the stream.
     */
    private void disableOutputEscaping() {
        addElement( new ProcessingInstruction( Result.PI_DISABLE_OUTPUT_ESCAPING, "" ) );
    }

    /**
     *  Gobbles up all hyperlinks that are encased in square brackets.
     *  Delegates to WikiLinkHandler.
     */
    private Element handleHyperlinks( final String linktext, final int pos ) {
        return linkHandler.handleHyperlinks( linktext, pos );
    }

    /**
     *  Pushes back any string that has been read.  It will obviously be pushed back in a reverse order.
     *
     *  @since 2.1.77
     */
    private void pushBack( final String s ) throws IOException {
        for( int i = s.length()-1; i >= 0; i-- ) {
            pushBack( s.charAt(i) );
        }
    }

    private Element handleBackslash() throws IOException {
        return formattingHandler.handleBackslash();
    }

    private Element handleUnderscore() throws IOException {
        return formattingHandler.handleUnderscore();
    }


    /**
     *  For example: italics.
     */
    private Element handleApostrophe() throws IOException {
        return formattingHandler.handleApostrophe();
    }

    private Element handleOpenbrace( final boolean isBlock ) throws IOException {
        return formattingHandler.handleOpenbrace( isBlock );
    }

    /**
     *  Handles both }} and }}}
     */
    private Element handleClosebrace() throws IOException {
        return formattingHandler.handleClosebrace();
    }

    private Element handleDash() throws IOException {
        int ch = nextToken();
        if( ch == '-' ) {
            final int ch2 = nextToken();
            if( ch2 == '-' ) {
                final int ch3 = nextToken();
                if( ch3 == '-' ) {
                    // Empty away all the rest of the dashes.
                    // Do not forget to return the first non-match back.
                    do {
                        ch = nextToken();
                    } while ( ch == '-' );

                    pushBack( ch );
                    startBlockLevel();
                    pushElement( new Element( "hr" ) );
                    return popElement( "hr" );
                }
                pushBack( ch3 );
            }
            pushBack( ch2 );
        }
        pushBack( ch );
        return null;
    }

    /**
     * Handles heading markup (!, !!, !!!).
     * Delegates to WikiHeadingHandler.
     */
    private Element handleHeading() throws IOException {
        return headingHandler.handleHeading();
    }

    /**
     * Reads the stream until the next EOL or EOF.  Note that it will also read the EOL from the stream.
     */
    private StringBuilder readUntilEOL() throws IOException {
        int ch;
        final StringBuilder buf = new StringBuilder( 256 );
        while( true ) {
            ch = nextToken();
            if( ch == -1 ) {
                break;
            }
            buf.append( (char) ch );
            if( ch == '\n' ) {
                break;
            }
        }
        return buf;
    }

    private boolean newLine;

    /**
     * Starts a block level element, therefore closing a potential open paragraph tag.
     */
    private void startBlockLevel() {
        formattingHandler.startBlockLevel();
    }

    /**
     * Handles both ordered ('#') and unordered ('*') lists.
     * Delegates to WikiListHandler.
     */
    private Element handleGeneralList() throws IOException {
        return listHandler.handleGeneralList();
    }

    /**
     * Unwinds (closes) all open list levels.
     * Delegates to WikiListHandler.
     */
    private Element unwindGeneralList() {
        return listHandler.unwindGeneralList();
    }

    /**
     * Handles definition list markup (;term:definition).
     * Delegates to WikiListHandler.
     */
    private Element handleDefinitionList() {
        return listHandler.handleDefinitionList();
    }

    private Element handleOpenbracket() throws IOException {
        final StringBuilder sb = new StringBuilder( 40 );
        final int pos = getPosition();
        int ch = nextToken();
        boolean isPlugin = false;
        if( ch == '[' ) {
            if( wysiwygEditorMode ) {
                sb.append( '[' );
            }
            sb.append( ( char )ch );
            while( ( ch = nextToken() ) == '[' ) {
                sb.append( ( char )ch );
            }
        }

        if( ch == '{' ) {
            isPlugin = true;
        }

        pushBack( ch );

        if( sb.length() > 0 ) {
            plainTextBuf.append( sb );
            return currentElement;
        }

        //  Find end of hyperlink
        ch = nextToken();
        int nesting = 1; // Check for nested plugins
        while( ch != -1 ) {
            final int ch2 = nextToken();
            pushBack( ch2 );
            if( isPlugin ) {
                if( ch == '[' && ch2 == '{' ) {
                    nesting++;
                } else if( nesting == 0 && ch == ']' && sb.charAt(sb.length()-1) == '}' ) {
                    break;
                } else if( ch == '}' && ch2 == ']' ) {
                    // NB: This will be decremented once at the end
                    nesting--;
                }
            } else {
                if( ch == ']' ) {
                    break;
                }
            }

            sb.append( (char) ch );

            ch = nextToken();
        }

        //  If the link is never finished, do some tricks to display the rest of the line unchanged.
        if( ch == -1 ) {
            LOG.debug( "Warning: unterminated link detected!" );
            formattingHandler.setEscaping( true );
            plainTextBuf.append( sb );
            flushPlainText();
            formattingHandler.setEscaping( false );
            return currentElement;
        }

        return handleHyperlinks( sb.toString(), pos );
    }

    /**
     *  Reads the stream until the current brace is closed or stream end.
     */
    private String readBraceContent( final char opening, final char closing ) throws IOException {
        final StringBuilder sb = new StringBuilder( 40 );
        int braceLevel = 1;
        int ch;
        while( ( ch = nextToken() ) != -1 ) {
            if( ch == '\\' ) {
                continue;
            } else if( ch == opening ) {
                braceLevel++;
            } else if( ch == closing ) {
                braceLevel--;
                if( braceLevel == 0 ) {
                    break;
                }
            }
            sb.append( ( char ) ch );
        }
        return sb.toString();
    }


    /**
     * Handles constructs of type %%(style) and %%class
     * @return An Element containing the div or span, depending on the situation.
     * @throws IOException
     */
    private Element handleDiv( ) throws IOException {
        int ch = nextToken();
        Element el = null;

        if( ch == '%' ) {
            String style = null;
            String clazz = null;

            ch = nextToken();

            //  Style or class?
            if( ch == '(' ) {
                style = readBraceContent('(',')');
            } else if( Character.isLetter( (char) ch ) ) {
                pushBack( ch );
                clazz = readUntil( "( \t\n\r" );
                //Note: ref.https://www.w3.org/TR/CSS21/syndata.html#characters
                //CSS Classnames can contain only the characters [a-zA-Z0-9] and
                //ISO 10646 characters U+00A0 and higher, plus the "-" and the "_".
                //They cannot start with a digit, two hyphens, or a hyphen followed by a digit.

                //(1) replace '.' by spaces, allowing multiple classnames on a div or span
                //(2) remove any invalid character
                if( clazz != null ) {
                    clazz = clazz.replace( '.', ' ' )
                                 .replaceAll( "[^\\s-_\\w\\x200-\\x377]+", "" );
                }
                ch = nextToken();

                // check for %%class1.class2( style information )
                if( ch == '(' ) {
                    style = readBraceContent( '(', ')' );
                //  Pop out only spaces, so that the upcoming EOL check does not check the next line.
                } else if( ch == '\n' || ch == '\r' ) {
                    pushBack( ch );
                }
            } else {
                // Anything else stops.
                pushBack( ch );
                try {
                    final Boolean isSpan = styleStack.pop();
                    if( isSpan == null ) {
                        // Fail quietly
                    } else if( isSpan ) {
                        el = popElement( "span" );
                    } else {
                        el = popElement( "div" );
                    }
                } catch( final NoSuchElementException e ) {
                    LOG.debug( "Page '" + context.getName() + "' closes a %%-block that has not been opened." );
                    return currentElement;
                }
                return el;
            }

            //  Check if there is an attempt to do something nasty
            try {
                style = StringEscapeUtils.unescapeHtml4(style);
                if( style != null && style.contains( "javascript:" ) ) {
                    LOG.debug( "Attempt to output javascript within CSS: {}", style );
                    final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );
                    return addElement( makeError( rb.getString( "markupparser.error.javascriptattempt" ) ) );
                }
            } catch( final NumberFormatException e ) {
                //  If there are unknown entities, we don't want the parser to stop.
                final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );
                final String msg = MessageFormat.format( rb.getString( "markupparser.error.parserfailure"), e.getMessage() );
                return addElement( makeError( msg ) );
            }

            //  Decide if we should open a div or a span?
            final String eol = peekAheadLine();

            if( !eol.isBlank() ) {
                // There is stuff after the class
                el = new Element("span");
                styleStack.push( Boolean.TRUE );
            } else {
                startBlockLevel();
                el = new Element("div");
                styleStack.push( Boolean.FALSE );
            }

            if( style != null ) el.setAttribute("style", style);
            if( clazz != null ) el.setAttribute("class", clazz);
            return pushElement( el );
        }
        pushBack( ch );
        return el;
    }

    private Element handleSlash( ) throws IOException {
        final int ch = nextToken();
        pushBack( ch );
        if( ch == '%' && !styleStack.isEmpty() ) {
            return handleDiv();
        }

        return null;
    }

    /**
     * Handles table markup (pipe character).
     * Delegates to WikiTableHandler.
     */
    private Element handleBar( final boolean newLine ) throws IOException {
        return tableHandler.handleBar( newLine );
    }

    /**
     *  Generic escape of next character or entity.
     */
    private Element handleTilde() throws IOException {
        return formattingHandler.handleTilde();
    }

    private void fillBuffer( final Element startElement ) throws IOException {
        currentElement = startElement;
        newLine = true;
        boolean quitReading = false;
        disableOutputEscaping();
        while( !quitReading ) {
            final int ch = nextToken();
            if( ch == -1 ) {
                break;
            }

            //  Check if we're actually ending the preformatted mode. We still must do an entity transformation here.
            if( formattingHandler.isEscaping() ) {
                if( ch == '}' ) {
                    if( handleClosebrace() == null ) plainTextBuf.append( (char) ch );
                } else if( ch == -1 ) {
                    quitReading = true;
                } else if( ch == '\r' ) {
                    // DOS line feeds we ignore.
                } else if( ch == '<' ) {
                    plainTextBuf.append( "&lt;" );
                } else if( ch == '>' ) {
                    plainTextBuf.append( "&gt;" );
                } else if( ch == '&' ) {
                    plainTextBuf.append( "&amp;" );
                } else if( ch == '~' ) {
                    String braces = readWhile( "}" );
                    if( braces.length() >= 3 ) {
                        plainTextBuf.append( "}}}" );
                        braces = braces.substring(3);
                    } else {
                        plainTextBuf.append( (char) ch );
                    }

                    for( int i = braces.length()-1; i >= 0; i-- ) {
                        pushBack( braces.charAt( i ) );
                    }
                } else {
                    plainTextBuf.append( (char) ch );
                }

                continue;
            }

            //  An empty line stops a list
            if( newLine && ch != '*' && ch != '#' && ch != ' ' && listHandler.getListLevel() > 0 ) {
                plainTextBuf.append(unwindGeneralList());
            }

            if( newLine && ch != '|' && tableHandler.isInTable() ) {
                tableHandler.endTable();
            }

            int skip = IGNORE;
            //  Do the actual parsing and catch any errors.
            try {
                skip = parseToken( ch );
            } catch( final IllegalDataException e ) {
                LOG.info( "Page {} contains data which cannot be added to DOM tree: {}", context.getPage().getName(), e.getMessage() );
                makeError( "Error: " + cleanupSuspectData( e.getMessage() ) );
            }

            // The idea is as follows:  If the handler method returns an element (el != null), it is assumed that it
            // has been added in the stack.  Otherwise, the character is added as is to the plaintext buffer.
            //
            // For the transition phase, if s != null, it also gets added in the plaintext buffer.
            switch( skip ) {
                case ELEMENT:
                    newLine = false;
                    break;

                case CHARACTER:
                    plainTextBuf.append( (char) ch );
                    newLine = false;
                    break;

                case IGNORE:
                default:
                    break;
            }
        }

        closeHeadings();
        popElement( "domroot" );
    }

    private String cleanupSuspectData( final String s ) {
        final StringBuilder sb = new StringBuilder( s.length() );
        for( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt(i);
            if( Verifier.isXMLCharacter( c ) ) sb.append( c );
            else sb.append( "0x" ).append( Integer.toString( c, 16 ).toUpperCase() );
        }

        return sb.toString();
    }

    /** The token is a plain character. */
    protected static final int CHARACTER = 0;

    /** The token is a wikimarkup element. */
    protected static final int ELEMENT   = 1;

    /** The token is to be ignored. */
    protected static final int IGNORE    = 2;

    /**
     *  Return CHARACTER, if you think this was a plain character; ELEMENT, if
     *  you think this was a wiki markup element, and IGNORE, if you think
     *  we should ignore this altogether.
     *  <p>
     *  To add your own MarkupParser, you can override this method, but it
     *  is recommended that you call super.parseToken() as well to gain advantage
     *  of JSPWiki's own markup.  You can call it at the start of your own
     *  parseToken() or end - it does not matter.
     *
     * @param ch The character under investigation
     * @return {@link #ELEMENT}, {@link #CHARACTER} or {@link #IGNORE}.
     * @throws IOException If parsing fails.
     */
    protected int parseToken( final int ch ) throws IOException {
        Element el = null;
        //  Now, check the incoming token.
        switch( ch ) {
          case '\r':
            // DOS linefeeds we forget
            return IGNORE;

          case '\n':
            //  Close things like headings, etc.
            // FIXME: This is not really very fast
            closeHeadings();

            popElement( "dl" ); // Close definition lists.
            if( tableHandler.isInTable() ) {
                popElement("tr");
            }
            listHandler.setDefinition( false );
            if( newLine ) {
                // Paragraph change.
                startBlockLevel();
                //  Figure out which elements cannot be enclosed inside a <p></p> pair according to XHTML rules.
                final String nextLine = peekAheadLine();
                if( nextLine.isEmpty() ||
                     ( !nextLine.isEmpty() &&
                       !nextLine.startsWith( "{{{" ) &&
                       !nextLine.startsWith( "----" ) &&
                       !nextLine.startsWith( "%%" ) &&
                       "*#!;".indexOf( nextLine.charAt( 0 ) ) == -1 ) ) {
                    pushElement( new Element( "p" ) );
                    formattingHandler.setOpenParagraph( true );

                    formattingHandler.restartItalicIfNeeded();
                    formattingHandler.restartBoldIfNeeded();
                }
            } else {
                plainTextBuf.append("\n");
                newLine = true;
            }
            return IGNORE;

          case '\\':
            el = handleBackslash();
            break;

          case '_':
            el = handleUnderscore();
            break;

          case '\'':
            el = handleApostrophe();
            break;

          case '{':
            el = handleOpenbrace( newLine );
            break;

          case '}':
            el = handleClosebrace();
            break;

          case '-':
            if( newLine ) {
                el = handleDash();
            }
            break;

          case '!':
            if( newLine ) {
                el = handleHeading();
            }
            break;

          case ';':
            if( newLine ) {
                el = handleDefinitionList();
            }
            break;

          case ':':
            if( listHandler.isDefinition() ) {
                popElement( "dt" );
                el = pushElement( new Element( "dd" ) );
                listHandler.setDefinition( false );
            }
            break;

          case '[':
            el = handleOpenbracket();
            break;

          case '*':
            if( newLine ) {
                pushBack( '*' );
                el = handleGeneralList();
            }
            break;

          case '#':
            if( newLine ) {
                pushBack( '#' );
                el = handleGeneralList();
            }
            break;

          case '|':
            el = handleBar( newLine );
            break;

          case '~':
            el = handleTilde();
            break;

          case '%':
            el = handleDiv();
            break;

          case '/':
            el = handleSlash();
            break;

          default:
            break;
        }

        return el != null ? ELEMENT : CHARACTER;
    }

    /**
     * Closes any open heading elements and adds hash anchor links.
     * Delegates to WikiHeadingHandler.
     */
    private void closeHeadings() {
        headingHandler.closeHeadings();
    }

    /**
     *  Parses the entire document from the Reader given in the constructor or set by {@link #setInputReader(Reader)}.
     *
     *  @return A WikiDocument, ready to be passed to the renderer.
     *  @throws IOException If parsing cannot be accomplished.
     */
    @Override
    public WikiDocument parse() throws IOException {
        final WikiDocument d = new WikiDocument( context.getPage() );
        d.setContext( context );
        final Element rootElement = new Element( "domroot" );
        d.setRootElement( rootElement );
        fillBuffer( rootElement );
        paragraphify( rootElement );

        return d;
    }

    /**
     *  Checks out that the first paragraph is correctly installed.
     *
     *  @param rootElement element to be checked.
     */
    private void paragraphify( final Element rootElement) {
        //  Add the paragraph tag to the first paragraph
        final List< Content > kids = rootElement.getContent();
        if( rootElement.getChild( "p" ) != null ) {
            final ArrayList<Content> ls = new ArrayList<>();
            int idxOfFirstContent = 0;
            int count = 0;

            for( final Iterator< Content > i = kids.iterator(); i.hasNext(); count++ ) {
                final Content c = i.next();
                if( c instanceof Element el ) {
                    if( isBlockLevel( el.getName() ) ) {
                        break;
                    }
                }

                if( !( c instanceof ProcessingInstruction ) ) {
                    ls.add( c );
                    if( idxOfFirstContent == 0 ) {
                        idxOfFirstContent = count;
                    }
                }
            }

            //  If there were any elements, then add a new <p> (unless it would be an empty one)
            if(!ls.isEmpty()) {
                final Element newel = new Element("p");
                for( final Content c : ls ) {
                    c.detach();
                    newel.addContent( c );
                }

                // Make sure there are no empty <p/> tags added.
                if( !newel.getTextTrim().isEmpty() || !newel.getChildren().isEmpty() ) {
                    rootElement.addContent( idxOfFirstContent, newel );
                }
            }
        }
    }

}
