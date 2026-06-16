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
package com.wikantik.derived;

import com.wikantik.parser.MarkupParser;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DerivedPageTest {

    @Test
    void isDerivedWhenDerivedFromPresent() {
        assertTrue( DerivedPage.isDerived( Map.of( DerivedPage.DERIVED_FROM, "Doc.pdf" ) ) );
        assertFalse( DerivedPage.isDerived( Map.of( "type", "article" ) ) );
        assertFalse( DerivedPage.isDerived( null ) );
    }

    @Test
    void derivedFromReturnsSourceName() {
        assertEquals( "Doc.pdf",
            DerivedPage.derivedFrom( Map.of( DerivedPage.DERIVED_FROM, "Doc.pdf" ) ).orElse( null ) );
        assertTrue( DerivedPage.derivedFrom( Map.of() ).isEmpty() );
    }

    @Test
    void sha256IsStableHexOfBytes() {
        final String a = DerivedPage.sha256( "hello".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        final String b = DerivedPage.sha256( "hello".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        assertEquals( a, b );
        assertEquals( 64, a.length() );
        assertNotEquals( a, DerivedPage.sha256( "world".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) );
    }

    @Test
    void pageNameSlugifiesFilename() {
        assertEquals( "Research Paper", DerivedPage.pageNameFor( "Research Paper.pdf" ) );
        // First character is uppercased so the name is stable under cleanLink() resolution
        // in DefaultAttachmentManager.getAttachmentInfo().
        assertEquals( "My-doc v2", DerivedPage.pageNameFor( "my-doc v2.docx" ) );
    }

    // -------------------------------------------------------------------------
    // Security: path-traversal and overwrite-via-filename (fix A)
    // -------------------------------------------------------------------------

    /** Unix path traversal: basename must be extracted, no slash or '..' in result. */
    @Test
    void pageNameFor_unixPathTraversal_returnsSafeName() {
        final String name = DerivedPage.pageNameFor( "../../../etc/passwd.pdf" );
        assertFalse( name.contains( "/" ),  "must not contain '/': " + name );
        assertFalse( name.contains( ".." ), "must not contain '..': " + name );
        assertEquals( "Passwd", name );
    }

    /** Windows path traversal: backslash separator must also be stripped as a basename separator. */
    @Test
    void pageNameFor_windowsPathTraversal_returnsSafeName() {
        final String name = DerivedPage.pageNameFor( "..\\..\\Main.pdf" );
        assertFalse( name.contains( "\\" ), "must not contain '\\': " + name );
        assertFalse( name.contains( ".." ), "must not contain '..': " + name );
        assertEquals( "Main", name );
    }

    /** Nested path: only the final component must be used. */
    @Test
    void pageNameFor_nestedPath_returnsBasenameOnly() {
        final String name = DerivedPage.pageNameFor( "a/b/c.txt" );
        assertFalse( name.contains( "/" ),  "must not contain '/': " + name );
        assertFalse( name.contains( ".." ), "must not contain '..': " + name );
        assertEquals( "C", name );
    }

    /** A name that reduces to empty after sanitization must return the safe fallback. */
    @Test
    void pageNameFor_emptyAfterSanitization_returnsFallback() {
        // A name consisting only of unsafe chars strips to "" → must return "Document"
        final String name = DerivedPage.pageNameFor( "!!##$$.pdf" );
        assertFalse( name.isBlank(), "fallback must not be blank: " + name );
    }

    /** Characters outside the safe set (letters, digits, space, hyphen, underscore) are removed. */
    @Test
    void pageNameFor_unsafeCharsStripped() {
        final String name = DerivedPage.pageNameFor( "my<doc>:v1*.pdf" );
        assertFalse( name.contains( "<" ), "must not contain '<': " + name );
        assertFalse( name.contains( ">" ), "must not contain '>': " + name );
        assertFalse( name.contains( ":" ), "must not contain ':': " + name );
        assertFalse( name.contains( "*" ), "must not contain '*': " + name );
    }

    /**
     * Fixed-point invariant: the name produced by {@code pageNameFor} must be stable under
     * a second {@code MarkupParser.cleanLink} pass — exactly what
     * {@code DefaultAttachmentManager.getAttachmentInfo} applies when resolving the parent
     * page from an attachment path.
     */
    @Test
    void pageNameFor_isFixedPointUnderCleanLink() {
        final String[] inputs = {
            "my report.pdf",
            "FOO bar.txt",
            "../../weird name.pdf",
            "Research Paper.pdf",
            "my-doc v2.docx",
            "Simple.pdf"
        };
        for ( final String input : inputs ) {
            final String produced = DerivedPage.pageNameFor( input );
            final String reClean  = MarkupParser.cleanLink( produced );
            assertEquals( produced, reClean,
                "pageNameFor(\"" + input + "\") = \"" + produced + "\" is not a fixed point under cleanLink; got \""
                + reClean + "\"" );
        }
    }
}
