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
package com.wikantik.api.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.PageManager;

import org.junit.jupiter.api.Test;

class PageSaveHelperTest {

    private PageSaveHelper newHelper( final PageManager pm ) {
        return new PageSaveHelper( mock( Engine.class ), pm );
    }

    // --- computeContentHash ------------------------------------------------

    @Test
    void computeContentHashMatchesKnownSha256Vectors() {
        assertEquals( "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PageSaveHelper.computeContentHash( "" ) );
        assertEquals( "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PageSaveHelper.computeContentHash( "abc" ) );
    }

    @Test
    void computeContentHashIsDeterministicAndInputSensitive() {
        assertEquals( PageSaveHelper.computeContentHash( "hello world" ),
                PageSaveHelper.computeContentHash( "hello world" ) );
        assertNotEquals( PageSaveHelper.computeContentHash( "hello world" ),
                PageSaveHelper.computeContentHash( "hello worle" ) );
    }

    // --- mergeMetadata -----------------------------------------------------

    @Test
    void mergeMetadataReturnsCallerMetadataWhenNoExistingPage() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( "Fresh" ), anyInt() ) ).thenReturn( null );
        final Map< String, Object > caller = Map.of( "title", "New" );

        assertSame( caller, newHelper( pm ).mergeMetadata( pm, "Fresh", caller ) );
    }

    @Test
    void mergeMetadataReturnsCallerMetadataWhenExistingPageEmpty() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( "Empty" ), anyInt() ) ).thenReturn( "" );
        final Map< String, Object > caller = Map.of( "title", "New" );

        assertSame( caller, newHelper( pm ).mergeMetadata( pm, "Empty", caller ) );
    }

    @Test
    void mergeMetadataReturnsCallerMetadataWhenExistingPageHasNoFrontmatter() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( "Plain" ), anyInt() ) ).thenReturn( "Just a body, no frontmatter." );
        final Map< String, Object > caller = Map.of( "title", "New" );

        assertSame( caller, newHelper( pm ).mergeMetadata( pm, "Plain", caller ) );
    }

    @Test
    void mergeMetadataMergesExistingFrontmatterWithCallerWinningConflicts() {
        final PageManager pm = mock( PageManager.class );
        when( pm.getPureText( eq( "Doc" ), anyInt() ) )
                .thenReturn( "---\ntitle: Old Title\ncluster: Reference\n---\nbody text" );

        final Map< String, Object > merged = newHelper( pm ).mergeMetadata(
                pm, "Doc", Map.of( "title", "New Title", "extra", "added" ) );

        assertEquals( "New Title", merged.get( "title" ) );   // caller overrides existing
        assertEquals( "Reference", merged.get( "cluster" ) ); // existing-only field preserved
        assertEquals( "added", merged.get( "extra" ) );       // caller-only field added
    }
}
