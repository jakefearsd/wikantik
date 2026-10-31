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

import com.vladsch.flexmark.ext.attributes.internal.AttributesNodePostProcessor;
import com.vladsch.flexmark.parser.block.DocumentPostProcessor;
import com.vladsch.flexmark.parser.block.DocumentPostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataKey;
import com.wikantik.api.core.Context;
import com.wikantik.markdown.extensions.callouts.CalloutPostProcessor;
import com.wikantik.markdown.extensions.nativelinks.NativeWikiLinkPostProcessorFactory;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.WikiHtmlInline;
import com.wikantik.parser.markdown.BlockWalk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Applies the {@code ^id} block-marker rules (spec §1.1, §1.4) to the rendered page's tree: numbers its blocks with
 * {@link BlockWalk} — the walk {@link com.wikantik.parser.markdown.BlockIds} uses, so every ordinal equals the
 * {@code BlockIds.list} index of the same block — takes the markers out of the text, and records each block's
 * {@code id} / {@code data-block} for {@link BlockIdAttributeProvider} under {@link #BLOCK_ATTRS}.
 *
 * <p>It runs after callouts are built and {@code {…}} attributes are attached (so the block structure is the one
 * {@code BlockIds} parses) and before the native-wikilink post-processor replaces an embed-only paragraph by its embed.
 * That paragraph's ordinal is then simply absent from the HTML; every later block keeps its own. The plugin / link
 * post-processor is left unordered against it (it runs first, as before): it only changes inline content, never which
 * blocks there are, and making it run after the attributes post-processor would orphan a link's {@code {.cls}}.
 *
 * <p>An orphan standalone marker (nothing before it to tag) becomes an empty {@code <span id="^id"
 * class="block-anchor">} in its place, so links to it still land.
 *
 * <p>Each parse numbers its own blocks from 0. Plugin output — an {@code [{InsertPage}]}, a generated page list — is
 * another parse spliced into the host, and not always inside a wrapper the reader recognises, so
 * {@code DefaultPluginManager} takes its ordinals out ({@link BlockIdAttributeProvider#withoutOrdinals}); its
 * {@code ^id} anchors stay. An embed's body (rendered at HTML time, inside {@code .wiki-embed}) keeps its own ordinals,
 * which the reader ignores, as it ignores any inside {@code .inserted-page}.
 *
 * <p>Never fails a render: anything unexpected is logged and the page renders without block ids or ordinals.
 */
public class BlockIdPostProcessor extends DocumentPostProcessor {

    private static final Logger LOG = LogManager.getLogger( BlockIdPostProcessor.class );

    /**
     * What a block's element gets.
     *
     * @param id      the block id without {@code ^}, or {@code null}
     * @param ordinal its {@code data-block} value
     */
    public record BlockAttrs( String id, int ordinal ) {
    }

    /** The blocks of a rendered document, by node identity. Empty when the document was not processed. */
    public static final DataKey< Map< Node, BlockAttrs > > BLOCK_ATTRS = new DataKey<>( "WIKANTIK_BLOCK_ATTRS", Map.of() );

    /** The block id (without {@code ^}) recorded for {@code node}, or {@code null} when it has none or was not processed. */
    public static String idOf( final Node node ) {
        final BlockAttrs attrs = BLOCK_ATTRS.get( node.getDocument() ).get( node );
        return attrs == null ? null : attrs.id();
    }

    private final String pageName;
    private final Function< Document, BlockWalk.Result > walker;

    BlockIdPostProcessor( final String pageName, final Function< Document, BlockWalk.Result > walker ) {
        this.pageName = pageName;
        this.walker = walker;
    }

    @Override
    public Document processDocument( final Document document ) {
        try {
            final BlockWalk.Result walk = walker.apply( document );
            final Map< Node, BlockAttrs > attrs = new IdentityHashMap<>();
            final List< BlockWalk.Entry > entries = walk.entries();
            for ( int i = 0; i < entries.size(); i++ ) {
                attrs.put( entries.get( i ).node(), new BlockAttrs( walk.idOf( entries.get( i ) ), i ) );
            }
            BlockWalk.detach( walk, m -> walk.wins( m ) ? anchor( m.id() ) : null );
            BLOCK_ATTRS.set( document, Collections.unmodifiableMap( attrs ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Block ids not rendered for page '{}': {}", pageName, e.getMessage(), e );
        }
        return document;
    }

    /** The empty anchor an orphan marker leaves; the id is {@code [A-Za-z0-9-]+}, so it needs no escaping. */
    private static Node anchor( final String id ) {
        return WikiHtmlInline.of( "<span id=\"^" + id + "\" class=\"block-anchor\"></span>" );
    }

    /** Registers the post-processor in the dependency order described on the class. */
    public static class Factory extends DocumentPostProcessorFactory {

        private final Context context;

        public Factory( final Context context ) {
            this.context = context;
        }

        @Override
        public Set< Class< ? > > getAfterDependents() {
            return Set.of( CalloutPostProcessor.Factory.class, AttributesNodePostProcessor.Factory.class );
        }

        @Override
        public Set< Class< ? > > getBeforeDependents() {
            return Set.of( NativeWikiLinkPostProcessorFactory.class );
        }

        @Override
        public DocumentPostProcessor apply( final Document document ) {
            final String pageName = context != null && context.getPage() != null ? context.getPage().getName() : null;
            return new BlockIdPostProcessor( pageName, BlockWalk::walk );
        }
    }
}
