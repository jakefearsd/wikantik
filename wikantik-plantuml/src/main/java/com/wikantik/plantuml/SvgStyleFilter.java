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

import static com.wikantik.plantuml.SvgAllowList.STYLE_PROPERTIES;
import static com.wikantik.plantuml.SvgUrlRules.LOCAL_URL;
import static com.wikantik.plantuml.SvgUrlRules.rewriteUrls;

/** Filters {@code style} attribute declarations down to allow-listed properties with inert values. */
final class SvgStyleFilter {

    /** Keeps only allow-listed {@code prop:value;} declarations with inert values; null when none survive. */
    static String scrubStyle( final String value, final String prefix ) {
        final StringBuilder kept = new StringBuilder();
        for( final String decl : value.split( ";" ) ) {
            final int colon = decl.indexOf( ':' );
            if( colon <= 0 ) {
                continue;
            }
            final String prop = decl.substring( 0, colon ).trim().toLowerCase( Locale.ROOT );
            final String val = decl.substring( colon + 1 ).trim();
            if( STYLE_PROPERTIES.contains( prop ) && !val.isEmpty() && styleValueIsInert( val ) ) {
                final String rewritten = rewriteUrls( val, prefix );
                if( rewritten != null ) {
                    kept.append( prop ).append( ':' ).append( rewritten ).append( ';' );
                }
            }
        }
        return kept.length() == 0 ? null : kept.toString();
    }

    private static boolean styleValueIsInert( final String val ) {
        if( val.contains( "\\" ) || val.contains( "/*" ) || val.contains( "@" ) || val.contains( ":" ) ) {
            return false;
        }
        return LOCAL_URL.matcher( val ).replaceAll( "" ).indexOf( '(' ) < 0;
    }

    private SvgStyleFilter() {
    }
}
