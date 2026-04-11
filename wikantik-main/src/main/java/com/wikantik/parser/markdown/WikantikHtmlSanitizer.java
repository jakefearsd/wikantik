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
package com.wikantik.parser.markdown;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Server-side HTML sanitizer applied to rendered Markdown when the wiki is
 * configured with {@code wikantik.translatorReader.allowHTML=true}.
 *
 * <p>Built on OWASP Java HTML Sanitizer with a deliberately permissive policy
 * that preserves the wiki's own generated structure (classes on links,
 * headings with ids, tables, footnotes, styling via {@code style=}) while
 * dropping anything that could execute script: {@code <script>},
 * {@code <iframe>}, {@code <object>}, {@code <embed>}, {@code <form>},
 * event-handler attributes ({@code onclick=}, {@code onerror=}, …), and
 * URL schemes outside {@code http/https/mailto}.</p>
 */
public final class WikantikHtmlSanitizer {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowStandardUrlProtocols()
            .allowCommonBlockElements()
            .allowCommonInlineFormattingElements()
            .allowElements(
                    "a", "img",
                    "table", "thead", "tbody", "tfoot", "caption", "colgroup", "col", "tr", "td", "th",
                    "dl", "dt", "dd",
                    "hr",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "figure", "figcaption",
                    "section", "article", "nav", "aside", "header", "footer", "main",
                    "pre", "code", "kbd", "samp", "var",
                    "ruby", "rt", "rp",
                    "time", "mark", "abbr", "cite", "q", "dfn",
                    "details", "summary" )
            .allowAttributes( "href", "title", "target", "rel" ).onElements( "a" )
            .allowAttributes( "src", "alt", "title", "width", "height" ).onElements( "img" )
            .allowAttributes( "align", "valign", "colspan", "rowspan", "scope" ).onElements( "td", "th" )
            .allowAttributes( "class", "id", "title", "lang", "dir" ).globally()
            .allowAttributes( "style" ).globally()
            .allowStyling()
            .toFactory();

    private WikantikHtmlSanitizer() {
        // utility class
    }

    /**
     * Sanitizes rendered Markdown HTML, returning a string that is safe to
     * embed in a page response. Callers should only invoke this when raw
     * HTML is allowed in wiki source ({@code allowHTML=true}); otherwise
     * Flexmark's {@code ESCAPE_HTML} already neutralizes raw markup and the
     * sanitizer run would only add cost.
     *
     * @param html rendered HTML from the markdown pipeline; may be {@code null}
     * @return the sanitized HTML, or the original value when {@code null}/empty
     */
    public static String sanitize( final String html ) {
        if ( html == null || html.isEmpty() ) {
            return html;
        }
        return POLICY.sanitize( html );
    }
}
