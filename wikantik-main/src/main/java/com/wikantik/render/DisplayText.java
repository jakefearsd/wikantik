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

import com.wikantik.api.core.Context;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * What a display render parses, and the one thing the raw text still does in a render.
 *
 * <p><b>Shown:</b> the page text less every access rule either reading finds ({@link AclDirectiveText#strip}). Removed
 * for every viewer alike, editors included, so a render stays one deterministic function of the page text and the
 * principal-less render caches (which hold only this display result) stay safe. The WYSIWYG editor mode is the
 * exception: it renders the markup back for editing, rules included, and is cached under its own key.
 *
 * <p><b>Enforced, as before:</b> the render path has always set the rendered page's ACL from the access rules its parse
 * reads, and enforcement is not this feature's to change. So when stripping removed something from a text that holds a
 * rule the render path could read, the raw text is parsed as well, for that side effect only (its output is dropped,
 * plugins are not run). A text the stripping leaves alone is parsed once, as before.
 */
final class DisplayText {

    private static final Logger LOG = LogManager.getLogger( DisplayText.class );

    /** Appended to a document-cache key for a WYSIWYG render, which parses the raw markup. */
    static final String WYSIWYG_KEY = "::wysiwyg";

    private DisplayText() {
    }

    /**
     * @param context  the render context
     * @param pagedata the raw text (frontmatter included, or a body)
     * @return the text to parse for display
     */
    static String of( final Context context, final String pagedata ) {
        return wysiwyg( context ) ? pagedata : AclDirectiveText.strip( pagedata );
    }

    /**
     * For {@code textToHTML}'s explicit form: a render ({@code justParse == false}) shows the text, so it parses it as
     * {@link #of} says; a bare parse reads the raw text and honours its caller's access-rule switch.
     *
     * @param context   the render context
     * @param pagedata  the raw text
     * @param justParse whether the caller only parses (for its link hooks) and renders nothing
     * @return the text to parse
     */
    static String forParse( final Context context, final String pagedata, final boolean justParse ) {
        return justParse ? pagedata : of( context, pagedata );
    }

    /** {@code ""}, or {@link #WYSIWYG_KEY} for a WYSIWYG render: what tells the two apart in the document cache. */
    static String cacheKeySuffix( final Context context ) {
        return wysiwyg( context ) ? WYSIWYG_KEY : "";
    }

    /**
     * Lets the raw text set the page's ACL as a render always did, when the display text differs from it: parses
     * {@code raw} with {@code renderer}'s parser, plugins off, and drops the result.
     *
     * @param renderer the rendering manager whose parser the render uses
     * @param context  the render context (its real page receives the ACL)
     * @param raw      the raw text
     * @param display  what the render parsed for display
     * @param enabled  whether the caller parses access rules at all
     */
    static void keepRawAccessRules( final RenderingManager renderer, final Context context, final String raw, final String display,
                                    final boolean enabled ) {
        if ( !enabled || display == raw || !RenderPathAccessRules.mayHoldRule( raw ) ) {
            return;
        }
        final Boolean executePlugins = context.getVariable( Context.VAR_EXECUTE_PLUGINS );
        context.setVariable( Context.VAR_EXECUTE_PLUGINS, Boolean.FALSE );
        try {
            renderer.getParser( context, raw ).parse();
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "The access rules of page {} could not be read from its raw text as a render reads them: {}",
                    context.getRealPage() == null ? "?" : context.getRealPage().getName(), e.getMessage() );
        } finally {
            context.setVariable( Context.VAR_EXECUTE_PLUGINS, executePlugins );
        }
    }

    private static boolean wysiwyg( final Context context ) {
        return Boolean.TRUE.equals( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) );
    }
}
