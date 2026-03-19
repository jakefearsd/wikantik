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
import org.apache.wiki.StringTransmutator;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.util.TextUtil;
import org.jdom2.Element;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 *   Provides an abstract class for the parser instances.
 *
 *   @since  2.4
 */
public abstract class MarkupParser {

    /** Allow this many characters to be pushed back in the stream.  In effect, this limits the size of a single line.  */
    protected static final int PUSHBACK_BUFFER_SIZE = 10*1024;
    protected PushbackReader in;
    private int pos = -1; // current position in reader stream

    protected final Engine engine;
    protected final Context context;

    /** Optionally stores internal wikilinks */
    protected final ArrayList< StringTransmutator > localLinkMutatorChain = new ArrayList<>();
    protected final ArrayList< StringTransmutator > externalLinkMutatorChain = new ArrayList<>();
    protected final ArrayList< StringTransmutator > attachmentLinkMutatorChain = new ArrayList<>();
    protected final ArrayList< StringTransmutator > linkMutators = new ArrayList<>();
    protected final ArrayList< HeadingListener > headingListenerChain = new ArrayList<>();

    protected boolean inlineImages = true;
    protected boolean parseAccessRules = true;
    /** Keeps image regexp Patterns */
    protected List< Pattern > inlineImagePatterns;
    protected final LinkParsingOperations linkParsingOperations;

    private static final Logger LOG = LogManager.getLogger( MarkupParser.class );

    /** If set to "true", allows using raw HTML within Wiki text.  Be warned, this is a VERY dangerous option to set -
       never turn this on in a publicly allowable Wiki, unless you are absolutely certain of what you're doing. */
    public static final String PROP_ALLOWHTML = "wikantik.translatorReader.allowHTML";

    /** If set to "true", enables plugins during parsing */
    public static final String PROP_RUNPLUGINS = "wikantik.translatorReader.runPlugins";

    /** If true, all outward links (external links) have a small link image appended. */
    public static final String PROP_USEOUTLINKIMAGE = "wikantik.translatorReader.useOutlinkImage";

    /** If set to "true", all external links are tagged with 'rel="nofollow"' */
    public static final String PROP_USERELNOFOLLOW = "wikantik.translatorReader.useRelNofollow";

    /** If true, consider CamelCase hyperlinks as well. */
    public static final String PROP_CAMELCASELINKS = "wikantik.translatorReader.camelCaseLinks";

    /** If true, all outward attachment info links have a small link image appended. */
    public static final String PROP_USEATTACHMENTIMAGE = "wikantik.translatorReader.useAttachmentImage";

    public static final String HASHLINK = "hashlink";

    /** Name of the outlink image; relative path to the JSPWiki directory. */
    public static final String OUTLINK_IMAGE = "images/out.png";
    /** Outlink css class. */
    public static final String OUTLINK = "outlink";

    private static final String INLINE_IMAGE_PATTERNS = "JSPWikiMarkupParser.inlineImagePatterns";

    /** The value for anchor element <tt>class</tt> attributes when used for wiki page (normal) links. The value is "wikipage". */
   public static final String CLASS_WIKIPAGE = "wikipage";

   /** The value for anchor element <tt>class</tt> attributes when used for edit page links. The value is "createpage". */
   public static final String CLASS_EDITPAGE = "createpage";

   /** The value for anchor element <tt>class</tt> attributes when used for interwiki page links. The value is "interwiki". */
   public static final String CLASS_INTERWIKI = "interwiki";

   /** The value for anchor element <tt>class</tt> attributes when used for footnote links. The value is "footnote". */
   public static final String CLASS_FOOTNOTE = "footnote";

   /** The value for anchor element <tt>class</tt> attributes when used for footnote links. The value is "footnote". */
   public static final String CLASS_FOOTNOTE_REF = "footnoteref";

   /** The value for anchor element <tt>class</tt> attributes when used for external links. The value is "external". */
   public static final String CLASS_EXTERNAL = "external";

   /** The value for anchor element <tt>class</tt> attributes when used for attachments. The value is "attachment". */
   public static final String CLASS_ATTACHMENT = "attachment";

