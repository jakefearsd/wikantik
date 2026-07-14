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
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DriveItemsTest {
    @Test void toItemBuildsMarkdownSourceItemShape() {
        DriveFile f = new DriveFile( "1AbC", "Guide", "application/vnd.google-apps.document",
                "2026-07-01T10:00:00Z", "https://docs.google.com/d/1AbC" );
        byte[] body = "# Guide\n\ntext".getBytes( StandardCharsets.UTF_8 );
        SourceItem it = DriveItems.toItem( f, body, "text/markdown" );

        assertEquals( "gdrive://1AbC", it.sourceUri() );
        assertEquals( "text/markdown", it.contentType() );
        assertArrayEquals( body, it.content() );
        assertTrue( it.aclRefs().isEmpty() );
        assertEquals( "1AbC", it.metadata().get( "id" ) );
        assertEquals( "Guide", it.metadata().get( "name" ) );
        assertEquals( "application/vnd.google-apps.document", it.metadata().get( "mimeType" ) );
        assertEquals( "2026-07-01T10:00:00Z", it.metadata().get( "modifiedTime" ) );
        assertEquals( "https://docs.google.com/d/1AbC", it.metadata().get( "webViewLink" ) );
        assertEquals( DriveItems.sha256Hex( body ), it.contentHash() );
        assertEquals( 64, it.contentHash().length() );   // sha-256 hex
    }
    @Test void sha256IsContentAddressed() {
        assertEquals( DriveItems.sha256Hex( "x".getBytes() ), DriveItems.sha256Hex( "x".getBytes() ) );
        assertNotEquals( DriveItems.sha256Hex( "x".getBytes() ), DriveItems.sha256Hex( "y".getBytes() ) );
    }
}
