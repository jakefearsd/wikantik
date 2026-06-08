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
package com.wikantik.ontology;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the free-text kg_nodes.node_type onto a wk: class local name.
 * Unrecognized or null/blank types default to "Concept" (matches the chunk
 * extractor's default and covers untyped / non-standard nodes such as the
 * NULL-typed nodes and "intelligence-summary").
 */
public final class NodeTypeMapping {

    private NodeTypeMapping() {}

    public static final String DEFAULT_CLASS = "Concept";

    private static final Map< String, String > MAP = Map.ofEntries(
            Map.entry( "person", "Person" ),
            Map.entry( "organization", "Organization" ),
            Map.entry( "place", "Place" ),
            Map.entry( "event", "Event" ),
            Map.entry( "product", "Product" ),
            Map.entry( "technology", "Technology" ),
            Map.entry( "concept", "Concept" ),
            Map.entry( "project", "Project" ),
            Map.entry( "version", "Version" ),
            Map.entry( "article", "Article" ),
            Map.entry( "hub", "Hub" ),
            Map.entry( "reference", "Reference" ),
            Map.entry( "runbook", "Runbook" ),
            Map.entry( "design", "DesignDoc" ),
            Map.entry( "design_doc", "DesignDoc" ) );

    /** Returns the wk: class local name for a node_type, defaulting to {@link #DEFAULT_CLASS}. */
    public static String classLocalName( final String nodeType ) {
        if ( nodeType == null || nodeType.isBlank() ) {
            return DEFAULT_CLASS;
        }
        final String key = nodeType.trim().toLowerCase( Locale.ROOT );
        return MAP.getOrDefault( key, DEFAULT_CLASS );
    }

    /** Default schema.org type for pages with no more-specific mapping. */
    public static final String SCHEMA_DEFAULT = "Article";

    private static final Map< String, String > SCHEMA_MAP = Map.of(
            "hub", "CollectionPage",
            "article", "Article",
            "runbook", "HowTo",
            "design", "TechArticle",
            "design_doc", "TechArticle" );

    /**
     * Maps a page {@code type} onto its schema.org type local name, mirroring the
     * {@code wk:<Class> rdfs:subClassOf schema:<Type>} axioms in {@code wikantik.ttl}.
     * Upgrade-only: anything without a more-specific mapping defaults to {@link #SCHEMA_DEFAULT}
     * ({@code Article}) — never the broader {@code CreativeWork}, so the SEO {@code @type} is
     * never downgraded. Used by the SEO head renderer so the page's schema.org {@code @type}
     * and its ontology classification share one source.
     */
    public static String schemaOrgType( final String pageType ) {
        if ( pageType == null || pageType.isBlank() ) {
            return SCHEMA_DEFAULT;
        }
        return SCHEMA_MAP.getOrDefault( pageType.trim().toLowerCase( Locale.ROOT ), SCHEMA_DEFAULT );
    }
}
