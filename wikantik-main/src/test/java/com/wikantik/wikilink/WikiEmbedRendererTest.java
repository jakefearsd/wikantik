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
package com.wikantik.wikilink;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiEmbedRendererTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "EmbHost", "Host.\n" );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    private Context hostContext() {
        return Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( "EmbHost" ) );
    }

    private WikiEmbedRenderer renderer( final WikiEmbedRenderer.ViewCheck view, final int max ) {
        return new WikiEmbedRenderer( WikiLinkResolver.forEngine( engine ), engine.getManager( PageManager.class ), view,
                WikiEmbedRenderer.defaultBodyRenderer( engine ), max );
    }

    @Test void rendersTheTargetBodyWithoutFrontmatter() throws Exception {
        engine.saveText( "EmbTarget", "---\ntype: article\n---\nHello **embed**.\n" );
        final var r = renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "EmbTarget", null );
        assertTrue( r.bodyHtml().contains( "<strong>embed</strong>" ), r.bodyHtml() );
        assertFalse( r.bodyHtml().contains( "type:" ) );
        assertFalse( r.missing() || r.restricted() || r.truncated() );
    }

    @Test void sectionEmbedTakesOnlyThatSection() throws Exception {
        engine.saveText( "EmbTarget", "Intro.\n\n## Usage\n\nRun it.\n\n## Other\n\nNo.\n" );
        final var r = renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "EmbTarget", "Usage" );
        assertTrue( r.bodyHtml().contains( "Run it." ) );
        assertFalse( r.bodyHtml().contains( "No." ) );
        assertEquals( "EmbTarget › Usage", r.title() );
    }

    @Test void missingPageAndMissingSection() throws Exception {
        assertTrue( renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "NoSuchEmb", null ).missing() );
        engine.saveText( "EmbTarget", "Body.\n" );
        final var r = renderer( ( c, p ) -> true, 20000 ).render( hostContext(), "EmbTarget", "Nope" );
        assertTrue( r.missing() );
        assertTrue( r.bodyHtml().contains( "Section not found: Nope" ) );
    }

    @Test void deniedViewerGetsNoBody() throws Exception {
        engine.saveText( "EmbSecret", "classified text\n" );
        final var r = renderer( ( c, p ) -> false, 20000 ).render( hostContext(), "EmbSecret", null );
        assertTrue( r.restricted() );
        assertFalse( r.bodyHtml().contains( "classified" ) );
        assertFalse( renderer( ( c, p ) -> false, 20000 ).renderBlock( hostContext(), "EmbSecret", null )
                .contains( "<a class=\"wikipage\"" ) );
    }

    @Test void everyRenderFlagsTheContextViewerSensitive() throws Exception {
        final Context ctx = hostContext();
        renderer( ( c, p ) -> true, 20000 ).render( ctx, "NoSuchEmb", null );
        assertEquals( Boolean.TRUE, ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) );
    }

    @Test void truncatesAtABlockBoundaryWithAContinueLink() throws Exception {
        engine.saveText( "EmbLong", "First paragraph.\n\nSecond paragraph that is long.\n" );
        final var r = renderer( ( c, p ) -> true, 25 ).render( hostContext(), "EmbLong", null );
        assertTrue( r.truncated() );
        assertTrue( r.bodyHtml().contains( "First paragraph." ) );
        assertFalse( r.bodyHtml().contains( "Second" ) );
        assertTrue( r.bodyHtml().contains( "Continue reading" ) );
    }

    @Test void truncationNeverCutsInsideAFence() throws Exception {
        engine.saveText( "EmbFence", "Intro.\n\n```\ncode line one\n\ncode line two\n```\n\nAfter.\n" );
        final var r = renderer( ( c, p ) -> true, 30 ).render( hostContext(), "EmbFence", null );
        assertTrue( r.truncated() );
        assertFalse( r.bodyHtml().contains( "code line" ), r.bodyHtml() );
    }

    @Test void bodyRendererFailureIsContained() throws Exception {
        engine.saveText( "EmbTarget", "Body.\n" );
        final var broken = new WikiEmbedRenderer( WikiLinkResolver.forEngine( engine ), engine.getManager( PageManager.class ),
                ( c, p ) -> true, ( c, md ) -> { throw new IOException( "boom" ); }, 20000 );
        assertTrue( broken.render( hostContext(), "EmbTarget", null ).bodyHtml().contains( "wiki-embed-error" ) );
    }

    @Test void selfEmbedIsStoppedAsALoop() throws Exception {
        engine.saveText( "EmbSelf", "Me: ![[EmbSelf]]\n" );
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( "EmbSelf" ) );
        assertTrue( renderer( ( c, p ) -> true, 20000 ).render( ctx, "EmbSelf", null ).bodyHtml().contains( "Embed loop stopped" ) );
    }

    @Test void blockMarkupCarriesTheDataAttributes() throws Exception {
        engine.saveText( "EmbTarget", "## Usage\n\nRun.\n" );
        final String html = renderer( ( c, p ) -> true, 20000 ).renderBlock( hostContext(), "EmbTarget", "Usage" );
        assertTrue( html.startsWith( "<div class=\"wiki-embed\" data-embed=\"EmbTarget\" data-section=\"Usage\">" ), html );
    }

    /** Body renderer that treats each "EMBED:Name" line as a nested embed, passing the context it was given. */
    private WikiEmbedRenderer recursive() {
        final WikiEmbedRenderer[] self = new WikiEmbedRenderer[ 1 ];
        final WikiEmbedRenderer.BodyRenderer body = ( ctx, md ) -> {
            final StringBuilder sb = new StringBuilder();
            for ( final String line : md.split( "\n" ) ) {
                sb.append( line.startsWith( "EMBED:" ) ? self[ 0 ].render( ctx, line.substring( 6 ), null ).bodyHtml() : line );
            }
            return sb.toString();
        };
        self[ 0 ] = new WikiEmbedRenderer( WikiLinkResolver.forEngine( engine ), engine.getManager( PageManager.class ),
                ( c, p ) -> true, body, 20000 );
        return self[ 0 ];
    }

    @Test void siblingEmbedsOfTheSamePageAreNotALoop() throws Exception {
        engine.saveText( "EmbLeaf", "leaf-body\n" );
        final var r = recursive();
        final Context ctx = hostContext();
        for ( int i = 0; i < 2; i++ ) {
            final String html = r.render( ctx, "EmbLeaf", null ).bodyHtml();
            assertTrue( html.contains( "leaf-body" ), html );
            assertFalse( html.contains( "loop" ), html );
        }
    }

    @Test void fiveSiblingEmbedsOfDistinctPagesAllRender() throws Exception {
        final var r = recursive();
        final Context ctx = hostContext();
        for ( int i = 0; i < 5; i++ ) {
            engine.saveText( "EmbSib" + i, "sib-body" + i + "\n" );
        }
        for ( int i = 0; i < 5; i++ ) {
            assertTrue( r.render( ctx, "EmbSib" + i, null ).bodyHtml().contains( "sib-body" + i ) );
        }
    }

    @Test void nestedChainStopsAtDepthAndCyclesAreReported() throws Exception {
        engine.saveText( "EmbA", "a-text EMBED:EmbB\n".replace( " EMBED", "\nEMBED" ) );
        engine.saveText( "EmbB", "b-text\nEMBED:EmbC\n" );
        engine.saveText( "EmbC", "c-text\nEMBED:EmbD\n" );
        engine.saveText( "EmbD", "d-text\nEMBED:EmbE\n" );
        engine.saveText( "EmbE", "e-text\n" );
        final String html = recursive().render( hostContext(), "EmbA", null ).bodyHtml();
        assertTrue( html.contains( "c-text" ), html );
        assertTrue( html.contains( "Embed loop stopped" ), html );
        assertFalse( html.contains( "d-text" ), html );

        engine.saveText( "EmbX", "x-text\nEMBED:EmbY\n" );
        engine.saveText( "EmbY", "y-text\nEMBED:EmbX\n" );
        final String cyc = recursive().render( hostContext(), "EmbX", null ).bodyHtml();
        assertTrue( cyc.contains( "y-text" ) && cyc.contains( "Embed loop stopped" ), cyc );
    }

    @Test void truncationFallsBackToAHardCutWhenTheBoundaryIsAtZero() throws Exception {
        engine.saveText( "EmbFenceFirst", "```\ncode line one is long enough\n```\n" );
        final var r = renderer( ( c, p ) -> true, 20 ).render( hostContext(), "EmbFenceFirst", null );
        assertTrue( r.truncated() );
        assertTrue( r.bodyHtml().contains( "code line" ), r.bodyHtml() );
    }
}
