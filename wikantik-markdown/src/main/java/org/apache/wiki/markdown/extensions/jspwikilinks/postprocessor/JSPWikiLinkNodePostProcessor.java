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
package org.apache.wiki.markdown.extensions.jspwikilinks.postprocessor;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.parser.PostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import org.apache.commons.lang3.Strings;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.markdown.nodes.JSPWikiLink;
import org.apache.wiki.parser.LinkParsingOperations;
import org.apache.wiki.parser.MarkupParser;
import org.apache.wiki.util.TextUtil;

import java.util.List;
import java.util.regex.Pattern;


/**
 * {@link NodePostProcessor} to convert {@link Link}s into {@link JSPWikiLink}s.
 * <p>
 * Acts as a factory of {@link NodePostProcessorState}, which are the classes generating the extra markup for each concrete type of link.
 */
public class JSPWikiLinkNodePostProcessor extends NodePostProcessor {

    protected final Context context;
    protected final LinkParsingOperations linkOperations;
    private final boolean isImageInlining;
    private final List< Pattern > inlineImagePatterns;
    protected boolean useOutlinkImage = true;
    protected final Document document;

    public JSPWikiLinkNodePostProcessor( final Context context,
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
        if( node instanceof Link linkNode ) {
            final JSPWikiLink link = replaceLinkWithJSPWikiLink( state, linkNode );

            final NodePostProcessorState< JSPWikiLink > linkPostProcessor;
            if( linkOperations.isAccessRule( link.getUrl().toString() ) ) {
                linkPostProcessor = new AccessRuleLinkNodePostProcessorState( context );
            } else if( linkOperations.isMetadata( link.getUrl().toString() ) ) {
                linkPostProcessor = new MetadataLinkNodePostProcessorState( context );
            } else if( linkOperations.isPluginLink( link.getUrl().toString() ) ) {
                linkPostProcessor = new PluginLinkNodePostProcessorState( context );
            } else if( linkOperations.isVariableLink( link.getUrl().toString() ) ) {
                linkPostProcessor = new VariableLinkNodePostProcessorState( context );
            } else if( linkOperations.isExternalLink( link.getUrl().toString() ) ) {
                linkPostProcessor = new ExternalLinkNodePostProcessorState( context, isImageInlining, inlineImagePatterns );
            } else if( linkOperations.isInterWikiLink( link.getUrl().toString() ) ) {
                linkPostProcessor = new InterWikiLinkNodePostProcessorState( context, document, isImageInlining, inlineImagePatterns );
            } else if( Strings.CS.startsWith( link.getUrl().toString(), "#" ) ) {
                linkPostProcessor = new LocalFootnoteLinkNodePostProcessorState( context );
            } else if( TextUtil.isNumber( link.getUrl().toString() ) ) {
                linkPostProcessor = new LocalFootnoteRefLinkNodePostProcessorState( context );
            } else {
                linkPostProcessor = new LocalLinkNodePostProcessorState( context, isImageInlining, inlineImagePatterns );
            }
            linkPostProcessor.process( state, link );
        }
    }

    JSPWikiLink replaceLinkWithJSPWikiLink( final NodeTracker state, final Link linkNode ) {
        final JSPWikiLink link = new JSPWikiLink( linkNode );
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
