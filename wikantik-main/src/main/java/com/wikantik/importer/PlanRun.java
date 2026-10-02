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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;
import com.wikantik.importer.VaultClusterPlanner.NoteRef;

/** The state of one planning pass; see {@link VaultImportPlanner}. */
final class PlanRun {

    private final FrontmatterSchema schema;
    private final AttachmentGate gate;
    private final VaultArchive archive;
    private final ImportOptions options;
    private final WikiSnapshot snapshot;

    private final List< String > notePaths;
    private final Map< String, VaultNote > notes = new LinkedHashMap<>();
    private Map< String, String > names;
    private final Map< String, PageStatus > statuses = new LinkedHashMap<>();
    private final Map< String, String > reasons = new LinkedHashMap<>();
    private final Set< String > collided = new TreeSet<>();
    private final Map< String, MappedNote > mapped = new LinkedHashMap<>();
    private final Map< String, Integer > validationWarnings = new LinkedHashMap<>();
    private final Map< String, List< String > > warnings = new LinkedHashMap<>();
    private final Map< String, String > bodies = new LinkedHashMap<>();
    private VaultClusterPlanner.Result clusters;
    private PlanTargets targets;

    PlanRun( final FrontmatterSchema schema, final AttachmentGate gate, final VaultArchive archive,
             final ImportOptions options, final WikiSnapshot snapshot ) {
        this.schema = schema;
        this.gate = gate;
        this.archive = archive;
        this.options = options;
        this.snapshot = snapshot;
        archive.notes().forEach( n -> notes.put( n.path(), n ) );
        this.notePaths = notes.keySet().stream().sorted( VaultPaths.ORDER ).toList();
    }

    PlanResult execute( final String zipSha256, final String vaultName ) {
        classify();
        do {
            planClusters();
            mapAndValidate();
        } while ( failedHubNote() );
        rewrite();
        final List< PlannedAttachment > attachments = attachments();
        return assemble( attachments, zipSha256, vaultName );
    }

    private PageStatus status( final String path ) {
        return statuses.get( path );
    }

    private void classify() {
        names = new VaultNoteNamer( snapshot ).assign( notePaths );
        for ( final String path : notePaths ) {
            final VaultNote note = notes.get( path );
            final String name = names.get( path );
            if ( note.oversized() ) {
                set( path, PageStatus.WILL_FAIL, "note exceeds " + ImportLimits.PROP_MAX_PAGE_BYTES );
            } else if ( snapshot.isSystemPage( name ) ) {
                set( path, PageStatus.SKIPPED_RESERVED, "system page name" );
            } else if ( snapshot.existingPage( name ).isPresent() ) {
                collided.add( snapshot.existingPage( name ).get() );
                set( path, PageStatus.SKIPPED_EXISTS, "page exists" );
            } else {
                set( path, PageStatus.NEW, null );
            }
        }
    }

    private void set( final String path, final PageStatus status, final String reason ) {
        statuses.put( path, status );
        if ( reason != null ) {
            reasons.put( path, reason );
        }
    }

    /**
     * True when a folder note chosen as a cluster hub failed validation. It is no longer NEW, so planning the clusters
     * again picks another folder note or a generated hub. Statuses only move NEW to WILL_FAIL, so this terminates.
     */
    private boolean failedHubNote() {
        return clusters.hubNotePaths().stream().anyMatch( p -> status( p ) != PageStatus.NEW );
    }

    private void planClusters() {
        final VaultNoteNamer namer = new VaultNoteNamer( snapshot );
        namer.assign( notePaths );
        final List< NoteRef > refs = notePaths.stream()
            .map( p -> new NoteRef( p, names.get( p ), status( p ) == PageStatus.NEW ) ).toList();
        clusters = new VaultClusterPlanner().plan( refs, options, snapshot, namer );
    }

    private void mapAndValidate() {
        final VaultFrontmatterMapper mapper = new VaultFrontmatterMapper( schema );
        final SchemaDrivenFrontmatterValidator validator = new SchemaDrivenFrontmatterValidator( schema );
        final Set< String > created = new HashSet<>();
        clusters.clusters().stream().filter( c -> c.action() == ClusterAction.CREATE ).forEach( c -> created.add( c.cluster() ) );
        final ValidationCtx vctx = new ValidationCtx( p -> true, a -> true, Severity.WARNING,
            c -> snapshot.isClusterDeclared( c ) || created.contains( c ) );
        for ( final String path : notePaths ) {
            if ( status( path ) != PageStatus.NEW ) {
                continue;
            }
            final NoteContext nctx = new NoteContext( names.get( path ), VaultPaths.withoutMd( VaultPaths.basename( path ) ),
                clusters.clusterByPath().get( path ), clusters.hubNotePaths().contains( path ) );
            final MappedNote m = mapper.map( notes.get( path ).text(), nctx );
            mapped.put( path, m );
            warnings.put( path, new ArrayList<>( m.warnings() ) );
            validate( path, validator.validate( m.metadata(), vctx ) );
        }
    }

    private void validate( final String path, final List< FieldViolation > violations ) {
        final List< String > errors = violations.stream().filter( v -> v.severity() == Severity.ERROR )
            .map( FieldViolation::message ).toList();
        if ( errors.isEmpty() ) {
            validationWarnings.put( path, violations.size() );
        } else {
            set( path, PageStatus.WILL_FAIL, "frontmatter: " + String.join( "; ", errors ) );
        }
    }

