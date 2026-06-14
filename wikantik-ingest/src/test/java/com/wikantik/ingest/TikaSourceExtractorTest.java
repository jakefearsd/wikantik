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
package com.wikantik.ingest;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class TikaSourceExtractorTest {

    private final TikaSourceExtractor ex = new TikaSourceExtractor();

    private InputStream fixture( final String name ) {
        return getClass().getResourceAsStream( "/derived/" + name );
    }

    @Test
    void supportsCommonDocTypes() {
        assertTrue( ex.supports( "application/pdf" ) );
        assertTrue( ex.supports( "text/plain" ) );
        assertTrue( ex.supports( "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ) );
        assertFalse( ex.supports( "image/png" ) );
    }

    @Test
    void extractsPlainText() throws Exception {
        try ( InputStream in = fixture( "sample.txt" ) ) {
            final ExtractionResult r = ex.extract( in, "text/plain", "sample.txt" );
            assertFalse( r.isEmpty() );
            assertTrue( r.markdownBody().toLowerCase().contains( "hello" ) );
        }
    }

    @Test
    void extractsDocxWithBodyText() throws Exception {
        try ( InputStream in = fixture( "sample.docx" ) ) {
            final ExtractionResult r = ex.extract( in, null, "sample.docx" );
            assertFalse( r.isEmpty(), "docx extraction should not be blank" );
        }
    }

    @Test
    void extractsPdfWithBodyText() throws Exception {
        try ( InputStream in = fixture( "sample.pdf" ) ) {
            final ExtractionResult r = ex.extract( in, "application/pdf", "sample.pdf" );
            assertFalse( r.isEmpty(), "pdf extraction should not be blank" );
        }
    }

    @Test
    void emptyExtractionIsReportedNotBlankSilently() throws Exception {
        // An empty text stream → isEmpty()==true (caller flags it, does not save a blank page).
        try ( InputStream in = new java.io.ByteArrayInputStream( "   ".getBytes() ) ) {
            assertTrue( ex.extract( in, "text/plain", "blank.txt" ).isEmpty() );
        }
    }
}
