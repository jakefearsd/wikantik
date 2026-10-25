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
package com.wikantik.markdown.extensions.fences;

import java.util.Set;

/**
 * A renderer for one fenced-code language (for example {@code mermaid}), the unit of the
 * rendered-fences hook. This Javadoc is the guide for adding the next renderer.
 *
 * <h2>What every renderer provides</h2>
 * <ul>
 *   <li>{@link #language()} - the lowercase registry key (matches {@link FenceInfo#language()}).</li>
 *   <li>{@link #check(String)} - the safety policy. The server runs it on every fence it renders
 *       and never rewrites the source: a refused fence is emitted with its original source plus a
 *       {@code data-fence-error} reason. It must never throw. The server is <em>not</em> the only
 *       gate, and for a browser renderer not even the last one: with {@code allowHTML} on, a
 *       hand-written {@code <figure class="wk-fence" data-fence-kind="browser">} envelope passes
 *       {@code WikantikHtmlSanitizer} without ever reaching this method, so the browser port of the
 *       policy (run by {@code drawFence.js} before every draw) is its only check. Never remove
 *       that browser check on the grounds that the server already enforces the policy.</li>
 *   <li>{@link #pageLinks(String)} - local page names the source links to, with any
 *       {@code #section} stripped, so they join the page's stored references (backlinks, Page
 *       Graph, broken-links report). It returns an empty set when the source is refused.</li>
 * </ul>
 *
 * <h2>Browser kind ({@link FenceKind#BROWSER})</h2>
 * Register the same language in {@code wikantik-frontend/src/utils/fences/registry.js}, and add
 * policy cases to the shared fixture {@code wikantik-frontend/src/utils/__fixtures__/renderedFences.json}.
 * The policy is one rule set in two languages: the editor previews never touch the server, so the
 * JS port must refuse exactly what this class refuses, and both are pinned by the fixture.
 *
 * <h2>Server kind ({@link FenceKind#SERVER})</h2>
 * <ul>
 *   <li>At render time the node renderer emits only a <em>placeholder</em>: the usual {@code figure.wk-fence}
 *       envelope plus a {@code data-fence-drawing} attribute holding a signed, stable token
 *       ({@link FencePlaceholders#sign(String)}, an HMAC over the source hash). No drawing is produced and none
 *       reaches the HTML render caches, which therefore hold placeholders only. A refused fence keeps
 *       {@code data-fence-error} and gets no token.</li>
 *   <li>{@link ServerFenceResolver} turns each verified placeholder into a drawing after the last sanitize and
 *       cache read, so the drawing is never sanitized or cached with the page. It runs in the outermost
 *       {@code RenderingManager.textToHTML(Context, String)} only (nested renders and parses are counted by
 *       {@code RenderDepth}), and through {@code RenderingManager.resolveServerFences} for HTML that leaves the
 *       manager another way (the embed endpoint). The drawing comes from a {@link ServerFenceDrawer} (register one
 *       per language in {@code DefaultRenderingManager.initialize}) and is scrubbed by the PlantUML module's SVG
 *       scrub; failure leaves the source visible with a reason.</li>
 *   <li>{@code WikantikHtmlSanitizer} lets {@code data-fence-drawing} through on {@code figure} only when it
 *       matches {@code [0-9a-f]{64}\.[0-9a-f]{64}}; hand-written SVG is still stripped.</li>
 *   <li>The token secret is generated per JVM. Placeholders cached by another node (the opt-in memcached
 *       adapter) fail verification here and show their source rather than a drawing.</li>
 *   <li>The renderer's {@code check} is the parse-time policy and must never throw; the drawer runs in its
 *       sandbox mode (PlantUML: {@code PLANTUML_SECURITY_PROFILE=SANDBOX}) under size and time caps.</li>
 * </ul>
 *
 * <h2>Rename (deferred)</h2>
 * Page rename does not yet rewrite diagram links; a rename leaves such a link broken and it shows
 * in the broken-links report. A future rewrite must touch only that renderer's link syntax, only
 * inside non-{@code example} rendered fences, and must alter nothing else.
 */
public interface RenderedFenceRenderer {

    /** Lowercase registry key. */
    String language();

    /** Whether the drawing happens in the browser or on the server. */
    FenceKind kind();

    /** Safety policy for {@code source}; must not throw. */
    FenceVerdict check( String source );

    /** Local page names linked from {@code source}; empty when the source is refused. */
    Set< String > pageLinks( String source );
}
