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

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.PageType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Phase 4 enforcement filter for the structural spine. Runs in {@code preSave}
 * with one responsibility:
 *
 * <ol>
 *   <li><b>Auto-assign canonical_id.</b> Pages saved without a {@code canonical_id}
 *       in frontmatter get a fresh ULID injected at the top of the block. This
 *       keeps the structural spine intact even when content reaches the wiki
 *       through a save path that wasn't routed through the backfill CLI.</li>
 * </ol>
 *
 * <p>This behaviour is gated by {@link #PROP_ENFORCEMENT_ENABLED} (default
 * {@code true}). System pages (registry-determined) are exempt — they are
 * managed by the engine itself and don't flow through the structural spine.</p>
 *
 * <p>Priority should run after generic frontmatter defaulting (so the page
 * already carries a frontmatter block) but before chunking/indexing
 * (so canonical_id is present when downstream filters inspect it).</p>
 */
public class StructuralSpinePageFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( StructuralSpinePageFilter.class );

    /** Master flag; default {@code true}. Setting to {@code false} reverts to Phase 2 warn-only behaviour. */
    public static final String PROP_ENFORCEMENT_ENABLED = "wikantik.structural_spine.enforcement.enabled";

    /**
     *  Gates the duplicate-cluster-declaration ERROR (ClusterDeclarationDesign Phase 2).
     *
     *  <p>Defaults to <b>false</b> so the rule can ship dark and be switched on only once a
     *  corpus verifies clean — enabling it against a corpus that still has duplicates would
     *  make the offending hub pages un-saveable, trapping the very content that needs
     *  editing to fix them.</p>
     */
    public static final String PROP_DUPLICATE_DECLARATION_ENFORCED =
            "wikantik.cluster_declaration.enforcement.enabled";

    private final StructuralIndexService structuralIndex;
    private final Predicate< String > isSystemPage;
    private final boolean enabled;
    private final boolean duplicateDeclarationEnforced;

    public StructuralSpinePageFilter( final StructuralIndexService structuralIndex,
                                       final Predicate< String > isSystemPage,
                                       final Properties props ) {
        this.structuralIndex = structuralIndex;
        this.isSystemPage = isSystemPage == null ? name -> false : isSystemPage;
        this.enabled = Boolean.parseBoolean(
                props.getProperty( PROP_ENFORCEMENT_ENABLED, "true" ) );
        this.duplicateDeclarationEnforced = Boolean.parseBoolean(
                props.getProperty( PROP_DUPLICATE_DECLARATION_ENFORCED, "false" ) );
        LOG.info( "StructuralSpinePageFilter: enforcement {}",
                  enabled ? "enabled" : "disabled" );
    }

    @Override
    public String preSave( final Context context, final String content ) throws FilterException {
        if ( !enabled || structuralIndex == null ) {
            return content;
        }
        final String pageName = context != null && context.getPage() != null
                ? context.getPage().getName() : null;
        if ( pageName != null && isSystemPage.test( pageName ) ) {
            return content;
        }

        final ParsedPage parsed = FrontmatterParser.parse( content );
        // Mutable working copy — we may inject canonical_id and rewrite.
        final Map< String, Object > metadata = new LinkedHashMap<>( parsed.metadata() );
        boolean rewritten = false;

        // -- canonical_id auto-assign --
        final Object canonicalRaw = metadata.get( "canonical_id" );
        final String existingId = canonicalRaw == null ? null : canonicalRaw.toString().trim();
        if ( existingId == null || existingId.isEmpty() ) {
            // Reuse the canonical_id already bound to this slug rather than minting
            // a new one. Without this, an MCP write that omits canonical_id (or any
            // save path that strips frontmatter) would orphan the existing DB row
            // and trip the page_canonical_ids_current_slug_key unique constraint.
            final String reusedId = pageName == null ? null
                    : structuralIndex.resolveCanonicalIdFromSlug( pageName ).orElse( null );
            final String newId = reusedId != null ? reusedId : UlidCreator.getUlid().toString();
            // Insert canonical_id as the first key for visual stability.
            final Map< String, Object > reordered = new LinkedHashMap<>( metadata.size() + 1 );
            reordered.put( "canonical_id", newId );
            reordered.putAll( metadata );
            metadata.clear();
            metadata.putAll( reordered );
            rewritten = true;
            if ( reusedId != null ) {
                LOG.info( "StructuralSpinePageFilter: reused canonical_id={} for '{}' (slug already bound)",
                          newId, pageName );
            } else {
                LOG.info( "StructuralSpinePageFilter: assigned canonical_id={} to '{}'",
                          newId, pageName );
            }
        }

        // -- duplicate cluster declaration (Phase 2) --
        if ( duplicateDeclarationEnforced ) {
            rejectDuplicateDeclaration( pageName, metadata );
        }

        // -- kg_include validation --
        final Object kgInclude = metadata.get( "kg_include" );
        if ( kgInclude != null ) {
            final String s = kgInclude.toString().trim().toLowerCase( java.util.Locale.ROOT );
            if ( !"true".equals( s ) && !"false".equals( s ) ) {
                throw new FilterException(
                        "Page '" + pageName + "' has invalid kg_include='"
                        + kgInclude + "' (must be true or false)" );
            }
        }

        return rewritten ? FrontmatterWriter.write( metadata, parsed.body() ) : content;
    }

    /**
     *  Refuses a save that would leave two hub pages declaring one cluster.
     *
     *  <p>This is the single blocking rule of ClusterDeclarationDesign, and the only thing
     *  that makes "exactly one hub per cluster" true by construction rather than by luck of
     *  filesystem enumeration order.</p>
     *
     *  <p>Crucially it lets the <b>existing</b> declarant re-save itself. Matching on the
     *  cluster alone would make every hub un-editable the moment the gate was flipped; a page
     *  is treated as the incumbent if either its canonical_id or its slug matches, so an
     *  in-flight rename does not lock the author out either.</p>
     */
    private void rejectDuplicateDeclaration( final String pageName, final Map< String, Object > metadata )
            throws FrontmatterValidationException {
        if ( PageType.fromFrontmatter( metadata.get( "type" ) ) != PageType.HUB ) {
            return;
        }
        final String cluster = str( metadata.get( "cluster" ) );
        if ( cluster == null ) {
            return;
        }
        final PageDescriptor incumbent = structuralIndex.getCluster( cluster )
                                                        .map( ClusterDetails::hubPage )
                                                        .orElse( null );
        if ( incumbent == null ) {
            return;
        }
        final String myId = str( metadata.get( "canonical_id" ) );
        final boolean samePage = ( myId != null && myId.equals( incumbent.canonicalId() ) )
                || ( pageName != null && pageName.equals( incumbent.slug() ) );
        if ( samePage ) {
            return;
        }
        throw new FrontmatterValidationException( List.of( FieldViolation.of(
                "cluster", Severity.ERROR, "cluster.duplicate_declaration",
                "cluster '" + cluster + "' is already declared by hub '" + incumbent.slug()
                        + "'. Exactly one hub may declare a cluster - either edit that page, or"
                        + " give this hub a sub-cluster of its own, e.g. '" + cluster + "/<name>'." ) ) );
    }

    private static String str( final Object raw ) {
        if ( raw == null ) {
            return null;
        }
        final String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
