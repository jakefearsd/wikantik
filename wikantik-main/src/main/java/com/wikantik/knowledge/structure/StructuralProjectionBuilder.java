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

import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable builder for a {@link StructuralProjection}. Populated during rebuild
 * by {@link DefaultStructuralIndexService}, then frozen into an immutable snapshot
 * via {@link #build()}.
 */
public final class StructuralProjectionBuilder {

    private final Map< String, PageDescriptor > byCanonicalId = new LinkedHashMap<>();
    private final Map< String, String >         slugToCanonicalId = new LinkedHashMap<>();
    private final Map< String, List< PageDescriptor > > byCluster = new LinkedHashMap<>();
    private final Map< String, PageDescriptor > hubByCluster = new HashMap<>();
    private final Map< String, List< PageDescriptor > > byTag = new LinkedHashMap<>();
    private final Map< PageType, List< PageDescriptor > > byType = new EnumMap<>( PageType.class );

    public StructuralProjectionBuilder addPage( final PageDescriptor page ) {
        byCanonicalId.put( page.canonicalId(), page );
        slugToCanonicalId.put( page.slug(), page.canonicalId() );

        if ( page.cluster() != null ) {
            byCluster.computeIfAbsent( page.cluster(), k -> new ArrayList<>() ).add( page );
            if ( page.type() == PageType.HUB ) {
                hubByCluster.put( page.cluster(), page );
            }
        }

        for ( final String tag : page.tags() ) {
            byTag.computeIfAbsent( tag, k -> new ArrayList<>() ).add( page );
        }

        byType.computeIfAbsent( page.type(), k -> new ArrayList<>() ).add( page );
        return this;
    }

    public StructuralProjection build() {
        return new StructuralProjection(
                byCanonicalId,
                slugToCanonicalId,
                byCluster,
                hubByCluster,
                byTag,
                byType,
                Instant.now() );
    }
}
