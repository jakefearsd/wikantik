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
 *   <li>{@link #check(String)} - the safety policy. It is enforced on the server, and the source
 *       is never rewritten: a refused fence is emitted with its original source plus a
 *       {@code data-fence-error} reason. It must never throw.</li>
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
 *   <li>{@link #renderDrawing(String)} output must pass {@code WikantikHtmlSanitizer}; an SVG
 *       drawing therefore needs a narrow SVG allowlist added there first.</li>
 *   <li>The renderer runs in its sandbox mode (PlantUML: {@code PLANTUML_SECURITY_PROFILE=SANDBOX},
 *       no {@code !include} file or URL access) under a size and time cap.</li>
 *   <li>A failure throws; the node renderer turns that into {@code render-failed}, showing the
 *       source and the error.</li>
 *   <li>Output is cached by the existing render caches.</li>
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

    /** Server kind only: the drawing HTML for {@code source}. Throws on failure. */
    default String renderDrawing( final String source ) {
        throw new UnsupportedOperationException( language() + " is a browser renderer" );
    }
}
