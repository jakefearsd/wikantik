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
package com.wikantik.kgpolicy;

import com.wikantik.api.structure.ClusterDetails;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.StructuralIndexService;

import java.util.List;

/**
 * Seam that resolves a cluster name to the list of page slugs (wiki page names)
 * that belong to it.  Kept as a narrow functional interface so tests can inject
 * a simple lambda instead of a full {@link StructuralIndexService} mock.
 *
 * <p>The production factory {@link #fromStructural} delegates to
 * {@link StructuralIndexService#getCluster}, extracting page slugs from
 * {@link ClusterDetails#articles()}.  Returns an empty list when the cluster
 * is unknown — reconciliation is then a no-op for that cluster.</p>
 *
 * <p>Note: {@link ClusterDetails} exposes articles via {@code articles()}, not
 * {@code pages()} — the factory uses the correct accessor.</p>
 */
public interface PagesByCluster {

    /**
     * Returns the slugs of all pages belonging to {@code cluster}, or an empty
     * list if the cluster does not exist in the index.
     *
     * @param cluster cluster name (e.g. {@code "java"}, {@code "van-life"})
     * @return unmodifiable list of page slugs; never null
     */
    List< String > pageNamesIn( String cluster );

    /**
     * Creates a {@code PagesByCluster} that reads from a live
     * {@link StructuralIndexService}.
     *
     * @param svc the structural index
     * @return a {@code PagesByCluster} backed by the structural index
     */
    static PagesByCluster fromStructural( final StructuralIndexService svc ) {
        return cluster -> svc.getCluster( cluster )
                .map( details -> details.articles().stream()
                        .map( PageDescriptor::slug )
                        .toList() )
                .orElse( List.of() );
    }
}
