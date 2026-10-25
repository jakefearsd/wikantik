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
package com.wikantik.plantuml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.regex.Pattern;

/**
 * Narrow allow-list filter that makes PlantUML's SVG safe to splice inline into page HTML. Page HTML is not
 * re-sanitized after drawings are inserted, so everything this class emits reaches readers verbatim.
 *
 * <p><b>Raster images (deviation from "no image").</b> C4 diagrams draw their person icon as an
 * {@code <image>} carrying a base64 PNG data URI, so {@code image} is allowed, but only when its
 * {@code href}/{@code xlink:href} fully matches {@code data:image/(png|jpeg|gif);base64,...}. Raster data URIs
 * can neither run script nor load anything; {@code svg+xml}, every other data type and every non-data URL
 * (so any {@code http(s)} image) drops the whole element. Only x, y, width, height, preserveAspectRatio and the
 * href survive on it.
 *
 * <p>The element and attribute allow-lists below are the union of what PlantUML 1.2026.8 emits for the
 * fixtures in {@code src/test/resources/svg} (class, activity, sequence, use case, component, state, object,
 * deployment, timing, C4-style, and a gradient/shadow/dashed/creole sample). Anything not on them is dropped:
 * unknown elements with their whole subtree, unknown attributes singly. The output is serialized by hand so
 * that every text node and attribute value is escaped; author label text can never become markup.
 */
public final class SvgScrubber {

    private static final Pattern PREFIX = Pattern.compile( "[A-Za-z][A-Za-z0-9_-]*" );

    /** Thrown when the input is not a well-formed, DOCTYPE-free SVG document. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Rejected( final String message, final Throwable cause ) {
            super( message, cause );
        }
    }

    private SvgScrubber() {
    }

    /**
     * Scrubs {@code svg} and returns inline-safe SVG markup (no XML declaration, no DOCTYPE).
     *
     * @param svg      PlantUML's raw SVG
     * @param idPrefix prefix applied to every id and local reference; must match {@code [A-Za-z][A-Za-z0-9_-]*}
     * @return the scrubbed SVG
     * @throws Rejected                 when the input is unparsable, has a DOCTYPE, or its root is not {@code svg}
     * @throws IllegalArgumentException when the prefix is unsafe
     */
    public static String scrub( final String svg, final String idPrefix ) {
        if( idPrefix == null || !PREFIX.matcher( idPrefix ).matches() ) {
            throw new IllegalArgumentException( "unsafe id prefix: " + idPrefix );
        }
        final Document doc = SvgParser.parse( svg );
        final Element root = doc.getDocumentElement();
        if( root == null || !"svg".equals( root.getTagName() ) ) {
            throw new Rejected( "root element is not svg", null );
        }
        final StringBuilder out = new StringBuilder( svg.length() );
        SvgSerializer.writeElement( root, idPrefix, out );
        return out.toString();
    }

}
