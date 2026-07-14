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
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GithubItemsTest {

    @Test void buildsUriMetadataAndHash() {
        byte[] body = "# Hello".getBytes( StandardCharsets.UTF_8 );
        GithubFile f = new GithubFile( "docs/guide/intro.md", "abc123", 7 );
        SourceItem item = GithubItems.toItem( "acme/handbook", "main", f, body );
        assertEquals( "github://acme/handbook/docs/guide/intro.md", item.sourceUri() );
        assertEquals( "text/markdown", item.contentType() );
        assertArrayEquals( body, item.content() );
        assertTrue( item.aclRefs().isEmpty() );
        assertEquals( 64, item.contentHash().length() );
        assertEquals( "docs/guide/intro.md", item.sourceMetadata().get( "id" ) );
        assertEquals( "intro.md", item.sourceMetadata().get( "name" ) );
        assertEquals( "text/markdown", item.sourceMetadata().get( "mimeType" ) );
        assertEquals( "https://github.com/acme/handbook/blob/main/docs/guide/intro.md",
            item.sourceMetadata().get( "webViewLink" ) );
        assertFalse( item.sourceMetadata().containsKey( "modifiedTime" ) );   // tree listing has none
    }

    @Test void sameContentSameHashDifferentContentDifferentHash() {
        GithubFile f = new GithubFile( "a.md", "s", 1 );
        String h1 = GithubItems.toItem( "o/r", "main", f, new byte[]{ 1 } ).contentHash();
        String h2 = GithubItems.toItem( "o/r", "main", f, new byte[]{ 1 } ).contentHash();
        String h3 = GithubItems.toItem( "o/r", "main", f, new byte[]{ 2 } ).contentHash();
        assertEquals( h1, h2 );
        assertNotEquals( h1, h3 );
    }
}
