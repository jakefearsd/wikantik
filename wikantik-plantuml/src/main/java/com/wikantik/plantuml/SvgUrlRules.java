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

import org.w3c.dom.Element;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** href / url(...) rules: which links survive, raster data URIs for images, and local-reference id rewriting. */
final class SvgUrlRules {

    private static final Pattern URL_CALL = Pattern.compile( "url\\(", Pattern.CASE_INSENSITIVE );
    static final Pattern LOCAL_URL = Pattern.compile( "url\\(#([A-Za-z0-9_.:-]+)\\)", Pattern.CASE_INSENSITIVE );
    private static final Pattern IMAGE_DATA_URI = Pattern.compile( "data:image/(png|jpeg|gif);base64,[A-Za-z0-9+/=\\s]+" );

    /** @return the image's raster data URI, or null when it has none (or any non-raster href) */
    static String imageHref( final Element el ) {
        String found = null;
        for( final String a : new String[] { "href", "xlink:href" } ) {
            if( el.hasAttribute( a ) ) {
                if( !IMAGE_DATA_URI.matcher( el.getAttribute( a ) ).matches() ) {
                    return null;
                }
                found = el.getAttribute( a );
            }
        }
        return found;
    }

    static String scrubHref( final String value, final String prefix ) {
        if( value.startsWith( "#" ) ) {
            return "#" + prefix + value.substring( 1 );
        }
        final String lower = value.toLowerCase( Locale.ROOT );
        if( value.startsWith( "/wiki/" ) || lower.startsWith( "http://" ) || lower.startsWith( "https://" ) ) {
            return value;
        }
        return null;
    }

    /** Prefixes every {@code url(#x)}; returns null when any {@code url(} is anything else. */
    static String rewriteUrls( final String value, final String prefix ) {
        final int calls = (int) URL_CALL.matcher( value ).results().count();
        if( calls == 0 ) {
            return value;
        }
        final Matcher m = LOCAL_URL.matcher( value );
        final StringBuilder sb = new StringBuilder();
        int found = 0;
        while( m.find() ) {
            found++;
            m.appendReplacement( sb, Matcher.quoteReplacement( "url(#" + prefix + m.group( 1 ) + ")" ) );
        }
        if( found != calls ) {
            return null;
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private SvgUrlRules() {
    }
}
