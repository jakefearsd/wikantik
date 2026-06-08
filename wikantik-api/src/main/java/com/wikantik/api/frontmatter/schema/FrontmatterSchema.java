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
package com.wikantik.api.frontmatter.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server-authoritative description of every authored frontmatter field: order, widget, and
 * constraints. One source of truth shared by the React form (rendering + advisory hints), the
 * {@code SchemaDrivenFrontmatterValidator} (enforcement), and the MCP write tools. Editing the
 * page-concept vocabulary means editing {@link #defaultSchema()} — there is no admin UI in v1.
 *
 * <p>See {@code docs/superpowers/specs/2026-06-08-structured-page-curation-design.md} §3–§4.2.</p>
 */
public final class FrontmatterSchema {

    /** Cluster slug: lowercase kebab segments, optional single {@code parent/sub} sub-cluster. */
    public static final String CLUSTER_SLUG_PATTERN =
            "^[a-z0-9]+(-[a-z0-9]+)*(/[a-z0-9]+(-[a-z0-9]+)*)?$";

    private final List< FieldSpec > fields;

    private FrontmatterSchema( final List< FieldSpec > fields ) {
        this.fields = List.copyOf( fields );
    }

    /** The ordered field specs ({@code canonical_id} first). */
    public List< FieldSpec > fields() {
        return fields;
    }

    /** The spec for a key, if the schema describes it. */
    public Optional< FieldSpec > field( final String key ) {
        return fields.stream().filter( f -> f.key().equals( key ) ).findFirst();
    }

    /** The default schema encoding the §3 field inventory. */
    public static FrontmatterSchema defaultSchema() {
        final List< FieldSpec > f = List.of(
                FieldSpec.readonly( "canonical_id", "Canonical ID" ),
                FieldSpec.text( "title", "Title" ),
                enumField( "type", "Type", true,
                        List.of( "article", "hub", "reference", "runbook", "design" ),
                        typeSuggestions() ),
                enumField( "status", "Status", true,
                        List.of( "draft", "active", "archived" ),
                        statusSuggestions() ),
                new FieldSpec( "summary", "Summary", Widget.TEXT, List.of(), false,
                        50, 160, null, Map.of() ),
                new FieldSpec( "tags", "Tags", Widget.TAGS, List.of(), false,
                        null, null, null, Map.of() ),
                new FieldSpec( "cluster", "Cluster", Widget.TEXT, List.of(), false,
                        null, null, CLUSTER_SLUG_PATTERN, Map.of() ),
                new FieldSpec( "related", "Related pages", Widget.PAGE_REFS, List.of(), false,
                        null, null, null, Map.of() ),
                new FieldSpec( "date", "Date", Widget.DATE, List.of(), false,
                        null, null, null, Map.of() ),
                FieldSpec.text( "author", "Author" ),
                new FieldSpec( "kg_include", "Include in Knowledge Graph", Widget.TRISTATE,
                        List.of(), false, null, null, null, Map.of() ),
                new FieldSpec( "verified_at", "Verified at", Widget.DATETIME, List.of(), false,
                        null, null, null, Map.of() ),
                FieldSpec.text( "verified_by", "Verified by" ),
                enumField( "audience", "Audience", false,
                        List.of( "humans", "agents", "both" ), Map.of() ),
                FieldSpec.readonly( "confidence", "Confidence" ),
                FieldSpec.readonly( "agent_hints", "Agent hints" ),
                new FieldSpec( "runbook", "Runbook", Widget.RUNBOOK_BLOCK, List.of(), false,
                        null, null, null, Map.of() ) );
        return new FrontmatterSchema( f );
    }

    private static FieldSpec enumField( final String key, final String label, final boolean open,
                                        final List< String > canonical,
                                        final Map< String, String > suggestions ) {
        return new FieldSpec( key, label, Widget.ENUM, canonical, open, null, null, null, suggestions );
    }

    private static Map< String, String > typeSuggestions() {
        final Map< String, String > m = new LinkedHashMap<>();
        m.put( "report", "article" );
        m.put( "intelligence", "article" );
        m.put( "explainer", "article" );
        m.put( "blueprint", "design" );
        m.put( "implementation", "design" );
        m.put( "concept", "reference" );
        return m;
    }

    private static Map< String, String > statusSuggestions() {
        final Map< String, String > m = new LinkedHashMap<>();
        m.put( "published", "active" );
        m.put( "official", "active" );
        m.put( "deployed", "active" );
        m.put( "production", "active" );
        m.put( "ongoing", "active" );
        m.put( "designed", "draft" );
        m.put( "proposed", "draft" );
        m.put( "in_progress", "draft" );
        return m;
    }
}
