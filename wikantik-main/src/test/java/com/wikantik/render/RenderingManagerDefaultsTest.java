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

import com.wikantik.StringTransmutator;
import com.wikantik.api.core.Context;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The {@link RenderingManager} interface defaults: what a manager that does not override them does. */
class RenderingManagerDefaultsTest {

    /** A manager whose abstract methods are mocked and whose default methods are real. */
    private static RenderingManager manager() {
        return Mockito.mock( RenderingManager.class, Mockito.CALLS_REAL_METHODS );
    }

    @Test
    void defaultServerFenceResolutionLeavesHtmlUntouched() {
        final RenderingManager rm = manager();
        final String html = "<figure data-fence-drawing=\"x\"></figure>";
        assertSame( html, rm.resolveServerFences( html ) );
        assertNull( rm.resolveServerFences( null ) );
    }

    @Test
    void defaultManagerHasNoServerFenceDrawers() {
        assertTrue( manager().serverFenceDrawer( "plantuml" ).isEmpty() );
        assertTrue( manager().serverFenceDrawer( null ).isEmpty() );
    }

    @Test
    void evictingAndReferrersDefaultsToEvictingTheNameItself() {
        final RenderingManager rm = manager();
        Mockito.doNothing().when( rm ).evictRenderCache( any() );
        rm.evictRenderCacheAndReferrers( "SomePage" );
        verify( rm ).evictRenderCache( "SomePage" );
    }

    @Test
    void getHtmlByNameUsesTheLatestVersion() {
        final RenderingManager rm = manager();
        when( rm.getHTML( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( "<p>latest</p>" );
        assertEquals( "<p>latest</p>", rm.getHTML( "P" ) );
    }

    @Test
    void getHtmlOfTextRendersTheCachedDocument() throws IOException {
        final RenderingManager rm = manager();
        final Context ctx = Mockito.mock( Context.class );
        final WikiDocument doc = Mockito.mock( WikiDocument.class );
        Mockito.doReturn( doc ).when( rm ).getRenderedDocument( ctx, "text" );
        Mockito.doReturn( "<p>text</p>" ).when( rm ).getHTML( ctx, doc );
        assertEquals( "<p>text</p>", rm.getHTML( ctx, "text" ) );
    }

    @Test
    void getHtmlOfTextReturnsNullWhenTheDocumentCannotBeRendered() throws IOException {
        final RenderingManager rm = manager();
        final Context ctx = Mockito.mock( Context.class );
        final WikiDocument doc = Mockito.mock( WikiDocument.class );
        Mockito.doReturn( doc ).when( rm ).getRenderedDocument( ctx, "text" );
        Mockito.doThrow( new IOException( "poorly formed" ) ).when( rm ).getHTML( ctx, doc );
        assertNull( rm.getHTML( ctx, "text" ) );
    }

    @Test
    void textToHtmlOverloadsDelegateWithTheDocumentedFlags() {
        final RenderingManager rm = manager();
        final Context ctx = Mockito.mock( Context.class );
        final StringTransmutator local = ( c, s ) -> s;
        final StringTransmutator ext = ( c, s ) -> s;
        final StringTransmutator att = ( c, s ) -> s;
        Mockito.doReturn( "<p>x</p>" ).when( rm ).textToHTML( any(), any(), any(), any(), any(), Mockito.anyBoolean(), Mockito.anyBoolean() );

        assertEquals( "<p>x</p>", rm.textToHTML( ctx, "x", local, ext ) );
        verify( rm ).textToHTML( ctx, "x", local, ext, null, true, false );
        assertEquals( "<p>x</p>", rm.textToHTML( ctx, "x", local, ext, att ) );
        verify( rm ).textToHTML( ctx, "x", local, ext, att, true, false );
    }

    @Test
    void renderEmbedBodyDefaultStripsAclDirectivesAndRendersTheParsedBody() throws IOException {
        final RenderingManager rm = manager();
        final Context ctx = Mockito.mock( Context.class );
        final MarkupParser parser = Mockito.mock( MarkupParser.class );
        final WikiDocument doc = Mockito.mock( WikiDocument.class );
        Mockito.doReturn( parser ).when( rm ).getParser( eq( ctx ), any() );
        when( parser.parse() ).thenReturn( doc );
        Mockito.doReturn( "<p>body</p>" ).when( rm ).getHTML( ctx, doc );

        assertEquals( "<p>body</p>", rm.renderEmbedBody( ctx, "body\n\n[{ALLOW view Admin}]\n" ) );

        final org.mockito.ArgumentCaptor< String > seen = org.mockito.ArgumentCaptor.forClass( String.class );
        verify( rm ).getParser( eq( ctx ), seen.capture() );
        assertTrue( !seen.getValue().contains( "ALLOW" ), "the access rule never reaches the display parse: " + seen.getValue() );
    }
}
