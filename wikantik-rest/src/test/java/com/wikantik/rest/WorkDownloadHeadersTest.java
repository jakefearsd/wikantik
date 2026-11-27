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
package com.wikantik.rest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Content-Disposition of an attachment download: the user-chosen name must never break out of the header. */
class WorkDownloadHeadersTest {

    /** Each character at the edge of a range in the encoder: just inside stays, just outside is escaped. */
    @Test
    void percentEncodingKeepsOnlyRfc5987AttrChars() {
        final Map< String, String > cases = new LinkedHashMap<>();
        for ( final String kept : new String[] { "0", "9", "A", "Z", "a", "z", "~", "!", "#", "$", "&", "+", "-", ".", "^", "_", "`", "|" } ) {
            cases.put( kept, kept );
        }
        cases.put( "/", "%2F" );
        cases.put( ":", "%3A" );
        cases.put( "@", "%40" );
        cases.put( "[", "%5B" );
        cases.put( "{", "%7B" );
        cases.put( " ", "%20" );
        cases.put( "\"", "%22" );
        cases.put( "'", "%27" );
        cases.put( "(", "%28" );
        cases.put( ")", "%29" );
        cases.put( "*", "%2A" );
        cases.put( "%", "%25" );
        cases.put( "\\", "%5C" );
        cases.put( ",", "%2C" );
        cases.put( ";", "%3B" );
        cases.put( "=", "%3D" );
        cases.put( "<", "%3C" );
        cases.put( ">", "%3E" );
        cases.put( "?", "%3F" );
        cases.forEach( ( in, out ) -> assertEquals( out, WorkDownloadHeaders.percentEncode( in ), "for [" + in + "]" ) );
    }

    @Test
    void percentEncodingWorksOnUtf8BytesAndZeroPadsEachOne() {
        assertEquals( "%C3%A9", WorkDownloadHeaders.percentEncode( "é" ) );
        assertEquals( "%E2%82%AC", WorkDownloadHeaders.percentEncode( "€" ) );
        assertEquals( "%F0%9F%98%80", WorkDownloadHeaders.percentEncode( "😀" ) );
        assertEquals( "%00%0A%0D%0F%10%7F", WorkDownloadHeaders.percentEncode( "\u0000\n\r\u000f\u0010\u007f" ) );
        assertEquals( "", WorkDownloadHeaders.percentEncode( "" ) );
    }

    @Test
    void theAsciiFallbackKeepsExactlyPrintableAsciiExceptQuoteAndBackslash() {
        assertEquals( "_ ~}|{", WorkDownloadHeaders.asciiFallback( "\u001f ~}|{" ) );
        assertEquals( " ", WorkDownloadHeaders.asciiFallback( " " ) );
        assertEquals( "~", WorkDownloadHeaders.asciiFallback( "~" ) );
        assertEquals( "_", WorkDownloadHeaders.asciiFallback( "\u007f" ) );
        assertEquals( "_", WorkDownloadHeaders.asciiFallback( "\u001f" ) );
        assertEquals( "a_b_c_d", WorkDownloadHeaders.asciiFallback( "a\"b\\céd" ) );
        assertEquals( "_", WorkDownloadHeaders.asciiFallback( "\"" ) );
        assertEquals( "_", WorkDownloadHeaders.asciiFallback( "\\" ) );
        assertEquals( "__", WorkDownloadHeaders.asciiFallback( "😀" ), "a surrogate pair is two characters" );
        assertEquals( "download", WorkDownloadHeaders.asciiFallback( "" ) );
    }

    @Test
    void theDispositionNamesInlineOrAttachmentAndBothForms() {
        assertEquals( "inline; filename=\"a b.png\"; filename*=UTF-8''a%20b.png", WorkDownloadHeaders.disposition( true, "a b.png" ) );
        assertEquals( "attachment; filename=\"a b.png\"; filename*=UTF-8''a%20b.png", WorkDownloadHeaders.disposition( false, "a b.png" ) );
    }
}