   public static final String[] CLASS_TYPES = {
      CLASS_WIKIPAGE,
      CLASS_EDITPAGE,
      "",
      CLASS_FOOTNOTE,
      CLASS_FOOTNOTE_REF,
      "",
      CLASS_EXTERNAL,
      CLASS_INTERWIKI,
      CLASS_EXTERNAL,
      CLASS_WIKIPAGE,
      CLASS_ATTACHMENT
   };

    /**
     *  Constructs a MarkupParser.  The subclass must call this constructor to set up the necessary bits and pieces.
     *
     *  @param context The WikiContext.
     *  @param in The reader from which we are reading the bytes from.
     */
    protected MarkupParser( final Context context, final Reader in ) {
        engine = context.getEngine();
        this.context = context;
        linkParsingOperations = new LinkParsingOperations( this.context );
        setInputReader( in );
    }

    /**
     *  Replaces the current input character stream with a new one.
     *
     *  @param in New source for input.  If null, this method does nothing.
     *  @return the old stream
     */
    public final Reader setInputReader( final Reader in ) {
        final Reader old = this.in;
        if( in != null ) {
            this.in = new PushbackReader( new BufferedReader( in ), PUSHBACK_BUFFER_SIZE );
        }

        return old;
    }

    /**
     *  Adds a hook for processing link texts.  This hook is called when the link text is written into the output stream, and
     *  you may use it to modify the text.  It does not affect the actual link, only the user-visible text.
     *
     *  @param mutator The hook to call.  Null is safe.
     */
    public void addLinkTransmutator( final StringTransmutator mutator ) {
        addLinkHook( linkMutators, mutator );
    }

    /**
     *  Adds a hook for processing local links.  The engine transforms both non-existing and existing page links.
     *
     *  @param mutator The hook to call.  Null is safe.
     */
    public void addLocalLinkHook( final StringTransmutator mutator ) {
        addLinkHook( localLinkMutatorChain, mutator );
    }

    /**
     *  Adds a hook for processing external links.  This includes all http:// ftp://, etc. links, including inlined images.
     *
     *  @param mutator The hook to call.  Null is safe.
     */
    public void addExternalLinkHook( final StringTransmutator mutator ) {
        addLinkHook( externalLinkMutatorChain, mutator );
    }

    /**
     *  Adds a hook for processing attachment links.
     *
     *  @param mutator The hook to call.  Null is safe.
     */
    public void addAttachmentLinkHook( final StringTransmutator mutator ) {
        addLinkHook( attachmentLinkMutatorChain, mutator );
    }

    void addLinkHook( final List< StringTransmutator > mutatorChain, final StringTransmutator mutator ) {
        if( mutator != null ) {
            mutatorChain.add( mutator );
        }
    }

    /**
     *  Adds a HeadingListener to the parser chain.  It will be called whenever a parsed header is found.
     *
     *  @param listener The listener to add.
     */
    public void addHeadingListener( final HeadingListener listener ) {
        if( listener != null ) {
            headingListenerChain.add( listener );
        }
    }

    /**
     *  Disables access rule parsing.
     */
    public void disableAccessRules()
    {
        parseAccessRules = false;
    }

    public boolean isParseAccessRules()
    {
        return parseAccessRules;
    }

    /**
     *  Use this to turn on or off image inlining.
     *
     *  @param toggle If true, images are inlined (as per set in jspwiki.properties)
     *                If false, then images won't be inlined; instead, they will be
     *                treated as standard hyperlinks.
     *  @since 2.2.9
     */
    public void enableImageInlining( final boolean toggle )
    {
        inlineImages = toggle;
    }

    public boolean isImageInlining() {
        return inlineImages;
    }

