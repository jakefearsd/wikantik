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

import java.util.Set;

/** The element, attribute, target and style-property allow-lists applied by {@link SvgScrubber}. */
final class SvgAllowList {

    /** Elements PlantUML emits. Deliberately excludes style, script, image, use, foreignObject, animate*, set. */
    static final Set< String > ELEMENTS = Set.of(
        "svg", "defs", "g", "a", "title", "text", "rect", "ellipse", "line", "path", "polygon",
        "linearGradient", "stop", "image",
        "filter", "feGaussianBlur", "feColorMatrix", "feOffset", "feBlend" );

    /** Attributes PlantUML emits (any on* attribute is refused regardless). href/xlink:href/target/style/id are special-cased. */
    static final Set< String > ATTRIBUTES = Set.of(
        "class", "contentStyleType", "cx", "cy", "d", "dx", "dy", "fill", "fill-opacity", "filter", "font-family",
        "font-size", "font-style", "font-weight", "height", "in", "in2", "lengthAdjust", "mode", "offset", "points",
        "preserveAspectRatio", "result", "rx", "ry", "stdDeviation", "stop-color", "text-decoration", "textLength",
        "title", "type", "values", "version", "viewBox", "width", "x", "x1", "x2", "y", "y1", "y2", "zoomAndPan",
        "xlink:actuate", "xlink:show", "xlink:title", "xlink:type",
        "data-diagram-type", "data-link-type", "data-qualified-name", "data-source-line",
        "data-visibility-modifier", "data-entity-1", "data-entity-2" );

    static final Set< String > TARGETS = Set.of( "_top", "_blank" );
    static final Set< String > IMAGE_ATTRIBUTES = Set.of( "x", "y", "width", "height", "preserveAspectRatio" );

    /** CSS properties allowed inside a {@code style} attribute: those PlantUML emits plus stroke/fill/font basics. */
    static final Set< String > STYLE_PROPERTIES = Set.of(
        "stroke", "stroke-width", "stroke-dasharray", "stroke-linejoin", "stroke-linecap", "stroke-miterlimit",
        "stroke-opacity", "fill", "fill-opacity", "width", "height", "background", "font-family", "font-size",
        "font-style", "font-weight" );

    private SvgAllowList() {
    }
}
