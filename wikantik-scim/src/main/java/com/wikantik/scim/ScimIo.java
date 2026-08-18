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
package com.wikantik.scim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.function.Function;

/**
 * Helpers shared by the SCIM resource servlets ({@link ScimUserResource}, {@link ScimGroupResource})
 * that are specific to their list/collection semantics — the generic request/response plumbing
 * (body parsing, error/response writing) already lives on {@link AbstractScimServlet}.
 */
final class ScimIo {

    private ScimIo() {}

    /**
     * Slices {@code matched} to the SCIM page described by {@code startIndex} (1-based, per the SCIM
     * spec) and {@code count}, and builds the standard {@code ListResponse} envelope: {@code schemas},
     * {@code totalResults} (the full, unpaged size of {@code matched}), {@code startIndex} (echoed
     * verbatim), {@code itemsPerPage} (the actual page size), and {@code Resources} (each page item
     * mapped through {@code toScim}).
     */
    static <T> JsonObject buildListResponse( final List<T> matched, final int startIndex, final int count,
                                             final Function<T, JsonObject> toScim ) {
        final int total = matched.size();
        final int from = Math.max( 0, startIndex - 1 );   // SCIM startIndex is 1-based
        final List<T> page = matched.subList( Math.min( from, total ), Math.min( from + count, total ) );

        final JsonArray resources = new JsonArray();
        for ( final T item : page ) {
            resources.add( toScim.apply( item ) );
        }

        final JsonObject listResp = new JsonObject();
        final JsonArray schemas = new JsonArray();
        schemas.add( "urn:ietf:params:scim:api:messages:2.0:ListResponse" );
        listResp.add( "schemas", schemas );
        listResp.addProperty( "totalResults", total );
        listResp.addProperty( "startIndex", startIndex );
        listResp.addProperty( "itemsPerPage", page.size() );
        listResp.add( "Resources", resources );
        return listResp;
    }

    /**
     * Extracts the single path segment following the collection base, e.g. {@code /abc123} →
     * {@code abc123}; {@code null} when the request targets the collection endpoint itself (no
     * path-info, {@code "/"}, or blank).
     */
    static String extractPathSegment( final HttpServletRequest req ) {
        final String pi = req.getPathInfo();
        if ( pi == null || pi.equals( "/" ) || pi.isBlank() ) return null;
        final String trimmed = pi.startsWith( "/" ) ? pi.substring( 1 ) : pi;
        return trimmed.isBlank() ? null : trimmed;
    }
}
