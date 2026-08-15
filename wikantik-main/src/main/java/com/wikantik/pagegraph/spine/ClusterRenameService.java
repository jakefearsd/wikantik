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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 *  Renames a cluster across every page that names it, carrying its sub-clusters along.
 *
 *  <p>ClusterDeclarationDesign Phase 4. Membership is a path held in each member's
 *  frontmatter, so reorganizing the taxonomy is O(member pages) by construction — this turns
 *  that into one operation. Without it, the path form's reorganization cost <i>is</i> the
 *  curation overhead the design exists to minimize.</p>
 *
 *  <p>Renaming does <b>not</b> touch page identity: no {@code canonical_id} changes, no
 *  {@code page_slug_history} row, no directory move, no URL change, no wikilink rewriting. It
 *  is a frontmatter edit across N pages, each independently versioned and revertable — which
 *  is exactly the property a directory-structured store would not have.</p>
 */
public final class ClusterRenameService {

    private static final Logger LOG = LogManager.getLogger( ClusterRenameService.class );

    private final PageManager pageManager;
    private final StructuralIndexService structuralIndex;
    private final PageSaveHelper saveHelper;

    public ClusterRenameService( final PageManager pageManager,
                                  final StructuralIndexService structuralIndex,
                                  final PageSaveHelper saveHelper ) {
        this.pageManager = pageManager;
        this.structuralIndex = structuralIndex;
        this.saveHelper = saveHelper;
    }

    /**
     *  Computes what a rename would change, without writing anything.
     *
     *  @param from the cluster path to rename
     *  @param to   its new path
     *  @return the plan, including any conflicts that must be resolved first
     *  @throws IllegalArgumentException when either path is blank, or they are equal
     */
    public ClusterRenamePlan plan( final String from, final String to ) {
        if ( from == null || from.isBlank() || to == null || to.isBlank() ) {
            throw new IllegalArgumentException( "both 'from' and 'to' cluster paths are required" );
        }
        if ( from.equals( to ) ) {
            throw new IllegalArgumentException( "'from' and 'to' are the same cluster: " + from );
        }

        // listPagesByFilter matches the cluster and everything beneath it (segment-aware),
        // so the subtree arrives in one query rather than being walked here.
        final List< PageDescriptor > members = structuralIndex.listPagesByFilter(
                new StructuralFilter( Optional.empty(), Optional.of( from ), null, null, 1000, null ) );

        final List< ClusterRenamePlan.PageChange > changes = new ArrayList<>();
        final Set< String > movingSlugs = new LinkedHashSet<>();
        final Set< String > targetPaths = new LinkedHashSet<>();
        for ( final PageDescriptor p : members ) {
            final String next = ClusterPath.reparent( p.cluster(), from, to );
            if ( next == null || next.equals( p.cluster() ) ) {
                continue;
            }
            changes.add( new ClusterRenamePlan.PageChange( p.slug(), p.cluster(), next ) );
            movingSlugs.add( p.slug() );
            targetPaths.add( next );
        }

        return new ClusterRenamePlan( from, to, changes, findConflicts( targetPaths, movingSlugs ) );
    }

    /**
     *  Every reason the rename must not proceed.
     *
     *  <p>The one that matters: a target path already declared by a hub that is <i>not</i>
     *  moving would leave two hubs declaring one cluster — the single defect this design
     *  forbids. Catching it here rather than at the first failing save matters, because a
     *  rename that dies half-way leaves the corpus split across two cluster names.</p>
     */
    private List< String > findConflicts( final Set< String > targetPaths, final Set< String > movingSlugs ) {
        final List< String > conflicts = new ArrayList<>();
        for ( final String target : targetPaths ) {
            final PageDescriptor incumbent = structuralIndex.getCluster( target )
                                                            .map( ClusterDetails::hubPage )
                                                            .orElse( null );
            if ( incumbent != null && !movingSlugs.contains( incumbent.slug() ) ) {
                conflicts.add( "cluster '" + target + "' is already declared by hub '"
                                       + incumbent.slug() + "'; merge or retire that hub first" );
            }
        }
        return conflicts;
    }

    /**
     *  Plans and applies a rename.
     *
     *  @param from  the cluster path to rename
     *  @param to    its new path
     *  @param actor who to record as the author of the resulting page revisions
     *  @return which pages moved and which did not
     *  @throws IllegalStateException when the plan has conflicts — nothing is written
     */
    public ClusterRenameResult apply( final String from, final String to, final String actor ) {
        final ClusterRenamePlan plan = plan( from, to );
        if ( plan.hasConflicts() ) {
            throw new IllegalStateException( "refusing to rename '" + from + "' to '" + to
                                                     + "': " + String.join( "; ", plan.conflicts() ) );
        }

        final List< String > renamed = new ArrayList<>();
        final Map< String, String > failures = new LinkedHashMap<>();
        for ( final ClusterRenamePlan.PageChange change : plan.changes() ) {
            try {
                rewriteCluster( change, actor );
                renamed.add( change.slug() );
            } catch ( final Exception e ) {
                // Never abort the sweep: a page that cannot be written leaves the rest
                // renameable, and the caller gets a precise list to retry.
                LOG.warn( "rename_cluster: failed to rewrite '{}' ({} -> {}): {}",
                          change.slug(), change.fromCluster(), change.toCluster(), e.getMessage() );
                failures.put( change.slug(), String.valueOf( e.getMessage() ) );
            }
        }
        LOG.info( "rename_cluster: '{}' -> '{}': {} page(s) rewritten, {} failed",
                  from, to, renamed.size(), failures.size() );
        return new ClusterRenameResult( from, to, renamed, failures );
    }

    private void rewriteCluster( final ClusterRenamePlan.PageChange change, final String actor )
            throws Exception {
        final Page page = pageManager.getPage( change.slug() );
        if ( page == null ) {
            throw new IllegalStateException( "page not found" );
        }
        final ParsedPage parsed = FrontmatterParser.parse( pageManager.getPureText( page ) );
        final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
        metadata.put( "cluster", change.toCluster() );

        saveHelper.saveText( change.slug(),
                             FrontmatterWriter.write( metadata, parsed.body() ),
                             SaveOptions.builder()
                                        .author( actor )
                                        .changeNote( "cluster " + change.fromCluster()
                                                             + " -> " + change.toCluster() )
                                        .build() );
    }
}
