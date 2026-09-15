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
package com.wikantik.extractcli.configref;

import com.wikantik.util.config.ConfigReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the per-source-file section models (name/anchor, hoisted preamble, table rows, and the
 * definition list of full descriptions) consumed by the Mustache templates. Split out of
 * {@link GenerateConfigReferenceCli} (2026-09, complexity burn-down) — was a single ~80-line
 * {@code buildSections} method combining section grouping, row building, hoist lookup,
 * description truncation and secrets collection in one nested-loop body.
 */
final class SectionBuilder {

    private SectionBuilder() {}

    /**
     * Builds this file's section models, in file order. Each section carries: its (possibly
     * disambiguated) display name and anchor slug, any hoisted section-preamble paragraphs (see
     * {@link HoistScanner}), its table rows, and a definition list of full descriptions for the
     * rows whose Description cell got truncated (see {@link DescriptionRenderer}). As a side
     * effect, every {@code secret}-typed entry is appended to {@code secretsOut}.
     */
    static List<Map<String, Object>> buildSections( final GenerateConfigReferenceCli.SourceFile sf,
                                                      final Set<String> usedSectionNames,
                                                      final List<Map<String, Object>> secretsOut ) {
        final Map<Integer, HoistScanner.Hoist> hoists = HoistScanner.scanHoists( sf.rawLines() );
        final LinkedHashMap<String, List<ConfigReference.Entry>> grouped = groupBySection( sf.parsed().entries() );

        final List<Map<String, Object>> sections = new ArrayList<>( grouped.size() );
        for ( final Map.Entry<String, List<ConfigReference.Entry>> g : grouped.entrySet() ) {
            sections.add( buildSection( sf, g.getKey(), g.getValue(), hoists, usedSectionNames, secretsOut ) );
        }
        return sections;
    }

    static List<Map<String, Object>> tocFor( final List<Map<String, Object>> sections ) {
        final List<Map<String, Object>> toc = new ArrayList<>( sections.size() );
        for ( final Map<String, Object> s : sections ) {
            final Map<String, Object> t = new LinkedHashMap<>();
            t.put( "name", s.get( "name" ) );
            t.put( "anchor", s.get( "anchor" ) );
            toc.add( t );
        }
        return toc;
    }

    /** Groups entries by section name, in file order (first appearance of each name wins the
     *  group's position); unnamed/blank sections fall into "Uncategorized". */
    private static LinkedHashMap<String, List<ConfigReference.Entry>> groupBySection( final List<ConfigReference.Entry> entries ) {
        final List<ConfigReference.Entry> sorted = new ArrayList<>( entries );
        sorted.sort( Comparator.comparingInt( ConfigReference.Entry::line ) );

        final LinkedHashMap<String, List<ConfigReference.Entry>> grouped = new LinkedHashMap<>();
        for ( final ConfigReference.Entry e : sorted ) {
            final String name = ( e.section() == null || e.section().isBlank() ) ? "Uncategorized" : e.section();
            grouped.computeIfAbsent( name, k -> new ArrayList<>() ).add( e );
        }
        return grouped;
    }

    /** A section's display name + anchor slug, bundled so row-building only needs one parameter
     *  for "where this row lives" instead of two (keeps {@link #addRow} under the parameter-list
     *  limit). */
    private record SectionRef( String name, String anchor ) {}

    /** The four output lists a row can append to, bundled for the same reason as
     *  {@link SectionRef}. */
    private record RowOutputs( List<Map<String, Object>> preamble, List<Map<String, Object>> rows,
                                List<Map<String, Object>> fullDescriptions, List<Map<String, Object>> secrets ) {}

    private static Map<String, Object> buildSection( final GenerateConfigReferenceCli.SourceFile sf,
                                                       final String originalName,
                                                       final List<ConfigReference.Entry> entries,
                                                       final Map<Integer, HoistScanner.Hoist> hoists,
                                                       final Set<String> usedSectionNames,
                                                       final List<Map<String, Object>> secretsOut ) {
        final String displayName = usedSectionNames.contains( originalName )
                ? originalName + " (" + sf.label() + ")"
                : originalName;
        usedSectionNames.add( originalName );
        final SectionRef section = new SectionRef( displayName, AnchorSlugger.slug( displayName ) );

        final RowOutputs outputs = new RowOutputs( new ArrayList<>(), new ArrayList<>( entries.size() ),
                new ArrayList<>(), secretsOut );

        for ( final ConfigReference.Entry e : entries ) {
            addRow( sf, e, hoists.get( e.line() ), section, outputs );
        }

        final Map<String, Object> sect = new LinkedHashMap<>();
        sect.put( "name", section.name() );
        sect.put( "anchor", section.anchor() );
        sect.put( "preamble", outputs.preamble() );
        sect.put( "entries", outputs.rows() );
        sect.put( "fullDescriptions", outputs.fullDescriptions() );
        sect.put( "hasFullDescriptions", !outputs.fullDescriptions().isEmpty() );
        return sect;
    }

    /** Builds one table row (and, as side effects, any hoisted preamble paragraphs, the
     *  full-description entry when the cell was truncated, and the secrets-index entry when the
     *  key is {@code secret}-typed) and appends each to its output list. */
    private static void addRow( final GenerateConfigReferenceCli.SourceFile sf, final ConfigReference.Entry e,
                                 final HoistScanner.Hoist hoist, final SectionRef section, final RowOutputs outputs ) {
        final List<String> ownDescription = hoist != null ? hoist.ownDescription() : e.description();
        if ( hoist != null ) {
            for ( final String paragraph : hoist.preambleParagraphs() ) {
                final Map<String, Object> p = new LinkedHashMap<>();
                p.put( "text", paragraph );
                outputs.preamble().add( p );
            }
        }

        final DescriptionRenderer.DescriptionRender dr = DescriptionRenderer.renderDescription( ownDescription );

        final Map<String, Object> row = new LinkedHashMap<>();
        row.put( "key", e.key() );
        row.put( "typeCell", RowCellRenderer.typeCell( e ) );
        row.put( "defaultCell", RowCellRenderer.defaultCell( e ) );
        row.put( "overrideCell", RowCellRenderer.overrideCell( e, sf.fixedOverrideText() ) );
        row.put( "descriptionCell", dr.summary().replace( "|", "\\|" ) );
        outputs.rows().add( row );

        if ( dr.truncated() ) {
            final Map<String, Object> fd = new LinkedHashMap<>();
            fd.put( "key", e.key() );
            fd.put( "full", dr.full() );
            outputs.fullDescriptions().add( fd );
        }

        if ( "secret".equals( e.type() ) ) {
            final Map<String, Object> sr = new LinkedHashMap<>();
            sr.put( "key", e.key() );
            sr.put( "fileLabel", sf.label() );
            sr.put( "sectionName", section.name() );
            sr.put( "sectionAnchor", section.anchor() );
            outputs.secrets().add( sr );
        }
    }
}
