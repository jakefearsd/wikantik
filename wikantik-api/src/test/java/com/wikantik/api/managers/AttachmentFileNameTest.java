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
package com.wikantik.api.managers;

import com.wikantik.api.exceptions.WikiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The upload file-name gate: path stripping, blank refusal, blocked active-content extensions, URL-hostile characters. */
class AttachmentFileNameTest {

    @ParameterizedTest
    @ValueSource( strings = { "", "   ", "\t", "..", "...", " . ", "./", "dir/..", "C:\\dir\\." } )
    void blankOrDotOnlyNamesAreRefusedAsEmpty( final String name ) {
        final WikiException e = assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( name ) );
        assertEquals( "attach.empty.file", e.getMessage() );
    }

    @Test
    void aNullNameIsRefusedAsEmpty() {
        final WikiException e = assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( null ) );
        assertEquals( "attach.empty.file", e.getMessage() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/", "//", "\\", "/\\/" } )
    void aNameThatIsOnlyPathSeparatorsIsRefusedAsEmptyNotACrash( final String name ) {
        final WikiException e = assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( name ) );
        assertEquals( "attach.empty.file", e.getMessage() );
    }

    @ParameterizedTest
    @CsvSource( delimiter = '|', value = {
            "photo.png|photo.png",
            "/etc/passwd.txt|passwd.txt",
            "C:\\Users\\me\\scan.pdf|scan.pdf",
            "a/b\\c/d.txt|d.txt",
            "  padded.txt  |padded.txt",
            "trailing.dots...|trailing.dots",
            "no extension|no extension",
            "a?b#c\"d'e.txt|a_b_c_d_e.txt",
            "dir/|dir",
    } )
    void thePathIsStrippedAndHostileCharactersReplaced( final String in, final String expected ) throws Exception {
        assertEquals( expected, AttachmentManager.validateFileName( in ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "x.jsp", "x.JSP", "x.html", "x.HtM", "x.svg", "x.xhtml", "x.phtml", "x.jspx",
            "trailing.html.", "trailing.html ..", "a/b/evil.shtml", "double.ext.jsp" } )
    void activeContentExtensionsAreRefusedWhateverTheCaseOrTrailingDots( final String name ) {
        final WikiException e = assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( name ) );
        assertEquals( "attach.unwanted.file", e.getMessage() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "x.txt", "x.png", "html", "svgfile", "x.html.txt", "x.jspa", ".html.bak" } )
    void otherNamesAreNotBlocked( final String name ) throws Exception {
        assertEquals( name, AttachmentManager.validateFileName( name ) );
    }

    @Test
    void aBlockedExtensionIsOnlyRecognisedAfterTheLastDot() throws Exception {
        assertEquals( "jsp.txt", AttachmentManager.validateFileName( "jsp.txt" ) );
        final WikiException e = assertThrows( WikiException.class, () -> AttachmentManager.validateFileName( ".jsp" ) );
        assertEquals( "attach.unwanted.file", e.getMessage() );
    }
}
