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

import org.apache.commons.lang3.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.StringTransmutator;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.util.TextUtil;
import org.jdom2.Attribute;
import org.jdom2.Content;
import org.jdom2.Element;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.Iterator;
import java.util.ResourceBundle;

/**
 * Handles all link-related parsing and rendering for WikantikMarkupParser.
 * This class extracts link handling logic to improve maintainability and testability.
 *
 * @since 2.12
 */
public class WikiLinkHandler {

    private static final Logger LOG = LogManager.getLogger( WikiLinkHandler.class );

    // Link type constants
    public static final int READ          = 0;
    public static final int EDIT          = 1;
    public static final int EMPTY         = 2;
    public static final int LOCAL         = 3;
    public static final int LOCALREF      = 4;
    public static final int IMAGE         = 5;
    public static final int EXTERNAL      = 6;
    public static final int INTERWIKI     = 7;
    public static final int IMAGELINK     = 8;
    public static final int IMAGEWIKILINK = 9;
    public static final int ATTACHMENT    = 10;

    private final WikantikMarkupParser parser;
    private final Context context;
    private final LinkParsingOperations linkParsingOperations;
    private final LinkParser linkParser = new LinkParser();

    /** Holds the image URL for the duration of this parser */
    private String outlinkImageURL;

    /** If true, all outward links use a small link image. */
    private final boolean useOutlinkImage;

    /** If true, all outward attachment info links use a small link image. */
    private final boolean useAttachmentImage;

    /** If true, allows raw HTML. */
    private final boolean allowHTML;

    /** If true, external links have rel="nofollow". */
    private final boolean useRelNofollow;

    /** If true, we're in WYSIWYG editor mode. */
    private final boolean wysiwygEditorMode;

    /**
     * Constructs a WikiLinkHandler.
     *
     * @param parser The parent WikantikMarkupParser
     * @param context The WikiContext
     * @param linkParsingOperations The link parsing operations helper
     * @param useOutlinkImage Whether to use outlink images
     * @param useAttachmentImage Whether to use attachment images
     * @param allowHTML Whether HTML is allowed
     * @param useRelNofollow Whether to use rel="nofollow"
     * @param wysiwygEditorMode Whether we're in WYSIWYG mode
     */
    public WikiLinkHandler( final WikantikMarkupParser parser,
                            final Context context,
                            final LinkParsingOperations linkParsingOperations,
                            final boolean useOutlinkImage,
                            final boolean useAttachmentImage,
                            final boolean allowHTML,
                            final boolean useRelNofollow,
                            final boolean wysiwygEditorMode ) {
        this.parser = parser;
        this.context = context;
        this.linkParsingOperations = linkParsingOperations;
        this.useOutlinkImage = useOutlinkImage;
        this.useAttachmentImage = useAttachmentImage;
        this.allowHTML = allowHTML;
        this.useRelNofollow = useRelNofollow;
        this.wysiwygEditorMode = wysiwygEditorMode;
    }

    /**
     * Creates a JDOM anchor element.
     *
     * @param type One of the link type constants
     * @param link URL to which to link to
     * @param text Link text
     * @param section If a particular section identifier is required.
     * @return An 'A' element.
     */
    public Element createAnchor( final int type, final String link, String text, String section ) {
        text = TextUtil.escapeHTMLEntities( text );
        section = TextUtil.escapeHTMLEntities( section );
        final Element el = new Element( "a" );
        el.setAttribute( "class", MarkupParser.CLASS_TYPES[ type ] );
        el.setAttribute( "href", link + section );
        el.addContent( text );
        return el;
    }

    /**
     * Creates a link element based on the type and parameters.
     *
     * @param type Link type constant
     * @param link The URL or page name
     * @param text Link text
     * @param section Section anchor (optional)
     * @param attributes Additional attributes (optional)
     * @return The created element, or null
     */
    public Element makeLink( int type, final String link, String text, String section, final Iterator< Attribute > attributes ) {
        Element el = null;
        if( text == null ) {
            text = link;
        }
        text = callMutatorChain( parser.getLinkMutators(), text );
        section = ( section != null ) ? ( "#" + section ) : "";

        // Make sure we make a link name that can be accepted as a valid URL.
        if( link.isEmpty() ) {
            type = EMPTY;
        }
        final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );

