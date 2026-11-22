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

import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import com.wikantik.api.core.Context;
import com.wikantik.markdown.nodes.WikantikLink;

import java.util.function.UnaryOperator;

/**
 * The host's {@link Context#VAR_LINK_TARGET_REWRITE} operator, applied to link and image targets. Inert unless the
 * variable holds a {@link UnaryOperator}; anything else (absent, a string a page {@code SET} stored) is ignored, so
 * page rendering is unchanged.
 */
final class LinkTargetRewrite {

    private final Context context;

    LinkTargetRewrite( final Context context ) {
        this.context = context;
    }

    /** The URL the operator gives {@code url}, or null when there is no operator or it leaves this URL alone. */
    @SuppressWarnings( "unchecked" )
    private String rewritten( final String url ) {
        if( context.getVariable( Context.VAR_LINK_TARGET_REWRITE ) instanceof UnaryOperator< ? > op ) {
            final Object result = ( ( UnaryOperator< String > )op ).apply( url );
            return result instanceof String s ? s : null;
        }
        return null;
    }

    /**
     * Rewrites the target of an image, or of a link (judged by its wiki-link target), in place when the host asks to;
     * true when it did. The node stays what it was: an image, or a plain link.
     */
    boolean rewrite( final Node node ) {
        final String rewritten = switch( node ) {
            case Image image -> rewritten( image.getUrl().toString() );
            case Link link -> rewritten( WikantikLink.wikiLinkOf( link ) );
            default -> null;
        };
        if( rewritten == null ) {
            return false;
        }
        if( node instanceof Image image ) {
            image.setUrl( BasedSequence.of( rewritten ) );
        } else {
            ( ( Link )node ).setUrl( BasedSequence.of( rewritten ) );
        }
        return true;
    }
}
