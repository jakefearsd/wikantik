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
package com.wikantik.connectors.web;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WebFetchItemsTest {
    @Test void sha256HexIs64LowercaseHex() {
        String h = WebFetchItems.sha256Hex( "abc".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( 64, h.length() );
        assertTrue( h.matches( "[0-9a-f]{64}" ) );
    }
    @Test void toItemBuildsTextHtmlSourceItem() {
        byte[] body = "<html><head><title>Hi</title></head><body>x</body></html>".getBytes( StandardCharsets.UTF_8 );
        SourceItem i = WebFetchItems.toItem( "https://ex.com/p", new FetchResult( 200, "text/html", body, "https://ex.com/p" ) );
        assertEquals( "https://ex.com/p", i.sourceUri() );
        assertEquals( "text/html", i.contentType() );
        assertTrue( i.aclRefs().isEmpty() );
        assertEquals( 64, i.contentHash().length() );
        assertEquals( "https://ex.com/p", i.sourceMetadata().get( "url" ) );
        assertEquals( "Hi", i.sourceMetadata().get( "title" ) );
        assertEquals( 200, i.sourceMetadata().get( "httpStatus" ) );
        assertNotNull( i.sourceMetadata().get( "fetchedAt" ) );
    }
    @Test void toItemFromContentBuildsTextHtmlItem() {
        byte[] html = "<p>inline feed content</p>".getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        com.wikantik.api.connectors.SourceItem i = WebFetchItems.toItemFromContent( "https://ex.com/post", html, "My Post" );
        assertEquals( "https://ex.com/post", i.sourceUri() );
        assertEquals( "text/html", i.contentType() );
        assertArrayEquals( html, i.content() );
        assertTrue( i.aclRefs().isEmpty() );
        assertEquals( 64, i.contentHash().length() );
        assertEquals( "My Post", i.sourceMetadata().get( "title" ) );
        assertEquals( "https://ex.com/post", i.sourceMetadata().get( "url" ) );
        assertNotNull( i.sourceMetadata().get( "fetchedAt" ) );
        assertFalse( i.sourceMetadata().containsKey( "httpStatus" ) );   // no page fetch
    }
}
