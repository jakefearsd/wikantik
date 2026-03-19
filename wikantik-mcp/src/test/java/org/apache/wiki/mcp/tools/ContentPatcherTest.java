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
package org.apache.wiki.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentPatcherTest {

    private static final String BODY =
            "## Introduction\n" +
            "Some intro text.\n" +
            "\n" +
            "## Details\n" +
            "Detail paragraph one.\n" +
            "\n" +
            "### Subsection\n" +
            "Subsection content.\n" +
            "\n" +
            "## Further Reading\n" +
            "- Link 1";

    @Test
    void testFindSections() {
        final List< ContentPatcher.Section > sections = ContentPatcher.findSections( BODY );
        assertEquals( 4, sections.size() );

        assertEquals( "Introduction", sections.get( 0 ).heading() );
        assertEquals( 2, sections.get( 0 ).level() );

        assertEquals( "Details", sections.get( 1 ).heading() );
        assertEquals( 2, sections.get( 1 ).level() );

        assertEquals( "Subsection", sections.get( 2 ).heading() );
        assertEquals( 3, sections.get( 2 ).level() );

        assertEquals( "Further Reading", sections.get( 3 ).heading() );
        assertEquals( 2, sections.get( 3 ).level() );
    }

    @Test
    void testAppendToMiddleSection() throws PatchException {
        final String result = ContentPatcher.appendToSection( BODY, "Details", "New detail line." );
        assertTrue( result.contains( "New detail line.\n## Further Reading" ) );
        assertTrue( result.contains( "Subsection content." ) );
    }

    @Test
    void testAppendToLastSection() throws PatchException {
        final String result = ContentPatcher.appendToSection( BODY, "Further Reading", "- Link 2" );
        assertTrue( result.contains( "- Link 1\n- Link 2" ) );
    }

    @Test
    void testInsertBefore() throws PatchException {
        final String result = ContentPatcher.insertBefore( BODY, "Detail paragraph one.", "INSERTED LINE" );
        assertTrue( result.contains( "INSERTED LINE\nDetail paragraph one." ) );
    }

    @Test
    void testInsertAfter() throws PatchException {
        final String result = ContentPatcher.insertAfter( BODY, "Detail paragraph one.", "INSERTED LINE" );
        assertTrue( result.contains( "Detail paragraph one.\nINSERTED LINE" ) );
    }

    @Test
    void testReplaceSection() throws PatchException {
        final String result = ContentPatcher.replaceSection( BODY, "Introduction", "Replaced intro." );
        assertTrue( result.contains( "## Introduction\nReplaced intro.\n## Details" ) );
        assertFalse( result.contains( "Some intro text." ) );
    }

    @Test
    void testSectionNotFound() {
        final PatchException ex = assertThrows( PatchException.class,
                () -> ContentPatcher.appendToSection( BODY, "NonExistent", "content" ) );
        assertTrue( ex.getMessage().contains( "Section not found" ) );
        assertNotNull( ex.getSuggestion() );
    }

    @Test
    void testMarkerNotFound() {
        final PatchException ex = assertThrows( PatchException.class,
                () -> ContentPatcher.insertBefore( BODY, "NOTHING_LIKE_THIS", "content" ) );
        assertTrue( ex.getMessage().contains( "Marker not found" ) );
    }

    @Test
    void testNestedSections() {
        final List< ContentPatcher.Section > sections = ContentPatcher.findSections( BODY );
        // Subsection (level 3) should end at the next level-2 heading
        final ContentPatcher.Section subsection = sections.get( 2 );
        assertEquals( "Subsection", subsection.heading() );
        assertEquals( 3, subsection.level() );
        // Subsection ends at "## Further Reading" line
        assertEquals( 9, subsection.endLine() );
    }

    @Test
    void testApplyMultipleOperations() throws PatchException {
        final List< Map< String, Object > > ops = List.of(
                Map.of( "action", "append_to_section", "section", "Further Reading", "content", "- Link 2" ),
                Map.of( "action", "insert_after", "marker", "Some intro text.", "content", "Extra intro line." )
        );

        final String result = ContentPatcher.applyOperations( BODY, ops );
        assertTrue( result.contains( "- Link 2" ) );
        assertTrue( result.contains( "Extra intro line." ) );
    }

    @Test
    void testUnknownAction() {
        final List< Map< String, Object > > ops = List.of(
                Map.of( "action", "delete_section", "section", "Introduction", "content", "x" )
        );

        final PatchException ex = assertThrows( PatchException.class,
                () -> ContentPatcher.applyOperations( BODY, ops ) );
        assertTrue( ex.getMessage().contains( "Unknown action" ) );
    }

    @Test
    void testMissingContentField() {
        final List< Map< String, Object > > ops = List.of(
                Map.of( "action", "append_to_section", "section", "Introduction" )
        );

        final PatchException ex = assertThrows( PatchException.class,
                () -> ContentPatcher.applyOperations( BODY, ops ) );
        assertTrue( ex.getMessage().contains( "content" ) );
    }
}
