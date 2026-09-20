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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.wikantik.api.knowledge.KgEdge;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.ontology.projection.PageRecord;

/**
 * Pure ACL split: selects the resources that may appear in the PUBLIC ontology dataset.
 * A page is public iff isPublic(slug); a node iff it records a source page that is itself
 * public, or — carrying no source page — it was authored by a human rather than extracted
 * by a machine; an edge iff both endpoints are public; a page-record iff its slug is
 * public. No auth here — the caller supplies the anonymous-view predicate.
 */
public final class PublicProjectionFilter {

    private PublicProjectionFilter() {}

    /**
     * The single authority for the node-level ACL rule. A node that records a source page is
     * public iff that page is anonymously viewable. A node with NO source page is public only
     * when it was authored by a human: a curator creating an entity by hand is an explicit act
     * of publication with no page body behind it, whereas a machine-derived entity without
     * provenance was extracted from some page we can no longer identify — possibly a restricted
     * one — so it FAILS CLOSED. Unknown/absent provenance is treated as machine-derived.
     *
     * <p>This is what kept LLM-extracted entities from ACL-restricted pages out of the
     * anonymous ontology; the companion half of the fix records provenance at materialisation
     * so those nodes are tested against their real source page instead.</p>
     *
     * <p>Both the full-rebuild path ({@link #publicNodes}) and the incremental path
     * ({@code OntologyEntitySync}) must route through this predicate.</p>
     */
    public static boolean isNodePublic( final KgNode node, final Predicate< String > isPublic ) {
        if ( node.sourcePage() != null ) {
            return isPublic.test( node.sourcePage() );
        }
        return !isMachineDerived( node.provenance() );
    }

    /** True for machine-produced provenance, and for unknown/absent provenance (fail closed). */
    private static boolean isMachineDerived( final Provenance provenance ) {
        return provenance == null
            || provenance == Provenance.AI_INFERRED
            || provenance == Provenance.AI_REVIEWED;
    }

    public static List< KgNode > publicNodes( final List< KgNode > nodes, final Predicate< String > isPublic ) {
        return nodes.stream()
                .filter( n -> isNodePublic( n, isPublic ) )
                .collect( Collectors.toList() );
    }

    public static Set< UUID > publicNodeIds( final List< KgNode > nodes, final Predicate< String > isPublic ) {
        return publicNodes( nodes, isPublic ).stream().map( KgNode::id ).collect( Collectors.toSet() );
    }

    public static List< KgEdge > publicEdges( final List< KgEdge > edges, final Set< UUID > publicNodeIds ) {
        return edges.stream()
                .filter( e -> publicNodeIds.contains( e.sourceId() ) && publicNodeIds.contains( e.targetId() ) )
                .collect( Collectors.toList() );
    }

    public static List< PageRecord > publicPages( final List< PageRecord > pages, final Predicate< String > isPublic ) {
        return pages.stream()
                .filter( p -> p.slug() != null && isPublic.test( p.slug() ) )
                .collect( Collectors.toList() );
    }
}
