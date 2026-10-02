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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VaultNoteNamerTest {

    @Test
    void caseVariantsInSameFolder() {
        final Map< String, String > n = new VaultNoteNamer( FakeWikiSnapshot.EMPTY )
            .assign( List.of( "Projects/ideas.md", "Projects/Ideas.md" ) );
        assertEquals( "Ideas", n.get( "Projects/Ideas.md" ) );
        assertEquals( "ideas (Projects)", n.get( "Projects/ideas.md" ) );
    }

    @Test
    void collisionAcrossFoldersThenNumbers() {
        final Map< String, String > n = new VaultNoteNamer( FakeWikiSnapshot.EMPTY )
            .assign( List.of( "A/Note.md", "B/Note.md", "B/x/Note.md", "Note.md" ) );
        assertEquals( "Note", n.get( "A/Note.md" ) );
        assertEquals( "Note (B)", n.get( "B/Note.md" ) );
        assertEquals( "Note (x)", n.get( "B/x/Note.md" ) );
        assertEquals( "Note 2", n.get( "Note.md" ) );
    }

    @Test
    void longNamesStayWithinLimitWhenSuffixed() {
        final String longName = "y".repeat( 128 );
        final Map< String, String > n = new VaultNoteNamer( FakeWikiSnapshot.EMPTY )
            .assign( List.of( "A/" + longName + ".md", "B/" + longName + ".md" ) );
        assertEquals( 128, n.get( "B/" + longName + ".md" ).length() );
        assertEquals( true, n.get( "B/" + longName + ".md" ).endsWith( " (B)" ) );
    }

    @Test
    void resultIsInOrderOrder() {
        final Map< String, String > n = new VaultNoteNamer( FakeWikiSnapshot.EMPTY )
            .assign( List.of( "b.md", "A.md" ) );
        assertEquals( List.of( "A.md", "b.md" ), List.copyOf( n.keySet() ) );
    }

    @Test
    void existingWikiNamesDoNotParticipateInAssign() {
        final Map< String, String > n = new VaultNoteNamer(
            new FakeWikiSnapshot( Set.of( "Alpha" ), Set.of(), Map.of() ) ).assign( List.of( "Alpha.md" ) );
        assertEquals( "Alpha", n.get( "Alpha.md" ) );
    }

    @Test
    void generatedNamesAvoidExistingWikiPages() {
        final VaultNoteNamer namer = new VaultNoteNamer(
            new FakeWikiSnapshot( Set.of( "Projects Hub" ), Set.of(), Map.of() ) );
        assertEquals( "Projects Hub 2", namer.allocateGenerated( "Projects Hub" ) );
        assertEquals( "Projects Hub 3", namer.allocateGenerated( "Projects Hub" ) );
    }

    @Test
    void generatedNamesAvoidAssignedNotes() {
        final VaultNoteNamer namer = new VaultNoteNamer( FakeWikiSnapshot.EMPTY );
        namer.assign( List.of( "Hub.md" ) );
        assertEquals( "hub 2", namer.allocateGenerated( "hub" ) );
    }

    @Test
    void hugeFolderNamesStillYieldValidNames() {
        final String folder = "f".repeat( 130 );
        final String name = "n".repeat( 128 );
        final Map< String, String > n = new VaultNoteNamer( FakeWikiSnapshot.EMPTY )
            .assign( List.of( folder + "/" + name + ".md", "Z/" + name + ".md", folder + "/Short.md", "Q/Short.md", "Short.md" ) );
        for ( final String page : n.values() ) {
            assertTrue( page.length() <= 128, page );
            assertTrue( com.wikantik.util.WikiPageNameValidator.isValid( page ), page );
            assertEquals( page.trim(), page );
        }
        assertEquals( 5, Set.copyOf( n.values().stream().map( String::toLowerCase ).toList() ).size() );
    }

    @Test
    void nfdNamesKeepTheirAccents() {
        final String nfd = java.text.Normalizer.normalize( "Caf\u00e9.md", java.text.Normalizer.Form.NFD );
        final Map< String, String > n = new VaultNoteNamer( FakeWikiSnapshot.EMPTY ).assign( List.of( nfd ) );
        assertEquals( "Caf\u00e9", n.get( nfd ) );
    }
}
