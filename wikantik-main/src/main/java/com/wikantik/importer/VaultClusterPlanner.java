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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Plans the cluster of every importable note and the hub page of every cluster. */
public final class VaultClusterPlanner {

    /** A vault note as the planner sees it. */
    public record NoteRef( String path, String name, boolean importable ) {}

    /** A hub page the import must generate. */
    public record GeneratedHub( String name, String cluster, String folderName ) {}

    /** The plan: cluster per note path, folder notes promoted to hubs, generated hubs, clusters in order. */
    public record Result( Map< String, String > clusterByPath, Set< String > hubNotePaths,
                          List< GeneratedHub > generated, List< PlannedCluster > clusters ) {
        /** Defensive, unmodifiable, order-preserving copies. */
        public Result {
            clusterByPath = java.util.Collections.unmodifiableMap( new LinkedHashMap<>( clusterByPath ) );
            hubNotePaths = java.util.Collections.unmodifiableSet( new LinkedHashSet<>( hubNotePaths ) );
            generated = List.copyOf( generated );
            clusters = List.copyOf( clusters );
        }
    }

    /** A folder segment with its slug and the original folder path ending at it. */
    private record Seg( String name, String slug, String folderPath ) {}

    /** Display name of a cluster (from the first folder that needed it) and every folder path that folds into it. */
    private record Origin( String display, List< String > folderPaths ) {
        String folderPath() {
            return folderPaths.get( 0 );
        }

        void add( final String folderPath ) {
            if ( !folderPaths.contains( folderPath ) ) {
                folderPaths.add( folderPath );
            }
        }
    }

    public Result plan( final List< NoteRef > notes, final ImportOptions options,
                        final WikiSnapshot snapshot, final VaultNoteNamer namer ) {
        return switch ( options.mode() ) {
            case NONE -> new Result( Map.of(), Set.of(), List.of(), List.of() );
            case FIXED -> planFixed( notes, options.cluster(), snapshot );
            case FOLDERS -> planFolders( notes, snapshot, namer );
        };
    }

    private static Result planFixed( final List< NoteRef > notes, final String cluster, final WikiSnapshot snapshot ) {
        final String hub = snapshot.hubPage( cluster ).orElseThrow( () ->
            new IllegalArgumentException( "cluster '" + cluster + "' is not declared by any hub" ) );
        final Map< String, String > byPath = new LinkedHashMap<>();
        for ( final NoteRef n : importable( notes ) ) {
            byPath.put( n.path(), cluster );
        }
        return new Result( byPath, Set.of(), List.of(),
            List.of( new PlannedCluster( cluster, ClusterAction.JOIN, hub, null ) ) );
    }

    private Result planFolders( final List< NoteRef > notes, final WikiSnapshot snapshot, final VaultNoteNamer namer ) {
        final List< NoteRef > sorted = importable( notes );
        final Map< String, String > byPath = new LinkedHashMap<>();
        final Map< String, Origin > origins = new TreeMap<>();
        assignClusters( sorted, byPath, origins );
        final Set< String > hubNotes = new LinkedHashSet<>();
        final List< GeneratedHub > generated = new ArrayList<>();
        final List< PlannedCluster > clusters = new ArrayList<>();
        for ( final String c : neededClusters( origins ) ) {
            clusters.add( planCluster( c, origins.get( c ), sorted, snapshot, namer, byPath, hubNotes, generated ) );
        }
        return new Result( byPath, hubNotes, generated, clusters );
    }

    private static List< NoteRef > importable( final List< NoteRef > notes ) {
        return notes.stream().filter( NoteRef::importable )
            .sorted( ( a, b ) -> VaultPaths.ORDER.compare( a.path(), b.path() ) ).toList();
    }

    private static void assignClusters( final List< NoteRef > sorted, final Map< String, String > byPath,
                                        final Map< String, Origin > origins ) {
        for ( final NoteRef note : sorted ) {
            final List< Seg > segs = segments( VaultPaths.parentFolder( note.path() ) );
            if ( segs.isEmpty() ) {
                continue;
            }
            final Seg s0 = segs.get( 0 );
            origin( origins, s0.slug(), s0 );
            String cluster = s0.slug();
            if ( segs.size() > 1 ) {
                final Seg s1 = segs.get( 1 );
                cluster = s0.slug() + "/" + s1.slug();
                origin( origins, cluster, s1 );
            }
            byPath.put( note.path(), cluster );
        }
    }

    private static void origin( final Map< String, Origin > origins, final String cluster, final Seg seg ) {
        origins.computeIfAbsent( cluster, c -> new Origin( seg.name(), new ArrayList<>() ) ).add( seg.folderPath() );
    }

    private static List< Seg > segments( final String folder ) {
        final List< Seg > segs = new ArrayList<>();
        if ( folder.isEmpty() ) {
            return segs;
        }
        final StringBuilder prefix = new StringBuilder();
        for ( final String part : folder.split( "/" ) ) {
            prefix.append( prefix.length() == 0 ? "" : "/" ).append( part );
            final String slug = VaultNames.slug( part );
            if ( !slug.isEmpty() ) {
                segs.add( new Seg( part, slug, prefix.toString() ) );
            }
        }
        return segs;
    }

    /** Parents sort before {@code parent/child}, so a sub-cluster's parent is always planned first. */
    private static Set< String > neededClusters( final Map< String, Origin > origins ) {
        return origins.keySet();
    }

    private static PlannedCluster planCluster( final String cluster, final Origin origin, final List< NoteRef > notes,
                                               final WikiSnapshot snapshot, final VaultNoteNamer namer,
                                               final Map< String, String > byPath, final Set< String > hubNotes,
                                               final List< GeneratedHub > generated ) {
        final Optional< String > declared = snapshot.hubPage( cluster );
        if ( declared.isPresent() ) {
            return new PlannedCluster( cluster, ClusterAction.JOIN, declared.get(), origin.folderPath() );
        }
        final Optional< NoteRef > folderNote = origin.folderPaths().stream()
            .map( f -> findFolderNote( f, notes ) ).flatMap( Optional::stream ).findFirst();
        if ( folderNote.isPresent() ) {
            hubNotes.add( folderNote.get().path() );
            byPath.put( folderNote.get().path(), cluster );
            return new PlannedCluster( cluster, ClusterAction.CREATE, folderNote.get().name(),
                VaultPaths.parentFolder( folderNote.get().path() ) );
        }
        final String name = namer.allocateGenerated( origin.display() + " Hub" );
        generated.add( new GeneratedHub( name, cluster, origin.display() ) );
        return new PlannedCluster( cluster, ClusterAction.CREATE, name, origin.folderPath() );
    }

    private static Optional< NoteRef > findFolderNote( final String folderPath, final List< NoteRef > notes ) {
        final String wanted = ( folderPath + "/" + VaultPaths.basename( folderPath ) + ".md" )
            .toLowerCase( Locale.ROOT );
        return notes.stream().filter( n -> n.path().toLowerCase( Locale.ROOT ).equals( wanted ) ).findFirst();
    }
}
