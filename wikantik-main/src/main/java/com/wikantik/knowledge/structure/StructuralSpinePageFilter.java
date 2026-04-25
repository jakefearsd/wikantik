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
package com.wikantik.knowledge.structure;

import com.github.f4b6a3.ulid.UlidCreator;
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.structure.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Phase 4 enforcement filter for the structural spine. Runs in {@code preSave}
 * with two responsibilities:
 *
 * <ol>
 *   <li><b>Auto-assign canonical_id.</b> Pages saved without a {@code canonical_id}
 *       in frontmatter get a fresh ULID injected at the top of the block. This
 *       keeps the structural spine intact even when content reaches the wiki
 *       through a save path that wasn't routed through the backfill CLI.</li>
 *   <li><b>Validate relations.</b> If the page declares a {@code relations:}
 *       field, every entry must have a known relation type and a target that
 *       resolves in the structural index. Invalid entries cause the save to
 *       abort via {@link FilterException}.</li>
 * </ol>
 *
 * <p>Both behaviours are gated by {@link #PROP_ENFORCEMENT_ENABLED} (default
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

    private final StructuralIndexService structuralIndex;
    private final Predicate< String > isSystemPage;
    private final boolean enabled;

    public StructuralSpinePageFilter( final StructuralIndexService structuralIndex,
                                       final Predicate< String > isSystemPage,
                                       final Properties props ) {
        this.structuralIndex = structuralIndex;
        this.isSystemPage = isSystemPage == null ? name -> false : isSystemPage;
        this.enabled = Boolean.parseBoolean(
                props.getProperty( PROP_ENFORCEMENT_ENABLED, "true" ) );
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
            final String newId = UlidCreator.getUlid().toString();
            // Insert canonical_id as the first key for visual stability.
            final Map< String, Object > reordered = new LinkedHashMap<>( metadata.size() + 1 );
            reordered.put( "canonical_id", newId );
            reordered.putAll( metadata );
            metadata.clear();
            metadata.putAll( reordered );
            rewritten = true;
            LOG.info( "StructuralSpinePageFilter: assigned canonical_id={} to '{}'",
                      newId, pageName );
        }

        // -- relations validation --
        final Object relationsField = metadata.get( "relations" );
        if ( relationsField != null ) {
            final String sourceId = metadata.get( "canonical_id" ).toString().trim();
            final var validation = FrontmatterRelationValidator.validate(
                    sourceId, relationsField,
                    target -> structuralIndex.getByCanonicalId( target ).isPresent() );
            if ( validation.hasIssues() ) {
                final StringBuilder msg = new StringBuilder();
                msg.append( "Page '" ).append( pageName ).append( "' has invalid relations: " );
                for ( final var issue : validation.issues() ) {
                    msg.append( '[' ).append( issue.kind() ).append( ' ' )
                       .append( issue.detail() ).append( "] " );
                }
                throw new FilterException( msg.toString().trim() );
            }
        }

        return rewritten ? FrontmatterWriter.write( metadata, parsed.body() ) : content;
    }
}