        switch( type ) {
            case READ:
                el = createAnchor( READ, context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), link ), text, section );
                break;

            case EDIT:
                el = createAnchor( EDIT, context.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), link ), text, "" );
                el.setAttribute( "title", MessageFormat.format( rb.getString( "markupparser.link.create" ), link ) );
                break;

            case EMPTY:
                el = new Element( "u" ).addContent( text );
                break;

            // These two are for local references - footnotes and references to footnotes.
            // We embed the page name to make sure the links are unique across Wiki.
            case LOCALREF:
                el = createAnchor( LOCALREF, "#ref-" + context.getName() + "-" + link, "[" + text + "]", "" );
                break;

            case LOCAL:
                el = new Element( "a" ).setAttribute( "class", MarkupParser.CLASS_FOOTNOTE );
                el.setAttribute( "name", "ref-" + context.getName() + "-" + link.substring( 1 ) );
                if( !allowHTML ) {
                    el.addContent( "[" + TextUtil.escapeHTMLEntities( text ) + "]" );
                } else {
                    el.addContent( "[" + text + "]" );
                }
                break;

            // With the image, external and interwiki types we need to make sure nobody can put in JavaScript or
            // something else annoying into the links themselves.
            case IMAGE:
                el = new Element( "img" ).setAttribute( "class", "inline" );
                el.setAttribute( "src", link );
                el.setAttribute( "alt", text );
                break;

            case IMAGELINK:
                el = new Element( "img" ).setAttribute( "class", "inline" );
                el.setAttribute( "src", link );
                el.setAttribute( "alt", text );
                el = createAnchor( IMAGELINK, text, "", "" ).addContent( el );
                break;

            case IMAGEWIKILINK:
                final String pagelink = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), text );
                el = new Element( "img" ).setAttribute( "class", "inline" );
                el.setAttribute( "src", link );
                el.setAttribute( "alt", text );
                el = createAnchor( IMAGEWIKILINK, pagelink, "", "" ).addContent( el );
                break;

            case EXTERNAL:
                el = createAnchor( EXTERNAL, link, text, section );
                if( useRelNofollow ) {
                    el.setAttribute( "rel", "nofollow" );
                }
                break;

            case INTERWIKI:
                el = createAnchor( INTERWIKI, link, text, section );
                break;

            case ATTACHMENT:
                final String attlink = context.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), link );
                final String infolink = context.getURL( ContextEnum.PAGE_INFO.getRequestContext(), link );
                final String imglink = context.getURL( ContextEnum.PAGE_NONE.getRequestContext(), "images/attachment_small.png" );
                el = createAnchor( ATTACHMENT, attlink, text, "" );
                if( context.getEngine().getManager( AttachmentManager.class ).forceDownload( attlink ) ) {
                    el.setAttribute( "download", "" );
                }

                parser.pushElement( el );
                parser.popElement( el.getName() );

                if( useAttachmentImage ) {
                    el = new Element( "img" ).setAttribute( "src", imglink );
                    el.setAttribute( "border", "0" );
                    el.setAttribute( "alt", "(info)" );

                    el = new Element( "a" ).setAttribute( "href", infolink ).addContent( el );
                    el.setAttribute( "class", "infolink" );
                } else {
                    el = null;
                }
                break;

            default:
                break;
        }

        if( el != null && attributes != null ) {
            while( attributes.hasNext() ) {
                final Attribute attr = attributes.next();
                if( attr != null ) {
                    el.setAttribute( attr );
                }
            }
        }

        if( el != null ) {
            parser.flushPlainText();
            parser.getCurrentElement().addContent( el );
        }
        return el;
    }

    /**
     * Returns an element for the external link image (out.png).
     * Caches the URL for the lifetime of this parser for performance.
     *
     * @return An element containing the HTML for the outlink image, or null if disabled.
     */
    public Element outlinkImage() {
        Element el = null;
        if( useOutlinkImage ) {
            if( outlinkImageURL == null ) {
                outlinkImageURL = context.getURL( ContextEnum.PAGE_NONE.getRequestContext(), MarkupParser.OUTLINK_IMAGE );
            }

            el = new Element( "img" ).setAttribute( "class", MarkupParser.OUTLINK );
            el.setAttribute( "src", outlinkImageURL );
            el.setAttribute( "alt", "" );
        }

        return el;
    }

    /**
     * When given a link to a WikiName, returns a proper HTML link for it.
     * The local link mutator chain is also called.
     *
     * @param wikiname The wiki page name
     * @return The current element
     */
    public Element makeCamelCaseLink( final String wikiname ) {
        final String matchedLink = linkParsingOperations.linkIfExists( wikiname );
        callMutatorChain( parser.getLocalLinkMutatorChain(), wikiname );
        if( matchedLink != null ) {
            makeLink( READ, matchedLink, wikiname, null, null );
        } else {
            makeLink( EDIT, wikiname, wikiname, null, null );
        }

        return parser.getCurrentElement();
    }

    /**
     * Takes a URL and turns it into a regular wiki link.
     *
     * @param url The URL to process
     * @return An anchor Element containing the link.
     */
    public Element makeDirectURILink( String url ) {
        final Element result;
        String last = null;

        if( url.endsWith( "," ) || url.endsWith( "." ) ) {
            last = url.substring( url.length() - 1 );
            url = url.substring( 0, url.length() - 1 );
        }

        callMutatorChain( parser.getExternalLinkMutatorChain(), url );

        if( linkParsingOperations.isImageLink( url, parser.isImageInlining(), parser.getInlineImagePatterns() ) ) {
            result = handleImageLink( Strings.CS.replace( url, "&amp;", "&" ), url, false );
        } else {
            result = makeLink( EXTERNAL, Strings.CS.replace( url, "&amp;", "&" ), url, null, null );
            parser.addElement( outlinkImage() );
        }

        if( last != null ) {
            parser.getPlainTextBuf().append( last );
        }

        return result;
    }

    /**
     * Image links are handled differently:
     * 1. If the text is a WikiName of an existing page, it gets linked.
     * 2. If the text is an external link, then it is inlined.
     * 3. Otherwise, it becomes an ALT text.
     *
     * @param reallink The link to the image.
     * @param link Link text portion, may be a link to somewhere else.
     * @param hasLinkText If true, then the defined link had a link text available.
     * @return The element created
     */
    public Element handleImageLink( final String reallink, final String link, final boolean hasLinkText ) {
        final String possiblePage = MarkupParser.cleanLink( link );
        if( linkParsingOperations.isExternalLink( link ) && hasLinkText ) {
            return makeLink( IMAGELINK, reallink, link, null, null );
        } else if( linkParsingOperations.linkExists( possiblePage ) && hasLinkText ) {
            callMutatorChain( parser.getLocalLinkMutatorChain(), possiblePage );
            return makeLink( IMAGEWIKILINK, reallink, link, null, null );
        } else {
            return makeLink( IMAGE, reallink, link, null, null );
        }
    }

    /**
     * Gobbles up all hyperlinks that are encased in square brackets.
     *
     * @param linktext The text inside the brackets
     * @param pos The position in the stream
     * @return The element created
     */
    public Element handleHyperlinks( String linktext, final int pos ) {
        final ResourceBundle rb = Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE );
        final StringBuilder sb = new StringBuilder( linktext.length() + 80 );

        if( linkParsingOperations.isAccessRule( linktext ) ) {
            return parser.handleAccessRule( linktext );
        }

        if( linkParsingOperations.isMetadata( linktext ) ) {
            return parser.handleMetadata( linktext );
        }

        if( linkParsingOperations.isPluginLink( linktext ) ) {
            try {
                final PluginContent pluginContent = PluginContent.parsePluginLine( context, linktext, pos );

                // This might sometimes fail, especially if there is something which looks like a plugin invocation but is really not.
                if( pluginContent != null ) {
                    parser.addElement( pluginContent );
                    pluginContent.executeParse( context );
                }
            } catch( final PluginException e ) {
                LOG.info( context.getRealPage().getWiki() + " : " + context.getRealPage().getName() + " - Failed to insert plugin: " + e.getMessage() );
                if( !wysiwygEditorMode ) {
                    final ResourceBundle rbPlugin = Preferences.getBundle( context, Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
                    return parser.addElement( MarkupParser.makeError( MessageFormat.format( rbPlugin.getString( "plugin.error.insertionfailed" ),
                                                                    context.getRealPage().getWiki(),
                                                                    context.getRealPage().getName(),
                                                                    e.getMessage() ) ) );
                }
            }
            return parser.getCurrentElement();
        }

        try {
            final LinkParser.Link link = linkParser.parse( linktext );
            linktext = link.getText();
            String linkref = link.getReference();
            // Yes, we now have the components separated.
            // linktext = the text the link should have
            // linkref = the url or page name.
            // In many cases these are the same. [linktext|linkref].
            if( linkParsingOperations.isVariableLink( linktext ) ) {
                final Content el = new VariableContent( linktext );
                parser.addElement( el );
            } else if( linkParsingOperations.isExternalLink( linkref ) ) {
                // It's an external link, out of this Wiki
                callMutatorChain( parser.getExternalLinkMutatorChain(), linkref );
                if( linkParsingOperations.isImageLink( linkref, parser.isImageInlining(), parser.getInlineImagePatterns() ) ) {
                    handleImageLink( linkref, linktext, link.hasReference() );
                } else {
                    makeLink( EXTERNAL, linkref, linktext, null, link.getAttributes() );
                    parser.addElement( outlinkImage() );
                }
            } else if( link.isInterwikiLink() ) {
                // It's an interwiki link
                final String extWiki = link.getExternalWiki();
                final String wikiPage = link.getExternalWikiPage();
                if( wysiwygEditorMode ) {
                    makeLink( INTERWIKI, extWiki + ":" + wikiPage, linktext, null, link.getAttributes() );
                } else {
                    String urlReference = context.getEngine().getInterWikiURL( extWiki );
                    if( urlReference != null ) {
                        urlReference = TextUtil.replaceString( urlReference, "%s", wikiPage );
                        urlReference = callMutatorChain( parser.getExternalLinkMutatorChain(), urlReference );

                        if( linkParsingOperations.isImageLink( urlReference, parser.isImageInlining(), parser.getInlineImagePatterns() ) ) {
                            handleImageLink( urlReference, linktext, link.hasReference() );
                        } else {
                            makeLink( INTERWIKI, urlReference, linktext, null, link.getAttributes() );
                        }
                        if( linkParsingOperations.isExternalLink( urlReference ) ) {
                            parser.addElement( outlinkImage() );
                        }
                    } else {
                        final Object[] args = { TextUtil.escapeHTMLEntities( extWiki ) };
                        parser.addElement( MarkupParser.makeError( MessageFormat.format( rb.getString( "markupparser.error.nointerwikiref" ), args ) ) );
                    }
                }
            } else if( linkref.startsWith( "#" ) ) {
                // It defines a local footnote
                makeLink( LOCAL, linkref, linktext, null, link.getAttributes() );
            } else if( TextUtil.isNumber( linkref ) ) {
                // It defines a reference to a local footnote
                makeLink( LOCALREF, linkref, linktext, null, link.getAttributes() );
            } else {
                final int hashMark;

                // Internal wiki link, but is it an attachment link?
                String attachment = context.getEngine().getManager( AttachmentManager.class ).getAttachmentInfoName( context, linkref );
                if( attachment != null ) {
                    callMutatorChain( parser.getAttachmentLinkMutatorChain(), attachment );
                    if( linkParsingOperations.isImageLink( linkref, parser.isImageInlining(), parser.getInlineImagePatterns() ) ) {
                        attachment = context.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), attachment );
                        sb.append( handleImageLink( attachment, linktext, link.hasReference() ) );
                    } else {
                        makeLink( ATTACHMENT, attachment, linktext, null, link.getAttributes() );
                    }
                } else if( ( hashMark = linkref.indexOf( '#' ) ) != -1 ) {
                    // It's an internal Wiki link, but to a named section
                    final String namedSection = linkref.substring( hashMark + 1 );
                    linkref = linkref.substring( 0, hashMark );
                    linkref = MarkupParser.cleanLink( linkref );
                    callMutatorChain( parser.getLocalLinkMutatorChain(), linkref );
                    final String matchedLink = linkParsingOperations.linkIfExists( linkref );
                    if( matchedLink != null ) {
                        String sectref = "section-" + context.getEngine().encodeName( matchedLink + "-" + MarkupParser.wikifyLink( namedSection ) );
                        sectref = sectref.replace( '%', '_' );
                        makeLink( READ, matchedLink, linktext, sectref, link.getAttributes() );
                    } else {
                        makeLink( EDIT, linkref, linktext, null, link.getAttributes() );
                    }
                } else {
                    // It's an internal Wiki link
                    linkref = MarkupParser.cleanLink( linkref );
                    callMutatorChain( parser.getLocalLinkMutatorChain(), linkref );
                    final String matchedLink = linkParsingOperations.linkIfExists( linkref );
                    if( matchedLink != null ) {
                        makeLink( READ, matchedLink, linktext, null, link.getAttributes() );
                    } else {
                        makeLink( EDIT, linkref, linktext, null, link.getAttributes() );
                    }
                }
            }

        } catch( final ParseException e ) {
            LOG.info( "Parser failure: ", e );
            final Object[] args = { e.getMessage() };
            parser.addElement( MarkupParser.makeError( MessageFormat.format( rb.getString( "markupparser.error.parserfailure" ), args ) ) );
        }
        return parser.getCurrentElement();
    }

    /**
     * Calls a transmutator chain.
     *
     * @param list Chain to call
     * @param text Text that should be passed to the mutate() method
     * @return The result of the mutation.
     */
    private String callMutatorChain( final Collection< StringTransmutator > list, String text ) {
        if( list == null || list.isEmpty() ) {
            return text;
        }

        for( final StringTransmutator m : list ) {
            text = m.mutate( context, text );
        }

        return text;
    }

}
