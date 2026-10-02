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
package com.wikantik.importer;

import com.wikantik.util.TextUtil;
import com.wikantik.util.WikiPageNameValidator;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns arbitrary vault names into legal wiki page names, cluster slugs and attachment names. */
public final class VaultNames {

    private static final String UNTITLED = "Untitled";
    private static final Pattern DOT_RUNS = Pattern.compile( "\\.{2,}" );
    private static final Pattern WHITESPACE = Pattern.compile( "\\s+" );
    private static final Pattern NON_SLUG = Pattern.compile( "[^a-z0-9]+" );
    private static final Pattern EDGE_DASHES = Pattern.compile( "^-+|-+$" );
    private static final Pattern ATTACHMENT_ILLEGAL = Pattern.compile( "[#|\\[\\]^\\\\/\\p{Cntrl}]" );

    private VaultNames() {
    }

    /** A name the wiki accepts: only the cleanLink character set, no {@code ..}, at most 128 chars; "Untitled" if empty. */
    public static String legalPageName( final String raw ) {
        final String nfc = nfc( raw );
        final StringBuilder sb = new StringBuilder( nfc.length() );
        for ( int i = 0; i < nfc.length(); i++ ) {
            final char c = nfc.charAt( i );
            sb.append( Character.isLetterOrDigit( c ) || TextUtil.PUNCTUATION_CHARS_ALLOWED.indexOf( c ) >= 0 ? c : ' ' );
        }
        String s = WHITESPACE.matcher( DOT_RUNS.matcher( sb ).replaceAll( " " ) ).replaceAll( " " ).trim();
        if ( s.length() > WikiPageNameValidator.MAX_LENGTH ) {
            s = s.substring( 0, WikiPageNameValidator.MAX_LENGTH ).trim();
        }
        return s.isEmpty() ? UNTITLED : s;
    }

    /** Unicode NFC form, so macOS-zipped (NFD) names match typed (NFC) links and keep their accents. */
    public static String nfc( final String s ) {
        return Normalizer.normalize( s, Normalizer.Form.NFC );
    }

    /** Lowercase {@code a-z0-9} slug with single dashes; empty when nothing survives. */
    public static String slug( final String folderName ) {
        final String dashed = NON_SLUG.matcher( folderName.toLowerCase( Locale.ROOT ) ).replaceAll( "-" );
        return EDGE_DASHES.matcher( dashed ).replaceAll( "" );
    }

    /** Replaces characters that would break an {@code ![[Owner/file]]} reference with {@code -}. */
    public static String attachmentName( final String fileName ) {
        return ATTACHMENT_ILLEGAL.matcher( nfc( fileName ) ).replaceAll( "-" ).trim();
    }
}
