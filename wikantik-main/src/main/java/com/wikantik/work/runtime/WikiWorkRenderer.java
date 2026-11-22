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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.render.RenderingManager;
import com.wikantik.variables.VariableManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import com.wikantik.work.WorkRenderer;


/**
 * Renders work item text (descriptions, comments) to HTML through the page pipeline, display-only.
 * See {@link #toHtml} for the guarantees.
 */
public final class WikiWorkRenderer implements WorkRenderer {

    /** The throwaway page every render runs against; the angle brackets keep it from being a real page name. */
    static final String SCRATCH_PAGE = "<work>";

    private final Engine engine;

    public WikiWorkRenderer( final Engine engine ) {
        this.engine = engine;
    }

    /**
     * Same pipeline, sanitising and wikilink/math/fence handling as a page, with these differences, each deliberate:
     * <ul>
     *   <li>the render runs against a throwaway page, so an {@code [{ALLOW ...}]} line in the text can only touch that
     *       page, and is shown as nothing (display renders strip access rules);</li>
     *   <li>nothing is registered with the reference manager (that only happens when a page is saved);</li>
     *   <li>the context is flagged {@link Context#VAR_VIEWER_SENSITIVE}, so the shared document and HTML caches never
     *       store the output;</li>
     *   <li>plugins and page filters do not run (the editor preview also runs no plugins; the citation filter would
     *       resolve {@code cite://} ids to page slugs with no access check);</li>
     *   <li>links are rendered as typed ({@link Context#VAR_LINKS_AS_TYPED}): no lookup of pages, titles, aliases,
     *       headings or attachments happens, and embeds are drawn as links, so the output never reveals what exists.</li>
     * </ul>
     */
    @Override
    public String toHtml( final String markdown ) {
        return toHtml( markdown, null );
    }

    /**
     * As {@link #toHtml(String)}, and the text's {@code work-attachment:<id>} tokens become the download route of
     * <em>this</em> item's attachment (a rewrite of the URL text, no lookup; see {@link WorkAttachmentLinks}). With
     * no usable key nothing is rewritten.
     */
    @Override
    public String toHtml( final String markdown, final String itemKey ) {
        final RenderingManager rm = RenderingSubsystemBridge.fromLegacyEngine( engine ).renderingManager();
        final Page scratch = Wiki.contents().page( engine, SCRATCH_PAGE );
        final Context ctx = Wiki.context().create( engine, scratch );
        ctx.setPage( scratch );
        ctx.setRealPage( scratch );
        ctx.setVariable( Context.VAR_EXECUTE_PLUGINS, Boolean.FALSE );
        ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
        // Links as typed: no page, title, alias, heading or attachment lookup, so output never reveals what exists.
        ctx.setVariable( Context.VAR_LINKS_AS_TYPED, Boolean.TRUE );
        ctx.setVariable( Context.VAR_LINK_TARGET_REWRITE, WorkAttachmentLinks.rewriter( engine.getBaseURL(), itemKey ) );
        // No page filters: the citation filter rewrites cite:// links to a page's current slug without any access check.
        ctx.setVariable( VariableManager.VAR_RUNFILTERS, Boolean.FALSE );
        return rm.textToHTML( ctx, markdown == null ? "" : markdown );
    }
}
