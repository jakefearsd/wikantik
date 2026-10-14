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
package com.wikantik.render;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AclDirectiveFixtures;
import com.wikantik.auth.acl.AclDirectiveFixtures.Fixture;
import com.wikantik.parser.WikiDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No rendered surface shows a directive the ACL manager enforces: display rendering parses the text with those rules
 * removed, whatever shape they take (inside an HTML block the renderer would otherwise print one). Rendering stays
 * deterministic — the same for every viewer — so the principal-less render caches stay safe.
 */
class RenderedAclDirectiveTest {

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        for ( final Fixture f : AclDirectiveFixtures.ALL ) {
            engine.saveText( pageName( f ), f.text() );
        }
    }

    @AfterAll
    static void stop() {
        engine.deleteQuietly( AclDirectiveFixtures.ALL.stream().map( RenderedAclDirectiveTest::pageName ).toArray( String[]::new ) );
        engine.deleteQuietly( "RenderedAclHost" );
        engine.stop();
    }

    static Stream< Fixture > fixtures() {
        return AclDirectiveFixtures.ALL.stream();
    }

    private static String pageName( final Fixture f ) {
        return "RenderedAcl" + AclDirectiveFixtures.ALL.indexOf( f );
    }

    private static RenderingManager rm() {
        return engine.getManager( RenderingManager.class );
    }

    private static Page page( final Fixture f ) {
        return engine.getManager( PageManager.class ).getPage( pageName( f ) );
    }

    private static Context viewContext( final Page page ) {
        final Context ctx = Wiki.context().create( engine, page );
        ctx.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return ctx;
    }

    /** What a reader of the rendered page may and may not see. */
    private static void assertReaderSafe( final Fixture f, final String html ) {
        final String lower = html.toLowerCase( Locale.ROOT );
        if ( f.loginHidden() || f == AclDirectiveFixtures.FRONTMATTER_OPENER ) {
            assertFalse( lower.contains( "example.test" ), "a login reached the rendered page: " + html );
        }
        if ( f.loginHidden() ) {
            assertFalse( html.contains( "ALLOW" ) || html.contains( "DENY" ), "a rule was rendered: " + html );
        }
        assertFalse( lower.contains( "verified_by" ), "frontmatter must never be rendered: " + html );
        assertTrue( html.contains( AclDirectiveFixtures.BODY ), "the page itself is rendered: " + html );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void textToHtmlShowsNoEnforcedRule( final Fixture f ) {
        assertReaderSafe( f, rm().textToHTML( viewContext( page( f ) ), f.text() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void getHtmlOfThePageShowsNoEnforcedRule( final Fixture f ) {
        final Page page = page( f );
        assertReaderSafe( f, rm().getHTML( viewContext( page ), page ) );
        assertReaderSafe( f, rm().getHTML( pageName( f ), page.getVersion() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void theCachedRenderIsTheSameStrippedRender( final Fixture f ) {
        final Page page = page( f );
        final String first = rm().textToHTML( viewContext( page ), f.text() );
        final String second = rm().textToHTML( viewContext( page ), f.text() );
        assertEquals( first, second );
        assertReaderSafe( f, second );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void anEmbedBodyShowsNoEnforcedRule( final Fixture f ) throws Exception {
        final String body = FrontmatterParser.parse( f.text() ).body();
        assertReaderSafe( f, rm().renderEmbedBody( viewContext( page( f ) ), body ) );
    }

    @Test
    void aWysiwygRenderNeverSharesTheCachedDisplayDocument() throws Exception {
        // The WYSIWYG editor mode renders the markup back, rules included; a reader's render must never be served that
        // document from the shared document cache (nor the other way round).
        final Fixture f = AclDirectiveFixtures.HTML_BLOCK;
        final Page page = page( f );
        final Context wysiwyg = viewContext( page );
        wysiwyg.setVariable( Context.VAR_WYSIWYG_EDITOR_MODE, Boolean.TRUE );
        final WikiDocument editorDoc = rm().getRenderedDocument( wysiwyg, f.text() );
        assertTrue( rm().getHTML( wysiwyg, editorDoc ).contains( "ALLOW" ), "the editor gets the markup, rules included" );
        final String readerHtml = rm().getHTML( viewContext( page ), rm().getRenderedDocument( viewContext( page ), f.text() ) );
        assertReaderSafe( f, readerHtml );
    }

    @Test
    void aPageThatEmbedsTheHtmlBlockFixtureShowsNoLogin() throws Exception {
        final Fixture f = AclDirectiveFixtures.HTML_BLOCK;
        engine.saveText( "RenderedAclHost", "Host page.\n\n![[" + pageName( f ) + "]]\n" );
        final Page host = engine.getManager( PageManager.class ).getPage( "RenderedAclHost" );
        final String html = rm().getHTML( viewContext( host ), host );
        assertTrue( html.contains( AclDirectiveFixtures.BODY ), "the embed rendered: " + html );
        assertFalse( html.toLowerCase( Locale.ROOT ).contains( "example.test" ), html );
    }
}