    protected final void initInlineImagePatterns() {
        //  We cache compiled patterns in the engine, since their creation is really expensive
        List< Pattern > compiledpatterns = engine.getAttribute( INLINE_IMAGE_PATTERNS );

        if( compiledpatterns == null ) {
            compiledpatterns = new ArrayList< >( 20 );
            final Collection< String > ptrns = engine.getAllInlinedImagePatterns();

            //  Make them into Regexp Patterns.  Unknown patterns are ignored.
            for( final String pattern : ptrns ) {
                try {
                    compiledpatterns.add( compileGlobPattern( pattern ) );
                } catch( final PatternSyntaxException e ) {
                    LOG.error( "Malformed pattern [" + pattern + "] in properties: ", e );
                }
            }

            engine.setAttribute( INLINE_IMAGE_PATTERNS, compiledpatterns );
        }

        inlineImagePatterns = Collections.unmodifiableList( compiledpatterns );
	}

    /**
     * Compiles a glob pattern (like *.jpg) into a Java regex Pattern.
     *
     * @param glob The glob pattern to compile
     * @return A compiled Pattern
     * @throws PatternSyntaxException If the pattern cannot be compiled
     */
    public static Pattern compileGlobPattern( final String glob ) throws PatternSyntaxException {
        final StringBuilder regex = new StringBuilder();
        for( int i = 0; i < glob.length(); i++ ) {
            final char c = glob.charAt( i );
            switch( c ) {
                case '*':
                    regex.append( ".*" );
                    break;
                case '?':
                    regex.append( '.' );
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '^':
                case '$':
                case '|':
                case '\\':
                case '+':
                    regex.append( '\\' ).append( c );
                    break;
                default:
                    regex.append( c );
            }
        }
        return Pattern.compile( regex.toString(), Pattern.CASE_INSENSITIVE );
    }

    public List< Pattern > getInlineImagePatterns() {
    	if( inlineImagePatterns == null ) {
    		initInlineImagePatterns();
    	}
    	return inlineImagePatterns;
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
     *  Parses the document.
     *
     *  @return the parsed document, as a WikiDocument
     *  @throws IOException If something goes wrong.
     */
    public abstract WikiDocument parse() throws IOException;

    /**
     *  Return the current position in the reader stream. The value will be -1 prior to reading.
     *
     * @return the reader position as an int.
     */
    public int getPosition()
    {
        return pos;
    }

    /**
     * Returns the next token in the stream.  This is the most called method in the entire parser, so it needs to be lean and mean.
     *
     * @return The next token in the stream; or, if the stream is ended, -1.
     * @throws IOException If something bad happens
     * @throws NullPointerException If you have not yet created an input document.
     */
    protected final int nextToken() throws IOException, NullPointerException {
        // if( in == null ) return -1;
        pos++;
        return in.read();
    }

    /**
     *  Push back any character to the current input.  Does not push back a read EOF, though.
     *
     *  @param c Character to push back.
     *  @throws IOException In case the character cannot be pushed back.
     */
    protected void pushBack( final int c ) throws IOException {
        if( c != -1 && in != null ) {
            pos--;
            in.unread( c );
        }
    }

    /**
     *  Writes HTML for error message.  Does not add it to the document, you have to do it yourself.
     *
     *  @param error The error string.
     *  @return An Element containing the error.
     */
    public static Element makeError( final String error ) {
        return new Element( "span" ).setAttribute( "class", "error" ).addContent( error );
    }

    /**
     *  Cleans a Wiki name.  The functionality of this method was changed in 2.6 so that the list of allowed characters is much larger.
     *  Use {@link #wikifyLink(String)} to get the legacy behaviour.
     *  <P>
     *  [ This is a link ] -&gt; This is a link
     *
     *  @param link Link to be cleared. Null is safe, and causes this to return null.
     *  @return A cleaned link.
     *
     *  @since 2.0
     */
    public static String cleanLink( final String link ) {
        return TextUtil.cleanString( link, TextUtil.PUNCTUATION_CHARS_ALLOWED );
    }

    /**
     *  Cleans away extra legacy characters.  This method functions exactly like pre-2.6 cleanLink()
     *  <P>
     *  [ This is a link ] -&gt; ThisIsALink
     *
     *  @param link Link to be cleared. Null is safe, and causes this to return null.
     *  @return A cleaned link.
     *  @since 2.6
     */
    public static String wikifyLink( final String link ) {
        return TextUtil.cleanString( link, TextUtil.LEGACY_CHARS_ALLOWED );
    }

}
