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

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import com.vladsch.flexmark.util.sequence.CharSubSequence;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.markdown.extensions.wikilinks.AbstractLinkState;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.parser.MarkupParser;

import java.util.List;
import java.util.regex.Pattern;


/**
 * {@link NodePostProcessorState} which further post processes local links.
 */
public class LocalLinkNodePostProcessorState extends AbstractLinkState implements NodePostProcessorState< WikantikLink > {

    public LocalLinkNodePostProcessorState( final Context wikiContext,
                                            final boolean isImageInlining,
                                            final List< Pattern > inlineImagePatterns ) {
        super( wikiContext, isImageInlining, inlineImagePatterns );
    }

    /**
     * {@inheritDoc}
     *
     * @see NodePostProcessorState#process(NodeTracker, Node)
     */
    @Override
    public void process( final NodeTracker state, final WikantikLink link ) {
        final String url = link.getUrl().toString();

        // Guard: skip attachment lookup for path traversal attempts
        if ( url.contains( ".." ) || url.startsWith( "/" ) ) {
            processAsWikiLink( state, link, url );
            return;
        }

        final int hashMark = url.indexOf( '#' );
        final String attachment = wikiContext().getEngine().getManager( AttachmentManager.class ).getAttachmentInfoName( wikiContext(), url );
        if( attachment != null  ) {
            if( !linkOperations().isImageLink( url, isImageInlining(), inlineImagePatterns() ) ) {
                final String attlink = wikiContext().getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), attachment );
                link.setUrl( CharSubSequence.of( attlink ) );
                link.removeChildren();
                final WikiHtmlInline content = WikiHtmlInline.of( link.getText().toString(), wikiContext() );
                link.appendChild( content );
                state.nodeAddedWithChildren( content );
            } else {
                new ImageLinkNodePostProcessorState( wikiContext(), attachment, link.hasRef() ).process( state, link );
            }
        } else if( hashMark != -1 ) { // It's an internal Wiki link, but to a named section
            final String namedSection = url.substring( hashMark + 1 );
            link.setUrl( CharSubSequence.of( url.substring( 0, hashMark ) ) );
            final String matchedLink = linkOperations().linkIfExists( link.getUrl().toString() );
            if( matchedLink != null ) {
                String sectref = "#section-" + wikiContext().getEngine().encodeName( matchedLink + "-" + MarkupParser.wikifyLink( namedSection ) );
                sectref = sectref.replace( '%', '_' );
                link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_VIEW.getRequestContext(), link.getUrl().toString() + sectref ) ) );
            } else {
                link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_EDIT.getRequestContext(), link.getUrl().toString() ) ) );
            }
        } else {
            processAsWikiLink( state, link, url );
        }
    }

    private void processAsWikiLink( final NodeTracker state, final WikantikLink link, final String url ) {
        if( linkOperations().linkExists( url ) ) {
            link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_VIEW.getRequestContext(), url ) ) );
        } else {
            link.setUrl( CharSubSequence.of( wikiContext().getURL( ContextEnum.PAGE_EDIT.getRequestContext(), url ) ) );
        }
    }


}
