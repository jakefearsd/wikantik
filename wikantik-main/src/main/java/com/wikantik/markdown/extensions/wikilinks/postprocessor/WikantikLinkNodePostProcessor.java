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
package com.wikantik.markdown.extensions.wikilinks.postprocessor;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.parser.PostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.apache.commons.lang3.Strings;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.parser.LinkParsingOperations;
import com.wikantik.parser.MarkupParser;
import com.wikantik.util.TextUtil;

import java.util.List;
import java.util.regex.Pattern;


/**
 * {@link NodePostProcessor} to convert {@link Link}s into {@link WikantikLink}s.
 * <p>
 * Acts as a factory of {@link NodePostProcessorState}, which are the classes generating the extra markup for each concrete type of link.
 */
public class WikantikLinkNodePostProcessor extends NodePostProcessor {

    protected final Context context;
    protected final LinkParsingOperations linkOperations;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;
    protected boolean useOutlinkImage = true;
    protected final Document document;
    private static final Pattern WEB_URL = Pattern.compile( "(?i)https?://|www\\." );
    private static final Pattern RAW_ANCHOR_OPEN = Pattern.compile( "(?i)<a[\\s>/]" );
    private static final Pattern RAW_ANCHOR_CLOSE = Pattern.compile( "(?i)</a\\s*>" );

    public WikantikLinkNodePostProcessor( final Context context,
                                         final Document document,
                                         final boolean isImageInlining,
                                         final List< Pattern > inlineImagePatterns ) {
        this.context = context;
        this.document = document;
        linkOperations = new LinkParsingOperations( context );
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
        useOutlinkImage = context.getBooleanWikiProperty( MarkupParser.PROP_USEOUTLINKIMAGE, useOutlinkImage );
    }

    /**
     * {@inheritDoc}
     *
     * @see PostProcessor#process(NodeTracker, Node)
     */
    @Override
    public void process( final NodeTracker state, final Node node ) {
        if( node instanceof Image imageNode ) {
            processImageNode( state, imageNode );
        } else if( node instanceof AutoLink autoLink
                   && ( isBareNonWebLink( autoLink ) || isBareLinkInsideRawAnchor( autoLink ) ) ) {
            unwrapToText( state, autoLink );
        } else if( node instanceof AutoLink || node instanceof MailLink ) {
            // A GFM autolink (bare URL, www., email) or a <…> autolink is an external link: same outlink icon
            // (its class and rel come from WikantikLinkAttributeProvider). Never inlined as an image: it reads as a link.
            NodePostProcessorStateCommonOperations.addOutlinkImage( state, node, context, useOutlinkImage );
        } else if( node instanceof Link linkNode ) {
            final WikantikLink link = replaceLinkWithWikantikLink( state, linkNode );
            final NodePostProcessorState< WikantikLink > linkPostProcessor = stateFor( link.getUrl().toString() );
            if( linkPostProcessor != null ) {
                linkPostProcessor.process( state, link );
            }
        }
    }

    /**
     * A GFM text autolink (no {@code <…>} markers) to anything but a web address: GFM, remark-gfm (the editor preview) and
     * the editor's live preview link only {@code http(s)://} and {@code www.} text (emails are {@link MailLink}s), but the
     * autolink extension links every {@code scheme://}, whose href the sanitizer would then strip.
     */
    static boolean isBareNonWebLink( final AutoLink link ) {
        return link.getOpeningMarker().isEmpty() && !WEB_URL.matcher( link.getText() ).lookingAt();
    }

