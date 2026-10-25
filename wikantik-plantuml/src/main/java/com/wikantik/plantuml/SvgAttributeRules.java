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

import java.util.Locale;

import static com.wikantik.plantuml.SvgAllowList.ATTRIBUTES;
import static com.wikantik.plantuml.SvgAllowList.IMAGE_ATTRIBUTES;
import static com.wikantik.plantuml.SvgAllowList.TARGETS;
import static com.wikantik.plantuml.SvgStyleFilter.scrubStyle;
import static com.wikantik.plantuml.SvgUrlRules.rewriteUrls;
import static com.wikantik.plantuml.SvgUrlRules.scrubHref;

/** Per-attribute decision: keep, rewrite or drop. */
final class SvgAttributeRules {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    /** @return the (possibly rewritten) value to emit, or {@code null} to drop the attribute */
    static String scrubAttribute( final String element, final String name, final String value, final boolean root, final String prefix ) {
        if( "image".equals( element ) ) {
            // elementAllowed() already proved every href on an image is a raster data URI
            return IMAGE_ATTRIBUTES.contains( name ) || "href".equals( name ) || "xlink:href".equals( name ) ? value : null;
        }
        if( name.toLowerCase( Locale.ROOT ).startsWith( "on" ) ) {
            return null;
        }
        return scrubGeneral( name, value, root, prefix );
    }

    private static String scrubGeneral( final String name, final String value, final boolean root, final String prefix ) {
        switch( name ) {
            case "xmlns":
                return root && SVG_NS.equals( value ) ? value : null;
            case "xmlns:xlink":
                return root && XLINK_NS.equals( value ) ? value : null;
            case "id":
                return prefix + value;
            default:
                return scrubReferencing( name, value, prefix );
        }
    }

    private static String scrubReferencing( final String name, final String value, final String prefix ) {
        switch( name ) {
            case "href":
            case "xlink:href":
                return scrubHref( value, prefix );
            case "target":
                return TARGETS.contains( value ) ? value : null;
            case "style":
                return scrubStyle( value, prefix );
            default:
                return ATTRIBUTES.contains( name ) ? rewriteUrls( value, prefix ) : null;
        }
    }

    private SvgAttributeRules() {
    }
}
