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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.WikiDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DefaultRenderingManager#getHTML(Context, WikiDocument)} picks the renderer from the WYSIWYG editor flag of
 * the context: the WYSIWYG renderer for an editor render, the standard one for everything else. The two are
 * configured separately, so distinct renderers make the choice observable.
 */
class RendererModeSelectionTest {

    /** Stands in for the standard renderer. */
    public static final class StandardMarker extends WikiRenderer {
        public StandardMarker( final Context context, final WikiDocument doc ) {
            super( context, doc );
        }

        @Override
        public String getString() {
            return "standard";
        }
    }

    /** Stands in for the WYSIWYG renderer. */
    public static final class WysiwygMarker extends WikiRenderer {
        public WysiwygMarker( final Context context, final WikiDocument doc ) {
            super( context, doc );
        }

        @Override
        public String getString() {
            return "wysiwyg";
        }
    }

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.setProperty( RenderingManager.PROP_RENDERER, StandardMarker.class.getName() );
        props.setProperty( RenderingManager.PROP_WYSIWYG_RENDERER, WysiwygMarker.class.getName() );
        final DefaultRenderingManager manager = new DefaultRenderingManager();
        manager.initialize( engine, props );
        engine.setManager( RenderingManager.class, manager );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private String render( final Object wysiwygFlag ) throws Exception {
        engine.saveText( "ModePage", "text\n" );
        final Context ctx = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( "ModePage" ) );
        ctx.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );
        ctx.setVariable( Context.VAR_WYSIWYG_EDITOR_MODE, wysiwygFlag );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final WikiDocument doc = rm.getRenderedDocument( ctx, "text\n" );
        return rm.getHTML( ctx, doc );
    }

    @Test
    void theWysiwygEditorFlagSelectsTheWysiwygRenderer() throws Exception {
        assertEquals( "wysiwyg", render( Boolean.TRUE ) );
    }

    @Test
    void noFlagOrAFalseFlagSelectsTheStandardRenderer() throws Exception {
        assertEquals( "standard", render( null ) );
        assertEquals( "standard", render( Boolean.FALSE ) );
    }
}
