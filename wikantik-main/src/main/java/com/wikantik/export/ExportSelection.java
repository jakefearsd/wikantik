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
package com.wikantik.export;

import java.util.List;
import java.util.Optional;

import com.wikantik.api.pagegraph.PageType;

/**
 * The criteria that decide which pages enter an Obsidian export: cluster membership
 * (optionally including sub-clusters), tag/type/status narrowing, an outbound-link
 * hop expansion, and how the render should treat links leaving the export set.
 */
public record ExportSelection( List< String > clusters, boolean includeSubClusters, List< String > tags,
                               Optional< PageType > type, Optional< String > status, int hops,
                               UnresolvedLinkMode unresolved ) {
    /** Upper bound on outbound-link hop expansion (inclusive). */
    public static final int MAX_HOPS = 2;

    public ExportSelection {
        clusters = normalise( clusters );
        tags = normalise( tags );
        type = type == null ? Optional.empty() : type;
        status = status == null ? Optional.empty() : status;
        unresolved = unresolved == null ? UnresolvedLinkMode.KEEP : unresolved;
        if ( hops < 0 || hops > MAX_HOPS ) {
            throw new IllegalArgumentException( "hops must be 0-2" );
        }
    }

    private static List< String > normalise( final List< String > raw ) {
        if ( raw == null ) {
            return List.of();
        }
        return raw.stream()
                .filter( s -> s != null && !s.isBlank() )
                .map( String::trim )
                .toList();
    }

    /** Whether any narrowing filter is set — clusters, tags, type, or status. */
    public boolean hasFilters() { return !clusters.isEmpty() || !tags.isEmpty() || type.isPresent() || status.isPresent(); }
}