    /**
     * A GFM text autolink that is (part of) the text of an author's raw {@code <a>}: with allowHTML on that anchor is
     * real markup, so linking the URL again would nest one {@code <a>} inside another (which the sanitizer then
     * splits, leaving an empty anchor). With allowHTML off the raw anchor is escaped text and the autolink stays.
     */
    boolean isBareLinkInsideRawAnchor( final AutoLink link ) {
        if( !link.getOpeningMarker().isEmpty()
            || !context.getBooleanWikiProperty( MarkupParser.PROP_ALLOWHTML, false ) ) {
            return false;
        }
        // Walk back through the inline content of the enclosing block, out of any emphasis the URL sits in.
        for( Node level = link; level != null && !( level instanceof Block ); level = level.getParent() ) {
            for( Node sibling = level.getPrevious(); sibling != null; sibling = sibling.getPrevious() ) {
                if( sibling instanceof HtmlInline html && !( sibling instanceof WikiHtmlInline ) ) {
                    final String tag = html.getChars().toString();
                    if( RAW_ANCHOR_CLOSE.matcher( tag ).lookingAt() ) {
                        return false;
                    }
                    if( RAW_ANCHOR_OPEN.matcher( tag ).lookingAt() && !tag.endsWith( "/>" ) ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Puts the autolink's source text back in its place. */
    private static void unwrapToText( final NodeTracker state, final AutoLink link ) {
        final Text text = new Text( link.getChars() );
        link.insertBefore( text );
        link.unlink();
        state.nodeRemoved( link );
        state.nodeAdded( text );
    }

    /** The post-processing a link with this URL gets; null for a relative path, left as-is for the browser to resolve. */
    private NodePostProcessorState< WikantikLink > stateFor( final String url ) {
        if( linkOperations.isAccessRule( url ) ) {
            return new AccessRuleLinkNodePostProcessorState( context );
        } else if( linkOperations.isMetadata( url ) ) {
            return new MetadataLinkNodePostProcessorState( context );
        } else if( linkOperations.isPluginLink( url ) ) {
            return new PluginLinkNodePostProcessorState( context );
        } else if( linkOperations.isVariableLink( url ) ) {
            return new VariableLinkNodePostProcessorState( context );
        } else if( linkOperations.isExternalLink( url ) ) {
            return new ExternalLinkNodePostProcessorState( context, isImageInlining, inlineImagePatterns );
        } else if( linkOperations.isInterWikiLink( url ) ) {
            return new InterWikiLinkNodePostProcessorState( context, document, isImageInlining, inlineImagePatterns );
        } else if( Strings.CS.startsWith( url, "#" ) ) {
            return new LocalFootnoteLinkNodePostProcessorState( context );
        } else if( TextUtil.isNumber( url ) ) {
            return new LocalFootnoteRefLinkNodePostProcessorState( context );
        } else if( isRelativePathLink( url ) ) {
            return null;
        }
        return new LocalLinkNodePostProcessorState( context, isImageInlining, inlineImagePatterns );
    }

    /**
     * Handles standard Markdown image nodes ({@code ![alt](url)}). If the URL references
     * an attachment on the current page, rewrites the src to the attachment servlet URL.
     * External and absolute URLs are left untouched.
     */
    private void processImageNode( final NodeTracker state, final Image imageNode ) {
        final String url = imageNode.getUrl().toString();

        // Skip external URLs, absolute paths, and path traversal
        if( linkOperations.isExternalLink( url ) || url.startsWith( "/" ) || url.contains( ".." ) ) {
            return;
        }

        // Check if this is an attachment on the current page
        final String attachment = PageSubsystemBridge.fromLegacyEngine( context.getEngine() )
                .attachments()
                .getAttachmentInfoName( context, url );

        if( attachment != null ) {
            // Use the full attachment name (e.g. "TestPage/image.jpg") for URL generation
            final String attachUrl = context.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), attachment );
            imageNode.setUrl( BasedSequence.of( attachUrl ) );
        }
    }

    /**
     * Returns {@code true} if the URL is a relative filesystem path (starts with {@code ./} or {@code ../}).
     * Such links are left untouched so the browser resolves them against the current page URL.
     */
    static boolean isRelativePathLink( final String url ) {
        return url.startsWith( "./" ) || url.startsWith( "../" );
    }

    WikantikLink replaceLinkWithWikantikLink( final NodeTracker state, final Link linkNode ) {
        final WikantikLink link = new WikantikLink( linkNode );
        final Node previous = linkNode.getPrevious();
        final Node parent = linkNode.getParent();

        link.takeChildren( linkNode );
        linkNode.unlink();

        if( previous != null ) {
            previous.insertAfter( link );
        } else if( parent != null ) {
            final Node firstChild = parent.getFirstChild();
            if( firstChild != null ) {
                firstChild.insertBefore( link );
            } else {
                parent.appendChild( link );
            }
        }

        state.nodeRemoved( linkNode );
        state.nodeAddedWithChildren( link );
        return link;
    }

}
