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
package com.wikantik.api.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentNameValidatorTest {

    @ParameterizedTest
    @ValueSource( strings = { "file.txt", "a.b", "my-file_v2.png", "FILE.TXT", "image123.jpeg", "a.1" } )
    void acceptsWellFormedNames( final String name ) {
        assertTrue( AttachmentNameValidator.isValid( name ), name );
    }

    @ParameterizedTest
    @ValueSource( strings = {
            "",            // empty
            "noextension", // no period
            ".hidden",     // leading period
            "file.tar.gz", // two periods
            "file-.txt",   // trailing hyphen before dot
            "file_.txt",   // trailing underscore before dot
            "my file.txt", // space not allowed
            "a.",          // empty extension
            "weird@name.txt"
    } )
    void rejectsMalformedNames( final String name ) {
        assertFalse( AttachmentNameValidator.isValid( name ), name );
    }

    @Test
    void rejectsNull() {
        assertFalse( AttachmentNameValidator.isValid( null ) );
    }

    @Test
    void acceptsNameExactlyAtMaxLength() {
        final String name = "a".repeat( 36 ) + ".txt"; // 40 chars
        assertEquals( 40, name.length() );
        assertTrue( AttachmentNameValidator.isValid( name ) );
    }

    @Test
    void rejectsNameOverMaxLength() {
        final String name = "a".repeat( 37 ) + ".txt"; // 41 chars
        assertEquals( 41, name.length() );
        assertFalse( AttachmentNameValidator.isValid( name ) );
    }

    @Test
    void getExtensionLowercasesAndStripsToLastDot() {
        assertEquals( "txt", AttachmentNameValidator.getExtension( "file.txt" ) );
        assertEquals( "tar", AttachmentNameValidator.getExtension( "archive.TAR" ) );
        assertEquals( "c", AttachmentNameValidator.getExtension( "a.b.c" ) );
        assertEquals( "gitignore", AttachmentNameValidator.getExtension( ".gitignore" ) );
    }

    @Test
    void getExtensionReturnsEmptyWhenAbsent() {
        assertEquals( "", AttachmentNameValidator.getExtension( "noext" ) );
        assertEquals( "", AttachmentNameValidator.getExtension( "" ) );
        assertEquals( "", AttachmentNameValidator.getExtension( null ) );
    }

    @Test
    void extensionsMatchIgnoringCase() {
        assertTrue( AttachmentNameValidator.extensionsMatch( "photo.JPG", "image.jpg" ) );
        assertTrue( AttachmentNameValidator.extensionsMatch( "noext", "other" ) );
        assertFalse( AttachmentNameValidator.extensionsMatch( "a.png", "b.gif" ) );
        assertFalse( AttachmentNameValidator.extensionsMatch( null, "x.txt" ) );
    }
}
