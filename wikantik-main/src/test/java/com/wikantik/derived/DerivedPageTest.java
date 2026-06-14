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
}
