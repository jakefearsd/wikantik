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
package com.wikantik.attachment;

import com.wikantik.api.attachment.AttachmentNameValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentNameValidatorTest {

    @Test
    void validSimpleName() {
        assertTrue( AttachmentNameValidator.isValid( "beach.jpg" ) );
    }

    @Test
    void validWithHyphenAndUnderscore() {
        assertTrue( AttachmentNameValidator.isValid( "my-photo_01.png" ) );
    }

    @Test
    void validMaxLength() {
        // 36 char stem + .jpg = 40 chars exactly
        assertTrue( AttachmentNameValidator.isValid( "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg" ) );
    }

    @Test
    void rejectTooLong() {
        // 37 char stem + .jpg = 41 chars
        assertFalse( AttachmentNameValidator.isValid( "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg" ) );
    }

    @Test
    void rejectSpaces() {
        assertFalse( AttachmentNameValidator.isValid( "my photo.jpg" ) );
    }

    @Test
    void rejectSpecialChars() {
        assertFalse( AttachmentNameValidator.isValid( "photo#1.jpg" ) );
        assertFalse( AttachmentNameValidator.isValid( "photo@2.jpg" ) );
        assertFalse( AttachmentNameValidator.isValid( "photo!.jpg" ) );
    }

    @Test
    void rejectNoPeriod() {
        assertFalse( AttachmentNameValidator.isValid( "noextension" ) );
    }

    @Test
    void rejectMultiplePeriods() {
        assertFalse( AttachmentNameValidator.isValid( "my.backup.jpg" ) );
    }

    @Test
    void rejectLeadingPeriod() {
        assertFalse( AttachmentNameValidator.isValid( ".hidden.jpg" ) );
    }

    @Test
    void rejectTrailingHyphen() {
        assertFalse( AttachmentNameValidator.isValid( "file-.jpg" ) );
    }

    @Test
    void rejectLeadingUnderscore() {
        assertFalse( AttachmentNameValidator.isValid( "_file.jpg" ) );
    }

    @Test
    void rejectNull() {
        assertFalse( AttachmentNameValidator.isValid( null ) );
    }

    @Test
    void rejectEmpty() {
        assertFalse( AttachmentNameValidator.isValid( "" ) );
    }

    @Test
    void extensionsMatchCaseInsensitive() {
        assertTrue( AttachmentNameValidator.extensionsMatch( "photo.JPG", "beach.jpg" ) );
        assertTrue( AttachmentNameValidator.extensionsMatch( "file.Png", "other.PNG" ) );
    }

    @Test
    void extensionsMismatch() {
        assertFalse( AttachmentNameValidator.extensionsMatch( "photo.jpg", "beach.png" ) );
    }

    @Test
    void extensionExtracted() {
        assertEquals( "jpg", AttachmentNameValidator.getExtension( "beach.jpg" ) );
        assertEquals( "png", AttachmentNameValidator.getExtension( "PHOTO.PNG" ) );
    }
}
