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
package com.wikantik.api.structure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Filter criteria for {@link StructuralIndexService#listPagesByFilter}. All fields
 * optional; empty optionals mean "no constraint". A null value in the static
 * factories means "not specified".
 */
public record StructuralFilter(
        Optional< PageType > type,
        Optional< String > cluster,
        List< String > tags,
        Optional< Instant > updatedSince,
        int limit,
        Optional< String > cursor
) {
    public StructuralFilter {
        type         = type         == null ? Optional.empty() : type;
        cluster      = cluster      == null ? Optional.empty() : cluster;
        tags         = tags         == null ? List.of()        : List.copyOf( tags );
        updatedSince = updatedSince == null ? Optional.empty() : updatedSince;
        cursor       = cursor       == null ? Optional.empty() : cursor;
        if ( limit <= 0 ) {
            limit = 100;
        }
        if ( limit > 1000 ) {
            limit = 1000;
        }
    }

    public static StructuralFilter none() {
        return new StructuralFilter( null, null, null, null, 100, null );
    }
}
