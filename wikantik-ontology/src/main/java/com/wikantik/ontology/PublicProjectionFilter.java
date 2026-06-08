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
import com.wikantik.ontology.projection.PageRecord;

/**
 * Pure ACL split: selects the resources that may appear in the PUBLIC ontology dataset.
 * A page is public iff isPublic(slug); a node iff it is a stub (no source page) or its
 * source page is public; an edge iff both endpoints are public; a page-record iff its
 * slug is public. No auth here — the caller supplies the anonymous-view predicate.
 */
public final class PublicProjectionFilter {

    private PublicProjectionFilter() {}

    public static List< KgNode > publicNodes( final List< KgNode > nodes, final Predicate< String > isPublic ) {
        return nodes.stream()
                .filter( n -> n.sourcePage() == null || isPublic.test( n.sourcePage() ) )
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