    private void rewrite() {
        final Map< String, VaultFile > filesByPath = new LinkedHashMap<>();
        archive.files().forEach( f -> filesByPath.put( f.path(), f ) );
        final VaultLinkIndex index = new VaultLinkIndex( notePaths, filesByPath.keySet() );
        targets = new PlanTargets( names, statuses, snapshot, index, gate, filesByPath );
        final VaultBodyRewriter rewriter = new VaultBodyRewriter();
        for ( final String path : notePaths ) {
            if ( status( path ) == PageStatus.NEW ) {
                targets.currentPage( names.get( path ) );
                final RewriteResult r = rewriter.rewrite( mapped.get( path ).body(), path, targets );
                bodies.put( path, r.body() );
                warnings.get( path ).addAll( r.warnings() );
            }
        }
    }

    private List< PlannedAttachment > attachments() {
        final List< PlannedAttachment > out = new ArrayList<>();
        for ( final VaultFile f : archive.files() ) {
            final String owner = targets.owner( f.path() );
            if ( owner != null ) {
                out.add( new PlannedAttachment( f.path(), f.entryName(), owner, targets.fileName( f.path() ), f.size(),
                    AttachmentStatus.IMPORT, null ) );
            } else if ( targets.blocked().containsKey( f.path() ) ) {
                out.add( new PlannedAttachment( f.path(), f.entryName(), null, null, f.size(), AttachmentStatus.BLOCKED,
                    targets.blocked().get( f.path() ) ) );
            } else {
                out.add( new PlannedAttachment( f.path(), f.entryName(), null, null, f.size(),
                    AttachmentStatus.SKIPPED_UNREFERENCED, null ) );
            }
        }
        return out;
    }

    private List< PlannedPage > pages() {
        final List< PlannedPage > pages = new ArrayList<>();
        for ( final String path : notePaths ) {
            pages.add( new PlannedPage( path, names.get( path ), status( path ), reasons.get( path ),
                clusters.hubNotePaths().contains( path ), clusters.clusterByPath().get( path ),
                List.copyOf( warnings.getOrDefault( path, List.of() ) ), validationWarnings.getOrDefault( path, 0 ) ) );
        }
        for ( final VaultClusterPlanner.GeneratedHub h : clusters.generated() ) {
            pages.add( new PlannedPage( null, h.name(), PageStatus.NEW, null, true, h.cluster(), List.of(), 0 ) );
        }
        return pages;
    }

    private List< PageDraft > drafts() {
        final List< PageDraft > hubs = new ArrayList<>();
        final List< PageDraft > others = new ArrayList<>();
        clusters.generated().forEach( h -> hubs.add( GeneratedHubDrafts.draft( h ) ) );
        for ( final String path : notePaths ) {
            if ( status( path ) != PageStatus.NEW ) {
                continue;
            }
            final boolean hub = clusters.hubNotePaths().contains( path );
            final PageDraft d = new PageDraft( names.get( path ), path, mapped.get( path ).metadata(), bodies.get( path ), hub );
            ( hub ? hubs : others ).add( d );
        }
        hubs.sort( Comparator.comparing( d -> String.valueOf( d.metadata().get( "cluster" ) ) ) );
        hubs.addAll( others );
        return hubs;
    }

    private PlanResult assemble( final List< PlannedAttachment > attachments, final String zipSha256, final String vaultName ) {
        final List< PlannedPage > pages = pages();
        final List< PageDraft > drafts = drafts();
        final PlanTotals totals = totals( pages, attachments, drafts );
        final ImportPlan plan = new ImportPlan( PlanHasher.hash( zipSha256, options, collided, clusters.clusters() ), vaultName, options.mode(),
            totals, pages, List.copyOf( attachments ), clusters.clusters(), warningGroups( pages ) );
        final List< PlannedAttachment > toImport = attachments.stream().filter( a -> a.status() == AttachmentStatus.IMPORT ).toList();
        return new PlanResult( plan, List.copyOf( drafts ), toImport );
    }

    private static Map< String, Integer > warningGroups( final List< PlannedPage > pages ) {
        final Map< String, Integer > groups = new TreeMap<>();
        for ( final PlannedPage p : pages ) {
            for ( final String w : p.warnings() ) {
                final int colon = w.indexOf( ':' );
                groups.merge( colon < 0 ? w : w.substring( 0, colon ), 1, Integer::sum );
            }
        }
        return groups;
    }

    private PlanTotals totals( final List< PlannedPage > pages, final List< PlannedAttachment > attachments,
                               final List< PageDraft > drafts ) {
        return new PlanTotals( countPages( pages, PageStatus.NEW ), countPages( pages, PageStatus.SKIPPED_EXISTS ),
            countPages( pages, PageStatus.SKIPPED_RESERVED ), countPages( pages, PageStatus.WILL_FAIL ),
            countAttachments( attachments, AttachmentStatus.IMPORT ), countAttachments( attachments, AttachmentStatus.BLOCKED ),
            countAttachments( attachments, AttachmentStatus.SKIPPED_UNREFERENCED ),
            countClusters( ClusterAction.CREATE ), countClusters( ClusterAction.JOIN ),
            ( int ) drafts.stream().filter( PageDraft::hub ).count() );
    }

    private static int countPages( final List< PlannedPage > pages, final PageStatus s ) {
        return ( int ) pages.stream().filter( p -> p.status() == s ).count();
    }

    private static int countAttachments( final List< PlannedAttachment > list, final AttachmentStatus s ) {
        return ( int ) list.stream().filter( a -> a.status() == s ).count();
    }

    private int countClusters( final ClusterAction a ) {
        return ( int ) clusters.clusters().stream().filter( c -> c.action() == a ).count();
    }
}
