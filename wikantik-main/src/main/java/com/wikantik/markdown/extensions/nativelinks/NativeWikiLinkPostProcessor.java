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
package com.wikantik.markdown.extensions.nativelinks;

import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.wikilink.WikiImage;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.parser.WikiLinkSyntax;
import com.wikantik.api.parser.WikiLinkSyntax.WikiLinkRef;
import com.wikantik.export.HeadingSlugs;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.WikiHtmlInline;
import com.wikantik.markdown.nodes.NativeWikiLinkNode;
import com.wikantik.markdown.nodes.NativeWikiLinkNode.Kind;
import com.wikantik.markdown.nodes.WikiEmbedBlock;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.parser.LinkParsingOperations;
import com.wikantik.util.TextUtil;
import com.wikantik.wikilink.WikiLinkResolver;
import com.wikantik.wikilink.WikiLinkResolver.Resolution;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;


/**
 * Replaces each flexmark wikilink token with a {@link NativeWikiLinkNode} (or inline HTML for an attachment
 * image embed). The token's text is re-parsed with {@link WikiLinkSyntax} so the target/heading/alias split is
 * identical to every other consumer; a token {@code WikiLinkSyntax} rejects (for example {@code [[ -f x ]]}) is
 * emitted as literal text.
 */
public class NativeWikiLinkPostProcessor extends NodePostProcessor {

    private static final Logger LOG = LogManager.getLogger( NativeWikiLinkPostProcessor.class );

    private final Context context;
    private final LinkParsingOperations linkOperations;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;
    private final WikiLinkResolver resolver;

    public NativeWikiLinkPostProcessor( final Context context,
                                        final boolean isImageInlining,
                                        final List< Pattern > inlineImagePatterns ) {
        this.context = context;
        this.linkOperations = new LinkParsingOperations( context );
        this.isImageInlining = isImageInlining;
        this.inlineImagePatterns = inlineImagePatterns;
        this.resolver = WikiLinkResolver.forEngine( context.getEngine() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void process( final NodeTracker state, final Node node ) {
        if ( node.getParent() == null || node.getParent().getParent() == null ) {
            return; // consumed by an earlier sibling's paragraph replacement (the unlinked paragraph keeps its children)
        }
        final Optional< WikiLinkRef > ref = WikiLinkSyntax.parse( node.getChars().toString() );
        if ( ref.isEmpty() ) {
            replace( state, node, new Text( node.getChars() ) );
            return;
        }
        final WikiLinkRef r = ref.get();
        final String attachment = attachmentName( r );
        if ( attachment != null ) {
            replace( state, node, attachmentNode( r, attachment ) );
        } else if ( r.embed() && r.isAttachment() ) {
            replace( state, node, WikiHtmlInline.of( "<span class=\"wiki-embed-missing\">"
                    + TextUtil.replaceEntities( r.fileName() ) + "</span>" ) );
        } else if ( r.embed() && !r.isSamePage() && embedOnlyParagraph( node ) ) {
            replaceParagraphWithEmbeds( state, ( Paragraph ) node.getParent() );
        } else {
            replace( state, node, pageLink( r ) );
        }
    }

    /** True when {@code node}'s parent paragraph holds nothing but page embeds and line breaks (placement rule R5). */
    private boolean embedOnlyParagraph( final Node node ) {
        if ( !( node.getParent() instanceof Paragraph ) ) {
            return false;
        }
        for ( Node child = node.getParent().getFirstChild(); child != null; child = child.getNext() ) {
            if ( !isEmbedOrBreak( child ) ) {
                return false;
            }
        }
        return true;
    }

    private boolean isEmbedOrBreak( final Node child ) {
        if ( child instanceof SoftLineBreak || child instanceof HardLineBreak ) {
            return true;
        }
        if ( child instanceof Text ) {
            return child.getChars().toString().isBlank();
        }
        if ( child instanceof WikiImage || child instanceof WikiLink ) {
            final Optional< WikiLinkRef > ref = WikiLinkSyntax.parse( child.getChars().toString() );
            return ref.isPresent() && ref.get().embed() && !ref.get().isSamePage() && !ref.get().isAttachment()
                    && attachmentName( ref.get() ) == null;
        }
        return false;
    }

    /**
     * Replaces the paragraph with one placeholder block per embed. Nothing is rendered or loaded here: a parse runs
     * for metadata refreshes and reference scans too, and its document may be cached and rendered for any viewer.
     */
    private void replaceParagraphWithEmbeds( final NodeTracker state, final Paragraph paragraph ) {
        final List< WikiEmbedBlock > blocks = new ArrayList<>();
        for ( Node child = paragraph.getFirstChild(); child != null; child = child.getNext() ) {
            if ( child instanceof WikiImage || child instanceof WikiLink ) {
                final WikiLinkRef r = WikiLinkSyntax.parse( child.getChars().toString() ).orElseThrow();
                blocks.add( new WikiEmbedBlock( r.target(), r.heading(), pageLink( r ) ) );
            }
        }
        for ( final WikiEmbedBlock block : blocks ) {
            paragraph.insertBefore( block );
        }
        paragraph.unlink();
        state.nodeRemoved( paragraph );
        blocks.forEach( state::nodeAddedWithChildren );
    }

    /** The full attachment name when {@code r} names an existing attachment, else null. */
    private String attachmentName( final WikiLinkRef r ) {
        final String target = r.target();
        if ( r.isSamePage() || target.contains( ".." ) || target.startsWith( "/" ) ) {
            return null;
        }
        // getAttachmentInfoName echoes any "Owner/file" name even when it does not exist, so ask for the real record
        try {
            final Attachment att = PageSubsystemBridge.fromLegacyEngine( context.getEngine() ).attachments().getAttachmentInfo( context, target );
            return att == null ? null : att.getName();
        } catch ( final ProviderException e ) {
            LOG.warn( "Attachment lookup failed for wikilink target '{}': {}", target, e.getMessage() );
            return null;
        }
    }

    private Node attachmentNode( final WikiLinkRef r, final String attachment ) {
        final String attUrl = context.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), attachment );
        if ( r.embed() && linkOperations.isImageLink( r.fileName(), isImageInlining, inlineImagePatterns ) ) {
            return WikiHtmlInline.of( "<img class=\"inline\" src=\"" + TextUtil.replaceEntities( attUrl ) + "\" alt=\""
                    + TextUtil.replaceEntities( r.fileName() ) + "\"" + sizeAttrs( r.size() ) + " />" );
        }
        return linkNode( attUrl, r.alias() != null ? r.alias() : r.target(), Kind.ATTACHMENT, attachment );
    }

