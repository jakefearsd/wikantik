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

import com.wikantik.attachment.AttachmentUploadPolicy;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentGateTest {

    private static final AttachmentGate OPEN =
        new AttachmentGate( new AttachmentUploadPolicy( new String[ 0 ], new String[ 0 ], Long.MAX_VALUE ) );

    @Test
    void activeContentExtensionsAreBlocked() {
        assertEquals( "blocked file type .svg", OPEN.rejection( "evil.svg", 1 ).orElseThrow() );
        assertEquals( "blocked file type .html", OPEN.rejection( "Page.HTML", 1 ).orElseThrow() );
    }

    @Test
    void oversizeIsRejectedNamingTheKey() {
        final AttachmentGate small = new AttachmentGate( new AttachmentUploadPolicy( new String[ 0 ], new String[ 0 ], 10 ) );
        assertTrue( small.rejection( "a.png", 11 ).orElseThrow().contains( "wikantik.attachment.maxsize (10 bytes)" ) );
        assertTrue( small.rejection( "a.png", 10 ).isEmpty() );
    }

    @Test
    void forbiddenExtensionFromProperties() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.attachment.forbidden", ".exe .bat" );
        final AttachmentGate gate = AttachmentGate.fromProperties( p );
        assertEquals( "file type not allowed by the attachment upload policy", gate.rejection( "run.exe", 1 ).orElseThrow() );
        assertTrue( gate.rejection( "pic.png", 1 ).isEmpty() );
    }

    @Test
    void ordinaryFileIsAccepted() {
        assertTrue( OPEN.rejection( "diagram.png", 1000 ).isEmpty() );
    }
}
