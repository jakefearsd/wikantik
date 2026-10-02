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

import com.wikantik.importer.VaultClusterPlanner.GeneratedHub;
import com.wikantik.importer.VaultClusterPlanner.NoteRef;
import com.wikantik.importer.VaultClusterPlanner.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VaultClusterPlannerTest {

    private static Result plan( final List< NoteRef > notes, final ImportOptions o, final FakeWikiSnapshot snap ) {
        return new VaultClusterPlanner().plan( notes, o, snap, new VaultNoteNamer( snap ) );
    }

    private static Result folders( final List< NoteRef > notes, final FakeWikiSnapshot snap ) {
        return plan( notes, ImportOptions.parse( "folders", null ), snap );
    }

    @Test
    void foldersCreateHubsFromFolderNotesOrGenerated() {
        final List< NoteRef > notes = List.of(
            new NoteRef( "Projects/Projects.md", "Projects", true ),
            new NoteRef( "Projects/Alpha.md", "Alpha", true ),
            new NoteRef( "Projects/Deep/Deeper/Gamma.md", "Gamma", true ),
            new NoteRef( "Notes/Daily.md", "Daily", true ),
            new NoteRef( "Root.md", "Root", true ) );
        final Result r = folders( notes, FakeWikiSnapshot.EMPTY );
        assertEquals( "projects", r.clusterByPath().get( "Projects/Alpha.md" ) );
        assertEquals( "projects", r.clusterByPath().get( "Projects/Projects.md" ) );
        assertEquals( "projects/deep", r.clusterByPath().get( "Projects/Deep/Deeper/Gamma.md" ) );
        assertNull( r.clusterByPath().get( "Root.md" ) );
        assertEquals( Set.of( "Projects/Projects.md" ), r.hubNotePaths() );
        assertEquals( List.of( "Deep Hub", "Notes Hub" ),
            r.generated().stream().map( GeneratedHub::name ).sorted().toList() );
        assertEquals( List.of( "notes", "projects", "projects/deep" ),
            r.clusters().stream().map( PlannedCluster::cluster ).toList() );
        assertTrue( r.clusters().stream().allMatch( c -> c.action() == ClusterAction.CREATE ) );
        assertEquals( "Projects", r.clusters().get( 1 ).hubPage() );
    }

    @Test
    void declaredClusterIsJoined() {
        final FakeWikiSnapshot snap = new FakeWikiSnapshot( Set.of( "ProjectsHub" ), Set.of(),
            Map.of( "projects", "ProjectsHub" ) );
        final Result r = folders( List.of(
            new NoteRef( "Projects/Projects.md", "Projects", true ),
            new NoteRef( "Projects/Alpha.md", "Alpha", true ) ), snap );
        assertTrue( r.hubNotePaths().isEmpty() );
        assertTrue( r.generated().isEmpty() );
        assertEquals( List.of( new PlannedCluster( "projects", ClusterAction.JOIN, "ProjectsHub", null ) ),
            r.clusters().stream().map( c -> new PlannedCluster( c.cluster(), c.action(), c.hubPage(), null ) ).toList() );
    }

    @Test
    void subClusterNeedsParentDeclared() {
        final Result r = folders( List.of( new NoteRef( "A/B/x.md", "x", true ) ), FakeWikiSnapshot.EMPTY );
        assertEquals( List.of( "a", "a/b" ), r.clusters().stream().map( PlannedCluster::cluster ).toList() );
        assertEquals( List.of( "A Hub", "B Hub" ), r.clusters().stream().map( PlannedCluster::hubPage ).toList() );
        assertTrue( r.clusters().stream().allMatch( c -> c.action() == ClusterAction.CREATE ) );
    }

    @Test
    void emptySlugSegmentSkipped() {
        final Result r = folders( List.of(
            new NoteRef( "日本/Note.md", "Note", true ),
            new NoteRef( "日本/Work/N.md", "N", true ) ), FakeWikiSnapshot.EMPTY );
        assertNull( r.clusterByPath().get( "日本/Note.md" ) );
        assertEquals( "work", r.clusterByPath().get( "日本/Work/N.md" ) );
    }

    @Test
    void sameSlugFromTwoFoldersIsOneCluster() {
        final Result r = folders( List.of(
            new NoteRef( "My Notes/a.md", "a", true ),
            new NoteRef( "my-notes/b.md", "b", true ) ), FakeWikiSnapshot.EMPTY );
        assertEquals( 1, r.clusters().size() );
        assertEquals( "my-notes", r.clusters().get( 0 ).cluster() );
        assertEquals( "My Notes Hub", r.generated().get( 0 ).name() );
        assertEquals( "My Notes", r.generated().get( 0 ).folderName() );
    }

    @Test
    void fixedModeRequiresDeclaredCluster() {
        final List< NoteRef > notes = List.of( new NoteRef( "a/x.md", "x", true ),
            new NoteRef( "b.md", "b", true ), new NoteRef( "c.md", "c", false ) );
        final ImportOptions fixed = ImportOptions.parse( "fixed", "team" );
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> plan( notes, fixed, FakeWikiSnapshot.EMPTY ) );
        assertEquals( "cluster 'team' is not declared by any hub", e.getMessage() );
        final Result r = plan( notes, fixed,
            new FakeWikiSnapshot( Set.of( "TeamHub" ), Set.of(), Map.of( "team", "TeamHub" ) ) );
        assertEquals( Map.of( "a/x.md", "team", "b.md", "team" ), r.clusterByPath() );
        assertEquals( 1, r.clusters().size() );
        assertEquals( ClusterAction.JOIN, r.clusters().get( 0 ).action() );
        assertEquals( "TeamHub", r.clusters().get( 0 ).hubPage() );
    }

    @Test
    void noneModeHasNoClusters() {
        final Result r = plan( List.of( new NoteRef( "a/x.md", "x", true ) ),
            ImportOptions.parse( "none", null ), FakeWikiSnapshot.EMPTY );
        assertTrue( r.clusterByPath().isEmpty() );
        assertTrue( r.clusters().isEmpty() );
        assertTrue( r.generated().isEmpty() );
    }

    @Test
    void nonImportableFolderNoteIsNotHub() {
        final Result r = folders( List.of(
            new NoteRef( "Projects/Projects.md", "Projects", false ),
            new NoteRef( "Projects/Alpha.md", "Alpha", true ) ), FakeWikiSnapshot.EMPTY );
        assertTrue( r.hubNotePaths().isEmpty() );
        assertEquals( "Projects Hub", r.clusters().get( 0 ).hubPage() );
        assertNull( r.clusterByPath().get( "Projects/Projects.md" ) );
    }

    @Test
    void generatedHubAvoidsExistingWikiPage() {
        final FakeWikiSnapshot snap = new FakeWikiSnapshot( Set.of( "Notes Hub" ), Set.of(), Map.of() );
        final Result r = folders( List.of( new NoteRef( "Notes/a.md", "a", true ) ), snap );
        assertEquals( "Notes Hub 2", r.clusters().get( 0 ).hubPage() );
    }
}