    private static String sizeAttrs( final int[] size ) {
        if ( size == null ) {
            return "";
        }
        return " width=\"" + size[ 0 ] + "\"" + ( size[ 1 ] >= 0 ? " height=\"" + size[ 1 ] + "\"" : "" );
    }

    private NativeWikiLinkNode pageLink( final WikiLinkRef r ) {
        final String fragment = r.heading() != null ? "#" + HeadingSlugs.slug( r.heading() ) : "";
        if ( r.isSamePage() ) {
            return linkNode( fragment, r.displayText(), Kind.ANCHOR, "" );
        }
        final Resolution res = resolver.resolve( r.target() );
        if ( res.exists() ) {
            final String url = context.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), res.pageName() ) + fragment;
            return linkNode( url, r.displayText(), Kind.PAGE, res.pageName() );
        }
        if ( !resolver.indexReady() ) {
            // The create-page link may be wrong (the title index would have matched it): never cache this render
            context.setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );
        }
        final String url = context.getURL( ContextEnum.PAGE_EDIT.getRequestContext(), res.pageName() );
        return linkNode( url, r.displayText(), Kind.MISSING, res.pageName() );
    }

    private static NativeWikiLinkNode linkNode( final String url, final String text, final Kind kind, final String target ) {
        final Link link = new Link();
        link.setUrl( BasedSequence.of( url ) );
        link.setText( BasedSequence.of( text ) );
        final NativeWikiLinkNode node = new NativeWikiLinkNode( link, kind, target );
        node.appendChild( new Text( text ) );
        return node;
    }

    private static void replace( final NodeTracker state, final Node node, final Node replacement ) {
        node.insertBefore( replacement );
        node.unlink();
        state.nodeRemoved( node );
        state.nodeAddedWithChildren( replacement );
    }

}
