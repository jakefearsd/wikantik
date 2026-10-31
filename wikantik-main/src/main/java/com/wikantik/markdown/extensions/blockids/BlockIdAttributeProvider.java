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
package com.wikantik.markdown.extensions.blockids;

import com.vladsch.flexmark.ext.attributes.internal.AttributesAttributeProvider;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.html.MutableAttributes;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Puts {@code id="^id"} and {@code data-block="i"} on a block's element, from what {@link BlockIdPostProcessor} recorded
 * on the document.
 *
 * <p>Only the block's <em>own</em> element gets them: the first element rendered for the node, whatever its
 * {@link AttributablePart} (a list item's is {@code TIGHT_LIST_ITEM} / {@code LOOSE_LIST_ITEM}, not {@code NODE}). Some
 * renderers open several elements for one node (a callout's outer, title and content divs; a fenced block's pre and
 * code; a rendered fence's figure and pre), and the outer one always comes first.
 *
 * <p>It runs after the {@code {…}} attributes provider, so a block id replaces an {@code {#custom}} id on the same
 * element: a {@code ^id} is what block links and embeds resolve, and the element can hold only one id.
 */
public class BlockIdAttributeProvider implements AttributeProvider {

    /** A {@code data-block} attribute inside an opening tag, with the rest of the tag before it. */
    private static final Pattern ORDINAL_IN_TAG = Pattern.compile( "(<[A-Za-z][^<>]*?)\\s+data-block=\"\\d+\"" );

    private final Map< Node, BlockIdPostProcessor.BlockAttrs > blocks;
    private final Set< Node > stamped = Collections.newSetFromMap( new IdentityHashMap<>() );

    BlockIdAttributeProvider( final Map< Node, BlockIdPostProcessor.BlockAttrs > blocks ) {
        this.blocks = blocks;
    }

    @Override
    public void setAttributes( final Node node, final AttributablePart part, final MutableAttributes attributes ) {
        if ( blocks.isEmpty() ) {
            return;
        }
        final BlockIdPostProcessor.BlockAttrs block = blocks.get( node );
        if ( block == null || !stamped.add( node ) ) {
            return;
        }
        if ( block.id() != null ) {
            attributes.replaceValue( "id", "^" + block.id() );
        }
        attributes.replaceValue( "data-block", Integer.toString( block.ordinal() ) );
    }

    /**
     * {@code html} without {@code data-block} ordinals. Plugin output spliced into a page is another parse's HTML —
     * an {@code [{InsertPage}]}, an {@code [{If}]} body, a generated list — numbered from 0 on its own, so its ordinals
     * would name the host's blocks, so {@code DefaultPluginManager} takes them out of every plugin's output. Its
     * {@code ^id} anchors stay. The nested render itself (and its cache entries) keeps them.
     *
     * @param html rendered HTML; may be {@code null}
     * @return the HTML without ordinals; the same instance when it has none
     */
    public static String withoutOrdinals( final String html ) {
        if ( html == null || !html.contains( "data-block=" ) ) {
            return html;
        }
        return ORDINAL_IN_TAG.matcher( html ).replaceAll( "$1" );
    }

    /** One provider per render, reading the rendered document's {@link BlockIdPostProcessor#BLOCK_ATTRS}. */
    public static class Factory extends IndependentAttributeProviderFactory {

        @Override
        public Set< Class< ? > > getAfterDependents() {
            return Set.of( AttributesAttributeProvider.Factory.class );
        }

        @Override
        public AttributeProvider apply( final LinkResolverContext context ) {
            return new BlockIdAttributeProvider( BlockIdPostProcessor.BLOCK_ATTRS.get( context.getDocument() ) );
        }
    }
}
